namespace GoHenry.Core.Geo;

/// <summary>
/// Great-circle helpers used to summarise how far a vehicle travelled. Computing
/// trip distance server-side (rather than on the phone from its local notification
/// history) keeps the figure correct even when the device missed the start-trip
/// push, reinstalled the app, or had on-device capture disabled.
/// </summary>
public static class GeoMath
{
    private const double EarthRadiusKm = 6371.0088;

    /// <summary>Great-circle distance in km between two lat/long points (Haversine).</summary>
    public static double HaversineKm(double lat1, double lon1, double lat2, double lon2)
    {
        var dLat = ToRadians(lat2 - lat1);
        var dLon = ToRadians(lon2 - lon1);
        var a = Math.Pow(Math.Sin(dLat / 2), 2) +
                Math.Cos(ToRadians(lat1)) * Math.Cos(ToRadians(lat2)) * Math.Pow(Math.Sin(dLon / 2), 2);
        return EarthRadiusKm * 2 * Math.Atan2(Math.Sqrt(a), Math.Sqrt(1 - a));
    }

    /// <summary>
    /// Straight-line distance (km) between a trip's start and end positions, or null
    /// when either endpoint is missing — so an end-trip alert with no captured start
    /// position simply carries no distance detail.
    /// </summary>
    public static double? TripDistanceKm(double? startLat, double? startLon, double? endLat, double? endLon)
    {
        if (startLat is not { } slat || startLon is not { } slon ||
            endLat is not { } elat || endLon is not { } elon)
            return null;
        return HaversineKm(slat, slon, elat, elon);
    }

    private static double ToRadians(double deg) => deg * Math.PI / 180.0;
}
