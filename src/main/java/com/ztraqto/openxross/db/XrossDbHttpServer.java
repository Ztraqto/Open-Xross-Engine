package com.ztraqto.openxross.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ztraqto.openxross.core.security.SecureJson;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.ztraqto.openxross.api.database.XrossDbConflictException;
import com.ztraqto.openxross.api.database.XrossDbException;
import com.ztraqto.openxross.api.database.XrossDbKey;
import com.ztraqto.openxross.config.XrossDbConfiguration;
import com.ztraqto.openxross.db.protocol.XrossDbProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class XrossDbHttpServer {

    private static final Logger logger = LoggerFactory.getLogger(XrossDbHttpServer.class);
    private static final int MAX_REQUEST_BYTES = 1024 * 1024;

    private final XrossDbConfiguration configuration;
    private final XrossDbGateway gateway;
    private final ObjectMapper mapper = SecureJson.newMapper();

    private HttpServer server;
    private ExecutorService executor;
    private ScheduledThreadPoolExecutor deadlines;

    public XrossDbHttpServer(XrossDbConfiguration configuration, XrossDbGateway gateway) {
        this.configuration = configuration;
        this.gateway = gateway;
    }

    public void start() throws IOException {
        if (server != null) {
            throw new IllegalStateException("XrossDB HTTP server is already running.");
        }
        configuration.validateServerSecurity();

        server = HttpServer.create(new InetSocketAddress(configuration.bindAddress(), configuration.port()), 64);
        int workers = Math.min(32, Math.max(4, Runtime.getRuntime().availableProcessors()));
        executor = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(64), new XrossDbThreadFactory(), new ThreadPoolExecutor.AbortPolicy());
        deadlines = new ScheduledThreadPoolExecutor(1, new XrossDbThreadFactory());
        deadlines.setRemoveOnCancelPolicy(true);
        server.setExecutor(executor);
        server.createContext("/health", exchange -> handleBounded(exchange, this::handleHealth));
        server.createContext("/v1/records/read", exchange -> handleBounded(exchange, request -> handleAuthenticated(request, this::handleRead)));
        server.createContext("/v1/records/write", exchange -> handleBounded(exchange, request -> handleAuthenticated(request, this::handleWrite)));
        server.createContext("/v1/records/delete", exchange -> handleBounded(exchange, request -> handleAuthenticated(request, this::handleDelete)));
        server.createContext("/v1/records/scan", exchange -> handleBounded(exchange, request -> handleAuthenticated(request, this::handleScan)));
        server.createContext("/v1/records/batch", exchange -> handleBounded(exchange, request -> handleAuthenticated(request, this::handleBatch)));
        server.start();

        logger.info("XrossDB is listening on {}:{}", configuration.bindAddress(), configuration.port());
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        if (deadlines != null) {
            deadlines.shutdownNow();
            deadlines = null;
        }
    }

    private void handleBounded(HttpExchange exchange, com.sun.net.httpserver.HttpHandler handler) throws IOException {
        var deadline = deadlines.schedule(exchange::close,
                configuration.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
        try {
            handler.handle(exchange);
        } finally {
            deadline.cancel(false);
            exchange.close();
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!isExactPath(exchange, "/health") || !"GET".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "METHOD_NOT_ALLOWED", "Only GET /health is supported.");
            return;
        }
        if (!isAuthorized(exchange)) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            sendError(exchange, 401, "UNAUTHORIZED", "XrossDB authentication failed.");
            return;
        }
        sendJson(exchange, 200, new XrossDbProtocol.HealthResponse("ok", XrossDbProtocol.VERSION));
    }

    private void handleAuthenticated(HttpExchange exchange, ExchangeHandler handler) throws IOException {
        if (!isAuthorized(exchange)) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            sendError(exchange, 401, "UNAUTHORIZED", "XrossDB authentication failed.");
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "METHOD_NOT_ALLOWED", "Only POST is supported.");
            return;
        }

        try {
            handler.handle(exchange);
        } catch (XrossDbConflictException exception) {
            sendError(exchange, 409, "REVISION_CONFLICT", exception.getMessage());
        } catch (IllegalArgumentException exception) {
            sendError(exchange, 400, "INVALID_REQUEST", exception.getMessage());
        } catch (JsonProcessingException exception) {
            sendError(exchange, 400, "INVALID_JSON", "Request body is not valid XrossDB JSON.");
        } catch (XrossDbException exception) {
            logger.error("XrossDB request failed", exception);
            sendError(exchange, 500, "DATABASE_ERROR", "XrossDB could not complete the request.");
        } catch (Exception exception) {
            logger.error("Unexpected XrossDB request failure", exception);
            sendError(exchange, 500, "INTERNAL_ERROR", "XrossDB encountered an internal error.");
        }
    }

    private void handleRead(HttpExchange exchange) throws IOException {
        requireExactPath(exchange, "/v1/records/read");
        XrossDbProtocol.KeyRequest request = readJson(exchange, XrossDbProtocol.KeyRequest.class);
        XrossDbKey key = new XrossDbKey(request.namespace(), request.collection(), request.key());
        var record = gateway.read(key);
        if (record.isEmpty()) {
            sendError(exchange, 404, "NOT_FOUND", "Record was not found.");
            return;
        }
        sendJson(exchange, 200, record.get());
    }

    private void handleWrite(HttpExchange exchange) throws IOException {
        requireExactPath(exchange, "/v1/records/write");
        XrossDbProtocol.WriteRequest request = readJson(exchange, XrossDbProtocol.WriteRequest.class);
        XrossDbKey key = new XrossDbKey(request.namespace(), request.collection(), request.key());
        sendJson(exchange, 200, gateway.write(key, request.payload(), request.expectedRevision()));
    }

    private void handleDelete(HttpExchange exchange) throws IOException {
        requireExactPath(exchange, "/v1/records/delete");
        XrossDbProtocol.DeleteRequest request = readJson(exchange, XrossDbProtocol.DeleteRequest.class);
        XrossDbKey key = new XrossDbKey(request.namespace(), request.collection(), request.key());
        boolean deleted = gateway.delete(key, request.expectedRevision());
        sendJson(exchange, 200, new XrossDbProtocol.DeleteResponse(deleted));
    }

    private void handleScan(HttpExchange exchange) throws IOException {
        requireExactPath(exchange, "/v1/records/scan");
        XrossDbProtocol.ScanRequest request = readJson(exchange, XrossDbProtocol.ScanRequest.class);
        sendJson(exchange, 200, gateway.scanPage(
                request.namespace(),
                request.collection(),
                request.afterKey(),
                request.limit()
        ));
    }

    private void handleBatch(HttpExchange exchange) throws IOException {
        requireExactPath(exchange, "/v1/records/batch");
        XrossDbProtocol.BatchRequest request = readJson(exchange, XrossDbProtocol.BatchRequest.class);
        sendJson(exchange, 200, gateway.applyBatch(request.mutations()));
    }

    private <T> T readJson(HttpExchange exchange, Class<T> type) throws IOException {
        byte[] body = readLimited(exchange.getRequestBody());
        return mapper.readValue(body, type);
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        byte[] body = input.readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("Request body is too large.");
        }
        return body;
    }

    private boolean isAuthorized(HttpExchange exchange) {
        String token = configuration.authToken();
        if (token == null) {
            return true;
        }

        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return false;
        }

        byte[] expected = token.getBytes(StandardCharsets.UTF_8);
        byte[] actual = authorization.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    private void sendJson(HttpExchange exchange, int status, Object response) throws IOException {
        byte[] body = mapper.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private void sendError(HttpExchange exchange, int status, String code, String message) throws IOException {
        sendJson(exchange, status, new XrossDbProtocol.ErrorResponse(code, message));
    }

    private static boolean isExactPath(HttpExchange exchange, String expectedPath) {
        return expectedPath.equals(exchange.getRequestURI().getPath());
    }

    private static void requireExactPath(HttpExchange exchange, String expectedPath) {
        if (!isExactPath(exchange, expectedPath)) {
            throw new IllegalArgumentException("Unknown XrossDB endpoint.");
        }
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws Exception;
    }

    private static final class XrossDbThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "XrossDB-HTTP-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
