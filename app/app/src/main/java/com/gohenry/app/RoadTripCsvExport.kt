package com.gohenry.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Exports road-trip history — every trip plus all the events on its timeline — to
 * a CSV file and hands it to the Android share sheet. One row per timeline event,
 * each carrying the parent trip's summary columns so a flat spreadsheet still
 * groups cleanly by trip. Trips with no events still emit a single summary row.
 *
 * Trip timelines only come back from the per-trip detail endpoint, so the caller
 * (the ViewModel) fetches each trip's detail before building the CSV.
 */
object RoadTripCsvExport {

    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    private val HEADERS = listOf(
        "TripName", "TripStatus", "Vehicle", "VIN",
        "TripStartedAt", "TripEndedAt", "DistanceKm", "Segments", "ChargeStops", "TripEventCount",
        "StartMethod", "EndReason",
        "EventDate", "EventTime", "Event", "Detail", "Latitude", "Longitude", "EventTimestampUtc",
    )

    /**
     * Builds the CSV text (with header row) for [trips]. [labelByVin] maps a VIN to
     * its on-screen vehicle nickname; missing VINs fall back to the raw VIN.
     */
    fun build(trips: List<RoadTrip>, labelByVin: Map<String, String>): String {
        val sb = StringBuilder()
        sb.append(HEADERS.joinToString(",") { escape(it) }).append("\r\n")
        for (trip in trips) {
            val vehicle = labelByVin[trip.vin] ?: trip.vin
            val tripCells = listOf(
                trip.name,
                trip.status,
                vehicle,
                trip.vin,
                trip.startedAt ?: "",
                trip.endedAt ?: "",
                trip.distanceKm?.let { it.toInt().toString() } ?: "",
                trip.segmentCount.toString(),
                trip.chargeStops.toString(),
                trip.eventCount.toString(),
                trip.startMethod,
                trip.endReason ?: "",
            )
            if (trip.timeline.isEmpty()) {
                // Keep the trip visible even with no captured events.
                sb.append((tripCells + listOf("", "", "", "", "", "", "")).joinToString(",") { escape(it) }).append("\r\n")
            } else {
                for (ev in trip.timeline) {
                    val (date, time) = localDateTime(ev.ts)
                    val eventCells = listOf(
                        date,
                        time,
                        ev.event,
                        ev.detail ?: "",
                        ev.latitude?.toString() ?: "",
                        ev.longitude?.toString() ?: "",
                        ev.ts,
                    )
                    sb.append((tripCells + eventCells).joinToString(",") { escape(it) }).append("\r\n")
                }
            }
        }
        return sb.toString()
    }

    /**
     * Writes [trips] to a CSV in the app cache and opens the system share sheet.
     * [label] becomes part of the filename (e.g. the vehicle nickname or "all").
     */
    fun share(context: Context, label: String, trips: List<RoadTrip>, labelByVin: Map<String, String>) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeLabel = label.ifBlank { "roadtrips" }.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').ifBlank { "roadtrips" }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(Instant.now().atZone(ZoneId.systemDefault()))
        val file = File(dir, "gohenry-roadtrips-$safeLabel-$stamp.csv")
        file.writeText(build(trips, labelByVin))

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "GoHenry road trips — $label")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share road trip history").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    /** Parses an ISO-8601 timestamp into local (date, time) cells; blanks on failure. */
    private fun localDateTime(iso: String?): Pair<String, String> {
        if (iso.isNullOrBlank()) return "" to ""
        return try {
            val z = Instant.parse(iso).atZone(ZoneId.systemDefault())
            z.format(DATE_FMT) to z.format(TIME_FMT)
        } catch (t: Throwable) {
            "" to ""
        }
    }

    /** RFC-4180 field escaping: always quote, doubling any embedded quotes. */
    private fun escape(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
}
