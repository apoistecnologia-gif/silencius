package br.com.apois.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Configurações e histórico do bloqueador, guardados só no aparelho.
 *
 * Como o CallScreeningService pode rodar antes de o app ser aberto, tudo aqui
 * é lido direto das SharedPreferences, sem depender de instância viva do app.
 */
object Prefs {
    private const val FILE = "silencio_prefs"
    const val KEY_ENABLED = "enabled"
    const val KEY_MODE = "mode"            // all | unknown | allowlist
    const val KEY_VOICE = "blockVoice"
    const val KEY_VIDEO = "blockVideo"
    const val KEY_OVERLAY = "showOverlay"
    const val KEY_ALLOWLIST = "allowlist"
    const val KEY_LOG = "log"
    const val KEY_COUNT = "blockedCount"
    const val KEY_WA = "waBlock"          // bloqueio de chamadas do WhatsApp
    const val KEY_WA_RECUSE = "waRecuse"  // tentar o botão "Recusar" da notificação

    const val MODE_ALL = "all"
    const val MODE_UNKNOWN = "unknown"
    const val MODE_ALLOWLIST = "allowlist"

    fun get(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(context: Context) = get(context).getBoolean(KEY_ENABLED, false)
    fun mode(context: Context) = get(context).getString(KEY_MODE, MODE_ALL) ?: MODE_ALL
    fun blockVoice(context: Context) = get(context).getBoolean(KEY_VOICE, true)
    fun blockVideo(context: Context) = get(context).getBoolean(KEY_VIDEO, true)
    fun waBlock(context: Context) = get(context).getBoolean(KEY_WA, true)
    fun waRecuse(context: Context) = get(context).getBoolean(KEY_WA_RECUSE, true)
    fun showOverlay(context: Context) = get(context).getBoolean(KEY_OVERLAY, true)

    fun allowlist(context: Context): Set<String> {
        val raw = get(context).getString(KEY_ALLOWLIST, "[]") ?: "[]"
        val out = mutableSetOf<String>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) out.add(arr.optString(i))
        }
        return out
    }

    fun setAllowlist(context: Context, numbers: Collection<String>) {
        get(context).edit().putString(KEY_ALLOWLIST, JSONArray(numbers.toList()).toString()).apply()
    }

    fun log(context: Context): JSONArray {
        val raw = get(context).getString(KEY_LOG, "[]") ?: "[]"
        return runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }

    fun addLogEntry(context: Context, entry: JSONObject) {
        val arr = log(context)
        val next = JSONArray().put(entry)
        val max = minOf(arr.length(), 49)
        for (i in 0 until max) next.put(arr.get(i))
        get(context).edit().putString(KEY_LOG, next.toString()).apply()
    }

    fun blockedCount(context: Context) = get(context).getInt(KEY_COUNT, 0)

    fun bumpCount(context: Context) {
        val p = get(context)
        p.edit().putInt(KEY_COUNT, p.getInt(KEY_COUNT, 0) + 1).apply()
    }

    fun clearLog(context: Context) {
        get(context).edit().putString(KEY_LOG, "[]").putInt(KEY_COUNT, 0).apply()
    }

    /** Normaliza um número para comparação: só dígitos, no máximo 11 (DDD + número). */
    fun normalize(raw: String?): String {
        val digits = (raw ?: "").filter { it.isDigit() }
        return if (digits.length > 11) digits.takeLast(11) else digits
    }
}
