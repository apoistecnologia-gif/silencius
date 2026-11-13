package br.com.apois.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Rede de segurança de nível mais alto: detecta a tela de chamada do WhatsApp
 * pelo conteúdo dela e toca em "Recusar".
 *
 * Serve para os casos em que a notificação não chega a tempo (o aparelho congela
 * o processo). Serviços de acessibilidade são acordados pelo sistema quando a
 * interface muda, então funcionam quando o listener de notificações não funciona.
 *
 * Não depende de o usuário interagir: é um serviço do sistema, ligado uma única vez.
 */
class CallAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ApoisA11y"
        private val PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

        /** Textos que costumam existir na tela de chamada do WhatsApp. */
        private val CALL_HINTS = listOf(
            "recusar", "rejeitar", "decline", "atender", "aceitar", "accept",
            "ligação de voz", "ligação de vídeo", "voice call", "video call",
            "chamada de voz", "chamada de vídeo"
        )

        fun isEnabled(ctx: android.content.Context): Boolean {
            val pref = android.provider.Settings.Secure.getString(
                ctx.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            if (!pref.contains(ctx.packageName)) return false
            val am = ctx.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            return am?.let { mgr ->
                mgr.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                    .any { it.resolveInfo.serviceInfo.packageName == ctx.packageName }
            } ?: false
        }

        fun isInstalledAndOn(): Boolean = true
    }

    private val busy = AtomicBoolean(false)
    private var lastReject = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return
        val pkg = ev.packageName?.toString() ?: return
        if (pkg !in PACKAGES) return
        if (!Prefs.isEnabled(this)) return
        if (!Prefs.waBlock(this)) return

        // Não mexer em ligação telefônica do sistema.
        if (isOnPhoneCall()) return

        val now = System.currentTimeMillis()
        if (now - lastReject < 1500) return

        if (!busy.compareAndSet(false, true)) return
        try {
            val root = runCatching { rootInActiveWindow }.getOrNull()
            if (root != null && looksLikeCallScreen(root)) {
                val clicked = clickReject(root)
                if (clicked) {
                    lastReject = now
                    Log.i(TAG, "tela de chamada detectada — Recusar tocado")
                    registerBlocked()
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "falha ao analisar a tela", e)
        } finally {
            busy.set(false)
        }
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Vigia as mudanças de conteúdo das telas do WhatsApp.
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        info.notificationTimeout = 100
        serviceInfo = info
        Log.i(TAG, "serviço de acessibilidade conectado")
    }

    private fun isOnPhoneCall(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return runCatching {
            getSystemService(android.telecom.TelecomManager::class.java)?.isInCall == true
        }.getOrDefault(false)
    }

    /** Tem cara de tela de chamada recebida? */
    private fun looksLikeCallScreen(root: AccessibilityNodeInfo): Boolean {
        val texts = collectTexts(root, limit = 120)
        if (!texts.any { t -> CALL_HINTS.any { t.contains(it, true) } }) return false

        val isVideo = texts.any {
            it.contains("vídeo", true) || it.contains("video", true)
        }
        if (isVideo && !Prefs.blockVideo(this)) return false
        if (!isVideo && !Prefs.blockVoice(this)) return false
        return true
    }

    private fun collectTexts(node: AccessibilityNodeInfo, limit: Int): List<String> {
        val out = ArrayList<String>(limit)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(node)
        while (queue.isNotEmpty() && out.size < limit) {
            val n = queue.removeFirst()
            n.text?.toString()?.let { out.add(it) }
            n.contentDescription?.toString()?.let { out.add(it) }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        return out
    }

    /** Procura e aciona o botão de recusar. */
    private fun clickReject(root: AccessibilityNodeInfo): Boolean {
        val candidates = listOf("recusar", "rejeitar", "decline")

        for (c in candidates) {
            val nodes = root.findAccessibilityNodeInfosByText(c) ?: continue
            for (n in nodes) {
                if (press(n)) return true
            }
        }
        // Alguns aparelhos usam só ícone, com descrição "Recusar" mais acima.
        return searchAndClick(root, candidates)
    }

    private fun searchAndClick(node: AccessibilityNodeInfo, words: List<String>): Boolean {
        val desc = (node.contentDescription?.toString() ?: "") + " " + (node.text?.toString() ?: "")
        if (words.any { desc.contains(it, true) }) {
            if (press(node)) return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (searchAndClick(child, words)) return true
        }
        return false
    }

    private fun press(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 4) {
            if (n.isClickable) {
                val ok = runCatching { n.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                    .getOrDefault(false)
                if (ok) return true
            }
            n = n.parent
            depth++
        }
        return false
    }

    private fun registerBlocked() {
        Prefs.bumpCount(this)
        Prefs.addLogEntry(this, org.json.JSONObject().apply {
            put("id", System.currentTimeMillis())
            put("number", "")
            put("kind", "voice")
            put("at", System.currentTimeMillis())
        })
        if (Prefs.showOverlay(this)) {
            runCatching {
                startService(
                    Intent(this, BlockOverlayService::class.java).putExtra("number", "")
                )
            }
        }
    }
}
