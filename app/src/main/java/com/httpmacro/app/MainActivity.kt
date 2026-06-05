package com.httpmacro.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.httpmacro.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val db by lazy { HttpMacroDatabase.getInstance(this) }
    private lateinit var adapter: MacroAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvMacros.layoutManager = LinearLayoutManager(this)
        adapter = MacroAdapter(emptyList(), onEdit = { showEditDialog(it) }, onDelete = { deleteMacro(it) }, onTrigger = { triggerMacro(it) })
        binding.rvMacros.adapter = adapter

        binding.btnAdd.setOnClickListener { showEditDialog(null) }
        loadMacros()
    }

    private fun loadMacros() {
        val macros = db.dao().getAll()
        adapter.submit(macros)
        if (macros.isEmpty()) {
            binding.tvEmpty.visibility = android.view.View.VISIBLE
        } else {
            binding.tvEmpty.visibility = android.view.View.GONE
        }
        updateShortcuts(macros)
    }

    private fun showEditDialog(macro: MacroEntry?) {
        val isEdit = macro != null
        val title = if (isEdit) "Edit Macro" else "New Macro"

        // Build form views
        val nameInput = EditText(this).apply { hint = "Name"; setText(macro?.name ?: "") }
        val urlInput = EditText(this).apply { hint = "URL"; setText(macro?.url ?: "") }
        val methodSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("GET", "POST", "PUT", "DELETE"))
            if (isEdit) setSelection(listOf("GET", "POST", "PUT", "DELETE").indexOfFirst { it == macro?.method }.takeIf { it >= 0 } ?: 0)
        }
        val bodyInput = EditText(this).apply { hint = "Body (for POST/PUT)"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE; maxLines = 4; setText(macro?.body ?: "") }
        val headersInput = EditText(this).apply { hint = "Headers (one per line, e.g. Content-Type: application/json)"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE; maxLines = 4; setText(macro?.headers ?: "") }

        val form = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32.dp, 32.dp, 32.dp, 32.dp)
            addView(nameInput)
            addView(urlInput)
            addView(methodSpinner)
            addView(bodyInput)
            addView(headersInput)
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(form)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString().trim()
                val url = urlInput.text.toString().trim()
                if (name.isEmpty() || url.isEmpty()) {
                    Toast.makeText(this, "Name and URL are required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val entry = MacroEntry(
                    id = macro?.id ?: 0,
                    name = name,
                    url = url,
                    method = methodSpinner.selectedItem.toString(),
                    body = bodyInput.text.toString().trim(),
                    headers = headersInput.text.toString().trim()
                )
                if (isEdit) {
                    db.dao().update(entry)
                } else {
                    db.dao().insert(entry)
                }
                loadMacros()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteMacro(entry: MacroEntry) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${entry.name}?")
            .setPositiveButton("Delete") { _, _ -> db.dao().delete(entry); loadMacros() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun triggerMacro(entry: MacroEntry) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("httpmacro://trigger/${entry.id}"))
        startActivity(intent)
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    /* ---- Shortcut helpers (BlackBerry keyboard shortcuts) ---- */

    private fun createShortcutId(entry: MacroEntry) = "macro:${entry.id}"

    private fun createShortcutIntent(entry: MacroEntry): Intent {
        return Intent(this, TriggerActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("shortcut_entry_id", entry.id)
        }
    }

    private fun updateShortcuts(macros: List<MacroEntry>) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                updateShortcutState(macros)
                updateDynamicShortcuts(macros)
            }.onFailure { e ->
                Log.e("Shortcuts", "Failed to update shortcuts", e)
            }
        }
    }

    /** Enable/disable pinned shortcuts based on which macros still exist. */
    private fun updateShortcutState(macros: List<MacroEntry>) {
        val pinned = ShortcutManagerCompat.getShortcuts(this, ShortcutManagerCompat.FLAG_MATCH_PINNED)
        val validIds = macros.map { createShortcutId(it) }.toSet()
        val pinnedIds = pinned.map { it.id }

        val enabled = pinned.filter { it.id in validIds }
        val disabled = pinnedIds.filterNot { it in validIds }

        ShortcutManagerCompat.enableShortcuts(this, enabled)
        if (disabled.isNotEmpty()) {
            ShortcutManagerCompat.disableShortcuts(this, disabled, "This macro has been deleted")
        }
    }

    /** Publish dynamic shortcuts for the current macros. */
    private fun updateDynamicShortcuts(macros: List<MacroEntry>) {
        val maxCount = ShortcutManagerCompat.getMaxShortcutCountPerActivity(this)
        val shortcuts = macros.take(maxCount).mapIndexed { i, entry ->
            ShortcutInfoCompat.Builder(this, createShortcutId(entry))
                .setIcon(IconCompat.createWithResource(this, android.R.drawable.ic_menu_send))
                .setShortLabel(entry.name)
                .setLongLabel("Trigger ${entry.name}")
                .setRank(i)
                .setIntent(createShortcutIntent(entry))
                .build()
        }
        ShortcutManagerCompat.setDynamicShortcuts(this, shortcuts)
    }
}

/* ---- RecyclerView Adapter ---- */
class MacroAdapter(
    private var macros: List<MacroEntry>,
    private val onEdit: (MacroEntry) -> Unit,
    private val onDelete: (MacroEntry) -> Unit,
    private val onTrigger: (MacroEntry) -> Unit
) : RecyclerView.Adapter<MacroAdapter.VH>() {

    fun submit(list: List<MacroEntry>) {
        macros = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = macros.size
    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val view = android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_macro, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = macros[position]
        holder.bind(entry, onEdit, onDelete, onTrigger)
    }

    class VH(view: android.view.View) : RecyclerView.ViewHolder(view) {
        private val tvName = view.findViewById<android.widget.TextView>(R.id.tvName)
        private val tvUrl = view.findViewById<android.widget.TextView>(R.id.tvUrl)
        private val tvMethod = view.findViewById<android.widget.TextView>(R.id.tvMethod)
        private val btnTrigger = view.findViewById<android.widget.Button>(R.id.btnTrigger)
        private val btnEdit = view.findViewById<android.widget.Button>(R.id.btnEdit)
        private val btnDelete = view.findViewById<android.widget.Button>(R.id.btnDelete)

        fun bind(entry: MacroEntry, onEdit: (MacroEntry) -> Unit, onDelete: (MacroEntry) -> Unit, onTrigger: (MacroEntry) -> Unit) {
            tvName.text = entry.name
            tvUrl.text = entry.url
            tvMethod.text = entry.method
            btnTrigger.setOnClickListener { onTrigger(entry) }
            btnEdit.setOnClickListener { onEdit(entry) }
            btnDelete.setOnClickListener { onDelete(entry) }
        }
    }
}
