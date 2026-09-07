import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 读取应用配置：系统环境变量优先，项目根目录 .env 作为本地开发兜底。 */
public final class AppConfig {
    private static final Map<String, String> DOT_ENV = loadDotEnv();

    private AppConfig() {
    }

    public static String get(String name) {
        String environmentValue = System.getenv(name);
        if (environmentValue != null && !environmentValue.isBlank()) {
            return environmentValue.trim();
        }
        String fileValue = DOT_ENV.get(name);
        return fileValue == null || fileValue.isBlank() ? null : fileValue.trim();
    }

    public static String getOrDefault(String name, String defaultValue) {
        String value = get(name);
        return value == null ? defaultValue : value;
    }

    public static String require(String name) {
        String value = get(name);
        if (value == null) {
            throw new IllegalStateException("缺少配置 " + name + "；请设置环境变量或在项目根目录创建 .env");
        }
        return value;
    }

    private static Map<String, String> loadDotEnv() {
        Path path = Path.of(".env");
        if (!Files.isRegularFile(path)) {
            return Map.of();
        }

        Map<String, String> values = new HashMap<>();
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("export ")) line = line.substring(7).trim();
                int separator = line.indexOf('=');
                if (separator <= 0) continue;
                String key = line.substring(0, separator).trim();
                String value = line.substring(separator + 1).trim();
                if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                values.put(key, value);
            }
            return Map.copyOf(values);
        } catch (IOException error) {
            throw new IllegalStateException("读取 .env 失败：" + error.getMessage(), error);
        }
    }
}
