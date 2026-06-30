package com.gohenry.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Exports the Raw Data screen's cached telemetry fields to a CSV file and hands it
 * to the Android share sheet. Rows mirror the on-screen sectioned layout exactly:
 * the same engine-scoped catalog sections, the same labels, and the same formatted
 * values, plus an "Other" section for any field not covered by the detail catalog.
 */
object FieldsCsvExport {

    private val HEADERS = listOf("Section", "Field", "Ford Field", "Value")

    /** Builds the CSV text (with header row) matching the on-screen sections for [engine]. */
    fun build(fields: List<RawField>, engine: String?): String {
        val rawByName = fields.associateBy { it.name }
        val accessed = mutableSetOf<String>()
        val tracking = object : Map<String, RawField> by rawByName {
            override fun get(key: String): RawField? { accessed.add(key); return rawByName[key] }
        }
        val sb = StringBuilder()
        sb.append(HEADERS.joinToString(",") { escape(it) }).append("\r\n")
        for (section in DetailSectionCatalog) {
            val visible = pruneBreaks(section.items.filter { fieldAppliesToEngine(it.engine, engine) })
            if (visible.none { it is DetailRow }) continue
            for (item in visible) {
                if (item is DetailRow) {
                    val value = item.value(tracking) ?: ""
                    val ford = fordFieldPath(item.rawNames, rawByName) ?: ""
                    sb.append(listOf(section.title, item.label, ford, value).joinToString(",") { escape(it) }).append("\r\n")
                }
            }
        }
        val others = fields.filter { it.name !in accessed }.sortedBy { it.name }
        for (f in others) {
            sb.append(listOf("Other", f.name, f.path ?: "", f.display).joinToString(",") { escape(it) }).append("\r\n")
        }
        return sb.toString()
    }

    /**
     * Writes [fields] to a CSV in the app cache and opens the system share sheet.
     * [label] becomes part of the filename (e.g. the vehicle title).
     */
    fun share(context: Context, label: String, fields: List<RawField>, engine: String?) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeLabel = label.ifBlank { "fields" }.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').ifBlank { "fields" }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(Instant.now().atZone(ZoneId.systemDefault()))
        val file = File(dir, "gohenry-fields-$safeLabel-$stamp.csv")
        file.writeText(build(fields, engine))

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "GoHenry raw fields — $label")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share raw fields").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    /** RFC-4180 field escaping: always quote, doubling any embedded quotes. */
    private fun escape(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
}
