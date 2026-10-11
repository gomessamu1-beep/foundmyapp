package br.com.foundmyapp

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

class SantanderNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val packageName = sbn.packageName.orEmpty()
            if (!packageName.contains("santander", ignoreCase = true)) return

            val extras = sbn.notification.extras ?: return
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val text = buildString {
                append(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty())
                append(" ")
                append(extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty())
                val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                lines?.forEach { append(" "); append(it?.toString().orEmpty()) }
            }.replace(Regex("\\s+"), " ").trim()

            val combined = listOf(title, text).filter { it.isNotBlank() }.joinToString(" — ")
            if (combined.isBlank()) return
            val purchaseWords = Regex("compra|cart[aã]o|estabelecimento|transa[cç][aã]o|aprovad[ao]|pagamento|d[eé]bito", RegexOption.IGNORE_CASE)
            val hasMoney = Regex("(R\\$\\s*)?\\d{1,3}(?:\\.\\d{3})*,\\d{2}|\\d+,\\d{2}").containsMatchIn(combined)
            if (!purchaseWords.containsMatchIn(combined) || !hasMoney) return

            val prefs = getSharedPreferences("foundmyapp_santander_notifications", MODE_PRIVATE)
            val list = try { JSONArray(prefs.getString("pending", "[]")) } catch (_: Exception) { JSONArray() }
            val uniqueId = sha256("$packageName|\${sbn.key}|\${sbn.postTime}|$combined")
            for (i in 0 until list.length()) {
                if (list.optJSONObject(i)?.optString("id") == uniqueId) return
            }

            val item = JSONObject()
                .put("id", uniqueId)
                .put("title", title)
                .put("text", text)
                .put("postedAt", sbn.postTime)
                .put("packageName", packageName)
            list.put(item)
            val bounded = JSONArray()
            val start = (list.length() - 100).coerceAtLeast(0)
            for (i in start until list.length()) bounded.put(list.getJSONObject(i))
            prefs.edit().putString("pending", bounded.toString()).apply()
        } catch (_: Exception) {
            // Never crash the app due to an unexpected notification format.
        }
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
