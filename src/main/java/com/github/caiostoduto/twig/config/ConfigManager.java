package com.github.caiostoduto.twig.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class ConfigManager {
    private final Path configFile;
    private Map<String, Object> config;
    private final Yaml yaml;

    public ConfigManager(Path dataDirectory) {
        this.configFile = dataDirectory.resolve("config.yml");

        DumperOptions options = new DumperOptions();
        options.setIndent(2);
        options.setPrettyFlow(true);
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        this.yaml = new Yaml(options);
    }

    public void load() throws IOException {
        if (!Files.exists(configFile)) {
            createDefaultConfig();
        } else {
            try (InputStream in = Files.newInputStream(configFile)) {
                final Map<String, Object> loadedConfig = yaml.load(in);
                config = loadedConfig != null ? loadedConfig : new LinkedHashMap<>();
            } catch (Exception e) {
                throw new IOException("Failed to load configuration from " + configFile, e);
            }
        }
    }

    private void createDefaultConfig() throws IOException {
        // Load default config from resources
        try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
            if (in != null) {
                final Map<String, Object> loadedConfig = yaml.load(in);
                config = loadedConfig != null ? loadedConfig : new LinkedHashMap<>();
            } else {
                config = new LinkedHashMap<>();
            }
        } catch (Exception e) {
            throw new IOException("Failed to load default configuration from resources", e);
        }

        // Generate UUID if it doesn't exist or is empty
        final Object existingUuid = config.get("twig_uuid");
        if (existingUuid == null || existingUuid.toString().trim().isEmpty()) {
            final String generatedUuid = UUID.randomUUID().toString();
            config.put("twig_uuid", generatedUuid);
        }

        save();
    }

    public void save() throws IOException {
        try (Writer writer = Files.newBufferedWriter(configFile)) {
            yaml.dump(config, writer);
        } catch (Exception e) {
            throw new IOException("Failed to save configuration to " + configFile, e);
        }
    }

    public String getString(final String key) {
        if (key == null) {
            return null;
        }
        final Object value = config.get(key);
        return value != null ? value.toString() : null;
    }

    public String getString(final String key, final String defaultValue) {
        final String value = getString(key);
        return value != null ? value : defaultValue;
    }

    public int getInt(final String key, final int defaultValue) {
        if (key == null) {
            return defaultValue;
        }
        final Object value = config.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    public boolean getBoolean(final String key, final boolean defaultValue) {
        if (key == null) {
            return defaultValue;
        }
        final Object value = config.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return defaultValue;
    }

    public Object get(final String key) {
        if (key == null) {
            return null;
        }
        return config.get(key);
    }

    public void set(final String key, final Object value) {
        if (key == null) {
            throw new IllegalArgumentException("Config key cannot be null");
        }
        config.put(key, value);
    }

    /**
     * Returns an unmodifiable view of the configuration map.
     * This prevents external modifications to the configuration.
     * 
     * @return Unmodifiable map of configuration values
     */
    public Map<String, Object> getConfig() {
        return Collections.unmodifiableMap(config);
    }
}
