import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Arrays;
import java.util.List;

/** 将用户问题向量化并从 Qdrant 检索最相关的学习策略 chunk。 */
public final class StrategySearcher {
    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("请提供问题，例如：mvn exec:java \"-Dexec.mainClass=StrategySearcher\" \"-Dexec.args=我总是学完就忘怎么办\"");
            System.exit(1);
        }

        String query = String.join(" ", Arrays.asList(args)).trim();
        try {
            try (EmbeddingClient embedding = new EmbeddingClient(System.getenv("SILICONFLOW_API_KEY"));
                 QdrantClient qdrant = new QdrantClient(StrategyIndexer.qdrantUrl())) {
                qdrant.ensureCollection(EmbeddingClient.DIMENSION);
                List<Float> queryVector = embedding.embedQuery(query);
                JsonArray points = qdrant.search(queryVector, 5);

                System.out.println("问题：" + query);
                System.out.println("最相关的 chunk：");
                for (int i = 0; i < points.size(); i++) {
                    JsonObject point = points.get(i).getAsJsonObject();
                    JsonObject payload = point.getAsJsonObject("payload");
                    System.out.printf("%n%d. 相似度 %.4f | %s | %s%n%s%n来源：%s%n",
                            i + 1,
                            point.get("score").getAsDouble(),
                            payload.get("strategyName").getAsString(),
                            payload.get("chunkType").getAsString(),
                            payload.get("text").getAsString(),
                            payload.get("sourceIds"));
                }
            }
        } catch (Exception error) {
            System.err.println("搜索失败：" + error.getMessage());
            System.exit(1);
        }
    }
}
