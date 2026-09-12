package com.ztraqto.openxross.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AITerminalServiceTest {

    @TempDir
    Path temporaryDirectory;

    private HttpServer server;
    private AITerminalService service;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void readsCompletedAiReportResponse() throws Exception {
        server.createContext("/v1/chat/completions", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(body.contains("system instruction"));
            assertTrue(body.contains("user prompt"));
            assertTrue(body.contains("\"stream\":false"));
            assertTrue(body.contains("\"chat_template_kwargs\":{\"enable_thinking\":false}"));
            assertTrue(body.contains("Required fields: schemaVersion(integer, always 1)"));
            assertTrue(body.contains("category must be NONE or one of the allowed categories"));
            assertTrue(body.contains("/no_think"));
            assertTrue(body.contains("\"max_tokens\":512"));
            assertTrue(body.contains("\"response_format\":{\"type\":\"json_schema\""));
            assertTrue(body.contains("\"additionalProperties\":false"));
            assertTrue(body.contains("\"pattern\":\"^[A-Z][A-Z0-9_]{0,31}$\""));
            assertTrue(body.contains("\"type\":\"image_url\""));
            assertTrue(body.contains("data:image/png;base64,AA=="));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write("{\"choices\":[{\"message\":{\"content\":\"Hello\"}}]}"
                        .getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        writeSettings(server.getAddress().getPort(), "ai-report-internal-nano", 0, 60, 1, 10);
        service = new AITerminalService(temporaryDirectory.resolve("xecute.aits.settings.json"));
        service.init(null);

        AIChat chat = service.promptComplete("ai-report-internal-nano", "system instruction", "user prompt", "data:image/png;base64,AA==");
        assertEquals("Hello", chat.completion().toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertEquals(AIChat.Status.COMPLETED, chat.status());
        assertEquals("Hello", chat.text());
    }

    @Test
    void rejectsReasoningOnlyResponsesAsEmpty() throws Exception {
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write("{\"choices\":[{\"message\":{\"reasoning_content\":\"thinking\",\"content\":\"\"}}]}"
                        .getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        writeSettings(server.getAddress().getPort(), "ai-report-internal-nano", 0, 60, 1, 10);
        service = new AITerminalService(temporaryDirectory.resolve("xecute.aits.settings.json"));
        service.init(null);

        AIChat chat = service.promptComplete("ai-report-internal-nano", "system", "prompt", null);
        ExecutionException exception = assertThrows(ExecutionException.class,
                () -> chat.completion().toCompletableFuture().get(5, TimeUnit.SECONDS));
        AIChat.Failure failure = (AIChat.Failure) exception.getCause();
        assertEquals("EMPTY_RESPONSE", failure.error().code());
        assertTrue(failure.error().message().contains("reasoning content"));
    }

    @Test
    void timesOutAnOpenEventStream() throws Exception {
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                writeEvent(output, "{\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking\"}}]}");
                try {
                    Thread.sleep(3_000);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        server.start();
        writeSettings(server.getAddress().getPort(), "local", 0, 60, 1, 10, 1);
        service = new AITerminalService(temporaryDirectory.resolve("xecute.aits.settings.json"));
        service.init(null);

        AIChat chat = service.prompt("local", "system", "prompt");
        ExecutionException exception = assertThrows(ExecutionException.class,
                () -> chat.completion().toCompletableFuture().get(5, TimeUnit.SECONDS));
        AIChat.Failure failure = (AIChat.Failure) exception.getCause();
        assertEquals("REQUEST_TIMEOUT", failure.error().code());
    }

    @Test
    void rateLimitedRequestsRemainVisibleInTheProfileQueue() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger requestCount = new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
            int current = requestCount.incrementAndGet();
            if (current == 1) {
                firstStarted.countDown();
                try {
                    releaseFirst.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                writeEvent(output, "{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}");
                output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        writeSettings(server.getAddress().getPort(), "local", 1, 1, 1, 5);
        service = new AITerminalService(temporaryDirectory.resolve("xecute.aits.settings.json"));
        service.init(null);

        AIChat first = service.prompt("local", "", "first");
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
        AIChat second = service.prompt("local", "", "second");
        assertEquals(1, service.getQueuedPrompts("local").size());
        assertEquals(second.id(), service.getQueuedPrompts("local").get(0).chatId());
        assertEquals(AIQueuedPrompt.QueueReason.RATE_LIMIT,
                service.getQueuedPrompts("local").get(0).reason());

        releaseFirst.countDown();
        assertEquals("ok", first.completion().toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertEquals("ok", second.completion().toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertEquals(2, requestCount.get());
    }

    private void writeSettings(int port, String profile, int maxRequests, int windowSeconds,
                               int maxConcurrent, int maxQueued) throws IOException {
        writeSettings(port, profile, maxRequests, windowSeconds, maxConcurrent, maxQueued, 300);
    }

    private void writeSettings(int port, String profile, int maxRequests, int windowSeconds,
                               int maxConcurrent, int maxQueued, int requestTimeoutSeconds) throws IOException {
        Files.writeString(temporaryDirectory.resolve("xecute.aits.settings.json"), """
                {
                  "version": 1,
                  "global": {"maxConcurrentRequests": 2},
                  "profiles": {
                    "%s": {
                      "baseUrl": "http://127.0.0.1:%d/v1",
                      "model": "test-model",
                      "requestTimeoutSeconds": %d,
                      "rateLimit": {
                        "maxRequests": %d,
                        "windowSeconds": %d,
                        "maxConcurrentRequests": %d,
                        "maxQueuedRequests": %d
                      }
                    }
                  }
                }
                """.formatted(profile, port, requestTimeoutSeconds, maxRequests, windowSeconds, maxConcurrent, maxQueued));
    }

    private static void writeEvent(OutputStream output, String json) throws IOException {
        output.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }
}
