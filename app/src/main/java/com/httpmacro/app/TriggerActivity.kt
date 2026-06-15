package com.httpmacro.app

import android.media.MediaPlayer
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Triggered by httpmacro://trigger/{id} intents.
 * Parses the entry ID, fires the HTTP request on a background thread,
 * shows a Toast + notification with the result, then finishes.
 */
class TriggerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_SHORTCUT_ENTRY_ID = "shortcut_entry_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val entryId = intent?.getIntExtra(EXTRA_SHORTCUT_ENTRY_ID, -1)?.takeIf { it > 0 }
            ?: intent?.data?.pathSegments?.getOrNull(0)?.toIntOrNull()

        if (entryId == null) {
            Toast.makeText(this, "Invalid trigger URI", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val db = HttpMacroDatabase.getInstance(this)
        val entry = db.dao().getById(entryId)

        if (entry == null) {
            Toast.makeText(this, "Macro #${entryId} not found", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (entry.showToast) {
            Toast.makeText(this, "Firing: ${entry.name}", Toast.LENGTH_SHORT).show()
        }

        Thread {
            try {
                val result = HttpExecutor.execute(entry)
                runOnUiThread {
                    val limit = entry.responseLimit.coerceAtLeast(50)

                    // Try JSON with embedded MP3: {"text": "...", "mp3": "base64..."}
                    val bodyStr = String(result.body)
                    val json = runCatching { JSONObject(bodyStr) }.getOrNull()
                    val hasMp3Field = json?.has("mp3") == true && !json.getString("mp3").isEmpty()

                    if (entry.playMp3 && hasMp3Field) {
                        val textPart = json?.optString("text", "")?.take(limit) ?: ""
                        val mp3Bytes = Base64.decode(json!!.getString("mp3"), Base64.DEFAULT)
                        playMp3(entry.name, result.code, textPart, mp3Bytes)
                    } else if (entry.playMp3 && isMp3Response(result)) {
                        // Raw MP3 response
                        playMp3(entry.name, result.code, "", result.body)
                    } else {
                        val bodySnippet = bodyStr.take(limit)
                        showNotification(entry.name, "HTTP ${result.code}\n\n$bodySnippet")
                    }
                }
            } catch (e: IOException) {
                runOnUiThread {
                    showNotification(entry.name, "Error: ${e.message}", true)
                }
            }
        }.start()

        finish()
    }

    /** Check if the response looks like a raw MP3 based on content type or magic bytes. */
    private fun isMp3Response(result: HttpExecutor.HttpResult): Boolean {
        result.contentType?.let { ct ->
            if (ct.contains("audio/mpeg", ignoreCase = true) ||
                ct.contains("audio/mp3", ignoreCase = true)) {
                return true
            }
        }
        if (result.body.size >= 3) {
            if (result.body[0] == 'I'.code.toByte() &&
                result.body[1] == 'D'.code.toByte() &&
                result.body[2] == '3'.code.toByte()) {
                return true  // ID3v2 tag
            }
            if (result.body[0] == 0xFF.toByte() && (result.body[1].toInt() and 0xF0) == 0xF0) {
                return true  // MPEG frame sync
            }
        }
        return false
    }

    /** Write the MP3 bytes to a temp file and play it with MediaPlayer. */
    private fun playMp3(title: String, httpCode: Int, textInfo: String, mp3Bytes: ByteArray) {
        val tempFile = File(cacheDir, "httpmacro_response_${System.currentTimeMillis()}.mp3")
        try {
            tempFile.writeBytes(mp3Bytes)

            val displayText = if (textInfo.isNotBlank()) textInfo else "HTTP $httpCode"
            val player = MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                prepare()
                setOnErrorListener { _, what, extra ->
                    runOnUiThread {
                        showNotification(title, "MP3 playback error (what=$what, extra=$extra)", true)
                    }
                    false
                }
                setOnCompletionListener { tempFile.delete() }
            }
            player.start()

            runOnUiThread {
                Toast.makeText(this, "Playing: $title", Toast.LENGTH_SHORT).show()
                showNotification(title, "Playing — $displayText")
            }

            // Safety cleanup after 2 minutes
            android.os.Handler(mainLooper).postDelayed({
                tempFile.delete()
                player.release()
            }, 120_000)

        } catch (e: Exception) {
            tempFile.delete()
            runOnUiThread {
                showNotification(title, "Failed to play MP3: ${e.message}", true)
            }
        }
    }

    private fun showNotification(title: String, content: String, isError: Boolean = false) {
        val channel = android.app.NotificationChannel(
            "trigger_channel",
            "HttpMacro Triggers",
            android.app.NotificationManager.IMPORTANCE_HIGH
        )
        val manager = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.createNotificationChannel(channel)

        val builder = NotificationCompat.Builder(this, "trigger_channel")
            .setSmallIcon(if (isError) android.R.drawable.stat_notify_error else android.R.drawable.stat_notify_sync)
            .setContentTitle("HttpMacro: $title")
            .setContentText(if (isError) content else "HTTP response — tap to expand")
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setChannelId("trigger_channel")

        NotificationManagerCompat.from(this).notify(System.currentTimeMillis().toInt(), builder.build())
    }
}
