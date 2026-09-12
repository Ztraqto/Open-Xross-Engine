package com.ztraqto.openxross.service;

import org.junit.jupiter.api.Test;
import com.ztraqto.openxross.config.XrossEditorConfiguration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EditorSensitiveDataServiceTest {
    @Test
    void protectsValuesForOneGuildAndFieldOnly() {
        EditorSensitiveDataService service = new EditorSensitiveDataService(XrossEditorConfiguration.builder()
                .editorUrl("https://editor.example/")
                .signingSecret("12345678901234567890123456789012")
                .build());
        service.init(null);

        String first = service.protect(42L, "ip-address", "203.0.113.7");
        String second = service.protect(42L, "ip-address", "203.0.113.7");
        assertNotEquals(first, second);
        assertEquals("203.0.113.7", service.reveal(42L, "ip-address", first));
        assertThrows(IllegalArgumentException.class, () -> service.reveal(43L, "ip-address", first));
        assertThrows(IllegalArgumentException.class, () -> service.reveal(42L, "email-address", first));

        String pluginValue = service.protect(42L, "ai-report", "api-key", "secret-value");
        assertEquals("secret-value", service.reveal(42L, "ai-report", "api-key", pluginValue));
        assertThrows(IllegalArgumentException.class,
                () -> service.reveal(42L, "anonymous-send", "api-key", pluginValue));
        assertEquals(service.blind(42L, "ai-report", "user-score", "123"),
                service.blind(42L, "ai-report", "user-score", "123"));
        assertNotEquals(service.blind(42L, "ai-report", "user-score", "123"),
                service.blind(42L, "ai-report", "user-score", "124"));
    }
}
