package com.httpmacro.app

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.*
import java.io.IOException

/* ---- Data Model ---- */
@Entity(tableName = "macros")
data class MacroEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val url: String,
    val method: String,          // GET, POST, PUT, DELETE
    val body: String = "",
    val headers: String = "",    // one "Key: Value" per line
    val displayType: String = "notification",  // "notification" or "popup"
    val responseLimit: Int = 500,  // max chars to display
    val showToast: Boolean = true,  // show "Firing: X" toast
    val playMp3: Boolean = false,  // play MP3 if response is audio/mpeg
    val saveClipboard: Boolean = false,  // save text/image result to clipboard
    val showNotification: Boolean = true,  // show result notification
    val clipboardAsBody: Boolean = false  // use current clipboard contents as request body
)

@Dao
interface MacroDao {
    @Query("SELECT * FROM macros ORDER BY id")
    fun getAll(): List<MacroEntry>

    @Query("SELECT * FROM macros WHERE id = :id LIMIT 1")
    fun getById(id: Int): MacroEntry?

    @Insert
    fun insert(entry: MacroEntry): Long

    @Update
    fun update(entry: MacroEntry)

    @Delete
    fun delete(entry: MacroEntry)
}

@Database(entities = [MacroEntry::class], version = 7, exportSchema = false)
abstract class HttpMacroDatabase : RoomDatabase() {
    abstract fun dao(): MacroDao
    companion object {
        @Volatile private var INSTANCE: HttpMacroDatabase? = null
        fun getInstance(ctx: Context): HttpMacroDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    ctx.applicationContext,
                    HttpMacroDatabase::class.java,
                    "httpmacro.db"
                )
                    .allowMainThreadQueries()
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .build().also { INSTANCE = it }
            }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE macros ADD COLUMN displayType TEXT NOT NULL DEFAULT 'notification'")
                db.execSQL("ALTER TABLE macros ADD COLUMN responseLimit INTEGER NOT NULL DEFAULT 500")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE macros ADD COLUMN showToast INTEGER NOT NULL DEFAULT 1")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE macros ADD COLUMN playMp3 INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE macros ADD COLUMN saveClipboard INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE macros ADD COLUMN showNotification INTEGER NOT NULL DEFAULT 1")
            }
        }
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE macros ADD COLUMN clipboardAsBody INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}

/* ---- HTTP Executor (OkHttp, fire-and-forget on a Thread) ---- */
object HttpExecutor {
    private val client = OkHttpClient.Builder()
        .callTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * Returns [HttpResult] with status code, content type, and body bytes.
     */
    data class HttpResult(val code: Int, val contentType: String?, val body: ByteArray)

    /**
     * Execute the macro's HTTP request. Returns [HttpResult].
     */
    fun execute(entry: MacroEntry, bodyOverride: String? = null): HttpResult {
        val effectiveBody = bodyOverride ?: entry.body
        val requestBuilder = Request.Builder().url(entry.url)

        // Parse method
        val method = entry.method.uppercase()
        val requestBody: RequestBody? = if (method in listOf("POST", "PUT") && effectiveBody.isNotBlank()) {
            effectiveBody.toRequestBody(JSON)
        } else {
            null
        }

        // Parse headers
        if (entry.headers.isNotBlank()) {
            for (line in entry.headers.split("\n")) {
                val parts = line.split(":", limit = 2)
                if (parts.size == 2) {
                    requestBuilder.addHeader(parts[0].trim(), parts[1].trim())
                }
            }
        }

        val request = when (method) {
            "POST"  -> requestBuilder.post(requestBody ?: "".toRequestBody(null)).build()
            "PUT"   -> requestBuilder.put(requestBody ?: "".toRequestBody(null)).build()
            "DELETE" -> requestBuilder.delete().build()
            else    -> requestBuilder.get().build()
        }

        return client.newCall(request).execute().use { response ->
            val code = response.code
            val contentType = response.body?.contentType()?.toString()
            val body = response.body?.bytes() ?: byteArrayOf()
            HttpResult(code, contentType, body)
        }
    }
}
