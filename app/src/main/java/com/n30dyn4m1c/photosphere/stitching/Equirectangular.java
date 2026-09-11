package com.n30dyn4m1c.photosphere.stitching;

/**
 * The equirectangular canvas: longitude across, latitude down.
 *
 * The canvas is always drawn with square pixels — a row maps to the same number
 * of degrees as a column — so its shape follows the extent of sphere it covers.
 * A full sphere spans 360° of longitude and 180° of latitude, which is the 2:1
 * canvas everything else assumes by default. A ring capture spans the same 360°
 * of longitude but only the band of latitude the frames cover, and a hemisphere
 * would span 180° about its own centre. The four helpers here all take that
 * window ({@code longitudeSpanDegrees}/{@code centerLongitudeDegrees} and
 * {@code latitudeSpanDegrees}/{@code centerLatitudeDegrees}) with the full-sphere values as
 * defaults, and the renderer, the footprints and the stitcher thread the same
 * four numbers through.
 *
 * Longitude runs {@code center - span/2}..{@code center + span/2} left to right and matches
 * the yaw convention used everywhere else, so a frame shot facing north lands
 * in the middle of a full-sphere canvas. Pixel centres are sampled at the
 * half-pixel.
 */
public final class Equirectangular {

    private Equirectangular() {}

    /** Longitude sampled at the centre of column {@code col}. */
    public static double longitudeDegrees(int col, int width) {
        return longitudeDegrees(col, width, 360f, 0f);
    }

    public static double longitudeDegrees(
        int col,
        int width,
        float longitudeSpanDegrees,
        float centerLongitudeDegrees
    ) {
        return centerLongitudeDegrees
            + (col + 0.5) / width * longitudeSpanDegrees
            - longitudeSpanDegrees / 2;
    }

    /** Latitude sampled at the centre of row {@code row}. */
    public static double latitudeDegrees(int row, int height) {
        return latitudeDegrees(row, height, 180f, 0f);
    }

    public static double latitudeDegrees(
        int row,
        int height,
        float latitudeSpanDegrees,
        float centerLatitudeDegrees
    ) {
        return centerLatitudeDegrees + latitudeSpanDegrees / 2
            - (row + 0.5) / height * latitudeSpanDegrees;
    }

    /** Column a longitude falls in. May sit outside {@code 0..width-1} before wrapping. */
    public static double columnFor(double longitudeDegrees, int width) {
        return columnFor(longitudeDegrees, width, 360f, 0f);
    }

    public static double columnFor(
        double longitudeDegrees,
        int width,
        float longitudeSpanDegrees,
        float centerLongitudeDegrees
    ) {
        return (longitudeDegrees - centerLongitudeDegrees + longitudeSpanDegrees / 2)
            / longitudeSpanDegrees * width - 0.5;
    }

    /** Row a latitude falls in, clamped to the canvas. */
    public static double rowFor(double latitudeDegrees, int height) {
        return rowFor(latitudeDegrees, height, 180f, 0f);
    }

    public static double rowFor(
        double latitudeDegrees,
        int height,
        float latitudeSpanDegrees,
        float centerLatitudeDegrees
    ) {
        return (centerLatitudeDegrees + latitudeSpanDegrees / 2 - latitudeDegrees)
            / latitudeSpanDegrees * height - 0.5;
    }

    /** Unit world direction at a longitude/latitude, in the X east, Y north, Z up frame. */
    public static double[] direction(double longitudeDegrees, double latitudeDegrees) {
        double lon = Math.toRadians(longitudeDegrees);
        double lat = Math.toRadians(latitudeDegrees);
        double cosLat = Math.cos(lat);
        return new double[] {Math.sin(lon) * cosLat, Math.cos(lon) * cosLat, Math.sin(lat)};
    }

    /** Longitude of a world direction, -180°..180°. */
    public static double longitudeOf(double x, double y) {
        return Math.toDegrees(Math.atan2(x, y));
    }

    /** Latitude of a *unit* world direction, -90°..90°. */
    public static double latitudeOf(double z) {
        return Math.toDegrees(Math.asin(clamp(z, -1.0, 1.0)));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
