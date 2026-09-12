package com.ztraqto.openxross.service;

import com.ztraqto.openxross.XrossEngine;
import com.ztraqto.openxross.api.IService;
import com.ztraqto.openxross.config.XrossEditorConfiguration;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/** Protects data that may only be disclosed through an authorized Editor session. */
public final class EditorSensitiveDataService implements IService {
    private static final String PREFIX = "XES1";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final XrossEditorConfiguration configuration;
    private final SecureRandom random = new SecureRandom();
    private byte[] masterKey;

    public EditorSensitiveDataService(XrossEditorConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override public void init(XrossEngine engine) {
        if (configuration.isEnabled()) masterKey = deriveMasterKey(configuration.signingSecret());
    }

    @Override public void shutdown() {
        if (masterKey != null) java.util.Arrays.fill(masterKey, (byte) 0);
        masterKey = null;
    }

    @Override public String getName() { return "EditorSensitiveDataService"; }

    public String protect(long guildId, String field, String plaintext) {
        return protect(guildId, "editor", field, plaintext);
    }

    public String protect(long guildId, String owner, String field, String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return "";
        requireReady();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(guildId, owner, field));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return PREFIX + "." + encoder.encodeToString(iv) + "." + encoder.encodeToString(encrypted);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Sensitive Editor data could not be protected.", exception);
        }
    }

    String reveal(long guildId, String field, String protectedValue) {
        return reveal(guildId, "editor", field, protectedValue);
    }

    public String reveal(long guildId, String owner, String field, String protectedValue) {
        if (protectedValue == null || protectedValue.isBlank()) return "";
        requireReady();
        String[] parts = protectedValue.split("\\.", -1);
        if (parts.length != 3 || !PREFIX.equals(parts[0])) throw new IllegalArgumentException("Unsupported protected data.");
        try {
            Base64.Decoder decoder = Base64.getUrlDecoder();
            byte[] iv = decoder.decode(parts[1]);
            byte[] encrypted = decoder.decode(parts[2]);
            if (iv.length != IV_BYTES || encrypted.length < 17) throw new IllegalArgumentException("Invalid protected data.");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(masterKey, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(guildId, owner, field));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Protected Editor data could not be revealed.", exception);
        }
    }

    public String blind(long guildId, String owner, String field, String value) {
        if (value == null || value.isBlank()) return "";
        requireReady();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(masterKey, "HmacSHA256"));
            mac.update(aad(guildId, owner, field));
            mac.update((byte) 0);
            byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Sensitive blind index could not be created.", exception);
        }
    }

    private void requireReady() {
        if (masterKey == null) throw new IllegalStateException("Sensitive Editor data protection is unavailable.");
    }

    private static byte[] aad(long guildId, String owner, String field) {
        if (owner == null || !owner.matches("[a-z][a-z0-9-]{0,63}")) throw new IllegalArgumentException("Invalid protected owner.");
        if (field == null || !field.matches("[a-z][a-z0-9-]{0,63}")) throw new IllegalArgumentException("Invalid protected field.");
        String prefix = "editor".equals(owner) ? "xecute-editor:" : "xecute-sensitive:" + owner + ":";
        return (prefix + Long.toUnsignedString(guildId) + ":" + field).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] deriveMasterKey(String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal("xecute-editor-sensitive-v1".getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Sensitive Editor key derivation is unavailable.", exception);
        }
    }
}
