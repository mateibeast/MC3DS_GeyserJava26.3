package dev.mc3ds.bridge;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Produces the encrypted "Floodgate data" that Geyser normally appends to the Java
 * handshake hostname, so Floodgate lets the player in without a Java account.
 * Format copied from GeyserMC common (AesCipher + Base64Topping + BedrockData, version 0).
 */
public final class Floodgate {
    private static final byte[] HEADER = ("^Floodgate^" + (char) (0x3E)).getBytes(StandardCharsets.UTF_8);
    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    private Floodgate(SecretKey key) { this.key = key; }

    public static Floodgate load(Path file) throws Exception {
        byte[] raw = Files.readAllBytes(file);
        if (raw.length != 16 && raw.length != 24 && raw.length != 32)
            throw new IllegalArgumentException("key.pem should be a raw AES key (16 bytes), got " + raw.length + " bytes");
        return new Floodgate(new SecretKeySpec(raw, "AES"));
    }

    /**
     * @param username player name (without Floodgate's "." prefix — Floodgate adds it)
     * @param xuid     numeric id; Floodgate turns it into the UUID 00000000-0000-0000-xxxx
     * @param ip       the player's real IP
     */
    public String encode(String username, long xuid, String ip) throws Exception {
        String data = "1.1.5" + '\0' + username + '\0' + xuid + '\0' +
                0 /* deviceOs: unknown */ + '\0' + "en_US" + '\0' +
                0 /* uiProfile classic */ + '\0' + 2 /* input: touch */ + '\0' + ip + '\0' +
                "null" + '\0' + 0 + '\0' + (-1) /* subscribeId: no skin upload */ + '\0' + "null";
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv = new byte[12];
        random.nextBytes(iv);
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(data.getBytes(StandardCharsets.UTF_8));
        byte[] ivB = Base64.getEncoder().encode(iv);
        byte[] ctB = Base64.getEncoder().encode(ct);
        ByteBuffer b = ByteBuffer.allocate(HEADER.length + ivB.length + 1 + ctB.length);
        b.put(HEADER).put(ivB).put((byte) 0x21).put(ctB);
        return new String(b.array(), StandardCharsets.UTF_8);
    }
}
