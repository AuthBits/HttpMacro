package com.httpmacro.app

import android.content.Context
import androidx.room.*
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
    val headers: String = ""     // one "Key: Value" per line
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

@Database(entities = [MacroEntry::class], version = 1, exportSchema = false)
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
                ).allowMainThreadQueries().build().also { INSTANCE = it }
            }
    }
}

/* ---- HTTP Executor (OkHttp, fire-and-forget on a Thread) ---- */
object HttpExecutor {
    private val client = OkHttpClient.Builder()
        .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * Returns (statusCode: Int, bodySnippet: String) or throws IOException.
     */
    fun execute(entry: MacroEntry): Pair<Int, String> {
        val requestBuilder = Request.Builder().url(entry.url)

        // Parse method
        val method = entry.method.uppercase()
        val requestBody: RequestBody? = if (method in listOf("POST", "PUT") && entry.body.isNotBlank()) {
            entry.body.toRequestBody(JSON)
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
            val bodySnippet = response.body?.string()?.take(200) ?: "(no body)"
            code to bodySnippet
        }
    }
}
