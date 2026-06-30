package com.gohenry.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Speed
import androidx.compose.ui.graphics.vector.ImageVector
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * One rendered line in a Detail-screen section.
 * - [DetailRow]   : a label + a value computed from the raw-field map (name -> [RawField]).
 * - [DetailBreak] : an in-section sub-header (e.g. the "Tire" breaker).
 *
 * [engine] is the workbook column-K scope ("BEV" | "HEV" | "BOTH") used for show/hide.
 */
sealed interface DetailItem {
    val engine: String
}

data class DetailRow(
    val label: String,
    override val engine: String,
    /** The raw-field name(s) this row reads, used to surface the Ford telemetry path. */
    val rawNames: List<String> = emptyList(),
    val value: (Map<String, RawField>) -> String?,
) : DetailItem

data class DetailBreak(
    val label: String,
    override val engine: String = "BOTH",
) : DetailItem

/** A collapsible group on the Detail screen (workbook column N), with a header icon. */
data class DetailSection(val title: String, val icon: ImageVector, val items: List<DetailItem>)

// ---- value helpers -------------------------------------------------------

private fun str(m: Map<String, RawField>, name: String): String? =
    m[name]?.value?.takeIf { it.isNotBlank() }

private fun num(m: Map<String, RawField>, name: String): Double? =
    m[name]?.value?.toDoubleOrNull()

/** Raw value with its original Ford unit appended, as-is (the default display). */
private fun raw(m: Map<String, RawField>, name: String): String? =
    m[name]?.takeIf { it.value.isNotBlank() }?.display

/** A plain field row that shows the raw value + unit verbatim. */
private fun field(label: String, lookupName: String, engine: String): DetailRow =
    DetailRow(label, engine, listOf(lookupName)) { m -> raw(m, lookupName) }

private val LOCAL_TS: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.getDefault()).withZone(ZoneId.systemDefault())

/** Parse an ISO-8601 instant and render it in the phone's local time zone. Falls back to the raw string. */
fun formatLocalTimestamp(rawValue: String?): String? {
    val s = rawValue?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val instant = runCatching { Instant.parse(s) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()
        ?: runCatching { ZonedDateTime.parse(s).toInstant() }.getOrNull()
    return instant?.let { LOCAL_TS.format(it) } ?: s
}

private fun hoursMinutes(totalMinutes: Double): String {
    val mins = totalMinutes.roundToInt().coerceAtLeast(0)
    return "%02d:%02d".format(mins / 60, mins % 60)
}

/** Merge a wheel's status + pressure into one "Status • Pressure" value. */
private fun tire(m: Map<String, RawField>, statusName: String, pressureName: String): String? {
    val parts = listOfNotNull(str(m, statusName), raw(m, pressureName))
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
}

// ---- catalog -------------------------------------------------------------

/**
 * The Detail-screen field catalog, transcribed from
 * `docs/GoHenry-Ford-Telemetry-Field-Mapping - Copy.xlsx` (all rows DETAIL=YES in
 * column M), grouped by column N (Detail Section) and tagged by column K (engine).
 * Labels are short, spaced, single-row names; lookup keys match the backend field
 * names verbatim (typos preserved) so values resolve against the wire payload.
 */
val DetailSectionCatalog: List<DetailSection> = listOf(
    DetailSection(
        "Battery", Icons.Default.BatteryChargingFull, listOf(
            field("SoC %", "SoCCharge%", "BEV"),
            field("Charge Display", "SoCChargeDisplayStatus", "BEV"),
            field("Charger Status", "ChargerStatus", "BEV"),
            field("Battery Temp", "BatteryTemp", "BEV"),
            field("Battery Current", "BatteryCurrent", "BEV"),
            field("Battery Voltage", "BatteryVoltage", "BEV"),
            field("Battery Capacity", "BatteryCapacity", "BEV"),
            field("Perf Status", "BatteryPrefStatus", "BEV"),
            DetailRow("Time to Full", "BEV", listOf("BatteryTime2Full")) { m -> num(m, "BatteryTime2Full")?.let { hoursMinutes(it) } },
            field("Charger Type", "ChargerType", "BEV"),
            field("Charger Comms", "ChargerCommunictionStatus", "BEV"),
            field("EV Distance", "BatteryTotalDistance", "BEV"),
            field("Battery Load", "BatteryLoad", "BEV"),
        )
    ),
    DetailSection(
        "Fuel", Icons.Default.LocalGasStation, listOf(
            DetailRow("Fuel Level", "HEV", listOf("FuelLevel")) { m -> num(m, "FuelLevel")?.let { "%.1f%%".format(it) } },
            field("Fuel Range", "FuelRange", "HEV"),
            field("Trip Economy", "FuelTripEconomy", "HEV"),
            field("Engine Speed", "EngineSpeed", "HEV"),
            field("Coolant Temp", "CoolantTemp", "HEV"),
        )
    ),
    DetailSection(
        "Motion", Icons.Default.Speed, listOf(
            DetailRow("Odometer", "BOTH", listOf("Odometer")) { m -> num(m, "Odometer")?.let { "%,.1f KM".format(it) } },
            DetailRow("Accel X", "BOTH", listOf("AccelerationX")) { m -> num(m, "AccelerationX")?.let { "%.2fg".format(it) } },
            DetailRow("Accel Y", "BOTH", listOf("AccelerationY")) { m -> num(m, "AccelerationY")?.let { "%.2fg".format(it) } },
            DetailRow("Accel Z", "BOTH", listOf("AccelerationZ")) { m -> num(m, "AccelerationZ")?.let { "%.2fg".format(it) } },
            field("Heading", "Heading", "BOTH"),
            field("Compass", "Compass", "BOTH"),
            field("Ignition", "IgnitionStatus", "BOTH"),
            field("Gear", "GearLever", "BOTH"),
            field("Brake Pedal", "BrakePedalStatus", "BOTH"),
            field("Outside Temp", "OutsideTemp", "BOTH"),
            field("Oil Life %", "OilLifeLeft%", "HEV"),
        )
    ),
    DetailSection(
        "Location", Icons.Default.LocationOn, listOf(
            DetailRow("Lat / Long", "BOTH", listOf("Latitude", "Logitude")) { m ->
                val lat = num(m, "Latitude")
                val lon = num(m, "Logitude")
                if (lat == null && lon == null) null
                else "${lat?.let { "%.3f".format(it) } ?: "—"}, ${lon?.let { "%.3f".format(it) } ?: "—"}"
            },
            field("Altitude", "Altitude", "BOTH"),
            DetailRow("Updated", "BOTH", listOf("PostionUpdateTimestamp")) { m -> formatLocalTimestamp(str(m, "PostionUpdateTimestamp")) },
        )
    ),
    DetailSection(
        "Vehicle", Icons.Default.DirectionsCar, listOf(
            field("Hybrid Mode", "HybridStatus", "HEV"),
            field("Lifecycle", "VehicleLifecycle", "BOTH"),
            field("Doors", "DoorLocked", "BOTH"),
            field("Alarm", "Alarm", "BOTH"),
            field("Hood", "HoodStatus", "BOTH"),
            field("Tailgate", "TailGateStatus", "BOTH"),
            field("Inner Tailgate", "InnerTailGateStatus", "BOTH"),
            field("Seats", "SeatOccupancy", "BOTH"),
            DetailBreak("Tire"),
            field("System", "TirePressureSystemStatus", "BOTH"),
            DetailRow("Front Left", "BOTH", listOf("TireFrontLeftStatus", "TirePressureFrontLeft")) { m -> tire(m, "TireFrontLeftStatus", "TirePressureFrontLeft") },
            DetailRow("Front Right", "BOTH", listOf("TireFrontRightStatus", "TirePressureFrontRight")) { m -> tire(m, "TireFrontRightStatus", "TirePressureFrontRight") },
            DetailRow("Rear Left", "BOTH", listOf("TireRearLeftStatus", "TirePressureRearLeft")) { m -> tire(m, "TireRearLeftStatus", "TirePressureRearLeft") },
            DetailRow("Rear Right", "BOTH", listOf("TireRearRightStatus", "TirePressureRearRight")) { m -> tire(m, "TireRearRightStatus", "TirePressureRearRight") },
            DetailBreak("General"),
            field("Interior Temp", "InteriorTemp", "BOTH"),
            field("Units", "SystemofMeasure", "BOTH"),
            DetailRow("Telemetry Time", "BOTH", listOf("FordTelemtryTimeStamp")) { m -> formatLocalTimestamp(str(m, "FordTelemtryTimeStamp")) },
            field("VIN", "Key", "BOTH"),
            field("Nickname", "Nickname", "BOTH"),
            field("Model", "Model", "BOTH"),
            field("Year", "modelyear", "BOTH"),
            field("Color", "Color", "BOTH"),
            field("Engine", "enginetype", "BOTH"),
            DetailRow("12V Battery", "BOTH", listOf("V12batteryStateOfCharge")) { m -> str(m, "V12batteryStateOfCharge")?.let { "$it%" } },
        )
    ),
)

/** Drop a [DetailBreak] that has no visible [DetailRow] before the next break/end. */
fun pruneBreaks(items: List<DetailItem>): List<DetailItem> {
    val out = mutableListOf<DetailItem>()
    items.forEachIndexed { i, item ->
        if (item is DetailBreak) {
            val hasFollowingRow = items.drop(i + 1).takeWhile { it !is DetailBreak }.any { it is DetailRow }
            if (hasFollowingRow) out.add(item)
        } else {
            out.add(item)
        }
    }
    return out
}

/**
 * Resolve the raw Ford telemetry field path(s) behind a [DetailRow] for the small
 * subtitle under its friendly name (and the CSV's "Ford Field" column). Looks up each
 * of the row's [DetailRow.rawNames] in the live field map for its Ford path, falling
 * back to the curated field name when the backend didn't surface a path. Returns null
 * when the row reads no named fields (e.g. a computed-only row).
 */
fun fordFieldPath(rawNames: List<String>, byName: Map<String, RawField>): String? {
    if (rawNames.isEmpty()) return null
    return rawNames
        .map { byName[it]?.path?.takeIf { p -> p.isNotBlank() } ?: it }
        .distinct()
        .joinToString(" • ")
        .takeIf { it.isNotBlank() }
}

/**
 * Whether a row tagged for [fieldEngine] ("BEV" | "HEV" | "BOTH") should show for a
 * vehicle of the given [vehicleEngine] type. Workbook column K drives this. BOTH always
 * shows; BEV-only rows show on battery/plug-in vehicles; HEV-only rows show on
 * hybrid/plug-in/gas vehicles. Unknown engine shows everything (don't hide real data).
 */
fun fieldAppliesToEngine(fieldEngine: String, vehicleEngine: String?): Boolean {
    val kind = engineVisualKind(vehicleEngine)
    if (kind == EngineVisualKind.Unknown) return true
    return when (fieldEngine.trim().uppercase()) {
        "BEV" -> kind == EngineVisualKind.BatteryElectric || kind == EngineVisualKind.PlugInHybrid
        "HEV" -> kind == EngineVisualKind.Hybrid || kind == EngineVisualKind.PlugInHybrid || kind == EngineVisualKind.Gas
        else -> true // BOTH or unrecognized tag
    }
}
