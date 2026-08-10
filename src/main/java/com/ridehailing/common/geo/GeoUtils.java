package com.ridehailing.common.geo;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Great circle distance. Used for trip distance and for sanity checks. */
public final class GeoUtils {

    private static final double EARTH_RADIUS_KM = 6371.0088;
    public static final int DISTANCE_SCALE = 3;

    private GeoUtils() {
    }

    public static BigDecimal distanceKm(BigDecimal fromLat, BigDecimal fromLng, BigDecimal toLat, BigDecimal toLng) {
        return distanceKm(fromLat.doubleValue(), fromLng.doubleValue(), toLat.doubleValue(), toLng.doubleValue());
    }

    public static BigDecimal distanceKm(double fromLat, double fromLng, double toLat, double toLng) {
        double dLat = Math.toRadians(toLat - fromLat);
        double dLng = Math.toRadians(toLng - fromLng);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(fromLat)) * Math.cos(Math.toRadians(toLat))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return BigDecimal.valueOf(EARTH_RADIUS_KM * c).setScale(DISTANCE_SCALE, RoundingMode.HALF_UP);
    }

    public static boolean isValidLatitude(BigDecimal latitude) {
        return latitude != null && latitude.doubleValue() >= -90 && latitude.doubleValue() <= 90;
    }

    public static boolean isValidLongitude(BigDecimal longitude) {
        return longitude != null && longitude.doubleValue() >= -180 && longitude.doubleValue() <= 180;
    }
}
