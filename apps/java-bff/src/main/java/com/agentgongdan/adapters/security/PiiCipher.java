package com.agentgongdan.adapters.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 轻量 PII 加密（AES-GCM）。PII_ENC_KEY 未配置时回退 JWT_SECRET，仅用于 dev。
 */
@Component
public class PiiCipher {

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public PiiCipher(@Value("${PII_ENC_KEY:}") String piiKey,
                     @Value("${JWT_SECRET:}") String jwtSecret) {
        String source = piiKey == null || piiKey.isBlank() ? jwtSecret : piiKey;
        if (source == null || source.isBlank()) {
            throw new IllegalStateException("PII_ENC_KEY 或 JWT_SECRET 至少配置一个");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(digest, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("初始化 PII 加密失败", e);
        }
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("PII 加密失败", e);
        }
    }

    public String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String value = phone.trim();
        if (value.length() <= 7) {
            return "****";
        }
        return value.substring(0, 3) + "****" + value.substring(value.length() - 4);
    }
}
