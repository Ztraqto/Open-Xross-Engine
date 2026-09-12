package com.ztraqto.openxross.core.editor;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

public final class EditorAttachmentCodec {

    public static final String LINK_PREFIX = "XE4C.1";
    public static final String FILE_NAME = "XrossSession.png";

    private static final byte[] MAGIC = "XE4EA1".getBytes(StandardCharsets.US_ASCII);
    private static final int KEY_LENGTH = 32;
    private static final int IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    // The signed launch token can be larger than its 64 KiB compressed payload
    // after Base64URL encoding. Keep a bounded but sufficiently large PNG envelope.
    private static final int MAX_ATTACHMENT_BYTES = 128 * 1024;
    private static final int MAX_IMAGE_DIMENSION = 1024;
    private static final long MAX_IMAGE_PIXELS = 1_048_576L;

    private final SecureRandom random = new SecureRandom();

    public EncryptedAttachment encrypt(String editorToken) {
        if (editorToken == null || editorToken.isBlank()) {
            throw new IllegalArgumentException("Editor token is required.");
        }

        byte[] key = new byte[KEY_LENGTH];
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(key);
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(editorToken.getBytes(StandardCharsets.UTF_8));
            byte[] data = ByteBuffer.allocate(MAGIC.length + iv.length + encrypted.length)
                    .put(MAGIC)
                    .put(iv)
                    .put(encrypted)
                    .array();
            if (data.length > MAX_ATTACHMENT_BYTES) {
                throw new IllegalArgumentException("Encrypted Editor session is too large.");
            }
            return new EncryptedAttachment(
                    encodePng(data),
                    Base64.getUrlEncoder().withoutPadding().encodeToString(key)
            );
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("AES-GCM is unavailable.", exception);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    static String decrypt(byte[] data, String encodedKey) {
        data = decodePng(data);
        if (data == null || data.length < MAGIC.length + IV_LENGTH + 16 || data.length > MAX_ATTACHMENT_BYTES) {
            throw new IllegalArgumentException("Encrypted Editor session is invalid.");
        }
        for (int index = 0; index < MAGIC.length; index++) {
            if (data[index] != MAGIC[index]) {
                throw new IllegalArgumentException("Encrypted Editor session format is unsupported.");
            }
        }

        byte[] key;
        try {
            key = Base64.getUrlDecoder().decode(encodedKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Encrypted Editor session key is invalid.", exception);
        }
        if (key.length != KEY_LENGTH) {
            throw new IllegalArgumentException("Encrypted Editor session key is invalid.");
        }

        byte[] iv = Arrays.copyOfRange(data, MAGIC.length, MAGIC.length + IV_LENGTH);
        byte[] encrypted = Arrays.copyOfRange(data, MAGIC.length + IV_LENGTH, data.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalArgumentException("Encrypted Editor session could not be decrypted.", exception);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private static byte[] encodePng(byte[] payload) {
        int encodedLength = payload.length + Integer.BYTES;
        int pixels = (encodedLength + 2) / 3;
        int width = Math.max(1, (int) Math.ceil(Math.sqrt(pixels)));
        int height = (int) Math.ceil((double) pixels / width);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        byte[] bytes = ByteBuffer.allocate(encodedLength).putInt(payload.length).put(payload).array();
        for (int pixel = 0; pixel < width * height; pixel++) {
            int offset = pixel * 3;
            int red = offset < bytes.length ? Byte.toUnsignedInt(bytes[offset]) : 0;
            int green = offset + 1 < bytes.length ? Byte.toUnsignedInt(bytes[offset + 1]) : 0;
            int blue = offset + 2 < bytes.length ? Byte.toUnsignedInt(bytes[offset + 2]) : 0;
            image.setRGB(pixel % width, pixel / width, (red << 16) | (green << 8) | blue);
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) throw new IllegalStateException("PNG encoder is unavailable.");
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create Editor image attachment.", exception);
        }
    }

    private static byte[] decodePng(byte[] data) {
        if (data == null || data.length < 8) return data;
        if (data[0] != (byte) 0x89 || data[1] != 0x50 || data[2] != 0x4E || data[3] != 0x47) return data;
        validatePngDimensions(data);
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
            if (image == null) throw new IllegalArgumentException("Encrypted Editor image is invalid.");
            byte[] packed = new byte[image.getWidth() * image.getHeight() * 3];
            for (int pixel = 0; pixel < image.getWidth() * image.getHeight(); pixel++) {
                int rgb = image.getRGB(pixel % image.getWidth(), pixel / image.getWidth());
                int offset = pixel * 3;
                packed[offset] = (byte) (rgb >>> 16);
                packed[offset + 1] = (byte) (rgb >>> 8);
                packed[offset + 2] = (byte) rgb;
            }
            int length = ByteBuffer.wrap(packed, 0, Integer.BYTES).getInt();
            if (length < MAGIC.length + IV_LENGTH + 16 || length > MAX_ATTACHMENT_BYTES || length > packed.length - Integer.BYTES) {
                throw new IllegalArgumentException("Encrypted Editor image is invalid.");
            }
            return Arrays.copyOfRange(packed, Integer.BYTES, Integer.BYTES + length);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Encrypted Editor image is invalid.", exception);
        }
    }

    private static void validatePngDimensions(byte[] data) {
        // PNG signature (8) + IHDR length/type (8) + width/height (8).
        if (data.length < 24
                || data[12] != 'I' || data[13] != 'H' || data[14] != 'D' || data[15] != 'R') {
            throw new IllegalArgumentException("Encrypted Editor image is invalid.");
        }
        int width = ByteBuffer.wrap(data, 16, 4).getInt();
        int height = ByteBuffer.wrap(data, 20, 4).getInt();
        if (width <= 0 || height <= 0
                || width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION
                || (long) width * height > MAX_IMAGE_PIXELS) {
            throw new IllegalArgumentException("Encrypted Editor image dimensions are invalid.");
        }
    }

    public record EncryptedAttachment(byte[] data, String key) {
        public EncryptedAttachment {
            data = data.clone();
        }

        @Override
        public byte[] data() {
            return data.clone();
        }
    }
}
