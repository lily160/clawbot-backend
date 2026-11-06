package com.github.wechat.cloudbot.modules.health.triage.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.health.triage.infra.DepartmentCatalogLoader;
import com.github.wechat.cloudbot.modules.health.triage.infra.DepartmentEntry;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;

/**
 * 导诊 RAG 语义检索服务：基于 DashScope text-embedding-v3 向量嵌入 + Qdrant 向量数据库。
 *
 * <p>架构设计：
 * <ul>
 *   <li>启动时将 departments.json 中每个科室的关键信息拼接成文本，调用 DashScope Embedding API 生成向量</li>
 *   <li>向量存入 Qdrant 向量数据库（gRPC 端口 6334），payload 携带科室名称、常见检查、追问问题</li>
 *   <li>用户描述症状时，将症状嵌入向量，在 Qdrant 中做余弦相似度搜索，返回 Top-K 科室</li>
 * </ul>
 *
 * <p>与 {@link com.github.wechat.cloudbot.modules.health.triage.service.TriageService} 的关系：
 * <ul>
 *   <li>关键词匹配是主引擎：精确、快速、关键词命中即得分</li>
 *   <li>RAG 是语义增强层：当关键词匹配得分不足时，用语义相似度补充</li>
 *   <li>例如用户说"心脏跳得很快"，关键词匹配可能只命中"心跳过快"（模糊），
 *       但 RAG 能理解"心脏跳得很快"≈"心悸心慌"，命中心血管内科</li>
 * </ul>
 *
 * <p>降级策略：Qdrant 连接失败或 API Key 未配置时，{@link #isReady()} 返回 false，
 * TriageService 自动回退到纯关键词匹配，不影响现有功能。</p>
 */
@Service
public class TriageRagService {

    private static final Logger log = LoggerFactory.getLogger(TriageRagService.class);

    /** DashScope OpenAI 兼容模式的 Embedding 端点 */
    private static final String EMBEDDING_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    /** 默认嵌入模型：DashScope text-embedding-v3，1024 维 */
    private static final String DEFAULT_EMBEDDING_MODEL = "text-embedding-v3";

    /** 默认向量维度：text-embedding-v3 输出 1024 维 */
    private static final int DEFAULT_VECTOR_SIZE = 1024;

    /** 语义匹配的相似度阈值：高于此值才认为是有效匹配 */
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.45;

    private final DepartmentCatalogLoader catalogLoader;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String embeddingModel;
    private final int vectorSize;
    private final double similarityThreshold;
    private final boolean ragEnabled;
    private final String qdrantHost;
    private final int qdrantPort;
    private final String qdrantCollection;
    private final String qdrantApiKey;

    /** Qdrant 客户端（gRPC） */
    private QdrantClient qdrantClient;

    /** 科室 ID 映射：Qdrant point ID → 科室名称，用于从搜索结果还原科室信息 */
    private final Map<String, DepartmentEntry> pointIdToEntry = new ConcurrentHashMap<>();

    /** RAG 是否就绪 */
    private volatile boolean ready = false;

    public TriageRagService(
            DepartmentCatalogLoader catalogLoader,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${qwen.api-key:${DASHSCOPE_API_KEY:}}") String apiKey,
            @Value("${triage.rag.embedding-model:" + DEFAULT_EMBEDDING_MODEL + "}") String embeddingModel,
            @Value("${triage.rag.vector-size:" + DEFAULT_VECTOR_SIZE + "}") int vectorSize,
            @Value("${triage.rag.similarity-threshold:" + DEFAULT_SIMILARITY_THRESHOLD + "}") double similarityThreshold,
            @Value("${triage.rag.enabled:true}") boolean ragEnabled,
            @Value("${triage.rag.qdrant-host:localhost}") String qdrantHost,
            @Value("${triage.rag.qdrant-port:6334}") int qdrantPort,
            @Value("${triage.rag.qdrant-collection:triage_departments}") String qdrantCollection,
            @Value("${triage.rag.qdrant-api-key:}") String qdrantApiKey
    ) {
        this.catalogLoader = catalogLoader;
        this.restClient = restClientBuilder.baseUrl(EMBEDDING_BASE_URL).build();
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.embeddingModel = embeddingModel;
        this.vectorSize = vectorSize;
        this.similarityThreshold = similarityThreshold;
        this.ragEnabled = ragEnabled;
        this.qdrantHost = qdrantHost;
        this.qdrantPort = qdrantPort;
        this.qdrantCollection = qdrantCollection;
        this.qdrantApiKey = qdrantApiKey;
    }

    /**
     * 启动时初始化 Qdrant 连接并构建科室向量索引。
     *
     * <p>步骤：
     * <ol>
     *   <li>连接 Qdrant（gRPC 端口 6334）</li>
     *   <li>如果 Collection 不存在则创建（Cosine 距离 + 1024 维）</li>
     *   <li>为每个科室生成嵌入向量并 upsert 到 Qdrant，payload 携带科室元数据</li>
     * </ol>
     */
    @PostConstruct
    public void init() {
        if (!ragEnabled) {
            log.info("ℹ️ RAG语义检索已禁用（triage.rag.enabled=false），导诊仅使用关键词匹配");
            return;
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("⚠️ RAG未启用：DASHSCOPE_API_KEY 未配置，导诊将仅使用关键词匹配");
            return;
        }

        try {
            // 1. 连接 Qdrant
            QdrantGrpcClient.Builder grpcBuilder = QdrantGrpcClient.newBuilder(qdrantHost, qdrantPort, false);
            if (qdrantApiKey != null && !qdrantApiKey.isBlank()) {
                grpcBuilder.withApiKey(qdrantApiKey);
            }
            this.qdrantClient = new QdrantClient(grpcBuilder.build());
            log.info("🔗 Qdrant 连接成功: {}:{}", qdrantHost, qdrantPort);

            // 2. 构建科室向量索引
            buildDepartmentVectors();

            this.ready = true;
            log.info("✅ RAG服务就绪：collection={}, 向量维度={}, 相似度阈值={}",
                    qdrantCollection, vectorSize, similarityThreshold);
        } catch (Exception e) {
            log.error("❌ RAG初始化失败，将降级为纯关键词匹配: {}", e.getMessage(), e);
            this.ready = false;
        }
    }

    /**
     * 应用关闭时释放 Qdrant 连接。
     */
    @PreDestroy
    public void destroy() {
        if (qdrantClient != null) {
            try {
                qdrantClient.close();
                log.info("🔒 Qdrant 连接已关闭");
            } catch (Exception e) {
                log.warn("关闭 Qdrant 连接时出错: {}", e.getMessage());
            }
        }
    }

    /**
     * 构建科室向量索引：为每个科室生成嵌入向量并存入 Qdrant。
     */
    private void buildDepartmentVectors() throws Exception {
        List<DepartmentEntry> catalog = catalogLoader.getCatalog();
        if (catalog.isEmpty()) {
            log.warn("⚠️ RAG初始化：分科目录为空，跳过向量构建");
            return;
        }

        // 确保 Collection 存在（不存在则创建）
        ensureCollectionExists();

        // 为每个科室构建待嵌入的文本
        List<String> texts = new ArrayList<>(catalog.size());
        for (DepartmentEntry entry : catalog) {
            texts.add(buildEmbeddingText(entry));
        }

        // 批量嵌入
        List<double[]> vectors = embed(texts);
        if (vectors.size() != catalog.size()) {
            throw new IllegalStateException("嵌入数量不匹配: 期望=" + catalog.size() + ", 实际=" + vectors.size());
        }

        // 构建 Qdrant Point 并 upsert
        List<io.qdrant.client.grpc.Points.PointStruct> points = new ArrayList<>(catalog.size());
        pointIdToEntry.clear();
        for (int i = 0; i < catalog.size(); i++) {
            DepartmentEntry entry = catalog.get(i);
            String pointId = String.valueOf(i + 1);
            pointIdToEntry.put(pointId, entry);

            // 将 double[] 转为 List<Float>
            List<Float> floatVector = toFloatList(vectors.get(i));

            io.qdrant.client.grpc.Points.PointStruct point =
                    io.qdrant.client.grpc.Points.PointStruct.newBuilder()
                            .setId(id(Long.parseLong(pointId)))
                            .setVectors(vectors(floatVector))
                            .putAllPayload(Map.of(
                                    "department", value(entry.department()),
                                    "point_index", value(pointId)
                            ))
                            .build();
            points.add(point);
        }

        qdrantClient.upsertAsync(qdrantCollection, points).get();
        log.info("✅ RAG向量索引构建完成：{} 个科室已写入 Qdrant collection={}",
                catalog.size(), qdrantCollection);
    }

    /**
     * 确保 Qdrant Collection 存在，不存在则创建。
     */
    private void ensureCollectionExists() throws Exception {
        // 尝试获取 Collection 信息，如果异常说明不存在
        try {
            qdrantClient.getCollectionInfoAsync(qdrantCollection).get();
            log.info("📋 Qdrant collection '{}' 已存在", qdrantCollection);
            return;
        } catch (Exception e) {
            // Collection 不存在，继续创建
            log.info("📋 Qdrant collection '{}' 不存在，开始创建...", qdrantCollection);
        }

        qdrantClient.createCollectionAsync(
                qdrantCollection,
                VectorParams.newBuilder()
                        .setDistance(Distance.Cosine)
                        .setSize(vectorSize)
                        .build()
        ).get();
        log.info("✅ Qdrant collection '{}' 创建成功（维度={}, 距离=Cosine）", qdrantCollection, vectorSize);
    }

    /**
     * 将科室信息拼接成适合嵌入的文本。
     *
     * @param entry 科室条目
     * @return 拼接后的文本，如"科室：心血管内科（心内科、心脏科）。常见症状：胸闷、胸痛、心悸..."
     */
    private String buildEmbeddingText(DepartmentEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("科室：").append(entry.department());
        if (!entry.aliases().isEmpty()) {
            sb.append("（").append(String.join("、", entry.aliases())).append("）");
        }
        sb.append("。常见症状：").append(String.join("、", entry.symptomKeywords()));
        if (!entry.highWeightKeywords().isEmpty()) {
            sb.append("。典型症状：").append(String.join("、", entry.highWeightKeywords()));
        }
        return sb.toString();
    }

    /** DashScope text-embedding-v3 单次批量上限 10 条 */
    private static final int EMBED_BATCH_SIZE = 10;

    /**
     * 调用 DashScope Embedding API 批量嵌入文本。
     *
     * <p>DashScope text-embedding-v3 限制单次请求最多 10 条文本，
     * 超过时自动分批调用，结果按顺序拼接返回。</p>
     *
     * @param inputs 待嵌入的文本列表
     * @return 向量列表，与输入一一对应
     */
    private List<double[]> embed(List<String> inputs) throws Exception {
        List<double[]> allVectors = new ArrayList<>(inputs.size());

        // 分批调用，每批最多 EMBED_BATCH_SIZE 条
        for (int start = 0; start < inputs.size(); start += EMBED_BATCH_SIZE) {
            int end = Math.min(start + EMBED_BATCH_SIZE, inputs.size());
            List<String> batch = inputs.subList(start, end);

            Map<String, Object> body = new java.util.HashMap<>();
            body.put("model", embeddingModel);
            body.put("input", batch);
            body.put("encoding_format", "float");

            JsonNode root = restClient.post()
                    .uri("/embeddings")
                    .header("Authorization", "Bearer " + apiKey)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            if (root == null || !root.path("data").isArray()) {
                throw new IllegalStateException("Embedding API 返回数据格式异常: " + root);
            }

            for (JsonNode row : root.path("data")) {
                JsonNode values = row.path("embedding");
                double[] vector = new double[values.size()];
                for (int i = 0; i < values.size(); i++) {
                    vector[i] = values.get(i).asDouble();
                }
                allVectors.add(vector);
            }

            if (log.isDebugEnabled()) {
                log.debug("Embedding 批次完成: {}/{}", end, inputs.size());
            }
        }

        if (allVectors.size() != inputs.size()) {
            throw new IllegalStateException("嵌入结果数量不匹配: 期望=" + inputs.size() + ", 实际=" + allVectors.size());
        }

        return allVectors;
    }

    /**
     * 语义检索：将用户症状描述嵌入向量，在 Qdrant 中搜索最相似的科室。
     *
     * <p>这是 RAG 的核心检索步骤。当用户用口语化描述症状（如"心脏跳得很快"）
     * 而关键词匹配未能命中时，本方法能通过语义理解找到正确科室。</p>
     *
     * @param symptoms 用户症状描述（已归一化）
     * @param topK     返回的最大科室数
     * @return 按相似度降序排列的匹配列表，可能为空（RAG 未就绪或检索失败时）
     */
    public List<RagMatch> semanticSearch(String symptoms, int topK) {
        if (!isReady()) {
            return List.of();
        }
        if (symptoms == null || symptoms.isBlank()) {
            return List.of();
        }
        try {
            // 嵌入用户症状
            List<double[]> queryVectors = embed(List.of(symptoms));
            List<Float> queryVector = toFloatList(queryVectors.get(0));

            // 在 Qdrant 中搜索最相似的科室向量
            List<ScoredPoint> scoredPoints = qdrantClient.searchAsync(
                    SearchPoints.newBuilder()
                            .setCollectionName(qdrantCollection)
                            .addAllVector(queryVector)
                            .setLimit(topK)
                            .setWithPayload(io.qdrant.client.grpc.Points.WithPayloadSelector.newBuilder()
                                    .setEnable(true)
                                    .build())
                            .build()
            ).get();

            if (scoredPoints == null || scoredPoints.isEmpty()) {
                return List.of();
            }

            // 将 Qdrant 搜索结果转换为 RagMatch
            List<RagMatch> matches = new ArrayList<>(scoredPoints.size());
            for (ScoredPoint sp : scoredPoints) {
                // 从 payload 中提取科室名和索引（payload 值是 protobuf Value 类型）
                String deptName = "";
                String pointIndex = "";
                Map<String, ? extends com.google.protobuf.Message> payload = sp.getPayloadMap();
                if (payload.containsKey("department")) {
                    deptName = payload.get("department").toString().replaceAll("string_value:\\s*\"|\"\\s*", "");
                }
                if (payload.containsKey("point_index")) {
                    pointIndex = payload.get("point_index").toString().replaceAll("string_value:\\s*\"|\"\\s*", "");
                }

                DepartmentEntry entry = pointIdToEntry.get(pointIndex);
                if (entry != null) {
                    matches.add(new RagMatch(
                            entry.department(),
                            sp.getScore(),
                            entry.commonExams(),
                            entry.followUpQuestions()
                    ));
                } else {
                    // 兜底：用科室名匹配（理论上不会走到这里）
                    matches.add(new RagMatch(deptName, sp.getScore(), List.of(), List.of()));
                }
            }

            if (log.isDebugEnabled()) {
                log.debug("RAG语义检索(Qdrant): symptoms='{}', topMatch={} (sim={}), results={}",
                        symptoms,
                        matches.isEmpty() ? "无" : matches.get(0).department(),
                        matches.isEmpty() ? 0 : String.format("%.3f", matches.get(0).similarity()),
                        matches.size());
            }

            return matches;
        } catch (Exception e) {
            log.error("RAG语义检索失败，降级为关键词匹配: symptoms={}, error={}", symptoms, e.getMessage());
            return List.of();
        }
    }

    /**
     * 检查 RAG 服务是否就绪可用。
     *
     * @return true 表示 Qdrant 连接正常且向量索引已构建
     */
    public boolean isReady() {
        return ready;
    }

    /**
     * 获取相似度阈值。
     *
     * @return 相似度阈值，高于此值才认为是有效匹配
     */
    public double getSimilarityThreshold() {
        return similarityThreshold;
    }

    /**
     * 将 double[] 转为 List<Float>（Qdrant 客户端需要 Float 列表）。
     */
    private static List<Float> toFloatList(double[] vector) {
        List<Float> result = new ArrayList<>(vector.length);
        for (double v : vector) {
            result.add((float) v);
        }
        return result;
    }

    // ════════════════════════════════════════════════════════════════
    //  内部数据结构
    // ════════════════════════════════════════════════════════════════

    /** RAG 匹配结果：科室名 + 相似度 + 常见检查 + 追问问题 */
    public record RagMatch(
            String department,
            double similarity,
            List<String> commonExams,
            List<String> followUpQuestions
    ) {}
}
