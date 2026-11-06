package com.github.wechat.cloudbot.modules.health.triage.service;

import com.github.wechat.cloudbot.modules.health.common.HealthDisclaimers;
import com.github.wechat.cloudbot.modules.health.common.TriageResult;
import com.github.wechat.cloudbot.modules.health.triage.infra.DepartmentCatalogLoader;
import com.github.wechat.cloudbot.modules.health.triage.infra.DepartmentEntry;
import com.github.wechat.cloudbot.modules.health.triage.rag.TriageRagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 症状导诊服务：基于关键词匹配 + RAG 语义增强，输出建议科室 + 常见检查名称 + 免责声明。
 *
 * <p>双层匹配架构：
 * <ol>
 *   <li>关键词匹配（主引擎）：精确命中 symptomKeywords/highWeightKeywords，IDF加权排序</li>
 *   <li>RAG 语义增强（补充层）：关键词得分不足时，用 DashScope 向量嵌入做语义相似度匹配</li>
 * </ol>
 *
 * <p><b>═══ 直接出结果的三级标准（满足任一即可）═══</b></p>
 * <ol>
 *   <li><b>关键词得分标准</b>（需同时满足2个条件）：
 *     <ul>
 *       <li>加权得分 ≥ {@value #MIN_SCORE_FOR_CONFIDENCE}（至少3个关键词命中）</li>
 *       <li>信息维度 ≥ {@value #MIN_DIMENSIONS_FOR_RAG_SKIP}（至少3个维度，防止"肚子疼"这种单症状直接出结果）</li>
 *     </ul>
 *   </li>
 *   <li><b>RAG语义标准</b>（需同时满足4个条件）：
 *     <ul>
 *       <li>RAG相似度 ≥ {@value #RAG_SKIP_FOLLOWUP_SIMILARITY}（语义高度匹配）</li>
 *       <li>描述长度 ≥ {@value #RAG_MIN_LENGTH_FOR_SKIP_FOLLOWUP} 字符（描述足够长）</li>
 *       <li>信息维度 ≥ {@value #MIN_DIMENSIONS_FOR_RAG_SKIP}（至少3个维度：主症状+伴随症状/部位/时间/性质/诱因中的2个）</li>
 *       <li>RAG服务就绪</li>
 *     </ul>
 *   </li>
 *   <li><b>兜底标准</b>：追问次数 ≥ {@value #MAX_FOLLOW_UP_ROUNDS} 或描述长度 > {@value #MAX_SYMPTOM_LENGTH_FOR_FOLLOWUP} 字符</li>
 * </ol>
 *
 * <p><b>═══ 必须追问的场景 ═══</b></p>
 * <ul>
 *   <li>"胃痛" — 1维度（只有主症状），得分不足 → 追问</li>
 *   <li>"持续性胃痛" — 2维度（主症状+性质），得分不足 → 追问</li>
 *   <li>"妈妈肚子疼 持续性的" — 2维度（主症状+性质），得分可能达标但维度不足 → 追问（维度门控）</li>
 *   <li>"持续性胃痛有点恶心" — 3维度但得分不足且RAG相似度可能不够 → 追问</li>
 *   <li>"心脏跳得很快" — RAG可能匹配但长度不足({@value #RAG_MIN_LENGTH_FOR_SKIP_FOLLOWUP}) → 追问</li>
 * </ul>
 *
 * <p>支持多轮追问：当症状描述不够具体时，返回追问问题供大模型向用户提问，
 * 用户补充信息后再次调用本方法，逐步细化匹配。</p>
 * <p>追问次数兜底：最多追问 {@value #MAX_FOLLOW_UP_ROUNDS} 次，超过后强制给出最佳匹配，
 * 防止大模型与用户陷入无限追问循环。</p>
 * <p>合规约束：只做科室指引，不输出诊断结论；所有结果均附带免责声明。</p>
 */
@Service
public class TriageService {

    private static final Logger log = LoggerFactory.getLogger(TriageService.class);

    /** 取建议科室的最大数量 */
    private static final int TOP_N_DEPARTMENTS = 3;

    /** 需要追问的加权得分阈值：低于此值认为症状不够具体。
     *  设为4.0而非3.0——避免"孩子胃疼"这种只命中2个词（孩子+2, 胃痛+1=3分）
     *  就直接出结论，确保至少命中3个关键词或1个高权重+2个普通词才不追问。
     *  注意：得分达标只是出结果的必要条件，不是充分条件——
     *  还需要信息维度 ≥ MIN_DIMENSIONS_FOR_RAG_SKIP 才能直接出结果。
     *  双门控机制：得分 + 维度，两个都达标才不追问。 */
    private static final double MIN_SCORE_FOR_CONFIDENCE = 4.0;

    /**
     * 症状描述最小长度（归一化后），低于此值视为过于简短。
     * 仅作为日志参考，实际追问决策以加权得分为准——
     * 得分不足时无论长度如何都会追问，得分足够时短描述也可直接出结论。
     */
    private static final int MIN_SYMPTOM_LENGTH = 3;

    /** 累积症状描述超过此长度时不再追问（避免无限循环），视为已充分沟通。
     *  设为120——症状累积后通常会比较长，50太低会导致追问1次就触发兜底。 */
    private static final int MAX_SYMPTOM_LENGTH_FOR_FOLLOWUP = 120;

    /** 最多追问次数：超过此值后强制停止追问，给出当前最佳匹配结果。
     *  注意：此兜底依赖大模型正确传入 followUpRound，若大模型未传则每次从 0 开始，
     *  实际由 MAX_SYMPTOM_LENGTH_FOR_FOLLOWUP 兜底。 */
    public static final int MAX_FOLLOW_UP_ROUNDS = 3;

    /** RAG 跳过追问的最低症状描述长度（归一化后）。
     *  短于此值时即使 RAG 匹配度高也继续追问收集细节。
     *  RAG 价值在于处理口语化长描述（如"心脏跳得很快"=7字），
     *  短描述（如"胃痛"=2字、"持续性胃痛"=5字）信息量不足，应先追问。
     *  设为8——"持续性胃痛"(5字)不够，"心脏跳得很快"(7字)也不够，
     *  需要更完整的描述如"心脏跳得很快有三四天了"(11字)才可跳过追问。 */
    private static final int RAG_MIN_LENGTH_FOR_SKIP_FOLLOWUP = 8;

    /** RAG 跳过追问的相似度阈值：高于此值才认为匹配足够精准到可以跳过追问。
     *  与 ragService.getSimilarityThreshold()（0.45，用于判断"是否匹配到"）不同，
     *  本阈值用于判断"匹配得是否足够好到可以跳过追问"。
     *  0.70：高于"可能相关"(0.45)且接近"高度匹配"(0.8)，
     *  确保只有语义高度对应时才跳过追问，避免"随便一说就出结果"。
     *  从0.65提升至0.70——0.65时"胃痛"也能匹配到消化内科(0.628)，
     *  但单症状+修饰词不应直接出结果，提高阈值确保只有真正高度匹配才跳过。 */
    private static final double RAG_SKIP_FOLLOWUP_SIMILARITY = 0.70;

    /** RAG 跳过追问的最低信息维度数。
     *  信息维度：主症状、伴随症状、部位、性质(持续/阵发/胀痛/刺痛等)、
     *  时间(三天/一周等)、诱因(饭后/运动后等)。
     *  设为3——"持续性胃痛"只有2个维度(主症状+性质)，不应直接出结果；
     *  "持续性胃痛三天了有点恶心"有4个维度(主症状+性质+时间+伴随)才可以。
     *  这是RAG跳过追问的额外门控：即使RAG相似度和长度达标，
     *  维度不足也必须追问，确保用户提供了足够的信息维度。 */
    private static final int MIN_DIMENSIONS_FOR_RAG_SKIP = 3;

    private final DepartmentCatalogLoader catalogLoader;
    private final TriageRagService ragService;

    /** ===== 排序优化：IDF 加权相关 =====
     *  关键词区分度（IDF）缓存：记录每个关键词出现在多少个科室中。
     *  出现在越少科室的关键词越有区分度，匹配时应给予更高权重。
     *  例如"心绞痛"只出现在心血管内科 → idf 高；"发烧"出现在5+科室 → idf 低。
     */
    private Map<String, Integer> keywordDeptFrequency;
    private int totalDeptCount = 0;

    /** 唯一关键词加分：只出现在1-2个科室的关键词命中时额外加分 */
    private static final double UNIQUE_KEYWORD_BONUS = 0.5;
    /** 低频关键词加分：出现在3个科室的关键词命中时额外加分 */
    private static final double RARE_KEYWORD_BONUS = 0.3;
    /** 全科降权：当专科也命中时，全科得分降低，确保专科优先 */
    private static final double GENERAL_DEPT_PENALTY = 0.5;

    public TriageService(DepartmentCatalogLoader catalogLoader, TriageRagService ragService) {
        this.catalogLoader = catalogLoader;
        this.ragService = ragService;
    }

    /**
     * 根据用户描述的症状，匹配分科目录并输出导诊建议（首轮调用，followUpRound=0）。
     *
     * <p>等效于 {@link #triage(String, int) triage(symptoms, 0)}。</p>
     *
     * @param symptoms 用户输入的症状描述，如"咳嗽三天低烧"
     * @return 导诊会话结果（包含 TriageResult + 追问状态），绝不包含诊断结论
     */
    public TriageSessionResult triage(String symptoms) {
        return triage(symptoms, 0);
    }

    /**
     * 根据用户描述的症状，匹配分科目录并输出导诊建议（带追问轮次）。
     *
     * <p>多轮追问机制：当匹配度不足时返回 needsMoreInfo=true 及追问问题，
     * 大模型向用户提问后带着更丰富的信息再次调用本方法。</p>
     *
     * <p>追问次数兜底：当 {@code followUpRound >= } {@value #MAX_FOLLOW_UP_ROUNDS} 时，
     * 即使得分不足也不再追问，直接返回当前最佳匹配结果。
     * 当大模型未传入 followUpRound 时，由症状长度兜底（{@value #MAX_SYMPTOM_LENGTH_FOR_FOLLOWUP} 字符）补偿。</p>
     *
     * @param symptoms      用户输入的症状描述，如"咳嗽三天低烧"
     * @param followUpRound 当前已追问轮次（0=首次调用，1=已追问1次…），由工具层传入
     * @return 导诊会话结果（包含 TriageResult + 追问状态 + 当前轮次），绝不包含诊断结论
     */
    public TriageSessionResult triage(String symptoms, int followUpRound) {
        return triage(symptoms, followUpRound, List.of());
    }

    /** 兼容编排层传入的已追问问题列表，确保多轮导诊不重复提问。 */
    public TriageSessionResult triage(String symptoms, int followUpRound, List<String> previouslyAsked) {
        List<DepartmentEntry> catalog = catalogLoader.getCatalog();

        if (catalog.isEmpty()) {
            log.warn("分科目录为空，无法进行导诊");
            return new TriageSessionResult(
                    new TriageResult(List.of(), List.of(), HealthDisclaimers.TRIAGE_DISCLAIMER),
                    false,
                    List.of(),
                    followUpRound
            );
        }

        // 安全处理输入
        String normalizedSymptoms = symptoms == null ? "" : symptoms.trim();
        if (normalizedSymptoms.isEmpty()) {
            log.warn("用户输入症状为空");
            return new TriageSessionResult(
                    new TriageResult(List.of(), List.of(), HealthDisclaimers.TRIAGE_DISCLAIMER),
                    false,
                    List.of(),
                    followUpRound
            );
        }

        // 口语归一化：用户常说"疼"而非"痛"，"拉肚子"而非"腹泻"等
        normalizedSymptoms = normalizeSymptomText(normalizedSymptoms);

        // 1. 遍历科室，统计命中关键词数
        List<DepartmentMatch> matches = scoreDepartments(catalog, normalizedSymptoms);

        // 2. 过滤得分为 0 的科室，按加权得分降序 + 高权重命中数降序取 Top N
        //    排序优化：四级排序维度，确保最精准的科室排在最前面
        //    ① 加权得分（含IDF加分和全科降权）→ 主体排序
        //    ② 高权重命中数 → 同分时优先取特异性词命中多的
        //    ③ 唯一关键词命中数 → 同分时优先取独有关键词命中多的
        //    ④ 总关键词命中数 → 同分时优先取匹配关键词更多的
        //    注意：reversed() 只能放在链末尾，否则会反转整个比较器导致升序排序
        List<DepartmentMatch> topMatches = matches.stream()
                .filter(m -> m.score() > 0)
                .sorted(Comparator
                        .comparingDouble(DepartmentMatch::score)
                        .thenComparingInt(DepartmentMatch::highWeightHits)
                        .thenComparingInt(DepartmentMatch::uniqueKeywordHits)
                        .thenComparingInt(DepartmentMatch::keywordHits)
                        .reversed())
                .limit(TOP_N_DEPARTMENTS)
                .toList();

        // 全科仅作为兜底：有专科命中时不与专科同时出现在建议列表中
        if (topMatches.stream().anyMatch(m -> !isGeneralDepartment(m.department()))) {
            topMatches = topMatches.stream()
                    .filter(m -> !isGeneralDepartment(m.department()))
                    .toList();
        }

        // 追问次数兜底：已达上限则强制停止追问
        boolean maxRoundsReached = followUpRound >= MAX_FOLLOW_UP_ROUNDS;
        // 长度兜底：累积描述过长则停止追问
        boolean tooManyRounds = normalizedSymptoms.length() > MAX_SYMPTOM_LENGTH_FOR_FOLLOWUP;
        boolean stopFollowUp = maxRoundsReached || tooManyRounds;

        if (maxRoundsReached) {
            log.info("追问次数已达上限({}/{}), 强制停止追问: symptoms={}",
                    followUpRound, MAX_FOLLOW_UP_ROUNDS, normalizedSymptoms);
        } else if (tooManyRounds) {
            log.info("症状描述过长({}字符), 停止追问: symptoms={}",
                    normalizedSymptoms.length(), normalizedSymptoms);
        }

        // 3. 无命中时回退到全科
        if (topMatches.isEmpty()) {
            log.info("未命中任何科室关键词，回退到全科{}: symptoms={}",
                    stopFollowUp ? "（不再追问）" : "并追问", normalizedSymptoms);
            Optional<DepartmentEntry> general = catalog.stream()
                    .filter(d -> "全科".equals(d.department()) || d.aliases().contains("全科"))
                    .findFirst();
            if (general.isPresent()) {
                DepartmentEntry g = general.get();
                return new TriageSessionResult(
                        new TriageResult(
                                List.of(g.department()),
                                g.commonExams(),
                                HealthDisclaimers.TRIAGE_DISCLAIMER
                        ),
                        !stopFollowUp,
                        stopFollowUp ? List.of() : g.followUpQuestions(),
                        followUpRound
                );
            }
            return new TriageSessionResult(
                    new TriageResult(List.of(), List.of(), HealthDisclaimers.TRIAGE_DISCLAIMER),
                    false,
                    List.of(),
                    followUpRound
            );
        }

        // 4. 判断是否需要追问
        //    双门控机制：得分不足 OR 信息维度不足 都会触发追问
        //    ① 得分门控：加权得分 < MIN_SCORE_FOR_CONFIDENCE → 症状不够具体
        //    ② 维度门控：信息维度 < MIN_DIMENSIONS_FOR_RAG_SKIP → 描述信息量不足
        //    两个门控独立工作，满足任一条件且未达追问上限就追问。
        //    维度门控确保即使关键词命中很多（如"肚子疼"命中消化内科多个词），
        //    但用户只说了1-2个维度（主症状+性质），仍然追问收集更多细节。
        //    例如"妈妈肚子疼 持续性的"=2维度（主症状+性质）→ 必须追问。
        double topScore = topMatches.get(0).score();

        // 维度门控：即使得分达标，维度不足也必须追问（除非已达追问上限）
        int dimensions = countSymptomDimensions(normalizedSymptoms);
        if (topScore >= MIN_SCORE_FOR_CONFIDENCE
                && dimensions < MIN_DIMENSIONS_FOR_RAG_SKIP
                && !stopFollowUp) {
            log.info("关键词得分达标({}>={}), 但信息维度不足({}<{}), 继续追问: symptoms='{}'",
                    String.format("%.1f", topScore), MIN_SCORE_FOR_CONFIDENCE,
                    dimensions, MIN_DIMENSIONS_FOR_RAG_SKIP, normalizedSymptoms);
            // 直接走追问流程，用关键词匹配的科室作为初步建议
            List<String> followUpQuestions = filterAnsweredQuestions(
                    topMatches.get(0).followUpQuestions(), normalizedSymptoms);
            List<String> preliminaryDepts = topMatches.stream()
                    .map(DepartmentMatch::department)
                    .collect(Collectors.toList());
            List<String> preliminaryExams = topMatches.stream()
                    .flatMap(m -> m.commonExams().stream())
                    .distinct()
                    .collect(Collectors.toList());

            log.info("维度不足触发追问: symptoms={}, topScore={}, 初步匹配={}, 信息维度={}, 追问轮次={}",
                    normalizedSymptoms, topScore, preliminaryDepts, dimensions, followUpRound);

            return new TriageSessionResult(
                    new TriageResult(
                            preliminaryDepts,
                            preliminaryExams,
                            HealthDisclaimers.TRIAGE_DISCLAIMER
                    ),
                    true,
                    followUpQuestions,
                    followUpRound + 1
            );
        }

        if (topScore < MIN_SCORE_FOR_CONFIDENCE && !stopFollowUp) {
            // ===== RAG 语义增强：关键词匹配得分不足时，尝试语义检索 =====
            // 当用户用口语化描述症状（如"心脏跳得很快"）导致关键词未命中时，
            // 用向量相似度补充匹配，避免不必要的追问
            if (ragService.isReady()) {
                List<TriageRagService.RagMatch> ragMatches =
                        ragService.semanticSearch(normalizedSymptoms, TOP_N_DEPARTMENTS);
                // 过滤全科：全科仅作为兜底，不通过RAG推荐
                ragMatches = ragMatches.stream()
                        .filter(m -> !isGeneralDepartment(m.department()))
                        .collect(Collectors.toList());
                if (!ragMatches.isEmpty()) {
                    TriageRagService.RagMatch topRag = ragMatches.get(0);
                    // 跳过追问需要更高的置信度：RAG_SKIP_FOLLOWUP_SIMILARITY(0.70)
                    // 高于匹配阈值(0.45)，确保只有语义高度对应时才跳过追问
                    if (topRag.similarity() >= RAG_SKIP_FOLLOWUP_SIMILARITY) {
                        // 症状描述过短时，即使 RAG 匹配度高也不跳过追问
                        // RAG 价值在于处理口语化长描述（如"心脏跳得很快"），
                        // 短描述（如"胃痛"）信息量不足，应先追问收集细节
                        if (normalizedSymptoms.length() < RAG_MIN_LENGTH_FOR_SKIP_FOLLOWUP) {
                            log.info("RAG匹配达标但症状描述过短({}字符<{}), 继续追问: symptoms='{}', topMatch={} (sim={})",
                                    normalizedSymptoms.length(), RAG_MIN_LENGTH_FOR_SKIP_FOLLOWUP,
                                    normalizedSymptoms, topRag.department(),
                                    String.format("%.3f", topRag.similarity()));
                            // 不 return，继续走下面的追问流程
                        } else {
                            // 信息维度检查：即使长度和相似度达标，
                            // 如果用户只提供了1-2个信息维度（如"持续性胃痛"=主症状+性质），
                            // 仍然不够具体，必须追问收集更多信息
                            // 注意：dimensions 变量已在上方维度门控中声明，此处直接复用
                            if (dimensions < MIN_DIMENSIONS_FOR_RAG_SKIP) {
                                log.info("RAG匹配达标且长度足够({}字符>={}), 但信息维度不足({}<{}), 继续追问: symptoms='{}', topMatch={} (sim={})",
                                        normalizedSymptoms.length(), RAG_MIN_LENGTH_FOR_SKIP_FOLLOWUP,
                                        dimensions, MIN_DIMENSIONS_FOR_RAG_SKIP,
                                        normalizedSymptoms, topRag.department(),
                                        String.format("%.3f", topRag.similarity()));
                                // 不 return，继续走下面的追问流程
                            } else {
                            // RAG 找到高置信度匹配且描述足够具体，直接出结果，跳过追问
                            // 只保留达到匹配阈值的科室，过滤掉低相似度的"凑数"科室
                            double matchThreshold = ragService.getSimilarityThreshold();
                            List<TriageRagService.RagMatch> qualifiedMatches = ragMatches.stream()
                                    .filter(m -> m.similarity() >= matchThreshold)
                                    .collect(Collectors.toList());
                            List<String> ragDepts = qualifiedMatches.stream()
                                    .map(TriageRagService.RagMatch::department)
                                    .collect(Collectors.toList());
                            List<String> ragExams = qualifiedMatches.stream()
                                    .flatMap(m -> m.commonExams().stream())
                                    .distinct()
                                    .collect(Collectors.toList());

                            log.info("RAG语义匹配成功（跳过追问）: symptoms='{}', 关键词topScore={}, " +
                                            "RAG topMatch={} (sim={}), 信息维度={}, 达标科室={} (共{}个, 匹配阈值={}, 跳过阈值={}, 维度阈值={}, 长度阈值={})",
                                    normalizedSymptoms, topScore, topRag.department(),
                                    String.format("%.3f", topRag.similarity()), dimensions, ragDepts,
                                    ragDepts.size(), String.format("%.3f", matchThreshold),
                                    String.format("%.3f", RAG_SKIP_FOLLOWUP_SIMILARITY),
                                    MIN_DIMENSIONS_FOR_RAG_SKIP, RAG_MIN_LENGTH_FOR_SKIP_FOLLOWUP);

                            return new TriageSessionResult(
                                    new TriageResult(
                                            ragDepts,
                                            ragExams,
                                            HealthDisclaimers.TRIAGE_DISCLAIMER
                                    ),
                                    false,
                                    List.of(),
                                    followUpRound
                            );
                            }
                        }
                    }
                    // RAG 有匹配但相似度不够高，记录日志后继续走追问流程
                    log.info("RAG语义匹配相似度不足跳过阈值: symptoms='{}', topMatch={} (sim={}, 跳过阈值={}), 继续追问",
                            normalizedSymptoms, topRag.department(),
                            String.format("%.3f", topRag.similarity()),
                            String.format("%.3f", RAG_SKIP_FOLLOWUP_SIMILARITY));
                }
            }
            // ===== RAG 语义增强结束 =====

            // 匹配度不足，返回初步匹配结果 + 追问问题
            // 智能过滤：去掉用户已经回答过的追问问题
            List<String> followUpQuestions = filterAnsweredQuestions(
                    topMatches.get(0).followUpQuestions(), normalizedSymptoms);

            // 同时组装初步结果，让大模型有上下文
            List<String> preliminaryDepts = topMatches.stream()
                    .map(DepartmentMatch::department)
                    .collect(Collectors.toList());
            List<String> preliminaryExams = topMatches.stream()
                    .flatMap(m -> m.commonExams().stream())
                    .distinct()
                    .collect(Collectors.toList());

            if (normalizedSymptoms.length() < MIN_SYMPTOM_LENGTH) {
                log.info("症状描述较短且得分不足，触发追问: symptoms={}, topScore={}, 初步匹配={}, 追问轮次={}",
                        normalizedSymptoms, topScore, preliminaryDepts, followUpRound);
            } else {
                log.info("症状不够具体，触发追问: symptoms={}, topScore={}, 初步匹配={}, 追问问题数={}, 追问轮次={}",
                        normalizedSymptoms, topScore, preliminaryDepts, followUpQuestions.size(), followUpRound);
            }

            // 返回 followUpRound + 1，提示大模型下次调用时传入
            return new TriageSessionResult(
                    new TriageResult(
                            preliminaryDepts,
                            preliminaryExams,
                            HealthDisclaimers.TRIAGE_DISCLAIMER
                    ),
                    true,
                    followUpQuestions,
                    followUpRound + 1
            );
        }

        // 5. 匹配度足够（或已达追问上限），组装最终结果
        List<String> suggestedDepartments = topMatches.stream()
                .map(DepartmentMatch::department)
                .collect(Collectors.toList());

        List<String> commonExams = topMatches.stream()
                .flatMap(m -> m.commonExams().stream())
                .distinct()
                .collect(Collectors.toList());

        log.info("导诊匹配完成: symptoms={} -> 命中科室={}, 检查项={}, topScore={}, 追问轮次={}",
                normalizedSymptoms, suggestedDepartments, commonExams, topScore, followUpRound);

        // 排序优化日志：输出Top3科室的详细匹配信息，便于调试排序效果
        if (log.isDebugEnabled() && !topMatches.isEmpty()) {
            for (DepartmentMatch m : topMatches) {
                log.debug("  排序详情: {} -> score={}, highWeightHits={}, uniqueHits={}, keywordHits={}",
                        m.department(), m.score(), m.highWeightHits(),
                        m.uniqueKeywordHits(), m.keywordHits());
            }
        }

        return new TriageSessionResult(
                new TriageResult(
                        suggestedDepartments,
                        commonExams,
                        HealthDisclaimers.TRIAGE_DISCLAIMER
                ),
                false,
                List.of(),
                followUpRound
        );
    }

    /**
     * 遍历分科目录，计算每个科室的加权匹配得分。
     */
    private List<DepartmentMatch> scoreDepartments(List<DepartmentEntry> catalog, String symptoms) {
        // 排序优化：预计算关键词区分度（IDF），用于后续加分
        computeKeywordFrequency(catalog);

        List<DepartmentMatch> matches = new ArrayList<>();
        for (DepartmentEntry entry : catalog) {
            ScoreResult scoreResult = calculateScore(entry, symptoms);
            matches.add(new DepartmentMatch(
                    entry.department(),
                    scoreResult.score(),
                    scoreResult.highWeightHits(),
                    scoreResult.keywordHits(),
                    scoreResult.uniqueKeywordHits(),
                    entry.commonExams(),
                    entry.followUpQuestions()
            ));
        }
        return matches;
    }

    /**
     * 预计算关键词在分科目录中的出现频率（IDF 基础数据）。
     *
     * <p>统计每个关键词出现在多少个科室中，用于后续判断关键词的区分度：
     * <ul>
     *   <li>出现在 1-2 个科室 → 唯一关键词，匹配时额外加分</li>
     *   <li>出现在 3 个科室 → 低频关键词，匹配时小幅加分</li>
     *   <li>出现在 4+ 个科室 → 通用关键词，不额外加分</li>
     * </ul>
     */
    private void computeKeywordFrequency(List<DepartmentEntry> catalog) {
        keywordDeptFrequency = new HashMap<>();
        totalDeptCount = catalog.size();
        for (DepartmentEntry entry : catalog) {
            // 合并普通关键词和高权重关键词去重统计
            Set<String> allKeywords = new HashSet<>();
            allKeywords.addAll(entry.symptomKeywords());
            allKeywords.addAll(entry.highWeightKeywords());
            for (String kw : allKeywords) {
                if (kw != null && !kw.isBlank()) {
                    keywordDeptFrequency.merge(kw, 1, Integer::sum);
                }
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("IDF预计算完成: {}个科室, {}个唯一关键词", totalDeptCount, keywordDeptFrequency.size());
        }
    }

    /**
     * 获取关键词的区分度加分（基于IDF）。
     *
     * @param keyword 关键词
     * @return 加分值：唯一词0.5，低频词0.3，通用词0
     */
    private double getIdfBonus(String keyword) {
        if (keywordDeptFrequency == null) {
            return 0;
        }
        Integer freq = keywordDeptFrequency.get(keyword);
        if (freq == null || freq == 0) {
            return 0;
        }
        if (freq <= 2) {
            return UNIQUE_KEYWORD_BONUS;
        }
        if (freq <= 3) {
            return RARE_KEYWORD_BONUS;
        }
        return 0; // 通用关键词不额外加分
    }

    /**
     * 判断是否为全科（综合科），全科在排序时给予降权处理。
     */
    private boolean isGeneralDepartment(String deptName) {
        return "全科".equals(deptName) || "全科医学科".equals(deptName) || "综合科".equals(deptName);
    }

    /** 儿科儿童指示词：用户描述中必须包含这些词之一，儿科才会计分 */
    private static final List<String> CHILD_INDICATORS = List.of(
            "孩子", "宝宝", "儿童", "小儿", "婴儿", "小孩", "幼儿", "小朋友"
    );

    /**
     * 检查症状描述中是否包含儿童指示词。
     *
     * <p>儿科的 symptomKeywords 中可能包含通用症状词（如"发烧""咳嗽"），
     * 如果没有儿童指示词，这些通用词不应导致儿科命中。
     * 本方法作为代码层面的防护，即使 JSON 数据被修改也保证不会误判。</p>
     */
    private boolean hasChildIndicator(String symptoms) {
        for (String indicator : CHILD_INDICATORS) {
            if (symptoms.contains(indicator)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 计算某科室的加权得分。
     *
     * <p>权重规则：</p>
     * <ul>
     *   <li>highWeightKeywords（特异性高，如"咳痰""心绞痛"）：每个命中 +2 分</li>
     *   <li>symptomKeywords（普通词，如"发烧""乏力"）：每个命中 +1 分</li>
     *   <li>aliases（科室别名）：每个命中 +1 分</li>
     * </ul>
     * <p>同时做否定排除：如果关键词前面紧邻"不/没/无"等否定字，则不计分。</p>
     * <p>儿科特殊防护：没有儿童指示词时直接返回0分，防止成人症状误命中。</p>
     */
    private ScoreResult calculateScore(DepartmentEntry entry, String symptoms) {
        // 儿科特殊防护：没有儿童指示词时不计分
        // 防止成人症状（如"咳嗽""发烧"）因通用关键词误命中儿科
        if ("儿科".equals(entry.department()) && !hasChildIndicator(symptoms)) {
            return new ScoreResult(0, 0, 0, 0);
        }

        double score = 0;
        int highWeightHits = 0;
        int keywordHits = 0;
        int uniqueKeywordHits = 0;
        java.util.Set<String> matchedKeywords = new java.util.HashSet<>();

        // 1. 先检查高权重关键词（权重 2）
        for (String keyword : entry.highWeightKeywords()) {
            if (keyword != null && !keyword.isBlank() && symptoms.contains(keyword)
                    && !isNegated(symptoms, keyword)) {
                score += 2;
                highWeightHits++;
                keywordHits++;
                matchedKeywords.add(keyword);
                // 排序优化：IDF 加分——高权重关键词如果只出现在少数科室，额外加分
                double idfBonus = getIdfBonus(keyword);
                score += idfBonus;
                if (idfBonus >= UNIQUE_KEYWORD_BONUS) {
                    uniqueKeywordHits++;
                }
            }
        }

        // 2. 再检查普通关键词（权重 1），跳过已作为高权重匹配过的词避免重复计分
        for (String keyword : entry.symptomKeywords()) {
            if (keyword != null && !keyword.isBlank() && !matchedKeywords.contains(keyword)
                    && symptoms.contains(keyword) && !isNegated(symptoms, keyword)) {
                score += 1;
                keywordHits++;
                // 排序优化：IDF 加分——普通关键词如果只出现在少数科室，额外加分
                double idfBonus = getIdfBonus(keyword);
                score += idfBonus;
                if (idfBonus >= UNIQUE_KEYWORD_BONUS) {
                    uniqueKeywordHits++;
                }
            }
        }

        // 3. 检查科室别名（权重 1，用户可能直接说科室名）
        for (String alias : entry.aliases()) {
            if (alias != null && !alias.isBlank() && symptoms.contains(alias)) {
                score += 1;
            }
        }

        // 排序优化：全科降权——当全科和专科同时命中通用词时，确保专科排序更靠前
        if (isGeneralDepartment(entry.department())) {
            score -= GENERAL_DEPT_PENALTY;
        }

        return new ScoreResult(score, highWeightHits, keywordHits, uniqueKeywordHits);
    }

    /**
     * 检查关键词在文本中是否被否定。
     *
     * <p>检测关键词前 1-2 个字符是否为否定词（不/没/无/没有/不是）。
     * 例如"不咳嗽""没有发烧""无胸痛"中的关键词不应计分。</p>
     *
     * @param text    症状文本
     * @param keyword 待检查的关键词
     * @return true 表示该关键词被否定，不应计分
     */
    private boolean isNegated(String text, String keyword) {
        int idx = text.indexOf(keyword);
        while (idx >= 0) {
            // 检查前 1 个字符是否为单字否定词
            if (idx >= 1) {
                char prev = text.charAt(idx - 1);
                if (prev == '不' || prev == '没' || prev == '无') {
                    return true;
                }
            }
            // 检查前 2 个字符是否为双字否定词
            if (idx >= 2) {
                String prev2 = text.substring(idx - 2, idx);
                if (prev2.equals("没有") || prev2.equals("不是")) {
                    return true;
                }
            }
            idx = text.indexOf(keyword, idx + keyword.length());
        }
        return false;
    }

    /**
     * 加权打分中间结果。
     *
     * @param score          加权总得分（含IDF加分和全科降权）
     * @param highWeightHits 高权重关键词命中数（用于同分时的排序优先级）
     * @param keywordHits    总关键词命中数（含高权重和普通，用于同分时的排序优先级）
     * @param uniqueKeywordHits 唯一关键词命中数（只出现在1-2个科室的词，排序优先级最高）
     */
    private record ScoreResult(double score, int highWeightHits, int keywordHits, int uniqueKeywordHits) {
    }

    /**
     * 科室匹配中间结果。
     *
     * @param department      科室名称
     * @param score           加权得分（含IDF加分和全科降权）
     * @param highWeightHits  高权重关键词命中数（同分时优先取高权重命中多的科室）
     * @param keywordHits     总关键词命中数（同分时的第二排序维度）
     * @param uniqueKeywordHits 唯一关键词命中数（同分时的第三排序维度，区分度最高）
     * @param commonExams     常见检查项目
     * @param followUpQuestions 追问问题
     */
    private record DepartmentMatch(
            String department,
            double score,
            int highWeightHits,
            int keywordHits,
            int uniqueKeywordHits,
            List<String> commonExams,
            List<String> followUpQuestions) {
    }

    /**
     * 智能过滤追问问题：去掉用户已经在症状描述中回答过的问题。
     *
     * <p>每个追问问题都有关键词触发检测：如果用户描述中已包含该问题对应的关键词，
     * 说明用户已经主动提供了这部分信息，不需要再追问。</p>
     *
     * <p>例如用户说了"发低烧"，追问问题"有没有发烧？量过体温吗？"应被过滤掉；
     * 用户说了"咳嗽三天"，追问问题"咳嗽多久了？"应被过滤掉。</p>
     *
     * <p>如果过滤后没有剩余问题，返回原列表——避免追问为空导致大模型无话可说。</p>
     *
     * @param questions          原始追问问题列表
     * @param normalizedSymptoms 用户已归一化的症状描述
     * @return 过滤后的追问问题列表
     */
    private List<String> filterAnsweredQuestions(List<String> questions, String normalizedSymptoms) {
        if (questions == null || questions.isEmpty()) {
            return questions;
        }

        List<String> filtered = new ArrayList<>();
        for (String question : questions) {
            if (!isQuestionAlreadyAnswered(question, normalizedSymptoms)) {
                filtered.add(question);
            }
        }

        // 如果全部被过滤了，返回原列表避免空追问
        if (filtered.isEmpty()) {
            log.info("追问问题智能过滤：全部问题已被用户回答，保留原列表避免空追问");
            return questions;
        }

        if (filtered.size() < questions.size()) {
            log.info("追问问题智能过滤：从{}个过滤为{}个（用户已回答部分问题）",
                    questions.size(), filtered.size());
        }

        return filtered;
    }

    /**
     * 检查某个追问问题是否已经被用户的症状描述回答了。
     *
     * <p>通过匹配追问问题中的关键词与用户描述中的关键词来判断。
     * 如果用户描述中包含了问题涉及的核心症状词，认为该问题已被回答。</p>
     *
     * @param question          追问问题文本
     * @param normalizedSymptoms 用户已归一化的症状描述
     * @return true 表示该问题已被用户回答，应过滤掉
     */
    private boolean isQuestionAlreadyAnswered(String question, String normalizedSymptoms) {
        // 追问问题关键词 → 用户描述中对应的关键词列表
        // 如果用户描述中包含任意一个对应关键词，则该问题已被回答
        String[][] questionKeywordMap = {
                // 发烧相关
                {"发烧", "发烧", "低烧", "发热", "高烧", "体温"},
                // 咳嗽相关
                {"咳嗽", "咳嗽", "咳"},
                // 痰相关
                {"痰", "痰", "咳痰"},
                // 胸闷相关
                {"胸闷", "胸闷"},
                // 气短相关
                {"气短", "气短", "喘"},
                // 腹痛位置相关
                {"腹痛", "腹痛", "胃痛", "肚子痛"},
                // 腹痛位置细化（追问"在哪个位置？上腹还是下腹？"时，用户说了具体位置则过滤）
                {"位置", "上腹", "下腹", "左腹", "右腹", "中腹", "左上", "右上", "左下", "右下", "上面", "下面", "左边", "右边"},
                // 腹泻相关
                {"腹泻", "腹泻", "拉肚子"},
                // 恶心呕吐相关（"恶心"追问也要匹配用户说的"想吐"/"不想吐"）
                {"恶心", "恶心", "反胃", "想吐", "吐", "恶心想吐"},
                {"呕吐", "呕吐", "吐了", "想吐", "恶心"},
                // 头痛部位相关
                {"头痛", "头痛", "头疼"},
                // 头晕相关
                {"头晕", "头晕", "眩晕"},
                // 便血相关
                {"便血", "便血", "血便"},
                // 反酸相关
                {"反酸", "反酸", "烧心"},
                // 体重变化相关
                {"体重", "体重下降", "体重增加", "消瘦"},
                // 多饮多尿相关
                {"口渴", "多饮", "口渴", "多尿"},
                // 皮疹相关
                {"皮疹", "皮疹", "疹子"},
                // 瘙痒相关
                {"痒", "瘙痒", "痒"},
                // 鼻塞相关
                {"鼻塞", "鼻塞", "鼻子堵"},
                // 喉咙痛相关
                {"喉咙痛", "喉咙痛", "咽喉痛", "咽痛"},
                // 牙痛相关
                {"牙痛", "牙痛", "牙疼"},
                // 牙龈相关
                {"牙龈", "牙龈", "牙龈出血", "牙龈肿"},
                // 尿频尿痛相关
                {"排尿", "尿频", "尿急", "尿痛", "排尿困难"},
                // 血尿相关
                {"血尿", "血尿"},
                // 腰痛相关
                {"腰痛", "腰痛", "腰酸"},
                // 月经相关
                {"月经", "月经", "经期", "痛经"},
                // 出血相关
                {"出血", "出血", "流血"},
                // 淋巴结相关
                {"淋巴结", "淋巴结"},
                // 贫血相关
                {"贫血", "贫血"},
                // 睡眠相关
                {"睡眠", "失眠", "睡不着", "睡眠"},
                // 情绪相关
                {"情绪", "焦虑", "抑郁", "情绪低落", "烦躁"},
                // 年龄相关（追问"孩子多大？"时，用户已说了岁数则过滤）
                {"多大", "岁", "个月", "周岁"},
                // 症状持续天数相关（追问"持续几天？"时，用户已说了天数则过滤）
                {"持续几天", "一天", "两天", "三天", "四天", "五天", "六天", "七天", "一周", "两周", "半个月", "一个月", "几天"},
                // 精神状态相关（追问"精神状态？"时，用户已描述则过滤）
                {"精神状态", "精神", "状态", "嗜睡", "烦躁", "哭闹", "蔫"},
        };

        for (String[] mapping : questionKeywordMap) {
            String questionKeyword = mapping[0];
            // 如果追问问题包含这个关键词
            if (question.contains(questionKeyword)) {
                // 检查用户描述中是否包含任意一个对应的关键词
                for (int i = 1; i < mapping.length; i++) {
                    if (normalizedSymptoms.contains(mapping[i])) {
                        log.info("追问问题智能过滤：「{}」被过滤，用户已提到「{}」",
                                question, mapping[i]);
                        return true;
                    }
                }
            }
        }

        // 口语模式匹配：追问问题包含标准词，但用户用的是口语表达
        // "阵发性" ← 用户说"一阵一阵的"、"一阵一阵"
        if (question.contains("阵发性") || question.contains("持续性")) {
            if (normalizedSymptoms.contains("一阵一阵") || normalizedSymptoms.contains("一阵阵")
                    || normalizedSymptoms.contains("间歇") || normalizedSymptoms.contains("时好时坏")
                    || normalizedSymptoms.contains("持续")) {
                log.info("追问问题智能过滤：「{}」被过滤，用户已描述发作模式", question);
                return true;
            }
        }
        // "跟吃饭有关" ← 用户说了跟吃饭有关系或没关系
        if (question.contains("吃饭")) {
            if (normalizedSymptoms.contains("吃饭") || normalizedSymptoms.contains("饭后")
                    || normalizedSymptoms.contains("饭前") || normalizedSymptoms.contains("进食")) {
                log.info("追问问题智能过滤：「{}」被过滤，用户已提到与进食的关系", question);
                return true;
            }
        }

        return false;
    }

    /**
     * 统计症状描述中的信息维度数。
     *
     * <p>信息维度是判断症状描述是否足够具体的核心标准。
     * 一个"足够具体"的描述至少需要3个维度的信息，
     * 而"持续性胃痛"只有2个维度（主症状+性质）→ 不够具体，必须追问。</p>
     *
     * <p>六维定义：</p>
     * <ol>
     *   <li>主症状：匹配到科室关键词（如"胃痛""咳嗽"）</li>
     *   <li>伴随症状：主症状之外的其他症状词（如"恶心""发烧"）</li>
     *   <li>部位：身体位置描述（如"上腹""左胸""右下"）</li>
     *   <li>性质：疼痛性质或发作模式（如"持续性""阵发性""胀痛""刺痛"）</li>
     *   <li>时间：持续时间信息（如"三天""一周""半个月"）</li>
     *   <li>诱因：触发因素（如"饭后""运动后""早晨"）</li>
     * </ol>
     *
     * @param symptoms 归一化后的症状描述
     * @return 信息维度数（0-6）
     */
    private int countSymptomDimensions(String symptoms) {
        if (symptoms == null || symptoms.isBlank()) {
            return 0;
        }
        int dimensions = 0;

        // 维度1：主症状 — 至少命中1个科室关键词
        // 这里用简化判断：包含常见症状字/词即算有主症状
        boolean hasChiefComplaint = false;
        String[] symptomWords = {
            "痛", "烧", "咳", "吐", "晕", "泻", "痒", "肿", "血",
            "闷", "悸", "麻", "疹", "哑", "鸣", "塞", "痰", "血尿",
            "发烧", "咳嗽", "呕吐", "腹泻", "便秘", "失眠",
            "皮疹", "鼻塞", "流涕", "咽痛", "腰酸", "乏力",
            "头晕", "心慌", "气短", "喘", "尿频", "尿急",
            "出血", "黄疸", "贫血", "食欲", "体重", "口渴"
        };
        for (String w : symptomWords) {
            if (symptoms.contains(w)) {
                hasChiefComplaint = true;
                break;
            }
        }
        if (hasChiefComplaint) {
            dimensions++;
        }

        // 维度2：伴随症状 — 主症状之外还有其他症状词
        // 统计症状词出现次数，>1说明有伴随症状
        int symptomCount = 0;
        for (String w : symptomWords) {
            if (symptoms.contains(w)) {
                symptomCount++;
            }
        }
        if (symptomCount >= 2) {
            dimensions++;
        }

        // 维度3：部位 — 身体位置描述
        String[] locationWords = {
            "上腹", "下腹", "左腹", "右腹", "中腹", "左上", "右上", "左下", "右下",
            "左胸", "右胸", "前胸", "后背", "头部", "前额", "后脑", "两侧",
            "左边", "右边", "上面", "下面", "膝盖", "肩膀", "颈椎", "腰部",
            "胸部", "腹部", "背部", "颈部", "眼部", "耳部", "喉部", "口腔",
            "胸", "头", "背", "腰", "腿", "手", "脚",
            "牙", "眼", "耳", "鼻", "喉", "胃", "肠", "肾", "肝", "肺", "心"
        };
        for (String w : locationWords) {
            if (symptoms.contains(w)) {
                dimensions++;
                break;
            }
        }

        // 维度4：性质 — 疼痛性质或发作模式
        String[] natureWords = {
            "持续性", "阵发性", "间歇性", "阵发", "间歇", "时好时坏",
            "胀痛", "刺痛", "钝痛", "隐痛", "绞痛", "烧灼", "放射",
            "搏动", "跳痛", "压痛", "胀", "痉挛", "一阵一阵", "一阵阵"
        };
        for (String w : natureWords) {
            if (symptoms.contains(w)) {
                dimensions++;
                break;
            }
        }

        // 维度5：时间 — 持续时间信息
        String[] durationWords = {
            "一天", "两天", "三天", "四天", "五天", "六天", "七天", "一周", "两周",
            "半个月", "一个月", "两个月", "三个月", "半年", "一年", "几天",
            "小时", "分钟", "早上", "晚上", "夜间", "早晨", "睡前",
            "今天", "昨天", "前天", "上周", "上个月", "最近"
        };
        for (String w : durationWords) {
            if (symptoms.contains(w)) {
                dimensions++;
                break;
            }
        }

        // 维度6：诱因 — 触发因素
        String[] triggerWords = {
            "饭后", "吃完", "进食", "饭前", "空腹", "运动", "走路", "跑步",
            "劳累", "熬夜", "受凉", "受风", "喝酒", "饮酒", "吃药", "服药",
            "接触", "吃了", "喝了", "吹风", "着凉", "感冒后", "术后"
        };
        for (String w : triggerWords) {
            if (symptoms.contains(w)) {
                dimensions++;
                break;
            }
        }

        return dimensions;
    }

    /**
     * 口语归一化：将用户常用口语表述映射到关键词词典中的标准用词。
     *
     * <p>例如"头疼"→"头痛"、"拉肚子"→"腹泻"、"胸口疼"→"胸痛"等，
     * 使关键词包含匹配能正确命中。</p>
     */
    private String normalizeSymptomText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        // 先处理"疼痛"→"痛"，避免"疼"→"痛"后变成"痛痛"
        result = result.replace("疼痛", "痛");
        // "疼" → "痛"：头疼→头痛、胃疼→胃痛、肚子疼→腹痛痛（后续按关键词匹配不影响）
        result = result.replace("疼", "痛");
        // 常见口语 → 标准症状词
        result = result.replace("拉肚子", "腹泻");
        result = result.replace("肚子痛", "腹痛");
        result = result.replace("肚子", "腹");
        result = result.replace("脑袋痛", "头痛");
        result = result.replace("脑袋", "头");
        result = result.replace("胸口痛", "胸痛");
        result = result.replace("胸口", "胸");
        result = result.replace("喘不上气", "气短");
        result = result.replace("喘气困难", "气短");
        result = result.replace("眼花", "视物模糊");
        result = result.replace("吐了", "呕吐");
        result = result.replace("反胃", "恶心");
        result = result.replace("没胃口", "食欲不振");
        result = result.replace("睡不着", "失眠");
        result = result.replace("起夜多", "夜尿增多");
        // 防止重复替换："尿频尿急尿痛"中包含"尿频尿急"，直接replace会导致无限追加"尿痛"
        if (result.contains("尿频尿急") && !result.contains("尿频尿急尿痛")) {
            result = result.replace("尿频尿急", "尿频尿急尿痛");
        }
        return result;
    }
}
