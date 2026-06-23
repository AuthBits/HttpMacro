package com.httpmacro.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * ContentProvider that serves clipboard image files as a stream.
 *
 * Messaging and other apps call query() first to get metadata (_id, _display_name, size, mime_type)
 * before calling openFile() to read the actual bytes. If query() returns null, paste fails.
 *
 * URI format: content://com.httpmacro.app.clipboard/image/{filename}
 */
class ClipboardContentProvider : ContentProvider() {

    companion object {
        @Volatile private var clipboardImage: ClipboardImage? = null

        data class ClipboardImage(val file: File, val mimeType: String)

        fun setImage(file: File, mimeType: String) {
            clipboardImage = ClipboardImage(file, mimeType)
        }

        fun clear() {
            clipboardImage?.file?.delete()
            clipboardImage = null
        }
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val image = clipboardImage ?: return null
        val file = image.file

        // Default projection — apps that pass null still need columns
        val cols = projection ?: arrayOf("_id", "_display_name", "_size", "mime_type")

        val cursor = MatrixCursor(cols)
        val row = mutableListOf<Any?>()

        for (col in cols) {
            when {
                col == "_id" ->
                    row.add(file.hashCode().toLong())

                col == "_display_name" ->
                    row.add(file.name)

                col == "_size" ->
                    row.add(file.length())

                col == "mime_type" || col == "mime" ->
                    row.add(image.mimeType)

                col == "_data" || col == "data" ->
                    row.add(file.absolutePath)

                else ->
                    row.add(null)
            }
        }

        cursor.addRow(row.toTypedArray())
        return cursor
    }

    override fun getType(uri: Uri): String? = clipboardImage?.mimeType

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val image = clipboardImage
            ?: throw IllegalArgumentException("No clipboard image available")
        return ParcelFileDescriptor.open(image.file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun call(method: String, argument: String?, extras: Bundle?): Bundle? = null
}
