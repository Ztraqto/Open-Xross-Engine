package com.ztraqto.openxross.service;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.utils.FileUpload;
import com.ztraqto.openxross.XrossEngine;
import com.ztraqto.openxross.api.IService;
import com.ztraqto.openxross.config.XrossEditorConfiguration;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

/** Stores encrypted evidence in a configured private Discord channel. */
public final class AttachmentArchiveService implements IService {
    public static final int MAX_PLAINTEXT_BYTES = 8 * 1024 * 1024;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MAGIC = 0x58413441; // XA4A
    private static final int MAX_ENCRYPTED_BYTES = MAX_PLAINTEXT_BYTES + 4 + IV_BYTES + 32;
    private final EditorSensitiveDataService sensitive;
    private final XrossEditorConfiguration configuration;
    private final SecureRandom random = new SecureRandom();
    private XrossEngine engine;

    public AttachmentArchiveService(EditorSensitiveDataService sensitive, XrossEditorConfiguration configuration) {
        this.sensitive = sensitive;
        this.configuration = configuration;
    }

    @Override public void init(XrossEngine engine) { this.engine = engine; }
    @Override public void shutdown() { this.engine = null; }
    @Override public String getName() { return "AttachmentArchiveService"; }

    public boolean isUsable(Guild guild) {
        TextChannel channel = channel(guild);
        return channel != null && channel.getGuild().getSelfMember().hasPermission(channel,
                Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_ATTACH_FILES);
    }

    public CompletableFuture<ArchiveReference> archive(Guild guild, byte[] plaintext, String fileName,
                                                        String contentType, long expiresAt) {
        if (guild == null || plaintext == null || plaintext.length == 0 || plaintext.length > MAX_PLAINTEXT_BYTES) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Archive payload must be 1-8 MiB."));
        }
        TextChannel channel = channel(guild);
        if (channel == null || !isUsable(guild)) {
            return CompletableFuture.failedFuture(new IllegalStateException("Private attachment archive is unavailable."));
        }
        byte[] key = new byte[32];
        random.nextBytes(key);
        byte[] encrypted;
        try {
            encrypted = encrypt(key, plaintext);
        } catch (GeneralSecurityException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        String protectedKey = sensitive.protect(guild.getIdLong(), "attachment-archive", "file-key",
                Base64.getUrlEncoder().withoutPadding().encodeToString(key));
        java.util.Arrays.fill(key, (byte) 0);
        String safeName = safeName(fileName) + ".xa4";
        return channel.sendFiles(FileUpload.fromData(encrypted, safeName)).submit().thenApply(message ->
                new ArchiveReference(channel.getIdLong(), message.getIdLong(), safeName,
                        contentType == null ? "application/octet-stream" : contentType, protectedKey, expiresAt));
    }

    public CompletableFuture<byte[]> retrieve(Guild guild, ArchiveReference reference) {
        if (guild == null || reference == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Archive reference is required."));
        TextChannel channel = engine == null ? null : engine.getShardManager().getTextChannelById(reference.channelId());
        if (channel == null) return CompletableFuture.failedFuture(new IllegalStateException("Archive channel is missing."));
        return channel.retrieveMessageById(reference.messageId()).submit().thenCompose(message -> {
            if (message.getAttachments().isEmpty()) return CompletableFuture.failedFuture(new IllegalStateException("Archive attachment is missing."));
            return message.getAttachments().get(0).getProxy().download();
        }).thenApply(input -> {
            try (input) {
                byte[] encrypted = readBounded(input, MAX_ENCRYPTED_BYTES);
                byte[] key = Base64.getUrlDecoder().decode(sensitive.reveal(guild.getIdLong(),
                        "attachment-archive", "file-key", reference.protectedKey()));
                try { return decrypt(key, encrypted); }
                finally { java.util.Arrays.fill(key, (byte) 0); }
            } catch (Exception exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        });
    }

    public CompletableFuture<Boolean> delete(Guild guild, ArchiveReference reference) {
        if (reference == null) return CompletableFuture.completedFuture(false);
        TextChannel channel = engine == null ? null : engine.getShardManager().getTextChannelById(reference.channelId());
        if (channel == null) return CompletableFuture.completedFuture(false);
        return channel.deleteMessageById(reference.messageId()).submit().handle((ignored, failure) -> {
            if (failure == null) return true;
            Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                    ? failure.getCause() : failure;
            return cause instanceof ErrorResponseException response
                    && response.getErrorResponse() == ErrorResponse.UNKNOWN_MESSAGE;
        });
    }

    private TextChannel channel(Guild guild) {
        if (guild == null || engine == null || configuration.attachmentChannelId() == 0L) return null;
        return engine.getShardManager().getTextChannelById(configuration.attachmentChannelId());
    }

    private byte[] encrypt(byte[] key, byte[] plaintext) throws GeneralSecurityException {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
        byte[] payload = cipher.doFinal(plaintext);
        return ByteBuffer.allocate(4 + IV_BYTES + payload.length).putInt(MAGIC).put(iv).put(payload).array();
    }

    private static byte[] decrypt(byte[] key, byte[] encrypted) throws GeneralSecurityException {
        if (encrypted.length < 4 + IV_BYTES + 17) throw new GeneralSecurityException("Archive payload is truncated.");
        ByteBuffer buffer = ByteBuffer.wrap(encrypted);
        if (buffer.getInt() != MAGIC) throw new GeneralSecurityException("Archive payload has an invalid header.");
        byte[] iv = new byte[IV_BYTES];
        buffer.get(iv);
        byte[] payload = new byte[buffer.remaining()];
        buffer.get(payload);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
        return cipher.doFinal(payload);
    }


    private static byte[] readBounded(java.io.InputStream input, int limit) throws java.io.IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(Math.min(limit, 64 * 1024));
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        for (int read; (read = input.read(buffer)) != -1; ) {
            total += read;
            if (total > limit) throw new java.io.IOException("Encrypted archive exceeds the configured size limit.");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String safeName(String value) {
        String name = value == null ? "evidence" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        return name.isBlank() ? "evidence" : name.substring(0, Math.min(80, name.length()));
    }

    public record ArchiveReference(long channelId, long messageId, String fileName, String contentType,
                                   String protectedKey, long expiresAt) { }
}
