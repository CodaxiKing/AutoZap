package com.codaxi.pinsave

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/** Banco de frases do usuário, com a marca de quais já foram usadas. */
object PhraseBank {
    class Entry(val text: String, val used: Boolean)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("phrases", Context.MODE_PRIVATE)

    /** Todas as frases, com as ainda não usadas primeiro. */
    fun list(ctx: Context): List<Entry> = try {
        val arr = JSONArray(prefs(ctx).getString("bank", "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { Entry(it.getString("t"), it.optBoolean("u")) }
            .sortedBy { it.used }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(ctx: Context, entries: List<Entry>) {
        val arr = JSONArray()
        entries.forEach { arr.put(JSONObject().put("t", it.text).put("u", it.used)) }
        prefs(ctx).edit().putString("bank", arr.toString()).apply()
    }

    /** Acrescenta uma frase por linha, sem repetir. Devolve quantas entraram. */
    fun add(ctx: Context, lines: String): Int {
        val current = list(ctx)
        val known = current.map { it.text }.toHashSet()
        val fresh = lines.lines().map { it.trim() }.filter { it.isNotEmpty() && known.add(it) }
        save(ctx, current + fresh.map { Entry(it, false) })
        return fresh.size
    }

    fun remove(ctx: Context, text: String) = save(ctx, list(ctx).filter { it.text != text })

    fun markUsed(ctx: Context, texts: Collection<String>) {
        val used = texts.map { it.trim() }.toHashSet()
        val current = list(ctx)
        if (current.none { !it.used && it.text in used }) return
        save(ctx, current.map { if (it.text in used) Entry(it.text, true) else it })
    }

    fun resetUsed(ctx: Context) = save(ctx, list(ctx).map { Entry(it.text, false) })

    fun label(entry: Entry): String = (if (entry.used) "✓ " else "") + entry.text

    /** Mostra o banco para escolher uma frase. */
    fun pickOne(activity: Activity, onPick: (String) -> Unit) {
        val entries = list(activity)
        if (entries.isEmpty()) {
            Toast.makeText(
                activity, "Seu banco de frases está vazio. Adicione frases na tela principal.", Toast.LENGTH_LONG
            ).show()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("Banco de frases (✓ = já usada)")
            .setItems(entries.map { label(it) }.toTypedArray()) { _, which -> onPick(entries[which].text) }
            .show()
    }

    /** Mostra o banco para escolher várias frases, ou sortear [draw] ainda não usadas. */
    fun pickMany(activity: Activity, draw: Int, onPick: (List<String>) -> Unit) {
        val entries = list(activity)
        if (entries.isEmpty()) {
            Toast.makeText(
                activity, "Seu banco de frases está vazio. Adicione frases na tela principal.", Toast.LENGTH_LONG
            ).show()
            return
        }
        val checked = BooleanArray(entries.size)
        AlertDialog.Builder(activity)
            .setTitle("Banco de frases (✓ = já usada)")
            .setMultiChoiceItems(entries.map { label(it) }.toTypedArray(), checked) { _, which, on -> checked[which] = on }
            .setPositiveButton("Usar marcadas") { _, _ ->
                onPick(entries.filterIndexed { i, _ -> checked[i] }.map { it.text })
            }
            .setNeutralButton("Sortear $draw") { _, _ ->
                val unused = entries.filter { !it.used }.shuffled()
                val rest = entries.filter { it.used }.shuffled()
                onPick((unused + rest).take(draw).map { it.text })
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
