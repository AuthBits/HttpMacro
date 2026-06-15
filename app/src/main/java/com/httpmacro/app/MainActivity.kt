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

        // ---- Response limit ----
        val limitInput = EditText(this).apply {
            hint = "Response limit (chars)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText((macro?.responseLimit ?: 500).toString())
        }

        // ---- Show toast checkbox ----
        val toastCheck = android.widget.CheckBox(this).apply {
            text = "Show \"Firing\" toast"
            isChecked = macro?.showToast ?: true
        }

        // ---- Play MP3 checkbox ----
        val mp3Check = android.widget.CheckBox(this).apply {
            text = "Play MP3 received in response"
            isChecked = macro?.playMp3 ?: false
        }

        // ---- Modular headers section ----
        val headersContainer = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        val headerPairs = mutableListOf<Pair<EditText, EditText>>()

        fun rebuildHeaders(): String {
            return headerPairs.map { (k, v) ->
                val key = k.text.toString().trim()
                val value = v.text.toString().trim()
                if (key.isNotEmpty()) "$key: $value" else ""
            }.filter { it.isNotEmpty() }.joinToString("\n")
        }

        fun addHeaderRow(key: String = "", value: String = "") {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 8.dp)
            }
            val keyEt = EditText(this).apply {
                hint = "Key"
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setText(key)
            }
            val colon = android.widget.TextView(this).apply {
                text = ":"
                textSize = 16f
                setPadding(8.dp, 0, 8.dp, 0)
                layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val valueEt = EditText(this).apply {
                hint = "Value"
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setText(value)
            }
            val removeBtn = android.widget.Button(this).apply {
                text = "\u2715"
                minWidth = 48.dp
                setPadding(8.dp, 0, 8.dp, 0)
                setBackgroundResource(android.R.color.transparent)
                setOnClickListener {
                    headersContainer.removeView(row)
                    headerPairs.removeAt(headerPairs.indexOfFirst { it.first == keyEt })
                }
            }
            row.addView(keyEt)
            row.addView(colon)
            row.addView(valueEt)
            row.addView(removeBtn)
            headersContainer.addView(row)
            headerPairs.add(keyEt to valueEt)

            // Rebuild headers string on any change
            val listener = object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { rebuildHeaders() }
                override fun afterTextChanged(s: android.text.Editable?) {}
            }
            keyEt.addTextChangedListener(listener)
            valueEt.addTextChangedListener(listener)
        }

        // Pre-populate existing headers
        if (macro?.headers?.isNotBlank() == true) {
            for (line in macro.headers.split("\n")) {
                val parts = line.split(":", limit = 2)
                if (parts.size == 2) {
                    addHeaderRow(parts[0].trim(), parts[1].trim())
                }
            }
        }

        val addHeaderBtn = android.widget.Button(this).apply {
            text = "+ Add Header"
            setPadding(0, 4.dp, 0, 4.dp)
            setBackgroundResource(android.R.color.transparent)
            setOnClickListener { addHeaderRow() }
        }

        val form = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32.dp, 32.dp, 32.dp, 32.dp)
            addView(nameInput)
            addView(urlInput)
            addView(methodSpinner)
            addView(headersContainer)
            addView(addHeaderBtn)
            addView(bodyInput)

            // Spacer
            addView(android.widget.Space(this@MainActivity).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    16.dp
                )
            })

            addView(android.widget.TextView(this@MainActivity).apply {
                text = "Response limit (chars)"
                textSize = 12f
                setPadding(0, 0, 0, 4.dp)
            })
            addView(limitInput)

            addView(toastCheck)
            addView(mp3Check)
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
                    headers = rebuildHeaders(),
                    responseLimit = limitInput.text.toString().toIntOrNull() ?: 500,
                    showToast = toastCheck.isChecked,
                    playMp3 = mp3Check.isChecked
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
