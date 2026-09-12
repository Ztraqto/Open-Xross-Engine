package com.ztraqto.openxross.runtime;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

final class XrossBotRuntimeTest {
    @Test
    void continuesWhenPluginWatcherCannotBeCreated() {
        assertDoesNotThrow(() -> XrossBotRuntime.startPluginWatcher(() -> {
            throw new IOException("inotify limit reached");
        }));
    }
}
