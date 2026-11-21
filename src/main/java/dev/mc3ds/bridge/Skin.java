package dev.mc3ds.bridge;

/** A plain 64x32 RGBA skin used for Java players shown on the 3DS. */
final class Skin {
    static final byte[] DEFAULT = new byte[64 * 32 * 4];
    static {
        for (int py = 0; py < 32; py++) {
            for (int px = 0; px < 64; px++) {
                int i = (py * 64 + px) * 4;
                int r, g, b;
                if (py < 16) { r = 0xB5; g = 0x86; b = 0x6A; }          // head: skin tone
                else if (px >= 40) { r = 0xB5; g = 0x86; b = 0x6A; }    // arms
                else if (px < 16) { r = 0x2E; g = 0x3B; b = 0x8C; }     // legs: blue
                else { r = 0x2A; g = 0xA1; b = 0xA6; }                  // body: teal
                if (py >= 8 && py < 16 && px >= 8 && px < 16 && (px == 9 || px == 14) && py == 12) { r = 0x20; g = 0x20; b = 0x60; } // eyes
                DEFAULT[i] = (byte) r; DEFAULT[i + 1] = (byte) g; DEFAULT[i + 2] = (byte) b; DEFAULT[i + 3] = (byte) 0xFF;
            }
        }
    }
}
