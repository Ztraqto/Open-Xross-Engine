package com.ztraqto.openxross.runtime.shard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ztraqto.openxross.XrossVersion;
import com.ztraqto.openxross.core.security.SecureJson;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/** Calls Discord's authenticated Get Gateway Bot endpoint without exposing the Bot token. */
public final class DiscordGatewayBotInfoClient {
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final URI ENDPOINT = URI.create("https://discord.com/api/v10/gateway/bot");

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final Duration requestTimeout;

    public DiscordGatewayBotInfoClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), SecureJson.newMapper(), Duration.ofSeconds(15));
    }

    DiscordGatewayBotInfoClient(HttpClient httpClient, ObjectMapper mapper, Duration requestTimeout) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
    }

    public DiscordGatewayBotInfo fetch(String botToken) throws IOException, InterruptedException {
        if (botToken == null || botToken.isBlank()) {
            throw new IllegalArgumentException("Discord Bot token is required.");
        }

        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(requestTimeout)
                .header("Authorization", "Bot " + botToken.trim())
                .header("Accept", "application/json")
                .header("User-Agent", "OpenXrossEngine/" + XrossVersion.current())
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        byte[] responseBytes;
        try (InputStream body = response.body()) {
            responseBytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
        }
        if (responseBytes.length > MAX_RESPONSE_BYTES) {
            throw new IOException("Discord Get Gateway Bot response was unexpectedly large.");
        }
        if (response.statusCode() != 200) {
            throw new IOException("Discord Get Gateway Bot failed with HTTP " + response.statusCode() + ".");
        }

        JsonNode root = mapper.readTree(new String(responseBytes, StandardCharsets.UTF_8));
        int shards = positive(root.path("shards").asInt(0), "shards");
        JsonNode limit = root.path("session_start_limit");
        int total = nonNegative(limit.path("total").asInt(0), "session_start_limit.total");
        int remaining = nonNegative(limit.path("remaining").asInt(0), "session_start_limit.remaining");
        long resetAfter = Math.max(0L, limit.path("reset_after").asLong(0L));
        int maxConcurrency = positive(limit.path("max_concurrency").asInt(1), "session_start_limit.max_concurrency");
        return new DiscordGatewayBotInfo(shards, total, remaining, resetAfter, maxConcurrency);
    }

    private static int positive(int value, String field) throws IOException {
        if (value < 1) throw new IOException("Discord Gateway Bot response contained invalid " + field + ".");
        return value;
    }

    private static int nonNegative(int value, String field) throws IOException {
        if (value < 0) throw new IOException("Discord Gateway Bot response contained invalid " + field + ".");
        return value;
    }
}
