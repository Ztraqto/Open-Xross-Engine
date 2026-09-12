package com.ztraqto.openxross;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class XrossVersionTest {
    @Test
    void sourceVersionIs141() {
        assertEquals("1.4.1", XrossVersion.VERSION);
    }
}
