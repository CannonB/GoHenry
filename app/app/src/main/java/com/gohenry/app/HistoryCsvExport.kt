package com.gohenry.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Exports captured alert history (whatever is currently filtered on screen) to a
 * CSV file and hands it to the Android share sheet. Columns mirror the on-screen
 * alert rows: when it happened, which car, the event, and the message/location.
 */
object HistoryCsvExport {

    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    private val HEADERS = listOf(
        "Date", "Time", "Vehicle", "VIN", "Event", "Title", "Message", "Latitude", "Longitude", "TimestampUtc",
    )

    /** Builds the CSV text (with header row) for [rows], newest first as shown. */
    fun build(rows: List<StoredNotification>): String {
        val sb = StringBuilder()
        sb.append(HEADERS.joinToString(",") { escape(it) }).append("\r\n")
        for (n in rows) {
            val zoned = Instant.ofEpochMilli(n.receivedAtMillis).atZone(ZoneId.systemDefault())
            val nickname = n.data["nickname"]?.takeIf { it.isNotBlank() } ?: n.vin
            val cells = listOf(
                zoned.format(DATE_FMT),
                zoned.format(TIME_FMT),
                nickname,
                n.vin,
                n.event ?: "",
                n.title,
                n.body,
                n.data["latitude"] ?: "",
                n.data["longitude"] ?: "",
                n.timestampUtc ?: "",
            )
            sb.append(cells.joinToString(",") { escape(it) }).append("\r\n")
        }
        return sb.toString()
    }

    /**
     * Writes [rows] to a CSV in the app cache and opens the system share sheet.
     * [label] becomes part of the filename (e.g. the vehicle nickname or "all").
     */
    fun share(context: Context, label: String, rows: List<StoredNotification>) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeLabel = label.ifBlank { "alerts" }.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').ifBlank { "alerts" }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(Instant.now().atZone(ZoneId.systemDefault()))
        val file = File(dir, "gohenry-alerts-$safeLabel-$stamp.csv")
        file.writeText(build(rows))

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "GoHenry alerts — $label")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share alert history").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    /** RFC-4180 field escaping: always quote, doubling any embedded quotes. */
    private fun escape(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
}
