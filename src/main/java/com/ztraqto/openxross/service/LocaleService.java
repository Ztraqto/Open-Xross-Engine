package com.ztraqto.openxross.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import com.ztraqto.openxross.XrossEngine;
import com.ztraqto.openxross.api.IService;
import com.ztraqto.openxross.api.plugin.XrossPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.Enumeration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class LocaleService implements IService {

    private static final Logger logger = LoggerFactory.getLogger(LocaleService.class);
    private static final String SYSTEM_ID = "XROSS-SYSTEM";
    private static final String DEFAULT_LANG = "ja";

    private final ObjectMapper mapper = new ObjectMapper();
    private final GuildSettingsService guildSettingsService;
    private final UserSettingsService userSettingsService;
    private final Map<String, Map<String, Map<String, String>>> cache = new ConcurrentHashMap<>();
    private final Map<Long, String> guildLanguages = new ConcurrentHashMap<>();

    public LocaleService(GuildSettingsService guildSettingsService, UserSettingsService userSettingsService) {
        this.guildSettingsService = guildSettingsService;
        this.userSettingsService = userSettingsService;
    }

    @Override
    public void init(XrossEngine engine) {
        loadLocales(SYSTEM_ID, getClass(), "xross-lang");
        loadLocales(SYSTEM_ID, getClass(), "lang");
        guildSettingsService.registerListener(
                "xross-locale",
                GuildSettingsService.LANGUAGE_KEY,
                (guildId, value) -> guildLanguages.put(guildId, value.asText())
        );
    }

    @Override
    public void shutdown() {
        cache.clear();
        guildLanguages.clear();
        guildSettingsService.unregisterListeners("xross-locale");
    }

    @Override
    public String getName() {
        return "LocaleService";
    }

    public void loadPluginLocales(XrossPlugin plugin) {
        loadLocales(plugin.getMeta().getId(), plugin.getClass(), "plugin-lang");
    }

    public void unloadPluginLocales(String pluginId) {
        cache.remove(pluginId);
    }

    public void setGuildLanguage(long guildId, String language) {
        if (language == null || !getAvailableSystemLanguages().contains(language)) {
            throw new IllegalArgumentException("Unsupported language: " + language);
        }
        guildSettingsService.setValue(guildId, GuildSettingsService.LANGUAGE_KEY, TextNode.valueOf(language));
        guildLanguages.put(guildId, language);
        logger.info("Saved language '{}' for guild {}", language, guildId);
    }

    public String get(XrossPlugin plugin, long guildId, String key, Object... args) {
        return translate(plugin.getMeta().getId(), guildId, key, args);
    }

    public String get(XrossPlugin plugin, long guildId, long userId, String key, Object... args) {
        return translate(plugin.getMeta().getId(), guildId, userId, key, args);
    }

    public String getSystemText(long guildId, String key, Object... args) {
        return translate(SYSTEM_ID, guildId, key, args);
    }

    public String getSystemText(long guildId, long userId, String key, Object... args) {
        return translate(SYSTEM_ID, guildId, userId, key, args);
    }

    public Map<DiscordLocale, String> getDiscordLocalizations(XrossPlugin plugin, String key) {
        String ownerId = plugin == null ? SYSTEM_ID : plugin.getMeta().getId();
        Map<String, Map<String, String>> languages = cache.get(ownerId);
        if (languages == null) {
            return Map.of();
        }
        EnumMap<DiscordLocale, String> localizations = new EnumMap<>(DiscordLocale.class);
        languages.forEach((language, dictionary) -> {
            String text = dictionary.get(key);
            if (text == null || text.isBlank()) {
                return;
            }
            if ("en".equalsIgnoreCase(language)) {
                localizations.put(DiscordLocale.ENGLISH_US, text);
                localizations.put(DiscordLocale.ENGLISH_UK, text);
                return;
            }
            DiscordLocale locale = DiscordLocale.from(language.replace('_', '-'));
            if (locale != DiscordLocale.UNKNOWN) {
                localizations.put(locale, text);
            }
        });
        return Map.copyOf(localizations);
    }

    public String getDefaultText(XrossPlugin plugin, String key, String fallback) {
        String ownerId = plugin == null ? SYSTEM_ID : plugin.getMeta().getId();
        Map<String, Map<String, String>> languages = cache.get(ownerId);
        if (languages == null) {
            return fallback;
        }
        Map<String, String> dictionary = languages.get(DEFAULT_LANG);
        if (dictionary == null) {
            return fallback;
        }
        String text = dictionary.get(key);
        return text == null || text.isBlank() ? fallback : text;
    }

    public Set<String> getAvailableSystemLanguages() {
        Map<String, Map<String, String>> systemLanguages = cache.get(SYSTEM_ID);
        return systemLanguages == null ? Set.of() : Set.copyOf(systemLanguages.keySet());
    }

    public String getGuildLanguage(long guildId) {
        return guildLanguages.computeIfAbsent(
                guildId,
                id -> guildSettingsService.getString(id, GuildSettingsService.LANGUAGE_KEY)
        );
    }

    public String getLanguage(long guildId, long userId) {
        if (userId > 0L && userSettingsService != null) {
            String preference = userSettingsService.getString(userId, UserSettingsService.LANGUAGE_PREFERENCE_KEY);
            if (preference != null && !preference.equalsIgnoreCase("inherit")
                    && getAvailableSystemLanguages().contains(preference)) {
                return preference;
            }
        }
        return getGuildLanguage(guildId);
    }

    private String translate(String ownerId, long guildId, String key, Object... args) {
        return translate(ownerId, guildId, 0L, key, args);
    }

    private String translate(String ownerId, long guildId, long userId, String key, Object... args) {
        String language = getLanguage(guildId, userId);
        Map<String, Map<String, String>> ownerLanguages = cache.get(ownerId);
        if (ownerLanguages == null) {
            return key;
        }
        Map<String, String> dictionary = ownerLanguages.get(language);
        if (dictionary == null || !dictionary.containsKey(key)) {
            dictionary = ownerLanguages.get(DEFAULT_LANG);
        }
        if (dictionary == null) {
            return key;
        }
        String translation = dictionary.get(key);
        if (translation == null) {
            return key;
        }
        try {
            return MessageFormat.format(translation, args);
        } catch (IllegalArgumentException exception) {
            return translation;
        }
    }

    private void loadLocales(String ownerId, Class<?> sourceClass, String basePath) {
        LinkedHashMap<String, Map<String, String>> loaded = new LinkedHashMap<>();
        scanClassPathResources(sourceClass, basePath, loaded);
        scanCodeSource(sourceClass, basePath, loaded);

        if (loaded.isEmpty()) {
            logger.warn("No locale resources found for {} at {}.", ownerId, basePath);
            return;
        }

        cache.merge(ownerId, loaded, (existing, incoming) -> {
            incoming.forEach((language, values) -> existing
                    .computeIfAbsent(language, ignored -> new ConcurrentHashMap<>())
                    .putAll(values));
            return existing;
        });
        logger.info("Loaded locales for {}: {}", ownerId, loaded.keySet());
    }

    private void scanClassPathResources(
            Class<?> sourceClass,
            String basePath,
            Map<String, Map<String, String>> target
    ) {
        try {
            Enumeration<URL> resources = sourceClass.getClassLoader().getResources(basePath);
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                if ("file".equals(resource.getProtocol())) {
                    scanDirectory(Path.of(resource.toURI()), target);
                }
            }
        } catch (Exception exception) {
            logger.warn("Failed to scan classpath locales at {}.", basePath, exception);
        }
    }

    private void scanCodeSource(Class<?> sourceClass, String basePath, Map<String, Map<String, String>> target) {
        try {
            URL location = sourceClass.getProtectionDomain().getCodeSource().getLocation();
            URI uri = location.toURI();
            Path path = Path.of(uri);
            if (Files.isDirectory(path)) {
                scanDirectory(path.resolve(basePath), target);
                return;
            }
            if (Files.isRegularFile(path)) {
                scanJar(path, basePath, target);
            }
        } catch (Exception exception) {
            logger.warn("Failed to scan code source locales for {}.", sourceClass.getName(), exception);
        }
    }

    private void scanDirectory(Path directory, Map<String, Map<String, String>> target) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var files = Files.list(directory)) {
            files.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(path -> {
                        try (InputStream input = Files.newInputStream(path)) {
                            mergeLocale(target, languageFromFile(path.getFileName().toString()), input);
                        } catch (IOException exception) {
                            logger.warn("Failed to read locale file {}.", path, exception);
                        }
                    });
        }
    }

    private void scanJar(Path jarPath, String basePath, Map<String, Map<String, String>> target) throws IOException {
        String prefix = basePath.endsWith("/") ? basePath : basePath + "/";
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith(prefix) || !name.endsWith(".json")) {
                    continue;
                }
                String relativeName = name.substring(prefix.length());
                if (relativeName.contains("/")) {
                    continue;
                }
                try (InputStream input = jar.getInputStream(entry)) {
                    mergeLocale(target, languageFromFile(relativeName), input);
                }
            }
        }
    }

    private void mergeLocale(Map<String, Map<String, String>> target, String language, InputStream input) throws IOException {
        Map<String, String> values = mapper.readValue(input, new TypeReference<>() {
        });
        target.computeIfAbsent(language, ignored -> new HashMap<>()).putAll(values);
    }

    private static String languageFromFile(String fileName) {
        return fileName.substring(0, fileName.length() - ".json".length());
    }
}
