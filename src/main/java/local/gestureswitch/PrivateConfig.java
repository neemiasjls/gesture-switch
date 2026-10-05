package local.gestureswitch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads the ignored local configuration without printing its values. */
final class PrivateConfig {
    private final Path path;
    private final Map<String, String> values;

    private PrivateConfig(Path path, Map<String, String> values) {
        this.path = path;
        this.values = values;
    }

    static PrivateConfig load() throws IOException {
        Path path = Path.of(".secrets", "config.ini");
        if (!Files.exists(path)) return new PrivateConfig(path, Map.of());
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        Map<String, String> values = new HashMap<>();
        String section = "";
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) continue;
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                section = trimmed.substring(1, trimmed.length() - 1).trim().toLowerCase();
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (section.isEmpty() || separator < 1)
                throw new IllegalArgumentException("Configuração local inválida; confira a sintaxe de .secrets/config.ini");
            String key = trimmed.substring(0, separator).trim().toLowerCase();
            String value = trimmed.substring(separator + 1).trim();
            values.put(section + "." + key, value);
        }
        return new PrivateConfig(path, values);
    }

    String get(String section, String key, String fallback) {
        return values.getOrDefault(section + "." + key, fallback);
    }

    String required(String section, String key) {
        String value = get(section, key, "");
        if (value.isBlank()) throw new IllegalArgumentException("Preencha " + section + "." + key + " em .secrets/config.ini");
        return value;
    }

    Path path() { return path; }
}
