package br.com.apois.app

import android.app.Notification
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import android.util.Log
import org.json.JSONObject

/**
 * Captura das chamadas de VoIP (WhatsApp pessoal e Business, e outros apps que
 * avisam por notificação em vez de usar o Telecom).
 *
 * O WhatsApp não registra as chamadas dele no sistema de telefonia, então a
 * triagem de chamadas (ScreeningService) nunca é acionada. O que ele faz é
 * publicar uma notificação de categoria "call" com botões "Recusar" e "Atender"
 * — e, em aparelhos sem o modo "ligação em tela cheia", ela é o padrão do
 * sistema para chamadas telefônicas reais. Para não derrubar ligação de telefone
 * por engano, é feita a checagem `TelecomManager.isInCall` (API 31+): se há
 * chamada telefônica em curso, nada é feito.
 *
 * Com isso: o APOIS registra a tentativa (aviso na tela + histórico) e, quando
 * permitido pelo sistema, toca no "Recusar" para derrubar a chamada.
 */
class CallNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "ApoisNotif"
        private val PACKAGES = setOf(
            "com.whatsapp",        // WhatsApp pessoal
            "com.whatsapp.w4b"     // WhatsApp Business
        )
        private const val DEDUP_MS = 5_000L
    }

    private var lastAt: Long = 0L

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        Log.d(TAG, "callback pkg=${sbn?.packageName} id=${sbn?.id}")
        runCatching { handle(sbn) }.onFailure { Log.w(TAG, "falha ao ler notificação", it) }
    }

    private fun handle(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName !in PACKAGES) return

        val n: Notification = sbn.notification ?: return
        val extras: Bundle = n.extras ?: Bundle()
        Log.d(TAG, "notif pkg=${sbn.packageName} id=${sbn.id} key=${sbn.key} cat=${n.category}")

        // 1) Tem que ser uma chamada de verdade.
        val category = n.category
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val isCall = category == Notification.CATEGORY_CALL ||
            text.contains("recebida", ignoreCase = true) ||
            text.contains("incoming", ignoreCase = true)
        if (!isCall) return
        if (text.contains("perdida", ignoreCase = true) || text.contains("missed", ignoreCase = true)) return

        // 2) Uma notificação não conta duas vezes.
        val now = System.currentTimeMillis()
        if (now - lastAt < DEDUP_MS) {
            Log.d(TAG, "ignorada (repetição em menos de ${DEDUP_MS / 1000}s)")
            return
        }
        lastAt = now
        Log.i(TAG, "chamada detectada: pkg=${sbn.packageName} text=$text")

        // 3) Não mexer em ligação telefônica em andamento.
        if (isOnPhoneCall()) return

        if (!Prefs.isEnabled(this)) return
        if (!Prefs.waBlock(this)) return

        val isVideo = text.contains("vídeo", true) || text.contains("video", true)
        if (isVideo && !Prefs.blockVideo(this)) return
        if (!isVideo && !Prefs.blockVoice(this)) return

        val number = guessNumber(title, text)
        registerBlocked(number, isVideo)

        if (Prefs.waRecuse(this)) {
            tryReject(n)
            reinforceReject(sbn)
        }
    }

    /**
     * Algumas chamadas do WhatsApp não caem na primeira tentativa (o app ainda
     * está montando a tela de chamada). Repetimos a recusa por alguns segundos
     * enquanto a mesma notificação de chamada continuar ativa.
     */
    private fun reinforceReject(sbn: StatusBarNotification) {
        val key = sbn.key ?: return
        Thread {
            repeat(8) {
                Thread.sleep(700)
                val still = runCatching {
                    activeNotifications?.any { it.key == key && it.notification?.category == Notification.CATEGORY_CALL }
                }.getOrDefault(false)
                if (still != true) return@Thread
                val fresh = runCatching { activeNotifications?.firstOrNull { it.key == key } }.getOrNull()
                if (fresh != null) {
                    Log.i(TAG, "chamada continua tocando — recusando de novo")
                    tryReject(fresh.notification)
                }
            }
        }.start()
    }

    /** Não fazer nada se houver chamada telefônica ativa (WhatsApp sobre a ligação). */
    private fun isOnPhoneCall(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return try {
            getSystemService(TelecomManager::class.java)?.isInCall == true
        } catch (e: Throwable) {
            false
        }
    }

    private fun guessNumber(title: String, text: String): String {
        val digits = (title + " " + text).filter { it.isDigit() }
        return if (digits.length >= 8) digits else ""
    }

    private fun registerBlocked(number: String, video: Boolean) {
        Prefs.bumpCount(this)
        Prefs.addLogEntry(this, JSONObject().apply {
            put("id", System.currentTimeMillis())
            put("number", number)
            put("kind", if (video) "video" else "voice")
            put("at", System.currentTimeMillis())
        })
        if (Prefs.showOverlay(this)) {
            runCatching {
                startService(
                    Intent(this, BlockOverlayService::class.java)
                        .putExtra("number", number)
                        .putExtra("video", video)
                )
            }
        }
    }

    /**
     * Tenta acionar o botão "Recusar" da notificação.
     *
     * O Android 13 só permite tocar em ações de notificação de outro app com a
     * permissão de assinatura/privilegiada "Notification Assistant"
     * (android.permission.NOTIFICATION_ASSISTANT) — que um app comum não
     * consegue obter. Por isso fazemos isso de forma protegida: se o sistema
     * recusar (SecurityException), o chamado é apenas registrado, sem derrubar
     * nada. Em aparelhos que permitem, a chamada é recusada de imediato.
     */
    private fun tryReject(n: Notification) {
        val actions = n.actions ?: return
        Log.i(TAG, "ações: ${actions.joinToString { it.title?.toString() ?: "?" }}")
        val action = actions.firstOrNull { it.title?.toString()?.contains("recusar", true) == true }
            ?: return
        try {
            action.actionIntent.send()
            Log.i(TAG, "ação Recusar acionada com sucesso")
        } catch (e: SecurityException) {
            Log.w(TAG, "sem permissão para acionar a ação da notificação", e)
        } catch (e: Throwable) {
            Log.w(TAG, "falha ao acionar ação", e)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "listener conectado")
    }

    private fun hasPermission(perm: String): Boolean =
        checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
}
