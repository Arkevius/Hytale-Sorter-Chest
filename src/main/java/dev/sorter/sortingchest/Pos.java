package dev.sorter.sortingchest;

/**
 * Neutral 3D position with no Hytale dependencies. Lets the rest of the plugin
 * carry positions without coupling to either flavor of Vector3d that Hytale
 * exposes across release / pre-release builds (hytale custom vs org.joml).
 * Conversion happens at the ECS boundary via {@link SpatialPositions#read}.
 */
public record Pos(double x, double y, double z) {

    public double distanceSquaredTo(Pos other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
