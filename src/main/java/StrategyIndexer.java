import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** 读取最终 chunk，批量向量化并写入 Qdrant。 */
public final class StrategyIndexer {
    private static final Path CHUNK_DIR = Path.of("data", "chunks");
    private static final Gson GSON = new Gson();

    public static void main(String[] args) {
        try {
            List<JsonObject> chunks = loadChunks();
            if (chunks.isEmpty()) {
                throw new IllegalStateException("data/chunks 中没有可入库的 .jsonl chunk");
            }

            try (EmbeddingClient embedding = new EmbeddingClient(System.getenv("SILICONFLOW_API_KEY"));
                 QdrantClient qdrant = new QdrantClient(qdrantUrl())) {
                qdrant.ensureCollection(EmbeddingClient.DIMENSION);

                int indexed = 0;
                for (int start = 0; start < chunks.size(); start += EmbeddingClient.MAX_BATCH_SIZE) {
                    List<JsonObject> batch = chunks.subList(start,
                            Math.min(start + EmbeddingClient.MAX_BATCH_SIZE, chunks.size()));
                    List<String> texts = batch.stream().map(chunk -> chunk.get("text").getAsString()).toList();
                    List<List<Float>> vectors = embedding.embedDocuments(texts);

                    List<JsonObject> points = new ArrayList<>();
                    for (int i = 0; i < batch.size(); i++) {
                        points.add(toPoint(batch.get(i), vectors.get(i)));
                    }
                    qdrant.upsert(points);
                    indexed += batch.size();
                    System.out.printf("入库进度：%d/%d%n", indexed, chunks.size());
                }
                System.out.println("入库完成，共写入 " + indexed + " 个 chunk。重复运行会按 chunkId 更新，不会产生重复点。");
            }
        } catch (Exception error) {
            System.err.println("入库失败：" + error.getMessage());
            System.exit(1);
        }
    }

    private static List<JsonObject> loadChunks() throws IOException {
        if (!Files.isDirectory(CHUNK_DIR)) {
            return List.of();
        }
        List<Path> files;
        try (Stream<Path> paths = Files.list(CHUNK_DIR)) {
            files = paths.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .sorted(Comparator.comparing(Path::toString)).toList();
        }

        List<JsonObject> chunks = new ArrayList<>();
        for (Path file : files) {
            int lineNumber = 0;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                lineNumber++;
                if (line.isBlank()) continue;
                try {
                    JsonObject chunk = GSON.fromJson(line, JsonObject.class);
                    if (!chunk.has("chunkId") || !chunk.has("text") || !chunk.has("metadata")) {
                        throw new IllegalArgumentException("缺少 chunkId、text 或 metadata");
                    }
                    chunks.add(chunk);
                } catch (RuntimeException error) {
                    throw new IOException(file + " 第 " + lineNumber + " 行无效：" + error.getMessage(), error);
                }
            }
        }
        return chunks;
    }

    private static JsonObject toPoint(JsonObject chunk, List<Float> vector) {
        JsonObject point = new JsonObject();
        point.addProperty("id", chunk.get("chunkId").getAsString());
        point.add("vector", GSON.toJsonTree(vector));
        JsonObject payload = chunk.getAsJsonObject("metadata").deepCopy();
        payload.addProperty("text", chunk.get("text").getAsString());
        payload.addProperty("embeddingModel", EmbeddingClient.MODEL);
        payload.addProperty("embeddingDimension", EmbeddingClient.DIMENSION);
        point.add("payload", payload);
        return point;
    }

    static String qdrantUrl() {
        String configured = System.getenv("QDRANT_URL");
        return configured == null || configured.isBlank() ? "http://127.0.0.1:6333" : configured.trim();
    }
}
