package dev.mc3ds.bridge;

import java.util.ArrayList;
import java.util.List;

/**
 * Pushes the 3DS player's position out of blocks it slightly clips into (like Geyser's collision
 * correction). PE's collision is a little looser than Java's, e.g. when jumping onto a block edge,
 * and Paper silently pulls a player back if a move puts them inside a block.
 * Uses Java 1.12 collision shapes; blocks with complicated shapes are left alone.
 */
final class Collision {
    interface World { int javaState(int x, int y, int z); }

    static final double HALF_WIDTH = 0.3, HEIGHT = 1.8;
    /** only fix small overlaps (float error / PE's looser collision); bigger ones are real disagreements */
    static final double MAX_FIX = 0.08;

    private static final boolean[] PASSABLE = new boolean[256];
    private static final boolean[] UNKNOWN = new boolean[256];
    static {
        int[] passable = {0, 6, 8, 9, 10, 11, 27, 28, 30, 31, 32, 37, 38, 39, 40, 50, 51, 55, 59, 63, 65, 66, 68, 69, 70, 72, 75, 76,
                77, 83, 90, 104, 105, 106, 115, 119, 131, 132, 141, 142, 143, 147, 148, 157, 175, 176, 177, 207, 209, 217, 36};
        for (int id : passable) PASSABLE[id] = true;
        // complicated shapes we don't model: never "correct" against these
        int[] unknown = {64, 71, 193, 194, 195, 196, 197, 96, 167, 85, 113, 188, 189, 190, 191, 192, 107, 183, 184, 185, 186, 187,
                139, 101, 102, 160, 117, 118, 140, 144, 145, 154, 127, 198, 199, 200, 122, 111, 34, 29, 33};
        for (int id : unknown) UNKNOWN[id] = true;
    }

    /** collision boxes (minX,minY,minZ,maxX,maxY,maxZ relative to the block), or null if unknown */
    static List<double[]> boxes(int state) {
        int id = (state >> 4) & 0xFF, m = state & 15;
        List<double[]> l = new ArrayList<>(2);
        if (PASSABLE[id]) return l;
        if (UNKNOWN[id]) return null;
        switch (id) {
            case 44: case 126: case 182: case 205: // slabs
                l.add((m & 8) != 0 ? b(0, 0.5, 0, 1, 1, 1) : b(0, 0, 0, 1, 0.5, 1)); return l;
            case 26: l.add(b(0, 0, 0, 1, 0.5625, 1)); return l;                           // bed
            case 54: case 146: case 130: l.add(b(0.0625, 0, 0.0625, 0.9375, 0.875, 0.9375)); return l; // chests
            case 60: case 208: l.add(b(0, 0, 0, 1, 0.9375, 1)); return l;                 // farmland, path
            case 88: l.add(b(0, 0, 0, 1, 0.875, 1)); return l;                            // soul sand
            case 78: { double h = (m & 7) / 8.0; if (h > 0) l.add(b(0, 0, 0, 1, h, 1)); return l; } // snow layers
            case 171: l.add(b(0, 0, 0, 1, 0.0625, 1)); return l;                          // carpet
            case 81: l.add(b(0.0625, 0, 0.0625, 0.9375, 0.9375, 0.9375)); return l;      // cactus
            case 116: l.add(b(0, 0, 0, 1, 0.75, 1)); return l;                            // enchanting table
            case 120: l.add(b(0, 0, 0, 1, 0.8125, 1)); return l;                          // end portal frame
            case 151: case 178: l.add(b(0, 0, 0, 1, 0.375, 1)); return l;                 // daylight sensor
            case 93: case 94: case 149: case 150: l.add(b(0, 0, 0, 1, 0.125, 1)); return l; // repeater/comparator
            case 92: l.add(b(0.0625, 0, 0.0625, 0.9375, 0.5, 0.9375)); return l;          // cake (approx)
            default: l.add(b(0, 0, 0, 1, 1, 1)); return l;                                // full block
        }
    }
    private static double[] b(double a, double c, double d, double e, double f, double g) { return new double[]{a, c, d, e, f, g}; }

    static boolean isStairs(int id) {
        switch (id) { case 53: case 67: case 108: case 109: case 114: case 128: case 134: case 135: case 136:
            case 156: case 163: case 164: case 180: case 203: return true; default: return false; }
    }
    // facings: 0 north, 1 east, 2 south, 3 west (clockwise); 1.12 stairs meta&3: 0 east, 1 west, 2 south, 3 north
    private static final int[] STAIR_FACING = {1, 3, 2, 0};
    private static final int[][] OFFSET = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    /** Java 1.12 BlockStairs: shape (0 straight, 1 inner left, 2 inner right, 3 outer left, 4 outer right) from neighbours */
    static int stairShape(World w, int x, int y, int z, int state) {
        int f = STAIR_FACING[state & 3]; boolean top = (state & 4) != 0;
        int front = w.javaState(x + OFFSET[f][0], y, z + OFFSET[f][1]);
        if (isStairs((front >> 4) & 0xFF) && ((front & 4) != 0) == top) {
            int f1 = STAIR_FACING[front & 3];
            if ((f1 & 1) != (f & 1) && differentStairs(w, x, y, z, state, (f1 + 2) & 3)) return f1 == ((f + 3) & 3) ? 3 : 4;
        }
        int back = w.javaState(x - OFFSET[f][0], y, z - OFFSET[f][1]);
        if (isStairs((back >> 4) & 0xFF) && ((back & 4) != 0) == top) {
            int f2 = STAIR_FACING[back & 3];
            if ((f2 & 1) != (f & 1) && differentStairs(w, x, y, z, state, f2)) return f2 == ((f + 3) & 3) ? 1 : 2;
        }
        return 0;
    }
    private static boolean differentStairs(World w, int x, int y, int z, int state, int dir) {
        int n = w.javaState(x + OFFSET[dir][0], y, z + OFFSET[dir][1]);
        return !isStairs((n >> 4) & 0xFF) || (n & 7) != (state & 7);
    }

    /** Java 1.12 stairs collision boxes (slab + quarter and/or eighth, depending on the corner shape) */
    static List<double[]> stairBoxes(World w, int x, int y, int z, int state) {
        List<double[]> l = new ArrayList<>(3);
        boolean top = (state & 4) != 0;
        int f = STAIR_FACING[state & 3];
        int shape = stairShape(w, x, y, z, state);
        l.add(top ? b(0, 0.5, 0, 1, 1, 1) : b(0, 0, 0, 1, 0.5, 1));
        double y0 = top ? 0 : 0.5, y1 = top ? 0.5 : 1;
        if (shape == 0 || shape == 1 || shape == 2) { // quarter block on the facing side
            switch (f) {
                case 0: l.add(b(0, y0, 0, 1, y1, 0.5)); break;
                case 1: l.add(b(0.5, y0, 0, 1, y1, 1)); break;
                case 2: l.add(b(0, y0, 0.5, 1, y1, 1)); break;
                default: l.add(b(0, y0, 0, 0.5, y1, 1)); break;
            }
        }
        if (shape != 0) { // eighth block
            int f1 = shape == 3 ? f : shape == 4 ? (f + 1) & 3 : shape == 2 ? (f + 2) & 3 : (f + 3) & 3;
            switch (f1) {
                case 0: l.add(b(0, y0, 0, 0.5, y1, 0.5)); break;    // NW
                case 2: l.add(b(0.5, y0, 0.5, 1, y1, 1)); break;    // SE
                case 3: l.add(b(0, y0, 0.5, 0.5, y1, 1)); break;    // SW
                default: l.add(b(0.5, y0, 0, 1, y1, 0.5)); break;   // NE
            }
        }
        return l;
    }

    /** @return corrected {x, y, z} (same as input if nothing to fix) */
    static double[] correct(World w, double x, double y, double z) {
        for (int pass = 0; pass < 4; pass++) {
            double minX = x - HALF_WIDTH, maxX = x + HALF_WIDTH, minY = y, maxY = y + HEIGHT, minZ = z - HALF_WIDTH, maxZ = z + HALF_WIDTH;
            double bestPen = Double.MAX_VALUE; int bestAxis = -1; double bestMove = 0;
            double bestStairUpPen = Double.MAX_VALUE; boolean bestStairUp = false;
            for (int bx = (int) Math.floor(minX); bx <= (int) Math.floor(maxX - 1e-9); bx++)
                for (int by = (int) Math.floor(minY); by <= (int) Math.floor(maxY - 1e-9); by++)
                    for (int bz = (int) Math.floor(minZ); bz <= (int) Math.floor(maxZ - 1e-9); bz++) {
                        int st = w.javaState(bx, by, bz);
                        boolean stairs = isStairs((st >> 4) & 0xFF);
                        List<double[]> bl = stairs ? stairBoxes(w, bx, by, bz, st) : boxes(st);
                        if (bl == null) return new double[]{x, y, z}; // shape we don't know: don't touch anything
                        for (double[] r : bl) {
                            double x0 = bx + r[0], y0 = by + r[1], z0 = bz + r[2], x1 = bx + r[3], y1 = by + r[4], z1 = bz + r[5];
                            if (maxX <= x0 + 1e-7 || minX >= x1 - 1e-7 || maxY <= y0 + 1e-7 || minY >= y1 - 1e-7 || maxZ <= z0 + 1e-7 || minZ >= z1 - 1e-7) continue;
                            // overlap: candidate pushes along each axis (up preferred for floors)
                            double up = y1 - minY, down = maxY - y0;
                            double east = x1 - minX, west = maxX - x0, south = z1 - minZ, north = maxZ - z0;
                            double[][] c = {{up, 1, up}, {down, 1, -down}, {east, 0, east}, {west, 0, -west}, {south, 2, south}, {north, 2, -north}};
                            for (double[] cc : c) {
                                double pen = cc[0] + (cc[1] == 1 && cc[2] > 0 ? -1e-4 : 0); // tiny preference for pushing up
                                if (pen < bestPen) { bestPen = pen; bestAxis = (int) cc[1]; bestMove = cc[2]; bestStairUp = false; }
                            }
                            if (stairs && up <= 0.6 && up < bestStairUpPen) { bestStairUpPen = up; }
                        }
                    }
            if (bestAxis < 0) break;                    // not inside anything
            if (bestPen > MAX_FIX && bestStairUpPen <= 0.6) { // walked into a stair step the 3DS shapes differently: step onto it
                y += bestStairUpPen + 1e-5;
                continue;
            }
            if (bestPen > MAX_FIX) break;               // big overlap = real disagreement, let the server decide
            double mv = bestMove + Math.signum(bestMove) * 1e-5;
            if (bestAxis == 0) x += mv; else if (bestAxis == 1) y += mv; else z += mv;
        }
        return new double[]{x, y, z};
    }
}
