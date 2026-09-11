package com.example.rag.recommendation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link QdrantClient#scrollAll} 的分页行为。
 *
 * <p>用假 fetcher 而不是活的 Qdrant：截断只在命中超过单页 100 个 chunk 时发生，而当前整个语料
 * 才 107 个 chunk、分散在 13 个策略里，补齐一次最多命中几十个。为了触发它去灌一万条假向量进
 * 集合，会污染开发库且测完还得清理，而分页的三个陷阱都能用预设的响应复现。
 */
class QdrantClientTest {
    @Test
    void collectsEveryPageAndPassesTheOffsetThroughUnchanged() throws IOException {
        // 这是原本的 bug：只取第一页，limit 写死 100，next_page_offset 直接丢弃。
        FakeFetcher fetcher = new FakeFetcher(
                page("id-3", "id-1", "id-2"),
                page("id-5", "id-3", "id-4"),
                page(null, "id-5", "id-6"));

        JsonArray points = QdrantClient.scrollAll(fetcher, 6, 200);

        assertEquals(List.of("id-1", "id-2", "id-3", "id-4", "id-5", "id-6"), idsOf(points));
        // offset 必须原样传回上一页给的 next_page_offset：Qdrant 的 offset 是包含语义，而
        // next_page_offset 已经是下一个尚未返回的点，所以自己减一或加一都会漏点或重复。
        assertEquals(3, fetcher.offsets.size());
        assertEquals("id-3", fetcher.offsets.get(1).getAsString());
        assertEquals("id-5", fetcher.offsets.get(2).getAsString());
    }

    @Test
    void stopsWhenTheLastPageReportsANullOffset() throws IOException {
        // 真实 Qdrant 的末页是 "next_page_offset": null —— 字段存在、值为 JSON null，
        // 而 Gson 的 has() 对 JsonNull 返回 true。只判断 has() 会把这个 null 当成 offset
        // 再发一次请求，白白多一趟，或者在 Qdrant 拒绝 null offset 时整条推荐失败。
        FakeFetcher fetcher = new FakeFetcher(page(null, "id-1", "id-2"));

        // 总数故意写得比实际能取到的大，好让循环有理由继续问下一页。
        JsonArray points = QdrantClient.scrollAll(fetcher, 5, 200);

        assertEquals(List.of("id-1", "id-2"), idsOf(points));
        assertEquals(1, fetcher.offsets.size(), "末页之后不应该再问一页");
    }

    @Test
    void stopsAtTheBudgetAndShrinksTheLastPage() throws IOException {
        // 预算上限存在的理由是提示词不能没有边界，所以它必须真的挡住请求，
        // 而且最后一页只该要剩下的额度：多要的点数会被取回来再丢掉，白花带宽。
        FakeFetcher fetcher = new FakeFetcher(
                page("p2", someIds(1, 100)),
                page("p3", someIds(101, 50)),
                page(null, someIds(151, 100)));

        JsonArray points = QdrantClient.scrollAll(fetcher, 250, 150);

        assertEquals(150, points.size());
        assertEquals(List.of(100, 50), fetcher.limits);
        assertEquals(2, fetcher.offsets.size(), "取满预算就不该再问第三页");
    }

    @Test
    void neverExceedsTheBudgetEvenWhenAPageReturnsMoreThanAsked() throws IOException {
        // 预算得在本地守住，不能只是“Qdrant 会遵守 limit”的推论：totalMatching 是另一次
        // count 请求问来的，两次之间有新点写入时它已经偏小。这里让第二页故意多还 50 个。
        FakeFetcher fetcher = new FakeFetcher(
                page("p2", someIds(1, 100)),
                page("p3", someIds(101, 100)));

        JsonArray points = QdrantClient.scrollAll(fetcher, 250, 150);

        assertEquals(150, points.size());
        assertEquals("id-150", points.get(149).getAsJsonObject().get("id").getAsString(),
                "应该是按页序截断，而不是丢开头留尾巴");
    }

    @Test
    void budgetIsCappedByTheReportedTotalSoNoExtraPageIsRequested() throws IOException {
        // 命中总数已经问过一次 count 接口，所以它同时是循环上界：总数只有 2 个时不该因为
        // 预算是 200 就继续翻页探底。
        FakeFetcher fetcher = new FakeFetcher(page(null, "id-1", "id-2"));

        JsonArray points = QdrantClient.scrollAll(fetcher, 2, 200);

        assertEquals(2, points.size());
        assertEquals(List.of(2), fetcher.limits);
    }

    @Test
    void breaksOnAnEmptyPageInsteadOfSpinningForever() throws IOException {
        // 唯一会死循环的形状：空页配非 null 的 offset —— points 不增、循环条件永远成立，
        // 请求会挂在这里直到客户端超时。Qdrant 正常不这么返回，但代价不对称：
        // 加一个 break 换掉一个可能挂死推荐接口的循环。
        FakeFetcher fetcher = new FakeFetcher(page("somewhere", new String[0]));

        JsonArray points = QdrantClient.scrollAll(fetcher, 10, 200);

        assertTrue(points.isEmpty());
        assertEquals(1, fetcher.offsets.size());
    }

    @Test
    void surfacesAMissingPointsArrayAsAnError() {
        // fetcher 抛出的 IOException 必须原样冒出去：补齐失败时调用方要能区分“检索不可用”
        // 与“取回了 0 个 chunk”，后者会让推荐静默变成 no_match。
        QdrantClient.PageFetcher failing = (offset, limit) -> {
            throw new IOException("Qdrant 查询响应中缺少 result.points");
        };

        IOException error = assertThrows(IOException.class, () -> QdrantClient.scrollAll(failing, 10, 200));
        assertEquals("Qdrant 查询响应中缺少 result.points", error.getMessage());
    }

    private static JsonObject page(String nextOffset, String... pointIds) {
        JsonObject result = new JsonObject();
        JsonArray points = new JsonArray();
        for (String id : pointIds) {
            JsonObject point = new JsonObject();
            point.addProperty("id", id);
            points.add(point);
        }
        result.add("points", points);
        // 键一定要存在：真实响应的末页带的是 null 值，不是缺字段，测试得复现这个形状。
        if (nextOffset == null) result.add("next_page_offset", JsonNull.INSTANCE);
        else result.addProperty("next_page_offset", nextOffset);
        return result;
    }

    /** 生成 count 个 id，从 {@code id-<firstId>} 开始。参数名故意不用 to：区间开闭弄错过一次。 */
    private static String[] someIds(int firstId, int count) {
        String[] generated = new String[count];
        for (int index = 0; index < count; index++) {
            generated[index] = "id-" + (firstId + index);
        }
        return generated;
    }

    private static List<String> idsOf(JsonArray points) {
        List<String> collected = new ArrayList<>();
        points.forEach(point -> collected.add(point.getAsJsonObject().get("id").getAsString()));
        return collected;
    }

    /** 按顺序发放预设的页，并记下每次被要求的 offset 与 limit。 */
    private static final class FakeFetcher implements QdrantClient.PageFetcher {
        private final List<JsonObject> pages;
        private final List<JsonElement> offsets = new ArrayList<>();
        private final List<Integer> limits = new ArrayList<>();

        private FakeFetcher(JsonObject... pages) {
            this.pages = List.of(pages);
        }

        @Override
        public JsonObject fetch(JsonElement offset, int limit) {
            offsets.add(offset);
            limits.add(limit);
            if (offsets.size() > pages.size()) {
                throw new AssertionError("分页多问了一页，说明停止条件没生效");
            }
            return pages.get(offsets.size() - 1);
        }
    }
}
