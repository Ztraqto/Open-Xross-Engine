package com.ztraqto.openxross.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ztraqto.openxross.api.database.XrossDbBatchResult;
import com.ztraqto.openxross.api.database.XrossDbClient;
import com.ztraqto.openxross.api.database.XrossDbConflictException;
import com.ztraqto.openxross.api.database.XrossDbException;
import com.ztraqto.openxross.api.database.XrossDbKey;
import com.ztraqto.openxross.api.database.XrossDbMutation;
import com.ztraqto.openxross.api.database.XrossDbPage;
import com.ztraqto.openxross.api.database.XrossDbRecord;
import com.ztraqto.openxross.config.XrossDbConfiguration;
import com.ztraqto.openxross.core.security.SecureJson;
import com.ztraqto.openxross.db.protocol.XrossDbProtocol;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;

public final class RemoteXrossDbClient implements XrossDbClient {

    /**
     * XrossDB records are intentionally small control/state documents. A remote
     * endpoint must never be able to make a Bot buffer an arbitrary response.
     */
    static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private final XrossDbConfiguration configuration;
    private final ObjectMapper mapper = SecureJson.newMapper();
    private final HttpClient httpClient;
    private final URI baseUri;

    public RemoteXrossDbClient(XrossDbConfiguration configuration) {
        configuration.validateRemoteClient();
        this.configuration = configuration;
        this.baseUri = configuration.serverBaseUri();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(configuration.connectTimeout())
                .version(HttpClient.Version.HTTP_2)
                .build();
    }

    @Override
    public Optional<XrossDbRecord> read(XrossDbKey key) {
        XrossDbProtocol.KeyRequest request = new XrossDbProtocol.KeyRequest(
                key.namespace(), key.collection(), key.key()
        );
        DbHttpResponse response = sendPost("/v1/records/read", request, true);
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        requireSuccess(response);
        return Optional.of(readBody(response, XrossDbRecord.class));
    }

    @Override
    public XrossDbRecord write(XrossDbKey key, com.fasterxml.jackson.databind.JsonNode payload, long expectedRevision) {
        XrossDbProtocol.WriteRequest request = new XrossDbProtocol.WriteRequest(
                key.namespace(), key.collection(), key.key(), payload, expectedRevision
        );
        DbHttpResponse response = sendPost("/v1/records/write", request, false);
        requireSuccess(response);
        return readBody(response, XrossDbRecord.class);
    }

    @Override
    public boolean delete(XrossDbKey key, long expectedRevision) {
        XrossDbProtocol.DeleteRequest request = new XrossDbProtocol.DeleteRequest(
                key.namespace(), key.collection(), key.key(), expectedRevision
        );
        DbHttpResponse response = sendPost("/v1/records/delete", request, false);
        requireSuccess(response);
        return readBody(response, XrossDbProtocol.DeleteResponse.class).deleted();
    }

    @Override
    public XrossDbPage scanPage(String namespace, String collection, String afterKey, int limit) {
        XrossDbProtocol.ScanRequest request = new XrossDbProtocol.ScanRequest(namespace, collection, afterKey, limit);
        DbHttpResponse response = sendPost("/v1/records/scan", request, true);
        requireSuccess(response);
        return readBody(response, XrossDbPage.class);
    }

    @Override
    public XrossDbBatchResult applyBatch(List<XrossDbMutation> mutations) {
        XrossDbProtocol.BatchRequest request = new XrossDbProtocol.BatchRequest(mutations);
        DbHttpResponse response = sendPost("/v1/records/batch", request, false);
        requireSuccess(response);
        return readBody(response, XrossDbBatchResult.class);
    }

    @Override
    public void verifyConnection() {
        HttpRequest request = requestBuilder("/health").GET().build();
        DbHttpResponse response = send(request, true);
        requireSuccess(response);
        XrossDbProtocol.HealthResponse health = readBody(response, XrossDbProtocol.HealthResponse.class);
        if (!"ok".equals(health.status())) {
            throw new XrossDbException("XrossDB health check did not return an operational state.");
        }
        if (health.protocolVersion() != XrossDbProtocol.VERSION) {
            throw new XrossDbException(
                    "Unsupported XrossDB protocol version " + health.protocolVersion()
                            + ". Expected " + XrossDbProtocol.VERSION + "."
            );
        }
    }

    private DbHttpResponse sendPost(String path, Object body, boolean retryable) {
        try {
            HttpRequest request = requestBuilder(path)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)))
                    .build();
            return send(request, retryable);
        } catch (IOException exception) {
            throw new XrossDbException("Failed to encode XrossDB request.", exception);
        }
    }

    private HttpRequest.Builder requestBuilder(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(configuration.requestTimeout())
                .header("Accept", "application/json");

        if (configuration.authToken() != null) {
            builder.header("Authorization", "Bearer " + configuration.authToken());
        }
        return builder;
    }

    private DbHttpResponse send(HttpRequest request, boolean retryable) {
        int attempts = retryable ? configuration.readRetryCount() + 1 : 1;
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream body = response.body()) {
                    long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                    if (declaredLength > MAX_RESPONSE_BYTES) {
                        throw new XrossDbException("XrossDB response exceeded the maximum allowed size.");
                    }
                    return new DbHttpResponse(response.statusCode(), readLimited(body));
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new XrossDbException("XrossDB request was interrupted.", exception);
            } catch (IOException exception) {
                lastFailure = exception;
                if (attempt < attempts) {
                    waitBeforeRetry(attempt);
                }
            }
        }
        throw new XrossDbException("Could not connect to XrossDB at " + baseUri, lastFailure);
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        byte[] body = input.readNBytes(MAX_RESPONSE_BYTES + 1);
        if (body.length > MAX_RESPONSE_BYTES) {
            throw new IOException("XrossDB response exceeded the maximum allowed size.");
        }
        return body;
    }

    private static void waitBeforeRetry(int attempt) {
        try {
            Thread.sleep(Math.min(500L, 100L * attempt));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new XrossDbException("XrossDB retry was interrupted.", exception);
        }
    }

    private void requireSuccess(DbHttpResponse response) {
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return;
        }

        XrossDbProtocol.ErrorResponse error;
        try {
            error = mapper.readValue(response.body(), XrossDbProtocol.ErrorResponse.class);
        } catch (Exception ignored) {
            error = new XrossDbProtocol.ErrorResponse(
                    "HTTP_" + response.statusCode(),
                    "XrossDB returned HTTP " + response.statusCode()
            );
        }

        String message = sanitizeRemoteMessage(error.message());
        if (response.statusCode() == 409) {
            throw new XrossDbConflictException(message);
        }
        throw new XrossDbException(sanitizeRemoteMessage(error.code()) + ": " + message);
    }

    private <T> T readBody(DbHttpResponse response, Class<T> type) {
        try {
            return mapper.readValue(response.body(), type);
        } catch (IOException exception) {
            throw new XrossDbException("Failed to decode XrossDB response.", exception);
        }
    }

    private static String sanitizeRemoteMessage(String value) {
        if (value == null || value.isBlank()) {
            return "Remote XrossDB error";
        }
        String sanitized = value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
        return sanitized.length() > 512 ? sanitized.substring(0, 512) + "..." : sanitized;
    }

    private record DbHttpResponse(int statusCode, byte[] body) {
        private DbHttpResponse {
            body = body == null ? new byte[0] : body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }
}
