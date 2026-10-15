package vn.edu.ltm.chess.repository;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public final class PasswordHasher {
    private static final int ITERATIONS = 120_000;
    private static final int KEY_LENGTH = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() { }

    public static Hash create(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return new Hash(Base64.getEncoder().encodeToString(hash(password, salt)),
                Base64.getEncoder().encodeToString(salt));
    }

    public static boolean verify(String password, String encodedHash, String encodedSalt) {
        byte[] expected = Base64.getDecoder().decode(encodedHash);
        byte[] actual = hash(password, Base64.getDecoder().decode(encodedSalt));
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] hash(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception exception) {
            throw new IllegalStateException("Không thể băm mật khẩu", exception);
        }
    }

    public record Hash(String value, String salt) { }
}
