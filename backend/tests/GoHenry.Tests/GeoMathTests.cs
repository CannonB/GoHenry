using FluentAssertions;
using GoHenry.Core.Geo;
using Xunit;

namespace GoHenry.Tests;

/// <summary>
/// Tests for the server-side trip-distance helper that backs the "Trip X.X km"
/// segment on a <c>trip.ended</c> push (moved off the phone so it no longer depends
/// on local notification history).
/// </summary>
public class GeoMathTests
{
    [Fact]
    public void Haversine_matches_known_distance()
    {
        // London ↔ Paris is ~343 km as the crow flies.
        var km = GeoMath.HaversineKm(51.5074, -0.1278, 48.8566, 2.3522);
        km.Should().BeInRange(330.0, 355.0);
    }

    [Fact]
    public void Haversine_is_zero_for_identical_points()
    {
        GeoMath.HaversineKm(47.6062, -122.3321, 47.6062, -122.3321).Should().BeApproximately(0.0, 1e-9);
    }

    [Fact]
    public void TripDistance_measures_start_to_end()
    {
        // Portland → Salem, OR.
        var km = GeoMath.TripDistanceKm(45.5152, -122.6784, 44.9429, -123.0351);
        km.Should().NotBeNull();
        km!.Value.Should().BeApproximately(GeoMath.HaversineKm(45.5152, -122.6784, 44.9429, -123.0351), 1e-9);
    }

    [Theory]
    [InlineData(null, -122.0, 44.9, -123.0)]
    [InlineData(45.5, null, 44.9, -123.0)]
    [InlineData(45.5, -122.0, null, -123.0)]
    [InlineData(45.5, -122.0, 44.9, null)]
    public void TripDistance_is_null_when_any_endpoint_missing(double? sLat, double? sLon, double? eLat, double? eLon)
    {
        GeoMath.TripDistanceKm(sLat, sLon, eLat, eLon).Should().BeNull();
    }
}
