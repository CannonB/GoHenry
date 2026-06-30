namespace GoHenry.Core.Normalization;

/// <summary>Where a raw field's value comes from.</summary>
public enum RawFieldSource
{
    /// <summary>Resolved from the raw Ford telemetry JSON via a JSON path.</summary>
    Telemetry,
    /// <summary>Vehicle metadata already stored on the VIN row (not in the feed).</summary>
    Metadata,
}

/// <summary>
/// One row of the user-approved Ford → GoHenry field mapping
/// (see <c>docs/GoHenry-Ford-Telemetry-Field-Mapping.xlsx</c>, tab "Field Mapping").
/// <see cref="Name"/> is the app-facing display name/key exactly as the user authored
/// it (spellings preserved). <see cref="Path"/> is the dotted Ford JSON path for
/// telemetry fields, or the metadata source key for metadata fields.
/// </summary>
public sealed record RawFieldDef(
    string Name,
    string Path,
    string Type,
    string? Unit,
    string Engine,            // BEV | HEV | BOTH
    RawFieldSource Source);

/// <summary>
/// The canonical 60-row field map. This is the single source of truth for the
/// "full Ford datafeed" the poller captures and the Raw Data screen renders. Names
/// are kept verbatim from the user's workbook (including authored typos) so the app
/// surfaces exactly what they asked for.
/// </summary>
public static class RawFieldMap
{
    public static readonly IReadOnlyList<RawFieldDef> All = new[]
    {
        // --- BEV energy / charging ---
        new RawFieldDef("SoCCharge%",                "metrics.xevBatteryStateOfCharge.value",            "number", "%",       "BEV",  RawFieldSource.Telemetry),
        new RawFieldDef("SoCChargeDisplayStatus",    "metrics.xevBatteryChargeDisplayStatus.value",      "string", null,      "BEV",  RawFieldSource.Telemetry),
        new RawFieldDef("ChargerStatus",             "metrics.xevPlugChargerStatus.value",               "string", null,      "BEV",  RawFieldSource.Telemetry),
        new RawFieldDef("BatteryTemp",               "metrics.xevBatteryTemperature.value",              "number", "C",       "BEV",  RawFieldSource.Telemetry),
        new RawFieldDef("BatteryCurrent",            "metrics.xevBatteryIoCurrent.value",                "number", "A",       "BEV",  RawFieldSource.Telemetry),
        new RawFieldDef("BatteryVoltage",            "metrics.xevBatteryVoltage.value",                  "number", "V",       "BEV",  RawFieldSource.Telemetry),
        new RawFieldDef("BatteryCapacity",           "metrics.xevBatteryCapacity.value",                 "number", "kWh",     "BEV",  RawFieldSource.Telemetry),
        new RawFieldDef("BatteryPrefStatus",         "metrics.xevBatteryPerformanceStatus.value",        "string", null,      "BEV",  RawFieldSource.Telemetry),
        new RawFieldDef("BatteryTime2Full",          "metrics.xevBatteryTimeToFullCharge.value",         "number", "min",     "BEV",  RawFieldSource.Telemetry),
        new RawFieldDef("ChargerType",               "metrics.xevChargeStationPowerType.value",          "string", null,      "BEV",  RawFieldSource.Telemetry),
        new RawFieldDef("ChargerCommunictionStatus", "metrics.xevChargeStationCommunicationStatus.value","string", null,      "BEV",  RawFieldSource.Telemetry),
        new RawFieldDef("BatteryTotalDistance",      "metrics.tripXevBatteryDistanceAccumulated.value",  "number", "km",      "BEV",  RawFieldSource.Telemetry),
        new RawFieldDef("BatteryLoad",               "metrics.batteryLoadStatus.value",                  "string", null,      "BEV",  RawFieldSource.Telemetry),

        // --- HEV / ICE fuel + engine ---
        new RawFieldDef("FuelLevel",                 "metrics.fuelLevel.value",                          "number", "%",       "HEV",  RawFieldSource.Telemetry),
        new RawFieldDef("FuelRange",                 "metrics.fuelRange.value",                          "number", "km",      "HEV",  RawFieldSource.Telemetry),
        new RawFieldDef("FuelTripEconomy",           "metrics.tripFuelEconomy.value",                    "number", "L/100km", "HEV",  RawFieldSource.Telemetry),
        new RawFieldDef("Odometer",                  "metrics.odometer.value",                           "number", "km",      "BOTH", RawFieldSource.Telemetry),
        new RawFieldDef("EngineSpeed",               "metrics.engineSpeed.value",                        "number", "rpm",     "HEV",  RawFieldSource.Telemetry),
        new RawFieldDef("CoolantTemp",               "metrics.engineCoolantTemp.value",                  "number", "C",       "HEV",  RawFieldSource.Telemetry),

        // --- Motion / position ---
        new RawFieldDef("AccelerationX",             "metrics.acceleration.value.x",                     "number", "g",       "BOTH", RawFieldSource.Telemetry),
        new RawFieldDef("AccelerationY",             "metrics.acceleration.value.y",                     "number", "g",       "BOTH", RawFieldSource.Telemetry),
        new RawFieldDef("AccelerationZ",             "metrics.acceleration.value.z",                     "number", "g",       "BOTH", RawFieldSource.Telemetry),
        new RawFieldDef("Latitude",                  "metrics.position.value.location.lat",              "number", "deg",     "BOTH", RawFieldSource.Telemetry),
        new RawFieldDef("Logitude",                  "metrics.position.value.location.lon",              "number", "deg",     "BOTH", RawFieldSource.Telemetry),
        new RawFieldDef("Altitude",                  "metrics.position.value.location.alt",              "number", "m",       "BOTH", RawFieldSource.Telemetry),
        new RawFieldDef("PostionUpdateTimestamp",    "metrics.position.updateTime",                      "datetime","ISO8601","BOTH", RawFieldSource.Telemetry),
        new RawFieldDef("Heading",                   "metrics.heading.value",                            "number", "deg",     "BOTH", RawFieldSource.Telemetry),
        new RawFieldDef("Compass",                   "metrics.compassDirection.value",                   "string", null,      "BOTH", RawFieldSource.Telemetry),

        // --- Drive / status ---
        new RawFieldDef("IgnitionStatus",            "metrics.ignitionStatus.value",                     "string", null,      "BOTH", RawFieldSource.Telemetry),
        new RawFieldDef("GearLever",                 "metrics.gearLeverPosition.value",                  "string", null,      "BOTH", RawFieldSource.Telemetry),
        new RawFieldDef("HybridStatus",              "metrics.hybridVehicleModeStatus.value",            "string", null,      "HEV",  RawFieldSource.Telemetry),
        new RawFieldDef("VehicleLifecycle",          "metrics.vehicleLifeCycleMode.value",               "string", null,      "BOTH", RawFieldSource.Telemetry),
        new RawFieldDef("BrakePedalStatus",          "metrics.brakePedalStatus.value",                   "string", null,      "BOTH", RawFieldSource.Telemetry),

        // --- Security / body ---
        new RawFieldDef("DoorLocked",                "metrics.doorLockStatus[].value",                   "string", null,      "BOTH", RawFieldSource.Telemetry),
        new RawFieldDef("Alarm",                     "metrics.alarmStatus.value",                        "string", null,      "BOTH", RawFieldSource.Telemetry),
        new RawFieldDef("HoodStatus",                "metrics.hoodStatus.value",                         "string", null,      "BOTH", RawFieldSource.Telemetry),
        new RawFieldDef("TailGateStatus",            "metrics.doorStatus[TAILGATE].value",               "string", null,      "BOTH", RawFieldSource.Telemetry),
        new RawFieldDef("InnerTailGateStatus",       "metrics.doorStatus[INNER_TAILGATE].value",         "string", null,      "BOTH", RawFieldSource.Telemetry),
        new RawFieldDef("SeatOccupancy",             "metrics.seatBeltStatus[].value",                   "string", null,      "BOTH", RawFieldSource.Telemetry),

        // --- Tires ---
        new RawFieldDef("TirePressureSystemStatus",  "metrics.tirePressureSystemStatus.value",           "string", null,      "BOTH", RawFieldSource.Telemetry),
        new RawFieldDef("TireFrontLeftStatus",       "metrics.tirePressureStatus[FRONT_LEFT].value",     "string", null,      "BOTH", RawFieldSource.Telemetry),
        new RawFieldDef("TireFrontRightStatus",      "metrics.tirePressureStatus[FRONT_RIGHT].value",    "string", null,      "BOTH", RawFieldSource.Telemetry),
        new RawFieldDef("TireRearLeftStatus",        "metrics.tirePressureStatus[REAR_LEFT].value",      "string", null,      "BOTH", RawFieldSource.Telemetry),
        new RawFieldDef("TireRearRightStatus",       "metrics.tirePressureStatus[REAR_RIGHT].value",     "string", null,      "BOTH", RawFieldSource.Telemetry),
        new RawFieldDef("TirePressureFrontLeft",     "metrics.tirePressure[FRONT_LEFT].value",           "number", "kPa",     "BOTH", RawFieldSource.Telemetry),
        new RawFieldDef("TirePressureFrontRight",    "metrics.tirePressure[FRONT_RIGHT].value",          "number", "kPa",     "BOTH", RawFieldSource.Telemetry),
        new RawFieldDef("TirePressureRearLeft",      "metrics.tirePressure[REAR_LEFT].value",            "number", "kPa",     "BOTH", RawFieldSource.Telemetry),
        new RawFieldDef("TirePressureRearRight",     "metrics.tirePressure[REAR_RIGHT].value",           "number", "kPa",     "BOTH", RawFieldSource.Telemetry),

        // --- Climate / service ---
        new RawFieldDef("OutsideTemp",               "metrics.outsideTemperature.value",                 "number", "C",       "BOTH", RawFieldSource.Telemetry),
        new RawFieldDef("InteriorTemp",              "metrics.ambientTemp.value",                        "number", "C",       "BOTH", RawFieldSource.Telemetry),
        new RawFieldDef("OilLifeLeft%",              "metrics.oilLifeRemaining.value",                   "number", "%",       "HEV",  RawFieldSource.Telemetry),
        new RawFieldDef("SystemofMeasure",           "metrics.displaySystemOfMeasure.value",             "string", null,      "BOTH", RawFieldSource.Telemetry),
        new RawFieldDef("FordTelemtryTimeStamp",     "$.updateTime",                                     "datetime","ISO8601","BOTH", RawFieldSource.Telemetry),

        // --- Vehicle metadata (from the VIN row, not the feed) ---
        new RawFieldDef("Key",                       "vin",                                              "string", null,      "BOTH", RawFieldSource.Metadata),
        new RawFieldDef("Nickname",                  "nickName",                                         "string", null,      "BOTH", RawFieldSource.Metadata),
        new RawFieldDef("Model",                     "modelName",                                        "string", null,      "BOTH", RawFieldSource.Metadata),
        new RawFieldDef("modelyear",                 "modelYear",                                        "number", null,      "BOTH", RawFieldSource.Metadata),
        new RawFieldDef("Color",                     "color",                                            "string", null,      "BOTH", RawFieldSource.Metadata),
        new RawFieldDef("enginetype",                "engineType",                                       "string", null,      "BOTH", RawFieldSource.Metadata),

        // --- 12V auxiliary battery (kept distinct from the EV traction SoC) ---
        new RawFieldDef("V12batteryStateOfCharge",   "metrics.batteryStateOfCharge.value",               "string", null,      "BOTH", RawFieldSource.Telemetry),
    };

    public static IEnumerable<RawFieldDef> Telemetry => All.Where(f => f.Source == RawFieldSource.Telemetry);
    public static IEnumerable<RawFieldDef> Metadata => All.Where(f => f.Source == RawFieldSource.Metadata);
}
