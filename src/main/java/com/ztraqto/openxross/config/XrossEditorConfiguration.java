package com.ztraqto.openxross.config;

import java.net.URI;
import java.time.Duration;

public final class XrossEditorConfiguration {

    private final URI editorUri;
    private final String signingSecret;
    private final Duration sessionTtl;
    private final boolean attachmentTransport;
    private final long attachmentChannelId;

    private XrossEditorConfiguration(Builder builder) {
        this.editorUri = normalizeEditorUri(builder.editorUrl);
        this.signingSecret = normalizeOptional(builder.signingSecret);
        this.sessionTtl = requirePositive(builder.sessionTtl);
        this.attachmentTransport = builder.attachmentTransport;
        this.attachmentChannelId = builder.attachmentChannelId;
        if (signingSecret != null && signingSecret.length() < 32) {
            throw new IllegalArgumentException("XROSS_EDITOR_SECRET must contain at least 32 characters.");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public URI editorUri() {
        return editorUri;
    }

    public String signingSecret() {
        return signingSecret;
    }

    public Duration sessionTtl() {
        return sessionTtl;
    }

    public boolean attachmentTransport() {
        return attachmentTransport;
    }

    public long attachmentChannelId() {
        return attachmentChannelId;
    }

    public boolean isEnabled() {
        return editorUri != null && signingSecret != null;
    }

    private static URI normalizeEditorUri(String value) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            return null;
        }

        URI uri = URI.create(normalized);
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("editorUrl must include a scheme and host.");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("editorUrl must use http or https.");
        }
        if ("http".equalsIgnoreCase(uri.getScheme()) && !isLoopbackHost(uri.getHost())) {
            throw new IllegalArgumentException(
                    "editorUrl must use HTTPS outside loopback so signed Editor sessions cannot be intercepted."
            );
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("editorUrl must not contain credentials, a query or a fragment.");
        }
        return uri;
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host;
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return "localhost".equalsIgnoreCase(normalized)
                || "127.0.0.1".equals(normalized)
                || "::1".equals(normalized)
                || "0:0:0:0:0:0:0:1".equals(normalized);
    }

    private static String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static Duration requirePositive(Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("sessionTtl must be positive.");
        }
        return value;
    }

    public static final class Builder {
        private String editorUrl = XrossLocalConfiguration.string("editor.url", "XROSS_EDITOR_URL");
        private String signingSecret = XrossLocalConfiguration.secret("editor.signingSecret", "XROSS_EDITOR_SECRET", "XROSS_EDITOR_SECRET_FILE");
        private Duration sessionTtl = Duration.ofMinutes(30);
        private boolean attachmentTransport = Boolean.parseBoolean(
                XrossLocalConfiguration.string("editor.attachmentTransport", "XROSS_EDITOR_ATTACHMENT_TRANSPORT", "true")
        );
        private long attachmentChannelId = parseChannelId(XrossLocalConfiguration.string(
                "editor.attachmentChannelId", "XROSS_EDITOR_ATTACHMENT_CHANNEL_ID"
        ));

        private Builder() {
        }

        public Builder editorUrl(String editorUrl) {
            this.editorUrl = editorUrl;
            return this;
        }

        public Builder signingSecret(String signingSecret) {
            this.signingSecret = signingSecret;
            return this;
        }

        public Builder sessionTtl(Duration sessionTtl) {
            this.sessionTtl = sessionTtl;
            return this;
        }

        public Builder attachmentTransport(boolean attachmentTransport) {
            this.attachmentTransport = attachmentTransport;
            return this;
        }

        public Builder attachmentChannelId(long attachmentChannelId) {
            this.attachmentChannelId = attachmentChannelId;
            return this;
        }

        public XrossEditorConfiguration build() {
            return new XrossEditorConfiguration(this);
        }

        private static long parseChannelId(String value) {
            if (value == null || value.isBlank()) return 0L;
            try {
                return Long.parseUnsignedLong(value.trim());
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("XROSS_EDITOR_ATTACHMENT_CHANNEL_ID must be a Discord channel id.", exception);
            }
        }
    }
}
