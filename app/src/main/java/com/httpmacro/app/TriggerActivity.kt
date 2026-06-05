package com.httpmacro.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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
        // No UI needed — just process the intent and finish

        // Support both: shortcut extra (from ShortcutManager) and URI path (from manual triggers)
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

        Toast.makeText(this, "Firing: ${entry.name}", Toast.LENGTH_SHORT).show()

        // Fire on a background thread
        Thread {
            try {
                val (code, bodySnippet) = HttpExecutor.execute(entry)
                val message = "${entry.name}: HTTP $code — ${bodySnippet.take(80)}"
                runOnUiThread {
                    Toast.makeText(this@TriggerActivity, message, Toast.LENGTH_LONG).show()
                    showNotification(entry.name, "$code — ${bodySnippet.take(100)}")
                }
            } catch (e: IOException) {
                val msg = "${entry.name}: ${e.message}"
                runOnUiThread {
                    Toast.makeText(this@TriggerActivity, msg, Toast.LENGTH_LONG).show()
                    showNotification(entry.name, "Error: ${e.message}", true)
                }
            }
        }.start()

        finish()
    }

    private fun showNotification(title: String, content: String, isError: Boolean = false) {
        val builder = NotificationCompat.Builder(this, "trigger_channel")
            .setSmallIcon(if (isError) android.R.drawable.stat_notify_error else android.R.drawable.stat_notify_sync)
            .setContentTitle("HttpMacro: $title")
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        // Create channel on first notification (API 26+)
        val channel = android.app.NotificationChannel(
            "trigger_channel",
            "HttpMacro Triggers",
            android.app.NotificationManager.IMPORTANCE_HIGH
        )
        val manager = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.createNotificationChannel(channel)
        builder.setChannelId("trigger_channel")

        NotificationManagerCompat.from(this).notify(System.currentTimeMillis().toInt(), builder.build())
    }
}
