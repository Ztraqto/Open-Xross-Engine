package com.ztraqto.openxross.core.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorAttachmentCodecTest {

    @Test
    void encryptsEditorTokenForDiscordAttachmentTransport() {
        EditorAttachmentCodec codec = new EditorAttachmentCodec();
        String token = "XE4E.1.authorization.signature.payload";

        EditorAttachmentCodec.EncryptedAttachment encrypted = codec.encrypt(token);

        assertEquals(token, EditorAttachmentCodec.decrypt(encrypted.data(), encrypted.key()));
        byte[] attachment = encrypted.data();
        assertTrue(attachment.length >= 8);
        assertEquals((byte) 0x89, attachment[0]);
        assertEquals((byte) 0x50, attachment[1]);
        assertEquals((byte) 0x4E, attachment[2]);
        assertEquals((byte) 0x47, attachment[3]);
        assertTrue(encrypted.key().matches("[A-Za-z0-9_-]{43}"));
    }

    @Test
    void rejectsWrongDecryptionKey() {
        EditorAttachmentCodec codec = new EditorAttachmentCodec();
        EditorAttachmentCodec.EncryptedAttachment encrypted = codec.encrypt("XE4E.1.authorization.signature.payload");
        EditorAttachmentCodec.EncryptedAttachment other = codec.encrypt("other");

        assertThrows(
                IllegalArgumentException.class,
                () -> EditorAttachmentCodec.decrypt(encrypted.data(), other.key())
        );
    }
}
