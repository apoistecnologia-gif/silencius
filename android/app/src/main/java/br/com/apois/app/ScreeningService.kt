package br.com.apois.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.VideoProfile
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject

/**
 * Serviço de triagem de chamadas.
 *
 * O Android chama onScreenCall() para TODA chamada recebida, antes de o telefone
 * tocar — inclusive as chamadas de voz e de vídeo originadas no WhatsApp, que
 * chegam ao sistema por um ConnectionService (o app aparece como "chamada do
 * WhatsApp" na tela de chamadas). É o único ponto em que um app comum pode
 * recusar uma chamada de outro aplicativo.
 */
class ScreeningService : CallScreeningService() {

    override fun onScreenCall(details: Call.Details) {
        val response = CallResponse.Builder()

        val allowed = try {
            shouldAllow(details)
        } catch (t: Throwable) {
            // Em caso de erro, nunca derrubar uma chamada legítima: deixa passar.
            true
        }

        if (allowed) {
            response.setDisallowCall(false)
                .setRejectCall(false)
                .setSilenceCall(false)
                .setSkipCallLog(false)
                .setSkipNotification(false)
        } else {
            // Recusa a chamada: o celular não toca e o WhatsApp recebe o "recusada".
            response.setDisallowCall(true)
                .setRejectCall(true)
                .setSilenceCall(false)
                .setSkipCallLog(false)
                .setSkipNotification(false)
        }

        respondToCall(details, response.build())

        if (!allowed) registerBlocked(details)
    }

    /** Decide se a chamada passa. */
    private fun shouldAllow(details: Call.Details): Boolean {
        val ctx = applicationContext

        if (!Prefs.isEnabled(ctx)) return true

        val isVideo = details.videoState != VideoProfile.STATE_AUDIO_ONLY

        // Voz x vídeo: se o tipo está desligado nas configurações, a chamada passa.
        if (isVideo && !Prefs.blockVideo(ctx)) return true
        if (!isVideo && !Prefs.blockVoice(ctx)) return true

        val number = Prefs.normalize(details.handle?.schemeSpecificPart)

        return when (Prefs.mode(ctx)) {
            Prefs.MODE_ALLOWLIST -> isInAllowlist(ctx, number)
            Prefs.MODE_UNKNOWN -> isSavedContact(ctx, number)
            else -> false
        }
    }

    private fun isInAllowlist(ctx: Context, number: String): Boolean {
        if (number.isEmpty()) return false
        val list = Prefs.allowlist(ctx)
        return list.any { it.isNotEmpty() && (it == number || it.endsWith(number) || number.endsWith(it)) }
    }

    private fun isSavedContact(ctx: Context, number: String): Boolean {
        if (number.isEmpty()) return false
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number)
        )
        var cursor: Cursor? = null
        return try {
            cursor = ctx.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup._ID),
                null, null, null
            )
            val saved = cursor?.moveToFirst() == true
            // Número sem identificação (ex.: chamadas privadas) não é contato salvo.
            saved
        } catch (e: SecurityException) {
            // Sem permissão de contatos: trata como desconhecido.
            false
        } finally {
            cursor?.close()
        }
    }

    /** Registra no histórico e mostra o aviso na tela. */
    private fun registerBlocked(details: Call.Details) {
        val ctx = applicationContext
        val number = Prefs.normalize(details.handle?.schemeSpecificPart)
        val video = details.videoState != VideoProfile.STATE_AUDIO_ONLY

        Prefs.bumpCount(ctx)
        val entry = JSONObject().apply {
            put("id", System.currentTimeMillis())
            put("number", number)
            put("kind", if (video) "video" else "voice")
            put("at", System.currentTimeMillis())
        }
        Prefs.addLogEntry(ctx, entry)

        // Aviso flutuante (se a permissão "sobrepor outros apps" estiver dada).
        if (Prefs.showOverlay(ctx) && canDrawOverlays(ctx)) {
            runCatching {
                startService(
                    Intent(ctx, BlockOverlayService::class.java)
                        .putExtra("number", number)
                        .putExtra("video", video)
                )
            }
        }

        notifyBlocked(ctx, number, video)
    }

    private fun canDrawOverlays(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(ctx)

    private fun notifyBlocked(ctx: Context, number: String, video: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "blocked_calls"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, "Chamadas bloqueadas", NotificationManager.IMPORTANCE_LOW)
                        .apply { description = "Avisos de chamadas recusadas pelo APOIS" }
                )
            }
        }

        val who = if (number.isEmpty()) "Número desconhecido" else formatPhone(number)
        val intent = Intent().apply {
            setClassName(ctx.packageName, "br.com.apois.app.MainActivity")
        }
        val pi = PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val n = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(R.drawable.ic_stat_block)
            .setContentTitle("Chamada de ${if (video) "vídeo" else "voz"} recusada")
            .setContentText(who)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        runCatching { NotificationManagerCompat.from(ctx).notify(number.hashCode(), n) }
    }

    private fun formatPhone(digits: String): String {
        val d = if (digits.length > 11) digits.takeLast(11) else digits
        return when (d.length) {
            11 -> "(${d.substring(0, 2)}) ${d.substring(2, 7)}-${d.substring(7)}"
            10 -> "(${d.substring(0, 2)}) ${d.substring(2, 6)}-${d.substring(6)}"
            else -> d
        }
    }
}
