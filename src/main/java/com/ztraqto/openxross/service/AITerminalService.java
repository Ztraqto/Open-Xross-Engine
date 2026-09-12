package com.ztraqto.openxross.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ztraqto.openxross.XrossEngine;
import com.ztraqto.openxross.api.IService;
import com.ztraqto.openxross.config.AITerminalSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sends one-shot prompts to OpenAI Chat Completions compatible endpoints.
 * Local LM Studio servers and remote providers use the same transport.
 */
public final class AITerminalService implements IService {

    private static final Logger logger = LoggerFactory.getLogger(AITerminalService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_ERROR_BODY_BYTES = 8 * 1024;
    private static final String AI_REPORT_OUTPUT_INSTRUCTION = """
            Return exactly one JSON object and no other text or Markdown.
            Required fields: schemaVersion(integer, always 1), violation(boolean), category(string), confidence(integer 0-100), penaltyScore(integer 0-100), reason(non-empty string, max 500 characters).
            category must be NONE or one of the allowed categories supplied in the user prompt.
            """;

    private final Path settingsPath;
    // ponytail: one service lock keeps profile queues and global counters coherent;
    // split by profile only if queue contention becomes measurable.
    private final Map<String, ProfileState> profileStates = new LinkedHashMap<>();
    private final Map<UUID, Request> requests = new LinkedHashMap<>();

    private AITerminalSettings settings = AITerminalSettings.empty();
    private HttpClient httpClient;
    private ExecutorService workers;
    private ScheduledExecutorService scheduler;
    private boolean running;
    private int globalActive;

    public AITerminalService() {
        this(defaultSettingsPath());
    }

    private static Path defaultSettingsPath() {
        if (Files.exists(AITerminalSettings.DEFAULT_PATH)) return AITerminalSettings.DEFAULT_PATH;
        if (Files.exists(AITerminalSettings.LEGACY_CONFIG_PATH)) return AITerminalSettings.LEGACY_CONFIG_PATH;
        return Files.exists(AITerminalSettings.LEGACY_PATH)
                ? AITerminalSettings.LEGACY_PATH : AITerminalSettings.DEFAULT_PATH;
    }

    AITerminalService(Path settingsPath) {
        this.settingsPath = Objects.requireNonNull(settingsPath, "settingsPath").toAbsolutePath().normalize();
    }

    @Override
    public synchronized void init(XrossEngine engine) {
        if (running) {
            throw new IllegalStateException("AITerminalService is already running.");
        }
        settings = loadSettings();
        profileStates.clear();
        settings.profiles().forEach((name, profile) -> profileStates.put(name, new ProfileState(name, profile)));
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        workers = Executors.newFixedThreadPool(
                settings.maxConcurrentRequests(),
                namedThreadFactory("Xross-AI-Worker-")
        );
        scheduler = Executors.newSingleThreadScheduledExecutor(namedThreadFactory("Xross-AI-Scheduler-"));
        running = true;
        logger.info("AITerminalService initialized with {} profile(s).", profileStates.size());
    }

    @Override
    public void shutdown() {
        List<Request> activeRequests;
        ExecutorService workerExecutor;
        ScheduledExecutorService scheduledExecutor;
        synchronized (this) {
            if (!running && workers == null && scheduler == null) {
                return;
            }
            running = false;
            activeRequests = new ArrayList<>(requests.values());
            workerExecutor = workers;
            scheduledExecutor = scheduler;
            workers = null;
            scheduler = null;
        }

        for (Request request : activeRequests) {
            cancelRequest(request);
        }
        if (scheduledExecutor != null) {
            scheduledExecutor.shutdownNow();
        }
        if (workerExecutor != null) {
            workerExecutor.shutdownNow();
        }
        synchronized (this) {
            requests.clear();
            profileStates.clear();
            globalActive = 0;
            httpClient = null;
        }
        logger.info("AITerminalService stopped.");
    }

    @Override
    public String getName() {
        return "AITerminalService";
    }

    /** Starts an asynchronous one-shot generation. */
    public AIChat prompt(String profileName, String systemPrompt, String promptContent) {
        return prompt(profileName, systemPrompt, promptContent, null);
    }

    /** Starts a multimodal one-shot generation using a configured profile. */
    public AIChat prompt(String profileName, String systemPrompt, String promptContent, String imageUrl) {
        return prompt(profileName, systemPrompt, promptContent, imageUrl, true);
    }

    /** Starts a one-shot generation whose completed response is returned as one JSON document. */
    public AIChat promptComplete(String profileName, String systemPrompt, String promptContent, String imageUrl) {
        return prompt(profileName, systemPrompt, promptContent, imageUrl, false);
    }

    private AIChat prompt(String profileName, String systemPrompt, String promptContent, String imageUrl,
                          boolean streaming) {
        String normalizedProfile = requireText(profileName, "profile");
        String normalizedPrompt = requirePrompt(promptContent, "promptContent");
        String normalizedSystem = systemPrompt == null ? "" : systemPrompt;

        synchronized (this) {
            requireRunning();
            ProfileState state = profileStates.get(normalizedProfile);
            if (state == null) {
                throw new IllegalArgumentException("Unknown AI profile: " + normalizedProfile);
            }

            return enqueueLocked(state, state.profile, normalizedSystem, normalizedPrompt, imageUrl, streaming);
        }
    }

    /** Uses a fixed official OpenAI-compatible endpoint with a guild-owned key. */
    public AIChat promptExternal(long guildId, String provider, String model, String apiKey,
                                 String systemPrompt, String promptContent, String imageUrl) {
        return promptExternal(guildId, provider, model, apiKey, systemPrompt, promptContent, imageUrl, true);
    }

    /** Uses a guild-owned external provider and waits for one completed JSON response. */
    public AIChat promptExternalComplete(long guildId, String provider, String model, String apiKey,
                                         String systemPrompt, String promptContent, String imageUrl) {
        return promptExternal(guildId, provider, model, apiKey, systemPrompt, promptContent, imageUrl, false);
    }

    private AIChat promptExternal(long guildId, String provider, String model, String apiKey,
                                  String systemPrompt, String promptContent, String imageUrl, boolean streaming) {
        if (guildId <= 0L) throw new IllegalArgumentException("guildId must be positive.");
        String normalizedProvider = requireText(provider, "provider").toLowerCase(java.util.Locale.ROOT);
        String baseUrl = switch (normalizedProvider) {
            case "openai" -> "https://api.openai.com/v1";
            case "gemini" -> "https://generativelanguage.googleapis.com/v1beta/openai";
            default -> throw new IllegalArgumentException("Unsupported external AI provider.");
        };
        String normalizedModel = requireText(model, "model");
        if (normalizedModel.length() > 200) throw new IllegalArgumentException("model is too long.");
        String normalizedKey = requireText(apiKey, "apiKey");
        AITerminalSettings.Profile endpoint = new AITerminalSettings.Profile(
                "openai-chat-completions", baseUrl, normalizedModel, normalizedKey, 300,
                new AITerminalSettings.RateLimit(0, 60, 2, 64));
        String stateName = "external-" + normalizedProvider + "-" + Long.toUnsignedString(guildId);
        synchronized (this) {
            requireRunning();
            ProfileState state = profileStates.computeIfAbsent(stateName, ignored -> new ProfileState(stateName, endpoint));
            return enqueueLocked(state, endpoint, systemPrompt == null ? "" : systemPrompt,
                    requirePrompt(promptContent, "promptContent"), imageUrl, streaming);
        }
    }

    private AIChat enqueueLocked(ProfileState state, AITerminalSettings.Profile endpoint, String systemPrompt,
                                 String promptContent, String imageUrl, boolean streaming) {
            AIChat chat = new AIChat(state.name);
            Request request = new Request(chat, state, endpoint, systemPrompt, promptContent,
                    validateImageUrl(imageUrl), streaming);
            chat.setCancelAction(() -> cancelRequest(request));
            if (state.queue.size() >= state.profile.rateLimit().maxQueuedRequests()) {
                chat.fail(
                        "RATE_LIMIT_QUEUE_FULL",
                        "The AI profile queue is full.",
                        0,
                        null
                );
                return chat;
            }
            state.queue.addLast(request);
            requests.put(chat.id(), request);
            dispatchLocked();
            return chat;
    }

    /** Returns the current FIFO queue for one profile. */
    public synchronized List<AIQueuedPrompt> getQueuedPrompts(String profileName) {
        ProfileState state = requireProfile(profileName);
        return snapshotQueue(state);
    }

    /** Returns a snapshot for every configured profile, including empty queues. */
    public synchronized Map<String, List<AIQueuedPrompt>> getQueuedPrompts() {
        Map<String, List<AIQueuedPrompt>> result = new LinkedHashMap<>();
        profileStates.forEach((name, state) -> result.put(name, snapshotQueue(state)));
        return Collections.unmodifiableMap(result);
    }

    private AITerminalSettings loadSettings() {
        try {
            return AITerminalSettings.load(settingsPath);
        } catch (NoSuchFileException exception) {
            logger.warn("AITerminal settings file was not found; AI profiles are disabled: {}", settingsPath);
            return AITerminalSettings.empty();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load AITerminal settings: " + settingsPath, exception);
        }
    }

    private void dispatchLocked() {
        if (!running || workers == null) {
            return;
        }
        boolean dispatched;
        do {
            dispatched = false;
            Instant now = Instant.now();
            for (ProfileState state : profileStates.values()) {
                if (globalActive >= settings.maxConcurrentRequests()) {
                    break;
                }
                if (state.queue.isEmpty()) {
                    continue;
                }
                Instant rateLimitedUntil = rateLimitedUntil(state, now);
                if (rateLimitedUntil != null) {
                    scheduleWakeupLocked(state, rateLimitedUntil);
                    continue;
                }
                if (state.active >= profileConcurrency(state)) {
                    continue;
                }

                Request request = state.queue.removeFirst();
                state.active++;
                globalActive++;
                if (state.profile.rateLimit().maxRequests() > 0) {
                    state.startedAt.addLast(now);
                }
                try {
                    request.task = workers.submit(() -> runRequest(request));
                } catch (RejectedExecutionException exception) {
                    state.active--;
                    globalActive--;
                    requests.remove(request.chat.id());
                    request.chat.fail("EXECUTOR_REJECTED", "The AI worker is unavailable.", 0, exception);
                }
                dispatched = true;
            }
        } while (dispatched && globalActive < settings.maxConcurrentRequests());
    }

    private void runRequest(Request request) {
        try {
            executeRequest(request);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (request.chat.status() != AIChat.Status.CANCELLED) {
                request.chat.fail("INTERRUPTED", "The AI request was interrupted.", 0, exception);
            }
        } catch (IOException | RuntimeException exception) {
            if (request.chat.status() != AIChat.Status.CANCELLED) {
                request.chat.fail("REQUEST_FAILED", safeMessage(exception), 0, exception);
            }
        } finally {
            synchronized (this) {
                request.input.set(null);
                request.state.active = Math.max(0, request.state.active - 1);
                globalActive = Math.max(0, globalActive - 1);
                requests.remove(request.chat.id());
                dispatchLocked();
            }
        }
    }

    private void executeRequest(Request request) throws IOException, InterruptedException {
        AITerminalSettings.Profile profile = request.endpoint;
        request.chat.status(AIChat.Status.CONNECTING);
        ScheduledFuture<?> timeout = scheduler.schedule(() -> {
            if (request.chat.fail("REQUEST_TIMEOUT", "The AI request timed out.", 0, null)) {
                closeQuietly(request.input.getAndSet(null));
            }
        }, profile.requestTimeoutSeconds(), TimeUnit.SECONDS);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(endpoint(profile))
                .timeout(Duration.ofSeconds(profile.requestTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Accept", request.streaming ? "text/event-stream" : "application/json");
        if (profile.apiKey() != null) {
            requestBuilder.header("Authorization", "Bearer " + profile.apiKey());
        }
        HttpRequest httpRequest = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(buildBody(profile, request)))
                .build();

        try {
            HttpResponse<InputStream> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                try (InputStream body = response.body()) {
                    String providerMessage = readProviderError(body);
                    request.chat.fail(
                            "HTTP_ERROR",
                            providerMessage == null ? "AI provider returned HTTP " + response.statusCode() + "." : providerMessage,
                            response.statusCode(),
                            null
                    );
                }
                return;
            }

            try (InputStream body = response.body()) {
                request.input.set(body);
                request.chat.status(AIChat.Status.PROCESSING);
                if (request.streaming) parseEventStream(request, body);
                else parseCompleteResponse(request, body);
            } finally {
                request.input.set(null);
            }
        } finally {
            timeout.cancel(false);
        }
    }

    private void parseCompleteResponse(Request request, InputStream input) throws IOException {
        JsonNode response = MAPPER.readTree(input);
        JsonNode providerError = response == null ? null : response.path("error");
        if (providerError != null && providerError.isObject()) {
            request.chat.fail("PROVIDER_ERROR", safeJsonMessage(providerError), 0, null);
            return;
        }
        JsonNode message = response == null ? null : response.path("choices").path(0).path("message");
        JsonNode reasoning = message == null ? null : message.path("reasoning_content");
        request.reasoningReceived = reasoning != null && reasoning.isTextual() && !reasoning.asText().isEmpty();
        JsonNode content = message == null ? null : message.path("content");
        if (content != null && content.isTextual() && !content.asText().isEmpty()) {
            request.chat.append(content.asText());
        }
        finishStream(request);
    }

    private void parseEventStream(Request request, InputStream input) throws IOException {
        StringBuilder eventData = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (handleEvent(request, eventData.toString())) {
                        return;
                    }
                    eventData.setLength(0);
                    continue;
                }
                if (line.startsWith(":")) {
                    continue;
                }
                if (line.startsWith("data:")) {
                    if (eventData.length() > 0) {
                        eventData.append('\n');
                    }
                    String data = line.substring("data:".length());
                    eventData.append(data.startsWith(" ") ? data.substring(1) : data);
                }
            }
        }
        if (eventData.length() > 0) {
            handleEvent(request, eventData.toString());
        }
        finishStream(request);
    }

    private boolean handleEvent(Request request, String data) {
        String normalized = data.trim();
        if (normalized.isEmpty()) {
            return false;
        }
        if ("[DONE]".equals(normalized)) {
            finishStream(request);
            return true;
        }
        if (request.chat.status() == AIChat.Status.CANCELLED) {
            return true;
        }

        try {
            JsonNode event = MAPPER.readTree(normalized);
            JsonNode providerError = event.path("error");
            if (providerError.isObject()) {
                request.chat.fail(
                        "PROVIDER_ERROR",
                        safeJsonMessage(providerError),
                        0,
                        null
                );
                return true;
            }
            JsonNode choices = event.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return false;
            }
            JsonNode choice = choices.get(0);
            JsonNode delta = choice.path("delta");
            JsonNode reasoning = delta.path("reasoning_content");
            if (reasoning.isTextual() && !reasoning.asText().isEmpty()) {
                request.reasoningReceived = true;
            }
            JsonNode content = delta.path("content");
            if (content.isTextual() && !content.asText().isEmpty()) {
                request.chat.status(AIChat.Status.GENERATING);
                request.chat.append(content.asText());
            }
            return false;
        } catch (IOException | RuntimeException exception) {
            request.chat.fail("MALFORMED_STREAM", "The AI provider returned malformed streaming data.", 0, exception);
            return true;
        }
    }

    private static void finishStream(Request request) {
        if (request.chat.status().terminal()) return;
        if (request.chat.text().isBlank()) {
            String message = request.reasoningReceived
                    ? "The AI provider returned reasoning content but no final response."
                    : "The AI provider returned an empty response.";
            request.chat.fail("EMPTY_RESPONSE", message, 0, null);
        } else {
            request.chat.complete();
        }
    }

    private boolean cancelRequest(Request request) {
        boolean cancelled;
        synchronized (this) {
            if (request.chat.status().terminal()) {
                return false;
            }
            boolean queued = request.state.queue.remove(request);
            cancelled = request.chat.cancelFromService();
            if (!cancelled) {
                return false;
            }
            if (queued) {
                requests.remove(request.chat.id());
                dispatchLocked();
            }
        }

        InputStream input = request.input.getAndSet(null);
        closeQuietly(input);
        Future<?> task = request.task;
        if (task != null) {
            task.cancel(true);
        }
        return true;
    }

    private List<AIQueuedPrompt> snapshotQueue(ProfileState state) {
        List<AIQueuedPrompt> result = new ArrayList<>(state.queue.size());
        int position = 1;
        Instant now = Instant.now();
        AIQueuedPrompt.QueueReason reason = queueReason(state, now);
        for (Request request : state.queue) {
            result.add(new AIQueuedPrompt(
                    request.chat.id(),
                    state.name,
                    position++,
                    request.chat.queuedAt(),
                    reason,
                    request.systemPrompt,
                    request.promptContent
            ));
        }
        return Collections.unmodifiableList(result);
    }

    private AIQueuedPrompt.QueueReason queueReason(ProfileState state, Instant now) {
        if (rateLimitedUntil(state, now) != null) {
            return AIQueuedPrompt.QueueReason.RATE_LIMIT;
        }
        if (state.active >= profileConcurrency(state)) {
            return AIQueuedPrompt.QueueReason.PROFILE_CONCURRENCY_LIMIT;
        }
        return AIQueuedPrompt.QueueReason.GLOBAL_CONCURRENCY_LIMIT;
    }

    private Instant rateLimitedUntil(ProfileState state, Instant now) {
        AITerminalSettings.RateLimit limit = state.profile.rateLimit();
        if (limit.maxRequests() == 0) {
            return null;
        }
        Instant cutoff = now.minusSeconds(limit.windowSeconds());
        while (!state.startedAt.isEmpty() && state.startedAt.peekFirst().isBefore(cutoff)) {
            state.startedAt.removeFirst();
        }
        if (state.startedAt.size() < limit.maxRequests()) {
            return null;
        }
        return state.startedAt.peekFirst().plusSeconds(limit.windowSeconds());
    }

    private void scheduleWakeupLocked(ProfileState state, Instant at) {
        if (scheduler == null || !running) {
            return;
        }
        long delayMillis = Math.max(1L, Duration.between(Instant.now(), at).toMillis());
        if (state.wakeup != null && !state.wakeup.isDone()
                && state.wakeup.getDelay(TimeUnit.MILLISECONDS) <= delayMillis) {
            return;
        }
        if (state.wakeup != null) {
            state.wakeup.cancel(false);
        }
        state.wakeup = scheduler.schedule(() -> {
            synchronized (AITerminalService.this) {
                state.wakeup = null;
                dispatchLocked();
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    private int profileConcurrency(ProfileState state) {
        int configured = state.profile.rateLimit().maxConcurrentRequests();
        return configured > 0 ? configured : settings.maxConcurrentRequests();
    }

    private ProfileState requireProfile(String profileName) {
        String normalized = requireText(profileName, "profile");
        ProfileState state = profileStates.get(normalized);
        if (state == null) {
            throw new IllegalArgumentException("Unknown AI profile: " + normalized);
        }
        return state;
    }

    private void requireRunning() {
        if (!running || httpClient == null || workers == null) {
            throw new IllegalStateException("AITerminalService is unavailable.");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value.trim();
    }

    private static String requirePrompt(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }

    private static String validateImageUrl(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > 12_000_000) throw new IllegalArgumentException("imageUrl is too large.");
        if (!(value.startsWith("https://") || value.startsWith("data:image/"))) {
            throw new IllegalArgumentException("imageUrl must use HTTPS or a data image URL.");
        }
        return value;
    }

    private static URI endpoint(AITerminalSettings.Profile profile) {
        String base = profile.baseUrl().endsWith("/") ? profile.baseUrl() : profile.baseUrl() + "/";
        return URI.create(base + "chat/completions");
    }

    private static String buildBody(AITerminalSettings.Profile profile, Request request) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", profile.model());
        ArrayNode messages = root.putArray("messages");
        boolean internalAiReport = request.state.name.startsWith("ai-report-internal-");
        String systemPrompt = request.systemPrompt;
        if (internalAiReport) {
            systemPrompt = (systemPrompt.isBlank() ? "" : systemPrompt + "\n")
                    + AI_REPORT_OUTPUT_INSTRUCTION + "\n/no_think";
        }
        if (!systemPrompt.isBlank()) {
            messages.addObject().put("role", "system").put("content", systemPrompt);
        }
        ObjectNode user = messages.addObject().put("role", "user");
        if (request.imageUrl == null) {
            user.put("content", request.promptContent);
        } else {
            ArrayNode content = user.putArray("content");
            content.addObject().put("type", "text").put("text", request.promptContent);
            content.addObject().put("type", "image_url").putObject("image_url").put("url", request.imageUrl);
        }
        root.put("stream", request.streaming);
        if (internalAiReport) {
            root.putObject("chat_template_kwargs").put("enable_thinking", false);
            root.put("max_tokens", 512);
            addAiReportResponseFormat(root);
        }
        return MAPPER.writeValueAsString(root);
    }

    private static void addAiReportResponseFormat(ObjectNode root) {
        ObjectNode format = root.putObject("response_format");
        format.put("type", "json_schema");
        ObjectNode jsonSchema = format.putObject("json_schema");
        jsonSchema.put("name", "ai_report_decision");
        jsonSchema.put("strict", true);
        ObjectNode schema = jsonSchema.putObject("schema");
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("schemaVersion").put("type", "integer").put("const", 1);
        properties.putObject("reason").put("type", "string").put("minLength", 1).put("maxLength", 500);
        properties.putObject("violation").put("type", "boolean");
        properties.putObject("category").put("type", "string").put("pattern", "^[A-Z][A-Z0-9_]{0,31}$");
        properties.putObject("confidence").put("type", "integer").put("minimum", 0).put("maximum", 100);
        properties.putObject("penaltyScore").put("type", "integer").put("minimum", 0).put("maximum", 100);
        ArrayNode required = schema.putArray("required");
        for (String field : new String[]{"schemaVersion", "violation", "category", "confidence", "penaltyScore", "reason"}) {
            required.add(field);
        }
    }

    private static String readProviderError(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_ERROR_BODY_BYTES);
        if (bytes.length == 0) {
            return null;
        }
        try {
            JsonNode body = MAPPER.readTree(bytes);
            JsonNode error = body == null ? null : body.path("error");
            if (error != null && error.isObject()) {
                return safeJsonMessage(error);
            }
        } catch (RuntimeException ignored) {
            // The HTTP status is still enough to report a provider failure.
        }
        return null;
    }

    private static String safeJsonMessage(JsonNode error) {
        String message = error.path("message").asText(null);
        if (message == null || message.isBlank()) {
            return "The AI provider returned an error.";
        }
        return message.length() > 512 ? message.substring(0, 512) : message;
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "The AI request failed.";
        }
        return message.length() > 512 ? message.substring(0, 512) : message;
    }

    private static void closeQuietly(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (IOException ignored) {
        }
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static final class ProfileState {
        private final String name;
        private final AITerminalSettings.Profile profile;
        private final Deque<Request> queue = new ArrayDeque<>();
        private final Deque<Instant> startedAt = new ArrayDeque<>();
        private int active;
        private ScheduledFuture<?> wakeup;

        private ProfileState(String name, AITerminalSettings.Profile profile) {
            this.name = name;
            this.profile = profile;
        }
    }

    private static final class Request {
        private final AIChat chat;
        private final ProfileState state;
        private final String systemPrompt;
        private final String promptContent;
        private final AtomicReference<InputStream> input = new AtomicReference<>();
        private volatile boolean reasoningReceived;
        private volatile Future<?> task;

        private final AITerminalSettings.Profile endpoint;
        private final String imageUrl;
        private final boolean streaming;

        private Request(AIChat chat, ProfileState state, AITerminalSettings.Profile endpoint,
                        String systemPrompt, String promptContent, String imageUrl, boolean streaming) {
            this.chat = chat;
            this.state = state;
            this.endpoint = endpoint;
            this.systemPrompt = systemPrompt;
            this.promptContent = promptContent;
            this.imageUrl = imageUrl;
            this.streaming = streaming;
        }
    }
}
