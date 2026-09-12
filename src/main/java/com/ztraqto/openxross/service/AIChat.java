package com.ztraqto.openxross.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** A single asynchronous AI generation. */
public final class AIChat {

    private final UUID id = UUID.randomUUID();
    private final String profile;
    private final Instant queuedAt = Instant.now();
    private final AtomicReference<Status> status = new AtomicReference<>(Status.QUEUED);
    private final AtomicReference<ErrorInfo> error = new AtomicReference<>();
    private final CompletableFuture<String> completion = new CompletableFuture<>();
    private final StringBuilder text = new StringBuilder();
    private final AtomicReference<BooleanSupplier> cancelAction = new AtomicReference<>();

    AIChat(String profile) {
        this.profile = profile;
    }

    public UUID id() {
        return id;
    }

    public String profile() {
        return profile;
    }

    public Instant queuedAt() {
        return queuedAt;
    }

    public Status status() {
        return status.get();
    }

    /** JavaBean alias, also convenient for JVM languages with property syntax. */
    public Status getStatus() {
        return status();
    }

    /** Returns the text generated so far, or the complete text after success. */
    public synchronized String text() {
        return text.toString();
    }

    public String getText() {
        return text();
    }

    public CompletionStage<String> completion() {
        return completion;
    }

    public CompletionStage<String> getCompletion() {
        return completion();
    }

    public Optional<ErrorInfo> error() {
        return Optional.ofNullable(error.get());
    }

    public boolean cancel() {
        BooleanSupplier action = cancelAction.get();
        return action != null && action.getAsBoolean();
    }

    void setCancelAction(BooleanSupplier action) {
        cancelAction.set(action);
    }

    void status(Status next) {
        status.updateAndGet(current -> current.terminal() ? current : next);
    }

    synchronized void append(String value) {
        if (!status.get().terminal()) {
            text.append(value);
        }
    }

    boolean complete() {
        if (!transitionTerminal(Status.COMPLETED)) {
            return false;
        }
        completion.complete(text());
        cancelAction.set(null);
        return true;
    }

    boolean fail(String code, String message, int httpStatus, Throwable cause) {
        if (!transitionTerminal(Status.FAILED)) {
            return false;
        }
        ErrorInfo info = new ErrorInfo(code, message, httpStatus);
        error.set(info);
        completion.completeExceptionally(new Failure(info, cause));
        cancelAction.set(null);
        return true;
    }

    boolean cancelFromService() {
        if (!transitionTerminal(Status.CANCELLED)) {
            return false;
        }
        completion.completeExceptionally(new CancellationException("AI generation was cancelled."));
        cancelAction.set(null);
        return true;
    }

    private boolean transitionTerminal(Status next) {
        while (true) {
            Status current = status.get();
            if (current.terminal()) {
                return false;
            }
            if (status.compareAndSet(current, next)) {
                return true;
            }
        }
    }

    public enum Status {
        QUEUED,
        CONNECTING,
        PROCESSING,
        GENERATING,
        COMPLETED,
        FAILED,
        CANCELLED;

        public boolean terminal() {
            return this == COMPLETED || this == FAILED || this == CANCELLED;
        }
    }

    public record ErrorInfo(String code, String message, int httpStatus) {
    }

    public static final class Failure extends RuntimeException {
        private final ErrorInfo error;

        public Failure(ErrorInfo error, Throwable cause) {
            super(error.message(), cause);
            this.error = error;
        }

        public ErrorInfo error() {
            return error;
        }
    }
}
