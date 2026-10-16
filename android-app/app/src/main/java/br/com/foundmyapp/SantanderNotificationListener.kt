package br.com.foundmyapp

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

class SantanderNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        // Processa também notificações Santander que ainda estão ativas na central.
        try {
            activeNotifications
                ?.filter { it.packageName.contains("santander", ignoreCase = true) }
                ?.forEach { processNotification(it) }
        } catch (_: Exception) {
            // A reconexão do listener não deve interromper o serviço.
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        processNotification(sbn)
    }

    private fun processNotification(sbn: StatusBarNotification) {
        try {
            val packageName = sbn.packageName.orEmpty()
            if (!packageName.contains("santander", ignoreCase = true)) return
            val extras = sbn.notification.extras ?: return
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val rawText = buildString {
                append(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty())
                append(" ")
                append(extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty())
                val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                lines?.forEach { append(" "); append(it?.toString().orEmpty()) }
            }.replace(Regex("\\s+"), " ").trim()

            val combined = listOf(title, rawText).filter { it.isNotBlank() }.joinToString(" — ")
            if (combined.isBlank()) return

            val purchaseWords = Regex(
                "compra|cart[aã]o|estabelecimento|transa[cç][aã]o|aprovad[ao]|pagamento|d[eé]bito",
                RegexOption.IGNORE_CASE
            )
            val amountMatch = Regex(
                "(?i)(?:R\\$\\s*)?(\\d{1,3}(?:\\.\\d{3})*,\\d{2}|\\d+,\\d{2})"
            ).find(combined)
            if (!purchaseWords.containsMatchIn(combined) || amountMatch == null) return

            val amount = amountMatch.groupValues[1]
            val date = Regex("\\b(\\d{2}/\\d{2}/\\d{4})\\b").find(combined)?.groupValues?.get(1).orEmpty()
            val merchant = extractMerchant(combined)
            val compactText = listOf("R$ $amount", date).filter { it.isNotBlank() }.joinToString(" • ")

            val prefs = getSharedPreferences("foundmyapp_santander_notifications", MODE_PRIVATE)
            val list = try { JSONArray(prefs.getString("pending", "[]")) } catch (_: Exception) { JSONArray() }
            val uniqueId = sha256("$packageName|\${sbn.key}|\${sbn.postTime}|$combined")
            for (i in 0 until list.length()) {
                if (list.optJSONObject(i)?.optString("id") == uniqueId) return
            }

            // Guarda somente os campos úteis para o lançamento; a mensagem completa não é exibida.
            list.put(JSONObject()
                .put("id", uniqueId)
                .put("title", merchant)
                .put("text", compactText)
                .put("merchant", merchant)
                .put("amount", amount)
                .put("date", date)
                .put("postedAt", sbn.postTime)
                .put("packageName", packageName))
            val bounded = JSONArray()
            val start = (list.length() - 100).coerceAtLeast(0)
            for (i in start until list.length()) bounded.put(list.getJSONObject(i))
            prefs.edit().putString("pending", bounded.toString()).apply()
        } catch (_: Exception) {
            // Nunca interrompe o app por um formato inesperado de notificação.
        }
    }

    private fun extractMerchant(combined: String): String {
        val explicit = Regex(
            "(?i)(?:nome do estabelecimento|estabelecimento|loja)\\s*[:：-]?\\s*(.+)$"
        ).find(combined)?.groupValues?.get(1)
        if (!explicit.isNullOrBlank()) return cleanMerchant(explicit)

        // No aviso descrito, o estabelecimento vem depois da hora da compra.
        val afterTime = Regex(
            "(?i)\\b(?:às|as)\\s*\\d{1,2}:\\d{2}\\s*(?:[-–—:|.]\\s*)?(.+)$"
        ).find(combined)?.groupValues?.get(1)
        if (!afterTime.isNullOrBlank()) {
            val cleaned = cleanMerchant(afterTime)
            if (cleaned.isNotBlank()) return cleaned
        }

        return "Compra Santander"
    }

    private fun cleanMerchant(value: String): String {
        return value
            .replace(Regex("(?i)^\\s*(?:no\\s+)?(?:estabelecimento|loja)\\s*[:：-]?\\s*"), "")
            .replace(Regex("(?i)^\\s*(?:em|no)\\s+"), "")
            .replace(Regex("[.!;]+\\s*$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(70)
            .ifBlank { "Compra Santander" }
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
