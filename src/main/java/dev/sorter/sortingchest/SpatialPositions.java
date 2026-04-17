package dev.sorter.sortingchest;

import com.hypixel.hytale.component.spatial.SpatialData;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Cross-build adapter for {@link SpatialData#getVector(int)}. Hytale's release
 * jar returns {@code com.hypixel.hytale.math.vector.Vector3d} from that method;
 * the pre-release jar migrated the same API to {@code org.joml.Vector3d}.
 * Our bytecode, compiled against one or the other, would NoSuchMethodError at
 * the other side.
 *
 * This class resolves the method at class-load time via {@link MethodHandles},
 * picking whichever signature the running JVM can find. It also resolves the
 * x/y/z accessors on the chosen return type — JOML exposes public fields,
 * Hytale's used getter methods, so we try both. Internally everything is a
 * {@code double}, so callers just get a {@link Pos}.
 *
 * If neither signature resolves (a future Hytale build changes the shape
 * again), {@link #isCompatible()} returns false and {@link #read} throws.
 * Callers should gate on {@code isCompatible()} at startup and route to
 * {@link SortingChestPlugin#markDisabled} if false.
 */
public final class SpatialPositions {

    private static final MethodHandle GET_VECTOR;
    private static final MethodHandle GET_X;
    private static final MethodHandle GET_Y;
    private static final MethodHandle GET_Z;
    private static final boolean COMPATIBLE;
    private static final String DIAGNOSTIC;

    private SpatialPositions() {}

    static {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        MethodHandle getVector = null;
        MethodHandle getX = null;
        MethodHandle getY = null;
        MethodHandle getZ = null;
        String diagnostic;

        Class<?> returnType = tryResolveGetVectorReturnType(lookup);
        if (returnType == null) {
            diagnostic = "SpatialData.getVector(int): neither the hytale nor the joml Vector3d signature resolved";
        } else {
            try {
                getVector = lookup.findVirtual(
                    SpatialData.class, "getVector", MethodType.methodType(returnType, int.class));
                getX = resolveAccessor(lookup, returnType, "x", "getX");
                getY = resolveAccessor(lookup, returnType, "y", "getY");
                getZ = resolveAccessor(lookup, returnType, "z", "getZ");
                if (getX == null || getY == null || getZ == null) {
                    diagnostic = "x/y/z accessors on " + returnType.getName() + " not resolvable as fields or getters";
                    getVector = null;
                } else {
                    diagnostic = "resolved against " + returnType.getName();
                }
            } catch (Throwable t) {
                diagnostic = "SpatialData.getVector resolution failed: " + t;
                getVector = null;
            }
        }

        GET_VECTOR = getVector;
        GET_X = getX;
        GET_Y = getY;
        GET_Z = getZ;
        COMPATIBLE = getVector != null && getX != null && getY != null && getZ != null;
        DIAGNOSTIC = diagnostic;
    }

    public static boolean isCompatible() {
        return COMPATIBLE;
    }

    /**
     * Human-readable description of what the shim found (or didn't find) at
     * class-load time. Safe to log.
     */
    public static String diagnostic() {
        return DIAGNOSTIC;
    }

    /**
     * Reads the position at {@code index} from {@code data} and materializes
     * it as a {@link Pos}. Throws if the shim is not compatible with the
     * running Hytale build.
     */
    public static Pos read(SpatialData<?> data, int index) throws Throwable {
        if (!COMPATIBLE) {
            throw new IllegalStateException(
                "SpatialPositions shim not compatible with this Hytale build: " + DIAGNOSTIC);
        }
        Object vec = GET_VECTOR.invoke(data, index);
        if (vec == null) return null;
        double x = (double) GET_X.invoke(vec);
        double y = (double) GET_Y.invoke(vec);
        double z = (double) GET_Z.invoke(vec);
        return new Pos(x, y, z);
    }

    private static Class<?> tryResolveGetVectorReturnType(MethodHandles.Lookup lookup) {
        // Try the hytale-native Vector3d first (release build).
        Class<?> hytaleVec = tryClass("com.hypixel.hytale.math.vector.Vector3d");
        if (hytaleVec != null && canFindGetVector(lookup, hytaleVec)) {
            return hytaleVec;
        }
        // Fall through to joml (pre-release; will likely become the only path once
        // Hytale promotes the migration to release).
        Class<?> jomlVec = tryClass("org.joml.Vector3d");
        if (jomlVec != null && canFindGetVector(lookup, jomlVec)) {
            return jomlVec;
        }
        return null;
    }

    private static boolean canFindGetVector(MethodHandles.Lookup lookup, Class<?> returnType) {
        try {
            lookup.findVirtual(SpatialData.class, "getVector", MethodType.methodType(returnType, int.class));
            return true;
        } catch (NoSuchMethodException | IllegalAccessException e) {
            return false;
        }
    }

    private static Class<?> tryClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /** Resolve a coordinate getter as either a public field (JOML) or a getter method (Hytale). */
    private static MethodHandle resolveAccessor(
        MethodHandles.Lookup lookup, Class<?> owner, String fieldName, String getterName
    ) {
        // Field form (JOML: public double x/y/z).
        try {
            return lookup.findGetter(owner, fieldName, double.class);
        } catch (NoSuchFieldException | IllegalAccessException ignored) {
            // fall through
        }
        // Method form (Hytale's Vector3d: double getX()).
        try {
            return lookup.findVirtual(owner, getterName, MethodType.methodType(double.class));
        } catch (NoSuchMethodException | IllegalAccessException ignored) {
            return null;
        }
    }
}
