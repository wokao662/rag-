package com.example.rag.cli;

import com.example.rag.AppConfig;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 把 data/strategies 的策略档案、data/chunks 的检索片段与 data/sources 的来源导入存储层。
 *
 * <p>此前策略只活在文件与 Qdrant payload 里：没有事务、没有审计、无法承载审核队列，
 * reviewStatus 也只是记录而不是闸门。导入之后这些字段才真正参与运行时判定。
 *
 * <p><b>写入边界</b>：导入器只写内容与"输入分"（evidence_score / effectiveness_score），
 * 绝不写 community_score / overall_score / tried_count / helpful_count / exposed_user_count /
 * exposure_state / pending_archive——这些是反馈消费服务的独占列，导入时覆盖会把真实用户数据抹成
 * 档案里的占位值。档案里的 communityScore（如 0.5、0.9）在 triedCount 为 0 时没有任何数据支撑，
 * 一律不导入。派生分由应用启动时按反馈表重算。
 *
 * <p>重复运行安全：内容按主键 upsert；评分与审核状态只在策略仍为 draft 时才跟随档案，
 * 一旦人工审核过，档案就不再是权威来源。
 */
public final class StrategyImporter {
    private static final Path STRATEGY_DIR = Path.of("data", "strategies");
    private static final Path CHUNK_DIR = Path.of("data", "chunks");
    private static final Path SOURCE_DIR = Path.of("data", "sources");
    private static final Gson GSON = new Gson();

    /** 档案里这些字段直接作为 JSONB 存下来，结构不做二次建模：它们是给模型和审核者看的原文。 */
    private static final List<String> JSON_FIELDS =
            List.of("aliases", "steps", "suitableFor", "notSuitableFor", "sourceIds");

    private StrategyImporter() {
    }

    public static void main(String[] args) {
        List<String> warnings = new ArrayList<>();
        try {
            Map<String, JsonObject> archives = loadArchives(warnings);
            Map<String, List<JsonObject>> chunksByStrategy = loadChunks(warnings);
            Map<String, JsonObject> sourceFiles = loadSources();

            try (Connection connection = open()) {
                connection.setAutoCommit(false);
                try {
                    int[] sourceCounts = importSources(connection, archives, sourceFiles, warnings);
                    int[] strategyCounts = importStrategies(connection, archives, chunksByStrategy, warnings);
                    int chunkCount = importChunks(connection, chunksByStrategy);
                    connection.commit();
                    report(archives, chunksByStrategy, sourceCounts, strategyCounts, chunkCount, warnings);
                } catch (SQLException | RuntimeException error) {
                    connection.rollback();
                    throw error;
                }
            }
        } catch (Exception error) {
            System.err.println("导入失败：" + error.getMessage());
            error.printStackTrace(System.err);
            System.exit(1);
        }
    }

    // ---------- 读取 ----------

    private static Map<String, JsonObject> loadArchives(List<String> warnings) throws IOException {
        Map<String, JsonObject> archives = new LinkedHashMap<>();
        for (Path file : listJson(STRATEGY_DIR)) {
            JsonObject strategy = parseObject(file);
            String strategyId = text(strategy, "strategyId");
            if (strategyId == null || strategyId.isBlank()) {
                warnings.add(file.getFileName() + "：缺少 strategyId，已跳过");
                continue;
            }
            if (archives.put(strategyId, strategy) != null) {
                warnings.add(file.getFileName() + "：strategyId 重复（" + strategyId + "），后者覆盖前者");
            }
        }
        if (archives.isEmpty()) {
            throw new IllegalStateException(STRATEGY_DIR + " 中没有可导入的策略档案");
        }
        return archives;
    }

    private static Map<String, List<JsonObject>> loadChunks(List<String> warnings) throws IOException {
        Map<String, List<JsonObject>> chunks = new LinkedHashMap<>();
        if (!Files.isDirectory(CHUNK_DIR)) {
            warnings.add(CHUNK_DIR + " 不存在，strategy_chunks 将为空");
            return chunks;
        }
        List<Path> files;
        try (Stream<Path> paths = Files.list(CHUNK_DIR)) {
            files = paths.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .sorted(Comparator.comparing(Path::toString)).toList();
        }
        for (Path file : files) {
            int lineNumber = 0;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                lineNumber++;
                if (line.isBlank()) continue;
                JsonObject chunk;
                try {
                    chunk = GSON.fromJson(line, JsonObject.class);
                } catch (RuntimeException error) {
                    throw new IOException(file + " 第 " + lineNumber + " 行不是合法 JSON：" + error.getMessage(), error);
                }
                JsonObject metadata = chunk.getAsJsonObject("metadata");
                if (metadata == null || text(metadata, "strategyId") == null) {
                    throw new IOException(file + " 第 " + lineNumber + " 行缺少 metadata.strategyId");
                }
                chunks.computeIfAbsent(text(metadata, "strategyId"), key -> new ArrayList<>()).add(chunk);
            }
        }
        return chunks;
    }

    private static Map<String, JsonObject> loadSources() throws IOException {
        Map<String, JsonObject> sources = new LinkedHashMap<>();
        if (!Files.isDirectory(SOURCE_DIR)) return sources;
        for (Path file : listJson(SOURCE_DIR)) {
            JsonObject source = parseObject(file);
            String sourceId = text(source, "sourceId");
            if (sourceId != null && !sourceId.isBlank()) sources.put(sourceId, source);
        }
        return sources;
    }

    private static List<Path> listJson(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(Path::toString)).toList();
        }
    }

    private static JsonObject parseObject(Path file) throws IOException {
        JsonObject parsed = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
        if (parsed == null) throw new IOException(file + " 是空文件");
        return parsed;
    }

    // ---------- 写入 ----------

    /**
     * 导入来源。除了 data/sources 里的档案，被策略引用却没有档案的 sourceId 也会建一行占位：
     * 宁可留一条"来源缺失"的显式记录，也不要让引用悬空。
     */
    private static int[] importSources(Connection connection, Map<String, JsonObject> archives,
                                      Map<String, JsonObject> sourceFiles, List<String> warnings)
            throws SQLException {
        Set<String> referenced = new LinkedHashSet<>();
        for (JsonObject archive : archives.values()) {
            for (String sourceId : textList(archive.get("sourceIds"))) referenced.add(sourceId);
        }
        Set<String> all = new LinkedHashSet<>(sourceFiles.keySet());
        all.addAll(referenced);

        String sql = """
                INSERT INTO sources (source_id, kind, title, attribution, url)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (source_id) DO UPDATE SET
                    kind = EXCLUDED.kind, title = EXCLUDED.title,
                    attribution = EXCLUDED.attribution, url = EXCLUDED.url
                RETURNING (xmax = 0) AS inserted
                """;
        int inserted = 0;
        int updated = 0;
        for (String sourceId : all) {
            JsonObject source = sourceFiles.get(sourceId);
            if (source == null) {
                warnings.add(sourceId + "：被策略引用但 data/sources 中没有档案，已建占位行");
            }
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, sourceId);
                statement.setString(2, source == null ? "other" : kindOf(text(source, "sourceType")));
                setNullableText(statement, 3, source == null ? null : text(source, "title"));
                setNullableText(statement, 4, source == null ? null : attributionOf(source));
                setNullableText(statement, 5, source == null ? null : text(source, "url"));
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next() && result.getBoolean("inserted")) inserted++;
                    else updated++;
                }
            }
        }
        return new int[]{inserted, updated};
    }

    /** research_paper / book / journal 都归 literature；其余归 other，由审核者再细分。 */
    private static String kindOf(String sourceType) {
        if (sourceType == null) return "other";
        return switch (sourceType) {
            case "research_paper", "book", "journal", "textbook", "literature" -> "literature";
            case "web", "website", "webpage" -> "web";
            case "user_submission" -> "user_submission";
            default -> "other";
        };
    }

    private static String attributionOf(JsonObject source) {
        List<String> parts = new ArrayList<>();
        List<String> authors = source.get("authors") != null && source.get("authors").isJsonArray()
                ? source.getAsJsonArray("authors").asList().stream()
                        .filter(element -> !element.isJsonNull())
                        .map(JsonElement::getAsString).toList()
                : List.of();
        if (!authors.isEmpty()) parts.add(String.join("、", authors));
        String publisher = text(source, "publisher");
        if (publisher != null) parts.add(publisher);
        JsonElement year = source.get("publishedYear");
        if (year != null && !year.isJsonNull()) parts.add(year.getAsString());
        return parts.isEmpty() ? null : String.join("，", parts);
    }

    /**
     * 导入策略档案。chunk 里没有对应档案的策略会建一行 archive_missing=true 的拦截行：
     * 它在 Qdrant 里对外服务却没有源档案，属于无溯源内容，闸门会挡下它直到补上档案。
     */
    private static int[] importStrategies(Connection connection, Map<String, JsonObject> archives,
                                         Map<String, List<JsonObject>> chunksByStrategy,
                                         List<String> warnings) throws SQLException {
        String sql = """
                INSERT INTO strategies (strategy_id, name, aliases, summary, steps, suitable_for,
                                        not_suitable_for, source_ids, evidence_score,
                                        effectiveness_score, review_status, archive_missing)
                VALUES (?, ?, ?::jsonb, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?)
                ON CONFLICT (strategy_id) DO UPDATE SET
                    name = EXCLUDED.name,
                    aliases = EXCLUDED.aliases,
                    summary = EXCLUDED.summary,
                    steps = EXCLUDED.steps,
                    suitable_for = EXCLUDED.suitable_for,
                    not_suitable_for = EXCLUDED.not_suitable_for,
                    source_ids = EXCLUDED.source_ids,
                    archive_missing = EXCLUDED.archive_missing,
                    evidence_score = CASE WHEN strategies.review_status = 'draft'
                                          THEN EXCLUDED.evidence_score
                                          ELSE strategies.evidence_score END,
                    effectiveness_score = CASE WHEN strategies.review_status = 'draft'
                                               THEN EXCLUDED.effectiveness_score
                                               ELSE strategies.effectiveness_score END,
                    review_status = CASE WHEN strategies.review_status = 'draft'
                                         THEN EXCLUDED.review_status
                                         ELSE strategies.review_status END,
                    updated_at = NOW()
                RETURNING (xmax = 0) AS inserted
                """;
        int inserted = 0;
        int updated = 0;
        for (Map.Entry<String, JsonObject> entry : archives.entrySet()) {
            if (importStrategy(connection, sql, entry.getKey(), entry.getValue(), false, warnings)) inserted++;
            else updated++;
        }

        for (String strategyId : chunksByStrategy.keySet()) {
            if (archives.containsKey(strategyId)) continue;
            JsonObject placeholder = new JsonObject();
            placeholder.addProperty("name", strategyId);
            warnings.add(strategyId + "：在 data/chunks 里对外服务但 data/strategies 中没有档案，"
                    + "已建 archive_missing 拦截行，闸门会挡下它直到补上档案");
            if (importStrategy(connection, sql, strategyId, placeholder, true, warnings)) inserted++;
            else updated++;
        }
        return new int[]{inserted, updated};
    }

    private static boolean importStrategy(Connection connection, String sql, String strategyId,
                                         JsonObject archive, boolean archiveMissing,
                                         List<String> warnings) throws SQLException {
        JsonObject recommendation = archive.getAsJsonObject("recommendation");
        Double evidence = scoreOf(recommendation, "evidenceScore");
        Double effectiveness = scoreOf(recommendation, "effectivenessScore");
        if (!archiveMissing) {
            List<String> missing = new ArrayList<>();
            if (evidence == null) missing.add("evidenceScore");
            if (effectiveness == null) missing.add("effectivenessScore");
            if (!missing.isEmpty()) {
                warnings.add(strategyId + "：档案缺少 " + String.join(" 与 ", missing)
                        + "，按 0 导入，需要审核者补分");
            }
        }

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, strategyId);
            statement.setString(2, requireName(archive, strategyId));
            statement.setString(3, jsonOf(archive, "aliases"));
            setNullableText(statement, 4, text(archive, "summary"));
            statement.setString(5, jsonOf(archive, "steps"));
            statement.setString(6, jsonOf(archive, "suitableFor"));
            statement.setString(7, jsonOf(archive, "notSuitableFor"));
            statement.setString(8, jsonOf(archive, "sourceIds"));
            statement.setDouble(9, evidence == null ? 0 : evidence);
            statement.setDouble(10, effectiveness == null ? 0 : effectiveness);
            // 档案里的 reviewStatus 原样导入（当前全是 draft）：审核是人的判断，导入器不代替人签字。
            statement.setString(11, orDefault(text(archive, "reviewStatus"), "draft"));
            statement.setBoolean(12, archiveMissing);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean("inserted");
            }
        }
    }

    private static String requireName(JsonObject archive, String strategyId) {
        String name = text(archive, "name");
        if (name == null || name.isBlank()) {
            throw new IllegalStateException(strategyId + "：档案缺少 name，无法导入");
        }
        return name;
    }

    /**
     * 导入 chunk 镜像。先删该策略的旧 chunk 再整批写入：档案改了步骤数之后，
     * 单纯 upsert 会把已不存在的旧 chunk 留在库里变成孤儿。
     */
    private static int importChunks(Connection connection, Map<String, List<JsonObject>> chunksByStrategy)
            throws SQLException {
        String delete = "DELETE FROM strategy_chunks WHERE strategy_id = ?";
        String insert = """
                INSERT INTO strategy_chunks (chunk_id, strategy_id, chunk_type, step_number,
                                             total_steps, condition_label, text, source_ids)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (chunk_id) DO UPDATE SET
                    strategy_id = EXCLUDED.strategy_id,
                    chunk_type = EXCLUDED.chunk_type,
                    step_number = EXCLUDED.step_number,
                    total_steps = EXCLUDED.total_steps,
                    condition_label = EXCLUDED.condition_label,
                    text = EXCLUDED.text,
                    source_ids = EXCLUDED.source_ids
                """;
        int total = 0;
        for (Map.Entry<String, List<JsonObject>> entry : chunksByStrategy.entrySet()) {
            try (PreparedStatement statement = connection.prepareStatement(delete)) {
                statement.setString(1, entry.getKey());
                statement.executeUpdate();
            }
            for (JsonObject chunk : entry.getValue()) {
                JsonObject metadata = chunk.getAsJsonObject("metadata");
                String chunkId = text(chunk, "chunkId");
                if (chunkId == null) throw new IllegalStateException(entry.getKey() + "：有 chunk 缺少 chunkId");
                try (PreparedStatement statement = connection.prepareStatement(insert)) {
                    statement.setObject(1, UUID.fromString(chunkId));
                    statement.setString(2, entry.getKey());
                    statement.setString(3, text(metadata, "chunkType"));
                    setNullableInt(statement, 4, metadata.get("stepNumber"));
                    setNullableInt(statement, 5, metadata.get("totalSteps"));
                    setNullableText(statement, 6, text(metadata, "conditionLabel"));
                    statement.setString(7, chunk.get("text").getAsString());
                    statement.setString(8, jsonArrayText(metadata.get("sourceIds")));
                    statement.executeUpdate();
                }
                total++;
            }
        }
        return total;
    }

    private static Connection open() throws SQLException {
        String url = "jdbc:postgresql://%s:%s/%s".formatted(
                AppConfig.getOrDefault("POSTGRES_HOST", "127.0.0.1"),
                AppConfig.getOrDefault("POSTGRES_PORT", "5432"),
                AppConfig.getOrDefault("POSTGRES_DB", "learning_app"));
        return DriverManager.getConnection(url,
                AppConfig.getOrDefault("POSTGRES_USER", "learning_app"),
                AppConfig.require("POSTGRES_PASSWORD"));
    }

    // ---------- 小工具 ----------

    private static void report(Map<String, JsonObject> archives, Map<String, List<JsonObject>> chunksByStrategy,
                              int[] sourceCounts, int[] strategyCounts, int chunkCount, List<String> warnings) {
        System.out.printf("来源：%d 新增，%d 更新%n", sourceCounts[0], sourceCounts[1]);
        System.out.printf("策略：%d 新增，%d 更新（档案 %d 个，chunk 覆盖 %d 个策略）%n",
                strategyCounts[0], strategyCounts[1], archives.size(), chunksByStrategy.size());
        System.out.printf("chunk 镜像：%d 行%n", chunkCount);
        System.out.println("community_score / overall_score / tried_count / helpful_count 未被导入器写入，"
                + "由应用启动时按 method_trial_feedback 重算。");
        if (!warnings.isEmpty()) {
            System.out.println("需要关注：");
            warnings.forEach(warning -> System.out.println("  - " + warning));
        }
    }

    private static Double scoreOf(JsonObject recommendation, String field) {
        if (recommendation == null) return null;
        JsonElement element = recommendation.get(field);
        if (element == null || element.isJsonNull()) return null;
        double value = element.getAsDouble();
        if (value < 0 || value > 1) {
            throw new IllegalStateException(field + " 必须在 0 到 1 之间，实际：" + value);
        }
        return value;
    }

    private static String jsonOf(JsonObject archive, String field) {
        return jsonArrayText(archive.get(field));
    }

    private static String jsonArrayText(JsonElement element) {
        return element == null || element.isJsonNull() ? "[]" : GSON.toJson(element);
    }

    private static String text(JsonObject object, String field) {
        if (object == null) return null;
        JsonElement element = object.get(field);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    private static List<String> textList(JsonElement element) {
        if (element == null || !element.isJsonArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (item != null && !item.isJsonNull()) values.add(item.getAsString());
        }
        return values;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void setNullableText(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null) statement.setNull(index, Types.VARCHAR);
        else statement.setString(index, value);
    }

    private static void setNullableInt(PreparedStatement statement, int index, JsonElement value)
            throws SQLException {
        if (value == null || value.isJsonNull()) statement.setNull(index, Types.INTEGER);
        else statement.setInt(index, value.getAsInt());
    }
}
