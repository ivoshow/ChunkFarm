package dev.ivoshow.chunkfarm.util;

/**
 * Packs a block position that is known to live inside one chunk into a single long,
 * so the per-farm crop cache can be a primitive array instead of a list of objects.
 *
 * <p>Layout: {@code y} in the high bits, then the chunk-local {@code x} and {@code z}
 * nibbles. {@code y} is stored biased so negative world heights keep their ordering.
 */
public final class BlockPos {

    private static final int Y_BIAS = 2048;

    private BlockPos() {
    }

    public static long pack(int localX, int y, int localZ) {
        return ((long) (y + Y_BIAS) << 8) | ((localX & 0xF) << 4) | (localZ & 0xF);
    }

    public static int localX(long packed) {
        return (int) ((packed >> 4) & 0xF);
    }

    public static int localZ(long packed) {
        return (int) (packed & 0xF);
    }

    public static int y(long packed) {
        return (int) (packed >> 8) - Y_BIAS;
    }
}
