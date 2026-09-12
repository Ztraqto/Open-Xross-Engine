package com.ztraqto.openxross.core.console;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import java.util.function.Consumer;

public final class DiscordConsoleAppender extends AppenderBase<ILoggingEvent> {

    private final Consumer<ILoggingEvent> receiver;

    public DiscordConsoleAppender(Consumer<ILoggingEvent> receiver) {
        this.receiver = receiver;
    }

    @Override
    protected void append(ILoggingEvent eventObject) {
        receiver.accept(eventObject);
    }
}
