package com.example.gemini.ui.settings

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast
import com.example.gemini.R
import com.example.gemini.data.schedules.ScheduleAlarm
import com.example.gemini.data.schedules.ScheduleStore
import com.example.gemini.data.schedules.ScheduledPrompt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Manage scheduled prompts (P7). Plain view: list + add/remove. */
class SchedulesActivity : Activity() {

    private lateinit var store: ScheduleStore
    private lateinit var adapter: ArrayAdapter<String>
    private val prompts = mutableListOf<ScheduledPrompt>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ScheduleStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        val list = ListView(this).apply { adapter = this@SchedulesActivity.adapter }
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val addButton = Button(this).apply { text = "Add prompt" }
        root.addView(addButton)

        addButton.setOnClickListener { showAddDialog() }
        list.setOnItemClickListener { _, _, position, _ ->
            val prompt = prompts[position]
            AlertDialog.Builder(this)
                .setTitle(prompt.text.take(40))
                .setMessage("Trigger: ${formatTime(prompt.triggerAtMillis)}" +
                    if (prompt.repeatIntervalMillis > 0) "\nRepeats: every ${prompt.repeatIntervalMillis / 60000} min" else "\nOne-time")
                .setPositiveButton("Remove") { _, _ -> removePrompt(prompt) }
                .setNegativeButton("Close", null)
                .show()
        }

        setContentView(root)
        reload()
    }

    private fun reload() {
        prompts.clear()
        prompts.addAll(store.all().sortedBy { it.triggerAtMillis })
        adapter.clear()
        adapter.addAll(prompts.map { "${formatTime(it.triggerAtMillis)} — ${it.text.take(48)}" })
        adapter.notifyDataSetChanged()
    }

    private fun addPrompt(text: String, triggerAt: Long, repeatMin: Long) {
        val prompt = store.add(text, triggerAt, repeatMin * 60_000L)
        ScheduleAlarm.schedule(this, prompt)
        Toast.makeText(this, "Scheduled", Toast.LENGTH_SHORT).show()
        reload()
    }

    private fun removePrompt(prompt: ScheduledPrompt) {
        ScheduleAlarm.cancel(this, prompt.id)
        store.remove(prompt.id)
        reload()
    }

    private fun showAddDialog() {
        val input = EditText(this).apply { hint = "Prompt text" }
        val repeat = CheckBox(this).apply { text = "Repeat daily" }

        AlertDialog.Builder(this)
            .setTitle("New scheduled prompt")
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(input)
                addView(repeat)
            })
            .setPositiveButton("In 1 hour") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                addPrompt(text, System.currentTimeMillis() + 3_600_000L, if (repeat.isChecked) 1440 else 0)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun formatTime(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
}
