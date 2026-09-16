package com.example.rag.recommendation;

import com.example.rag.observability.ModelCallLogger;
import com.example.rag.observability.ModelReply;
import com.example.rag.observability.TokenUsage;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 编排完整推荐链路：画像生成检索文本、Qdrant 召回、治理闸门过滤、聊天模型生成、Java 校验。 */
@Service
public class RecommendationService {
    private static final int RETRIEVAL_LIMIT = 5;
    /**
     * 知识负载预算（字符）：拼接完成的知识超过它时按策略整组裁剪。以字符计而非 token：中文语料下
     * 1 字符≈1 token 量级，不引入 tokenizer 即可充当保守近似。12000 ≈ 填厚前典型负载（5 个策略
     * 全部 chunk 约 4200 字符）的 3 倍，远低于 200 点收集上限对应的十万字符量级。
     */
    private static final int KNOWLEDGE_BUDGET_CHARS = 12000;

    private final EmbeddingClient embeddingClient;
    private final QdrantClient qdrantClient;
    private final RecommendationChatClient chatClient;
    private final ProfileQueryBuilder queryBuilder;
    private final RecommendationValidator validator;
    private final ModelCallLogger callLogger;
    private final StrategyGovernanceService governance;
    private final AtomicBoolean collectionChecked = new AtomicBoolean();

    public RecommendationService(
            EmbeddingClient embeddingClient,
            QdrantClient qdrantClient,
            RecommendationChatClient chatClient,
            ProfileQueryBuilder queryBuilder,
            RecommendationValidator validator,
            ModelCallLogger callLogger,
            StrategyGovernanceService governance
    ) {
        this.embeddingClient = embeddingClient;
        this.qdrantClient = qdrantClient;
        this.chatClient = chatClient;
        this.queryBuilder = queryBuilder;
        this.validator = validator;
        this.callLogger = callLogger;
        this.governance = governance;
    }

    /** 推荐生成调用的两种形态（整块 / 流式）共用同一条管线，入口方法只决定怎么调。 */
    @FunctionalInterface
    private interface ChatGenerator {
        ModelReply<JsonObject> generate(JsonObject profile, String queryText, JsonArray knowledge) throws IOException;
    }

    public RecommendationResult recommend(JsonObject profile, UUID userId, UUID conversationId) {
        return recommendInternal(profile, userId, conversationId, chatClient::generate);
    }

    /**
     * recommend 的流式版本：answer 字段的增量文本在生成过程中就交给 onAnswerDelta
     * （用户先看到开头的话逐字出现），检索、闸门、校验、日志照常，返回结果与 recommend 同构。
     */
    public RecommendationResult recommendStreaming(JsonObject profile, UUID userId, UUID conversationId,
                                                   Consumer<String> answerDelta) {
        return recommendInternal(profile, userId, conversationId,
                (queryProfile, queryText, knowledge) ->
                        chatClient.generateStream(queryProfile, queryText, knowledge, answerDelta));
    }

    private RecommendationResult recommendInternal(JsonObject profile, UUID userId, UUID conversationId,
                                                   ChatGenerator generator) {
        String queryText = queryBuilder.build(profile);
        long start = System.currentTimeMillis();

        JsonArray knowledge;
        int filteredOut;
        // 被两大上限挡下的 chunk 数：Qdrant 收集侧的 200 点上限，与拼接后的知识负载预算。
        // 写在 try 外面并给初值：下面四个 logCall 分支都要用它，包括检索本身就失败的那一条（那时确实是 0）。
        int knowledgeDropped = 0;
        try {
            ensureCollection();
            List<Float> queryVector = embeddingClient.embedQuery(queryText);
            JsonArray hits = qdrantClient.search(queryVector, RETRIEVAL_LIMIT);
            // 闸门在召回之后、补齐之前：被挡下的策略连它的其他 chunk 也不应该被拉回来送给模型。
            JsonArray admitted = admit(hits);
            filteredOut = hits.size() - admitted.size();
            QdrantClient.Expansion expansion = expandByStrategy(admitted);
            knowledge = mergeKnowledge(buildKnowledge(admitted), expansion.points());
            if (expansion.truncated()) {
                knowledgeDropped = expansion.totalMatching() - expansion.points().size();
            }
            BudgetedKnowledge budgeted = trimToBudget(knowledge, KNOWLEDGE_BUDGET_CHARS);
            knowledge = budgeted.knowledge();
            knowledgeDropped += budgeted.droppedChunks();
        } catch (IOException error) {
            logCall(userId, conversationId, queryText, 0, 0, null, start, "failed", error.getMessage(), null);
            throw new RecommendationUnavailableException("策略检索服务暂时不可用", error);
        }

        if (knowledge.size() == 0) {
            RecommendationResult empty = new RecommendationResult(
                    "no_match", "知识库中还没有可推荐的学习策略，请稍后再试。",
                    queryText, List.of(), List.of(), List.of(), List.of());
            logCall(userId, conversationId, queryText, filteredOut, knowledgeDropped, outputOf(empty), start,
                    "success", null, null);
            return empty;
        }

        ModelReply<JsonObject> reply;
        try {
            reply = generator.generate(profile, queryText, knowledge);
        } catch (IOException error) {
            // 内容解析失败时 token 已经花掉了：MalformedOutputException 自带用量，照记不丢。
            // 其余失败（HTTP 错误、响应结构异常）确实没有可用的用量，维持 null。
            TokenUsage usage = error instanceof RecommendationChatClient.MalformedOutputException malformed
                    ? malformed.usage() : null;
            logCall(userId, conversationId, queryText, filteredOut, knowledgeDropped, null, start, "failed",
                    error.getMessage(), usage);
            throw new RecommendationUnavailableException("推荐生成服务暂时不可用", error);
        }
        JsonObject raw = reply.content();

        RecommendationValidator.Output output;
        try {
            output = validator.validate(raw, candidateChunkIds(knowledge));
        } catch (IllegalArgumentException error) {
            // 校验失败时 token 已经花掉了，用量照记。这类行正是“模型输出不合规范”的样本，
            // 而它花了多少 token 是判定该压 max_tokens 还是该改提示词的依据。
            logCall(userId, conversationId, queryText, filteredOut, knowledgeDropped, raw, start, "failed",
                    error.getMessage(), reply.usage());
            throw new RecommendationUnavailableException("推荐结果未通过格式校验", error);
        }
        RecommendationResult result = new RecommendationResult(output.status(), output.answer(), queryText,
                output.userConstraints(), output.recommendations(), output.followUpQuestions(),
                evidenceSources(knowledge, output.recommendations()));
        logCall(userId, conversationId, queryText, filteredOut, knowledgeDropped, outputOf(result), start,
                "success", null, reply.usage());
        recordExposure(userId, result.recommendations());
        return result;
    }

    /**
     * 用治理闸门过滤召回结果：未审核通过、待归档、已暂停或超出当前档位曝光人数上限的策略不进入推荐。
     *
     * <p>此前 reviewStatus 只写在 Qdrant payload 里而 Java 从不读取，draft 内容直接对外服务。
     */
    private JsonArray admit(JsonArray hits) {
        List<String> candidates = strategyIdsOf(hits);
        if (candidates.isEmpty()) return hits;
        Set<String> allowed;
        try {
            allowed = governance.recommendable(candidates);
        } catch (RuntimeException error) {
            // 闸门查不动时宁可保守：挡下全部候选，也不能把未审核内容当作已审核推给用户。
            // 打整个栈：外层异常只有一句“数据库操作失败”，真正的 SQL 错误在 cause 里，
            // 而这条路径失败会让推荐静默变空，不留栈就查不下去。
            System.err.println("策略闸门查询失败，本次不推荐任何策略：" + error.getMessage());
            error.printStackTrace(System.err);
            return new JsonArray();
        }
        return admitByGate(hits, allowed);
    }

    /**
     * 记录本次推荐曝光，驱动渐进投放的人数上限与扩量。
     *
     * <p>失败不阻断主流程（与 ModelCallLogger 同一原则）：推荐已经生成，不能因为记账写入失败
     * 就把它丢掉；曝光计数偏差下一次推荐就会补上。
     */
    private void recordExposure(UUID userId, List<RecommendationValidator.Recommendation> recommendations) {
        if (userId == null || recommendations == null || recommendations.isEmpty()) return;
        List<String> exposed = recommendations.stream()
                .map(RecommendationValidator.Recommendation::strategyId)
                .toList();
        try {
            governance.recordExposure(userId, exposed);
        } catch (RuntimeException error) {
            System.err.println("记录策略曝光失败，不影响本次推荐：" + error.getMessage());
            error.printStackTrace(System.err);
        }
    }

    /** 只保留通过闸门的命中。allowed 为 null 时不过滤。 */
    static JsonArray admitByGate(JsonArray hits, Set<String> allowed) {
        if (allowed == null) return hits;
        JsonArray admitted = new JsonArray();
        for (JsonElement element : hits) {
            JsonObject hit = element.getAsJsonObject();
            String strategyId = hit.getAsJsonObject("payload").get("strategyId").getAsString();
            if (allowed.contains(strategyId)) admitted.add(hit);
        }
        return admitted;
    }

    /** 召回结果里出现过的策略 ID，去重且保留召回顺序。 */
    static List<String> strategyIdsOf(JsonArray hits) {
        List<String> strategyIds = new ArrayList<>();
        for (JsonElement element : hits) {
            String strategyId = element.getAsJsonObject().getAsJsonObject("payload")
                    .get("strategyId").getAsString();
            if (!strategyIds.contains(strategyId)) strategyIds.add(strategyId);
        }
        return strategyIds;
    }

    /**
     * @param usage token 用量。检索阶段失败、知识库为空、生成请求失败这三种情况都没有真正
     *              调用模型，传 {@code null}。
     */
    private void logCall(UUID userId, UUID conversationId, String queryText, int gateFilteredOut,
                         int knowledgeDropped, JsonObject output, long start, String status, String errorMessage,
                         TokenUsage usage) {
        JsonObject input = new JsonObject();
        input.addProperty("queryText", queryText);
        // 把闸门挡下的数量记进日志：推荐突然变空时，这是区分“没召回”与“被闸门挡下”的唯一证据。
        input.addProperty("gateFilteredOut", gateFilteredOut);
        // 同理记下补齐时被预算截断的数量：推荐质量下降时得能区分“闸门挡的”“截断丢的”与
        // “知识库本来就薄”。这正是原先 limit=100 静默截断最缺的东西：那时三者在日志里长得一模一样。
        input.addProperty("knowledgeDropped", knowledgeDropped);
        callLogger.log(userId, conversationId, "recommend", RecommendationChatClient.MODEL,
                input, output, System.currentTimeMillis() - start, status, errorMessage, usage);
    }

    private static JsonObject outputOf(RecommendationResult result) {
        JsonObject output = new JsonObject();
        output.addProperty("status", result.status());
        JsonArray strategies = new JsonArray();
        result.recommendations().forEach(recommendation -> strategies.add(recommendation.strategyId()));
        output.add("strategyIds", strategies);
        return output;
    }

    private void ensureCollection() throws IOException {
        if (collectionChecked.compareAndSet(false, true)) {
            qdrantClient.ensureCollection(EmbeddingClient.DIMENSION);
        }
    }

    /** 把召回命中策略的全部 chunk 拉回，避免模型只看到定义片段而缺少实施步骤。 */
    private QdrantClient.Expansion expandByStrategy(JsonArray hits) throws IOException {
        List<String> strategyIds = strategyIdsOf(hits);
        return strategyIds.isEmpty()
                ? new QdrantClient.Expansion(new JsonArray(), 0)
                : qdrantClient.findByStrategyIds(strategyIds);
    }

    /** 合并补齐的 chunk：已召回的保留相似度分数，补齐的不重复、不带分数。 */
    static JsonArray mergeKnowledge(JsonArray knowledge, JsonArray expandedPoints) {
        Set<String> existing = candidateChunkIds(knowledge);
        for (JsonElement element : expandedPoints) {
            JsonObject point = element.getAsJsonObject();
            String chunkId = point.get("id").getAsString();
            if (existing.add(chunkId)) {
                knowledge.add(toKnowledgeItem(point, false));
            }
        }
        return knowledge;
    }

    /**
     * 知识负载预算：逐策略整组累计字符，超预算的策略整组丢弃。粒度必须是策略而不是 chunk——
     * 定义与步骤分离的"半截资料"比没有资料更坏，且整组裁剪保证被裁事实可数（记入 knowledgeDropped）。
     *
     * <p>第一个策略组无条件保留：宁可超预算，也不能让模型在空上下文里编造。
     */
    static BudgetedKnowledge trimToBudget(JsonArray knowledge, int budgetChars) {
        Map<String, List<JsonObject>> groups = new LinkedHashMap<>();
        for (JsonElement element : knowledge) {
            JsonObject item = element.getAsJsonObject();
            groups.computeIfAbsent(item.get("strategyId").getAsString(), key -> new ArrayList<>()).add(item);
        }
        Set<String> droppedIds = new HashSet<>();
        int retainedChars = 0;
        boolean first = true;
        for (List<JsonObject> group : groups.values()) {
            int groupChars = 0;
            for (JsonObject item : group) {
                groupChars += item.get("content").getAsString().length();
            }
            if (first || retainedChars + groupChars <= budgetChars) {
                retainedChars += groupChars;
            } else {
                for (JsonObject item : group) {
                    droppedIds.add(item.get("chunkId").getAsString());
                }
            }
            first = false;
        }
        if (droppedIds.isEmpty()) {
            return new BudgetedKnowledge(knowledge, 0);
        }
        JsonArray kept = new JsonArray();
        for (JsonElement element : knowledge) {
            if (!droppedIds.contains(element.getAsJsonObject().get("chunkId").getAsString())) {
                kept.add(element);
            }
        }
        return new BudgetedKnowledge(kept, droppedIds.size());
    }

    private static JsonArray buildKnowledge(JsonArray hits) {
        JsonArray knowledge = new JsonArray();
        for (JsonElement element : hits) {
            knowledge.add(toKnowledgeItem(element.getAsJsonObject(), true));
        }
        return knowledge;
    }

    private static JsonObject toKnowledgeItem(JsonObject hit, boolean includeScore) {
        JsonObject payload = hit.getAsJsonObject("payload");
        JsonObject item = new JsonObject();
        item.addProperty("chunkId", hit.get("id").getAsString());
        if (includeScore) {
            item.addProperty("retrievalScore", hit.get("score").getAsDouble());
        }
        item.addProperty("strategyId", payload.get("strategyId").getAsString());
        item.addProperty("strategyName", payload.get("strategyName").getAsString());
        item.addProperty("chunkType", payload.get("chunkType").getAsString());
        item.addProperty("content", sanitize(payload.get("text").getAsString()));
        item.add("sourceIds", payload.get("sourceIds").deepCopy());
        // evidenceUrl 只有证据 chunk 才有。URL 走 payload 而不是解析 content：
        // content 会被 sanitize 截断，payload 始终完整。
        JsonElement evidenceUrl = payload.get("evidenceUrl");
        if (evidenceUrl != null && !evidenceUrl.isJsonNull()) {
            item.addProperty("evidenceUrl", evidenceUrl.getAsString());
        }
        return item;
    }

    private static String sanitize(String text) {
        String cleaned = text.replace("```", "''' ").replace("---", "___");
        return cleaned.length() <= 500 ? cleaned : cleaned.substring(0, 500);
    }

    private static Set<String> candidateChunkIds(JsonArray knowledge) {
        Set<String> ids = new HashSet<>();
        for (JsonElement element : knowledge) {
            ids.add(element.getAsJsonObject().get("chunkId").getAsString());
        }
        return ids;
    }

    /**
     * 从知识集里提取被推荐策略的研究证据，供前端随卡片展示出处。
     *
     * <p>evidence chunk 的文本由生成器写死为「{策略名}的研究证据：{结论}（出处：{引用} {URL}）」，
     * 这里按该格式拆出结论与文献引用；URL 一律取 payload 的 evidenceUrl，文本解析只作兜底。
     */
    static List<EvidenceSource> evidenceSources(
            JsonArray knowledge, List<RecommendationValidator.Recommendation> recommendations) {
        if (recommendations.isEmpty()) return List.of();
        Set<String> recommended = new HashSet<>();
        for (RecommendationValidator.Recommendation recommendation : recommendations) {
            recommended.add(recommendation.strategyId());
        }
        List<EvidenceSource> sources = new ArrayList<>();
        for (JsonElement element : knowledge) {
            JsonObject item = element.getAsJsonObject();
            if (!"evidence".equals(item.get("chunkType").getAsString())) continue;
            String strategyId = item.get("strategyId").getAsString();
            if (!recommended.contains(strategyId)) continue;
            EvidenceSource source = parseEvidence(item);
            if (source != null) sources.add(source);
        }
        return List.copyOf(sources);
    }

    /** 解析 evidence chunk；文本不合固定格式时返回 null，宁可不展示也不展示半截证据。 */
    private static EvidenceSource parseEvidence(JsonObject item) {
        String text = item.get("content").getAsString();
        String prefix = item.get("strategyName").getAsString() + "的研究证据：";
        int start = text.indexOf(prefix);
        if (start < 0) return null;
        String body = text.substring(start + prefix.length());
        String url = item.has("evidenceUrl") ? item.get("evidenceUrl").getAsString() : "";
        String claim;
        String citation = "";
        int sourceMark = body.lastIndexOf("（出处：");
        if (sourceMark < 0) {
            claim = body.trim();
        } else {
            claim = body.substring(0, sourceMark).trim();
            String tail = body.substring(sourceMark + "（出处：".length());
            if (tail.endsWith("）")) tail = tail.substring(0, tail.length() - 1);
            citation = tail.trim();
            if (url.isEmpty()) {
                int space = citation.lastIndexOf(' ');
                if (space > 0 && citation.substring(space + 1).startsWith("http")) {
                    url = citation.substring(space + 1);
                    citation = citation.substring(0, space).trim();
                }
            } else if (citation.endsWith(url)) {
                citation = citation.substring(0, citation.length() - url.length()).trim();
            }
        }
        if (claim.isEmpty()) return null;
        return new EvidenceSource(item.get("strategyId").getAsString(), claim, citation, url);
    }

    public record RecommendationResult(
            String status,
            String answer,
            String queryText,
            List<String> userConstraints,
            List<RecommendationValidator.Recommendation> recommendations,
            List<String> followUpQuestions,
            List<EvidenceSource> evidenceSources
    ) {
    }

    /** 预算裁剪结果：保留原顺序的知识集与被整组裁掉的 chunk 数。 */
    public record BudgetedKnowledge(JsonArray knowledge, int droppedChunks) {
    }

    /** 一条研究证据：结论 + 文献引用 + 可点开的原文链接。 */
    public record EvidenceSource(
            String strategyId,
            String claim,
            String citation,
            String url
    ) {
    }

    public static final class RecommendationUnavailableException extends RuntimeException {
        public RecommendationUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
