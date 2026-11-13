package br.com.apois.app

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback

@CapacitorPlugin(
    name = "Silence",
    permissions = [
        Permission(alias = "contacts", strings = ["android.permission.READ_CONTACTS"]),
        Permission(alias = "notifications", strings = ["android.permission.POST_NOTIFICATIONS"])
    ]
)
class SilencePlugin : Plugin() {

    /** Estado completo para a interface. */
    @PluginMethod
    fun getState(call: PluginCall) {
        val ctx = context
        val ret = JSObject()
        ret.put("enabled", Prefs.isEnabled(ctx))
        ret.put("mode", Prefs.mode(ctx))
        ret.put("blockVoice", Prefs.blockVoice(ctx))
        ret.put("blockVideo", Prefs.blockVideo(ctx))
        ret.put("showOverlay", Prefs.showOverlay(ctx))
        ret.put("waBlock", Prefs.waBlock(ctx))
        ret.put("waRecuse", Prefs.waRecuse(ctx))
        ret.put("blockedCount", Prefs.blockedCount(ctx))
        ret.put("allowlist", JSArray(Prefs.allowlist(ctx).toList()))
        ret.put("log", JSArray(Prefs.log(ctx).toString()))

        val perms = JSObject()
        perms.put("screening", isDefaultScreening(ctx))
        perms.put("overlay", canDrawOverlays(ctx))
        perms.put("notifications", hasNotifications(ctx))
        perms.put("listener", isListenerEnabled(ctx))
        perms.put("accessibility", CallAccessibilityService.isEnabled(ctx))
        perms.put("battery", isIgnoringBattery(ctx))
        perms.put("restricted", isRestricted(ctx))
        ret.put("permissions", perms)
        ret.put("onboarded", Prefs.get(ctx).getBoolean("onboarded", false))

        call.resolve(ret)
    }

    @PluginMethod
    fun save(call: PluginCall) {
        val s = call.getObject("settings") ?: run {
            call.reject("settings ausente")
            return
        }
        val ctx = context
        val editor = Prefs.get(ctx).edit()

        if (s.has("enabled")) editor.putBoolean(Prefs.KEY_ENABLED, s.optBoolean("enabled", false))
        if (s.has("mode")) editor.putString(Prefs.KEY_MODE, s.optString("mode", Prefs.MODE_ALL))
        if (s.has("blockVoice")) editor.putBoolean(Prefs.KEY_VOICE, s.optBoolean("blockVoice", true))
        if (s.has("blockVideo")) editor.putBoolean(Prefs.KEY_VIDEO, s.optBoolean("blockVideo", true))
        if (s.has("showOverlay")) editor.putBoolean(Prefs.KEY_OVERLAY, s.optBoolean("showOverlay", true))
        if (s.has("waBlock")) editor.putBoolean(Prefs.KEY_WA, s.optBoolean("waBlock", true))
        if (s.has("waRecuse")) editor.putBoolean(Prefs.KEY_WA_RECUSE, s.optBoolean("waRecuse", true))

        val list = s.optJSONArray("allowlist")
        if (list != null) {
            val numbers = mutableListOf<String>()
            for (i in 0 until list.length()) {
                val n = Prefs.normalize(list.optString(i))
                if (n.isNotEmpty()) numbers.add(n)
            }
            editor.putString(Prefs.KEY_ALLOWLIST, org.json.JSONArray(numbers.toList()).toString())
        }
        editor.apply()

        // Serviço em primeiro plano: mantém o app vivo (hibernação da Infinix/Tecno).
        if (Prefs.isEnabled(ctx)) {
            GuardService.start(ctx)
            WatchdogReceiver.schedule(ctx)
        } else {
            GuardService.stop(ctx)
            WatchdogReceiver.cancel(ctx)
        }

        if (s.has("enabled")) {
            val on = s.optBoolean("enabled", false)
            if (on) {
                if (!isDefaultScreening(ctx)) openScreeningSettings()
                else requestAuxPermissions(call)
            }
        }

        getState(call)
    }

    /** Pede as permissões auxiliares (agenda para "só desconhecidos", avisos). */
    private fun requestAuxPermissions(call: PluginCall) {
        if (getPermissionState("contacts") != PermissionState.GRANTED) {
            requestPermissionForAlias("contacts", call, "afterPermission")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            getPermissionState("notifications") != PermissionState.GRANTED
        ) {
            requestPermissionForAlias("notifications", call, "afterPermission")
        }
    }

    @PermissionCallback
    private fun afterPermission(call: PluginCall) { /* opcional para o app */ }

    @PluginMethod
    fun clearLog(call: PluginCall) {
        Prefs.clearLog(context)
        call.resolve()
    }

    @PluginMethod
    fun openScreeningSettings(call: PluginCall) {
        openScreeningSettings()
        call.resolve()
    }

    @PluginMethod
    fun requestOverlay(call: PluginCall) {
        val ctx = context
        if (!canDrawOverlays(ctx)) {
            val i = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + ctx.packageName)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(i) }.onFailure {
                runCatching {
                    ctx.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
        requestAuxPermissions(call)
        call.resolve()
    }

    /**
     * Abre a Acessibilidade já destacando o Silêncius. Tenta primeiro a tela de detalhes
     * do serviço (funciona em vários aparelhos); se o sistema recusar, abre a lista com o
     * item do Silêncius realçado.
     */
    @PluginMethod
    fun openAccessibility(call: PluginCall) {
        val ctx = context
        val cn = android.content.ComponentName(ctx, CallAccessibilityService::class.java).flattenToString()
        val args = android.os.Bundle().apply { putString(":settings:fragment_args_key", cn) }
        val tries = listOf(
            Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, cn),
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .putExtra(":settings:fragment_args_key", cn)
                .putExtra(":settings:show_fragment_args", args),
            Intent(Settings.ACTION_SETTINGS)
        )
        startFirst(tries)
        call.resolve()
    }

    /** Abre "Informações do app" (onde fica "Permitir configurações restritas"). */
    @PluginMethod
    fun openAppInfo(call: PluginCall) {
        startFirst(listOf(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
        ))
        call.resolve()
    }

    /** Pede avisos (notificações) e contatos, pelas janelas normais do sistema. */
    @PluginMethod
    fun requestBasics(call: PluginCall) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            getPermissionState("notifications") != PermissionState.GRANTED
        ) {
            requestPermissionForAlias("notifications", call, "afterBasics")
            return
        }
        afterBasics(call)
    }

    @PermissionCallback
    private fun afterBasics(call: PluginCall) {
        if (getPermissionState("contacts") != PermissionState.GRANTED) {
            requestPermissionForAlias("contacts", call, "afterContacts")
            return
        }
        call.resolve()
    }

    @PermissionCallback
    private fun afterContacts(call: PluginCall) { call.resolve() }

    @PluginMethod
    fun setOnboarded(call: PluginCall) {
        Prefs.get(context).edit().putBoolean("onboarded", call.getBoolean("value", true) ?: true).apply()
        call.resolve()
    }

    /** Tenta abrir cada intent em ordem até uma funcionar. */
    private fun startFirst(list: List<Intent>) {
        for (i in list) {
            val ok = runCatching {
                context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (ok) return
        }
    }

    /** Abre o pedido do sistema para o app não ser otimizado (hibernação). */
    @PluginMethod
    fun requestIgnoreBattery(call: PluginCall) {
        val ctx = context
        runCatching {
            val pm = ctx.getSystemService(android.os.PowerManager::class.java)
            if (pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true) return@runCatching
            ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(android.net.Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        call.resolve()
    }

    /** Garante o serviço em primeiro plano quando a proteção está ligada. */
    @PluginMethod
    fun startGuard(call: PluginCall) {
        val ctx = context
        if (Prefs.isEnabled(ctx)) {
            GuardService.start(ctx)
            WatchdogReceiver.schedule(ctx)
        }
        call.resolve()
    }

    /** Abre o Acesso a notificações direto no botão do Silêncius (Android 11+). */
    @PluginMethod
    fun openNotificationAccess(call: PluginCall) {
        val ctx = context
        val cn = android.content.ComponentName(ctx, CallNotificationListener::class.java).flattenToString()
        val tries = mutableListOf<Intent>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tries.add(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn)
            )
        }
        tries.add(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        tries.add(Intent(Settings.ACTION_SETTINGS))
        startFirst(tries)
        call.resolve()
    }

    /** Estado real do acesso a notificações (usa o NotificationManager). */
    private fun isListenerEnabled(ctx: Context): Boolean {
        return try {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: ""
            flat.split(":").any { it.contains(ctx.packageName) }
        } catch (e: Throwable) {
            false
        }
    }

    @PluginMethod
    fun diagnose(call: PluginCall) {
        val ctx = context
        val ret = JSObject()
        val screening = isDefaultScreening(ctx)
        val overlay = canDrawOverlays(ctx)
        ret.put("screening", screening)
        ret.put("overlay", overlay)
        ret.put("listener", isListenerEnabled(ctx))
        ret.put("guard", GuardService.isRunning(ctx))
        ret.put("enabled", Prefs.isEnabled(ctx))
        ret.put(
            "summary",
            when {
                !screening -> "Falta escolher o Silêncius como triagem de chamadas padrão"
                !Prefs.isEnabled(ctx) -> "Triagem ativa, mas a proteção está desligada"
                !isListenerEnabled(ctx) -> "Falta liberar o acesso a notificações (chamadas do WhatsApp)"
                !overlay -> "Proteção ativa (sem o aviso flutuante)"
                else -> "Tudo pronto: proteção ativa"
            }
        )
        call.resolve(ret)
    }

    /* ---------------- utilitários ---------------- */

    private fun isDefaultScreening(ctx: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val role = ctx.getSystemService(RoleManager::class.java)
                role != null &&
                    role.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
                    role.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
            } else {
                @Suppress("DEPRECATION")
                (ctx.getSystemService(TelecomManager::class.java))?.defaultDialerPackage == ctx.packageName
            }
        } catch (e: Throwable) {
            false
        }
    }

    private fun openScreeningSettings() {
        val ctx = context
        val intent: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val role = ctx.getSystemService(RoleManager::class.java)
            if (role != null && role.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
                role.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
            } else null
        } else {
            Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, ctx.packageName)
        }

        if (intent != null) {
            // O pedido de "role" precisa sair da própria Activity, sem NEW_TASK;
            // senão o Android fecha a janela na hora (requestingPackageName=null).
            val act = activity
            runCatching {
                if (act != null) act.startActivityForResult(intent, 7001)
                else ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure {
                runCatching {
                    ctx.startActivity(
                        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
    }

    private fun isIgnoringBattery(ctx: Context): Boolean =
        ctx.getSystemService(android.os.PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(ctx.packageName) == true

    /**
     * true quando o Android 13+ bloqueia Acessibilidade/Acesso a notificações por o app
     * ter sido instalado fora de uma loja ("Configuração restrita").
     * Instalado pela Play Store isso nunca acontece.
     */
    private fun isRestricted(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val installer = runCatching {
            ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName
        }.getOrNull()
        if (installer == "com.android.vending") return false
        return runCatching {
            val ops = ctx.getSystemService(android.app.AppOpsManager::class.java)
            ops.unsafeCheckOpNoThrow(
                "android:access_restricted_settings",
                ctx.applicationInfo.uid, ctx.packageName
            ) != android.app.AppOpsManager.MODE_ALLOWED
        }.getOrDefault(true)
    }

    private fun canDrawOverlays(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)

    private fun hasNotifications(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
}
