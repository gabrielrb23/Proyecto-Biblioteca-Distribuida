package edu.javeriana.biblioteca.common;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;

public class CryptoUtil {
    private static final String HMAC_ALGO = "HmacSHA256";
    private static final String AES_ALGO = "AES";
    private static final String AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128; // bits
    private static final int GCM_IV_LENGTH = 12; // bytes
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String PBKDF2_ALGO = "PBKDF2WithHmacSHA256";
    private static final int PBKDF2_ITER = 200_000;
    private static final int KEY_LEN = 256;

    public static String hmacBase64(String secretKey, String message) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance(HMAC_ALGO);
            javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGO);
            mac.init(keySpec);
            byte[] raw = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            throw new RuntimeException("Error computing HMAC", e);
        }
    }

    public static boolean verifyHmacBase64(String secretKey, String message, String signatureBase64) {
        String expected = hmacBase64(secretKey, message);
        return constantTimeEquals(expected, signatureBase64);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] aa = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        if (aa.length != bb.length) return false;
        int result = 0;
        for (int i = 0; i < aa.length; i++) result |= aa[i] ^ bb[i];
        return result == 0;
    }

    private static byte[] deriveAesKey(String secretKey) {
        try {
            String saltEnv = System.getenv("ACTOR_KEY_SALT");
            byte[] salt = (saltEnv != null && !saltEnv.isEmpty()) ? saltEnv.getBytes(StandardCharsets.UTF_8) : "biblioteca_default_salt_2025".getBytes(StandardCharsets.UTF_8);
            SecretKeyFactory skf = SecretKeyFactory.getInstance(PBKDF2_ALGO);
            KeySpec spec = new PBEKeySpec(secretKey.toCharArray(), salt, PBKDF2_ITER, KEY_LEN);
            byte[] key = skf.generateSecret(spec).getEncoded();
            return key;
        } catch (Exception e) {
            throw new RuntimeException("Error deriving AES key", e);
        }
    }

    public static String encryptAesGcmBase64(String secretKey, String plaintext) {
        try {
            byte[] keyBytes = deriveAesKey(secretKey);
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, AES_ALGO);

            byte[] iv = new byte[GCM_IV_LENGTH];
            RANDOM.nextBytes(iv);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);

            Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec);

            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("AES encryption error", e);
        }
    }

    public static String decryptAesGcmBase64(String secretKey, String base64IvCipherText) {
        try {
            byte[] combined = Base64.getDecoder().decode(base64IvCipherText);
            if (combined.length < GCM_IV_LENGTH) throw new IllegalArgumentException("invalid ciphertext");

            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            byte[] cipherText = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, GCM_IV_LENGTH, cipherText, 0, cipherText.length);

            byte[] keyBytes = deriveAesKey(secretKey);
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, AES_ALGO);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);

            Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, spec);

            byte[] plain = cipher.doFinal(cipherText);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (javax.crypto.AEADBadTagException ex) {
            throw new RuntimeException("Decryption authentication failed (tag mismatch). Possibly tampered ciphertext or wrong key.", ex);
        } catch (Exception e) {
            throw new RuntimeException("AES decryption error", e);
        }
    }
}
