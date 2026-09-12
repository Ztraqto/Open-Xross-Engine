package com.ztraqto.openxross.api.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public record GuildSettingDefinition(
        String owner,
        String key,
        String label,
        String description,
        SettingType type,
        JsonNode defaultValue,
        Integer minimum,
        Integer maximum,
        List<SettingChoice> choices,
        Map<String, String> labels,
        Map<String, String> descriptions,
        List<String> channelTypes,
        SettingScope scope
) {

    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");
    private static final Pattern LANGUAGE_PATTERN = Pattern.compile("[a-z]{2}(?:-[a-z]{2})?");

    public GuildSettingDefinition {
        owner = requireId(owner, "owner");
        key = requireId(key, "key");
        label = requireText(label, "label", 80);
        description = requireText(description, "description", 240);
        type = Objects.requireNonNull(type, "type");
        defaultValue = Objects.requireNonNull(defaultValue, "defaultValue").deepCopy();
        choices = choices == null ? List.of() : List.copyOf(choices);
        labels = validateLocalizations(labels, "label", 80);
        descriptions = validateLocalizations(descriptions, "description", 240);
        channelTypes = validateChannelTypes(channelTypes);
        scope = scope == null ? SettingScope.GUILD : scope;

        if (!key.startsWith(owner + ".")) {
            throw new IllegalArgumentException("Setting key must start with owner prefix '" + owner + ".'.");
        }
        if (minimum != null && maximum != null && minimum > maximum) {
            throw new IllegalArgumentException("minimum must not exceed maximum.");
        }
        validateValue(defaultValue, type, minimum, maximum, choices, defaultValue);
    }

    public GuildSettingDefinition(
            String owner,
            String key,
            String label,
            String description,
            SettingType type,
            JsonNode defaultValue,
            Integer minimum,
            Integer maximum,
            List<SettingChoice> choices
    ) {
        this(owner, key, label, description, type, defaultValue, minimum, maximum, choices, Map.of(), Map.of(), List.of(), SettingScope.GUILD);
    }

    public String label(String language) {
        return labels.getOrDefault(normalizeLanguage(language), label);
    }

    public String description(String language) {
        return descriptions.getOrDefault(normalizeLanguage(language), description);
    }

    public JsonNode validate(JsonNode value) {
        JsonNode copy = Objects.requireNonNull(value, "value").deepCopy();
        validateValue(copy, type, minimum, maximum, choices, defaultValue);
        return copy;
    }

    public static Builder builder(String owner, String key, SettingType type) {
        return new Builder(owner, key, type);
    }

    private static void validateValue(
            JsonNode value,
            SettingType type,
            Integer minimum,
            Integer maximum,
            List<SettingChoice> choices,
            JsonNode template
    ) {
        switch (type) {
            case BOOLEAN -> {
                if (!value.isBoolean()) {
                    throw new IllegalArgumentException("Boolean setting requires a boolean value.");
                }
            }
            case INTEGER -> {
                if (!value.isIntegralNumber()) {
                    throw new IllegalArgumentException("Integer setting requires an integer value.");
                }
                int number = value.intValue();
                if (minimum != null && number < minimum) {
                    throw new IllegalArgumentException("Setting value is below minimum " + minimum + ".");
                }
                if (maximum != null && number > maximum) {
                    throw new IllegalArgumentException("Setting value exceeds maximum " + maximum + ".");
                }
            }
            case STRING, SECRET -> {
                if (!value.isTextual()) {
                    throw new IllegalArgumentException(type + " setting requires a text value.");
                }
                int length = value.textValue().length();
                if (minimum != null && length < minimum) {
                    throw new IllegalArgumentException("Setting text is shorter than " + minimum + " characters.");
                }
                if (maximum != null && length > maximum) {
                    throw new IllegalArgumentException("Setting text exceeds " + maximum + " characters.");
                }
            }
            case SELECT -> {
                if (!value.isTextual() || choices.stream().noneMatch(choice -> choice.value().equals(value.textValue()))) {
                    throw new IllegalArgumentException("Select setting contains an unsupported choice.");
                }
            }
            case LIST -> validateObjectList(value, minimum, maximum, choices, template);
            case CHANNEL, ROLE -> {
                if (!value.isTextual() || !value.textValue().matches("0|[1-9][0-9]{5,24}")) {
                    throw new IllegalArgumentException(type + " setting requires a Discord snowflake string or 0.");
                }
            }
            case CHANNEL_LIST, ROLE_LIST -> validateDiscordIdList(value, type);
        }
    }

    private static void validateObjectList(JsonNode value, Integer minimum, Integer maximum,
                                           List<SettingChoice> fields, JsonNode template) {
        if (!value.isArray() || fields.isEmpty()) {
            throw new IllegalArgumentException("List setting requires an array and field definitions.");
        }
        if (minimum != null && value.size() < minimum) throw new IllegalArgumentException("List has too few items.");
        if (maximum != null && value.size() > maximum) throw new IllegalArgumentException("List has too many items.");
        JsonNode templateItem = template != null && template.isArray() && !template.isEmpty() ? template.get(0) : null;
        java.util.Set<String> allowed = fields.stream().map(SettingChoice::value)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (JsonNode item : value) {
            if (!item.isObject() || item.size() != allowed.size()) {
                throw new IllegalArgumentException("List item must contain exactly the configured fields.");
            }
            for (String field : allowed) {
                JsonNode fieldValue = item.get(field);
                JsonNode expected = templateItem == null ? null : templateItem.get(field);
                if (fieldValue == null
                        || (expected == null && !fieldValue.isTextual())
                        || (expected != null && (expected.isTextual() != fieldValue.isTextual()
                        || expected.isIntegralNumber() != fieldValue.isIntegralNumber()
                        || expected.isBoolean() != fieldValue.isBoolean()))) {
                    throw new IllegalArgumentException("List item field has an invalid type: " + field);
                }
                if (fieldValue.isTextual() && (fieldValue.textValue().length() > 1_000
                        || (("id".equals(field) || "word".equals(field) || "meaning".equals(field))
                        && fieldValue.textValue().isBlank()))) {
                    throw new IllegalArgumentException("List text field is blank or too long: " + field);
                }
                if (fieldValue.isIntegralNumber() && (fieldValue.longValue() < 0 || fieldValue.longValue() > 1_000_000)) {
                    throw new IllegalArgumentException("List integer field is out of range: " + field);
                }
                if ("points".equals(field) && fieldValue.asLong() > 100) {
                    throw new IllegalArgumentException("List points field exceeds 100.");
                }
            }
            if (allowed.contains("id")) {
                String id = item.path("id").asText();
                if (!id.matches("[A-Z][A-Z0-9_]{0,31}") || !ids.add(id)) {
                    throw new IllegalArgumentException("List item id is invalid or duplicated.");
                }
            }
        }
    }

    private static void validateDiscordIdList(JsonNode value, SettingType type) {
        if (!value.isTextual()) {
            throw new IllegalArgumentException(type + " setting requires a comma-separated Discord snowflake list.");
        }
        String input = value.textValue().trim();
        if (input.isEmpty()) {
            return;
        }
        for (String part : input.split(",")) {
            String normalized = part.trim();
            if (type == SettingType.CHANNEL_LIST) {
                normalized = normalized.split(":", 2)[0].trim();
            }
            if (!normalized.matches("[1-9][0-9]{5,24}")) {
                throw new IllegalArgumentException(type + " setting contains an invalid Discord snowflake.");
            }
        }
    }

    private static String requireId(String value, String name) {
        if (value == null || !ID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " contains unsupported characters or has an invalid length.");
        }
        return value;
    }

    private static String requireText(String value, String name, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " is required and must not exceed " + maxLength + " characters.");
        }
        return value.trim();
    }

    private static Map<String, String> validateLocalizations(
            Map<String, String> localizations,
            String name,
            int maxLength
    ) {
        if (localizations == null || localizations.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, String> validated = new LinkedHashMap<>();
        localizations.forEach((language, text) -> {
            String normalizedLanguage = normalizeLanguage(language);
            if (!LANGUAGE_PATTERN.matcher(normalizedLanguage).matches()) {
                throw new IllegalArgumentException("Unsupported setting localization language: " + language);
            }
            validated.put(normalizedLanguage, requireText(text, name + "[" + normalizedLanguage + "]", maxLength));
        });
        return Map.copyOf(validated);
    }

    private static List<String> validateChannelTypes(List<String> types) {
        if (types == null || types.isEmpty()) {
            return List.of();
        }
        ArrayList<String> validated = new ArrayList<>();
        for (String type : types) {
            String normalized = Objects.requireNonNull(type, "channel type").trim().toUpperCase(java.util.Locale.ROOT);
            if (!normalized.matches("[A-Z][A-Z_]{0,31}")) {
                throw new IllegalArgumentException("Unsupported Discord channel type: " + type);
            }
            if (!validated.contains(normalized)) {
                validated.add(normalized);
            }
        }
        return List.copyOf(validated);
    }

    private static String normalizeLanguage(String language) {
        if (language == null) {
            return "";
        }
        return language.trim().replace('_', '-').toLowerCase(java.util.Locale.ROOT);
    }

    public static final class Builder {
        private final String owner;
        private final String key;
        private final SettingType type;
        private String label;
        private String description = "No description provided.";
        private JsonNode defaultValue;
        private Integer minimum;
        private Integer maximum;
        private final List<SettingChoice> choices = new ArrayList<>();
        private final Map<String, String> labels = new LinkedHashMap<>();
        private final Map<String, String> descriptions = new LinkedHashMap<>();
        private final List<String> channelTypes = new ArrayList<>();
        private SettingScope scope = SettingScope.GUILD;

        private Builder(String owner, String key, SettingType type) {
            this.owner = owner;
            this.key = key;
            this.type = type;
            this.label = key;
            this.defaultValue = switch (type) {
                case BOOLEAN -> BooleanNode.FALSE;
                case INTEGER -> IntNode.valueOf(0);
                default -> TextNode.valueOf(type == SettingType.CHANNEL || type == SettingType.ROLE ? "0" : "");
            };
        }

        public Builder label(String label) {
            this.label = label;
            return this;
        }

        public Builder label(String language, String label) {
            this.labels.put(language, label);
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder description(String language, String description) {
            this.descriptions.put(language, description);
            return this;
        }

        public Builder defaultValue(boolean defaultValue) {
            this.defaultValue = BooleanNode.valueOf(defaultValue);
            return this;
        }

        public Builder defaultValue(int defaultValue) {
            this.defaultValue = IntNode.valueOf(defaultValue);
            return this;
        }

        public Builder defaultValue(String defaultValue) {
            this.defaultValue = TextNode.valueOf(defaultValue);
            return this;
        }

        public Builder defaultValue(JsonNode defaultValue) {
            this.defaultValue = Objects.requireNonNull(defaultValue, "defaultValue").deepCopy();
            return this;
        }

        public Builder range(int minimum, int maximum) {
            this.minimum = minimum;
            this.maximum = maximum;
            return this;
        }

        public Builder choice(String value, String label) {
            this.choices.add(new SettingChoice(value, label));
            return this;
        }

        public Builder channelTypes(String... channelTypes) {
            if (type != SettingType.CHANNEL && type != SettingType.CHANNEL_LIST) {
                throw new IllegalStateException("Channel types can only be set for CHANNEL settings.");
            }
            if (channelTypes != null) {
                for (String channelType : channelTypes) {
                    this.channelTypes.add(channelType);
                }
            }
            return this;
        }

        public Builder scope(SettingScope scope) {
            this.scope = Objects.requireNonNull(scope, "scope");
            return this;
        }

        public GuildSettingDefinition build() {
            return new GuildSettingDefinition(
                    owner,
                    key,
                    label,
                    description,
                    type,
                    defaultValue,
                    minimum,
                    maximum,
                    choices,
                    labels,
                    descriptions,
                    channelTypes,
                    scope
            );
        }
    }
}
