using System.Text.Json;

namespace GoHenry.Core.Normalization;

/// <summary>One resolved raw field ready for the wire/Raw Data screen.
/// <see cref="Path"/> is the raw Ford telemetry field path (e.g.
/// <c>metrics.xevBatteryChargeDisplayStatus.value</c>) surfaced under the friendly
/// name on the Raw Data screen and in its CSV export.</summary>
public sealed record RawFieldEntry(string Name, string Value, string? Unit, string Engine, string Path);

/// <summary>
/// Resolves every telemetry-sourced <see cref="RawFieldMap"/> path against a raw Ford
/// "metrics"-envelope payload and returns the present values as display strings (raw,
/// un-converted). Absent / null / wrong-shape fields are simply omitted, so an engine
/// only surfaces the metrics its feed actually carries.
///
/// Supported path grammar:
///   <list type="bullet">
///   <item><c>$.updateTime</c> — root property.</item>
///   <item><c>metrics.X.value</c> — metric leaf (metrics are {updateTime, value, …}).</item>
///   <item><c>metrics.acceleration.value.x</c> — nested scalar under a metric value.</item>
///   <item><c>metrics.position.value.location.lat</c> — deep object chain.</item>
///   <item><c>metrics.position.updateTime</c> — a metric's own metadata field.</item>
///   <item><c>metrics.ARR[KEY].value</c> — array element matched on a discriminator.</item>
///   <item><c>metrics.doorLockStatus[].value</c> — unindexed array (ALL_DOORS-first).</item>
///   </list>
/// Array discriminators mirror FleetFoot's FordPayloadNormalizer: <c>vehicleWheel</c>
/// for tire arrays, <c>vehicleDoor</c> for door arrays.
/// </summary>
public static class FordRawFieldExtractor
{
    public static IReadOnlyList<RawFieldEntry> Extract(string? fordJson)
    {
        var result = new List<RawFieldEntry>();
        if (string.IsNullOrWhiteSpace(fordJson)) return result;

        JsonDocument doc;
        try { doc = JsonDocument.Parse(fordJson); }
        catch (JsonException) { return result; }

        using (doc)
        {
            foreach (var def in RawFieldMap.Telemetry)
            {
                if (TryResolve(doc.RootElement, def.Path, out var el) && TryStringify(el, out var s))
                    result.Add(new RawFieldEntry(def.Name, s, def.Unit, def.Engine, def.Path));
            }
        }
        return result;
    }

    private readonly record struct Segment(string Name, bool IsArray, string? Key);

    private static IEnumerable<Segment> SplitSegments(string path)
    {
        foreach (var token in path.Split('.', StringSplitOptions.RemoveEmptyEntries))
        {
            var lb = token.IndexOf('[');
            if (lb < 0)
            {
                yield return new Segment(token, false, null);
                continue;
            }
            var name = token[..lb];
            var rb = token.IndexOf(']', lb);
            var inside = rb > lb ? token[(lb + 1)..rb] : "";
            yield return new Segment(name, true, inside.Length == 0 ? null : inside);
        }
    }

    private static bool TryResolve(JsonElement root, string path, out JsonElement found)
    {
        found = default;
        var current = root;
        foreach (var seg in SplitSegments(path))
        {
            if (seg.Name == "$") continue; // root anchor

            if (seg.IsArray)
            {
                if (current.ValueKind != JsonValueKind.Object ||
                    !current.TryGetProperty(seg.Name, out var arr) ||
                    arr.ValueKind != JsonValueKind.Array ||
                    !TryPickArrayElement(arr, seg.Key, seg.Name, out current))
                    return false;
            }
            else
            {
                if (current.ValueKind != JsonValueKind.Object ||
                    !current.TryGetProperty(seg.Name, out var next))
                    return false;
                current = next;
            }
        }
        found = current;
        return true;
    }

    private static string? DiscriminatorFor(string arrName) => arrName switch
    {
        "tirePressureStatus" or "tirePressure" => "vehicleWheel",
        "doorStatus" or "doorLockStatus" or "doorPresenceStatus" => "vehicleDoor",
        _ => null,
    };

    private static bool TryPickArrayElement(JsonElement arr, string? key, string arrName, out JsonElement picked)
    {
        picked = default;
        var disc = DiscriminatorFor(arrName);
        JsonElement? first = null;
        JsonElement? allDoors = null;

        foreach (var item in arr.EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.Object) continue;
            first ??= item;

            if (disc is not null &&
                item.TryGetProperty(disc, out var dv) &&
                dv.ValueKind == JsonValueKind.String)
            {
                var dval = dv.GetString();
                if (key is not null && string.Equals(dval, key, StringComparison.OrdinalIgnoreCase))
                {
                    picked = item;
                    return true;
                }
                if (key is null && string.Equals(dval, "ALL_DOORS", StringComparison.OrdinalIgnoreCase))
                    allDoors = item;
            }
        }

        if (key is null)
        {
            if (allDoors is { } a) { picked = a; return true; }
            if (first is { } f) { picked = f; return true; }
        }
        return false; // keyed-but-missing, or empty array
    }

    private static bool TryStringify(JsonElement el, out string value)
    {
        switch (el.ValueKind)
        {
            case JsonValueKind.Number:
                value = el.GetRawText(); // invariant, no rounding, no unit math
                return true;
            case JsonValueKind.String:
                value = el.GetString() ?? "";
                return value.Length > 0;
            case JsonValueKind.True:
                value = "true"; return true;
            case JsonValueKind.False:
                value = "false"; return true;
            case JsonValueKind.Object:
                if (el.TryGetProperty("value", out var inner))
                    return TryStringify(inner, out value);
                value = ""; return false;
            default:
                value = ""; return false;
        }
    }
}
