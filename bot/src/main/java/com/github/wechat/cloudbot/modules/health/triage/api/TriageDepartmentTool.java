package com.github.wechat.cloudbot.modules.health.triage.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.wechat.cloudbot.modules.health.common.HealthToolNames;
import com.github.wechat.cloudbot.modules.health.common.TriageResult;
import com.github.wechat.cloudbot.modules.health.triage.service.TriageService;
import com.github.wechat.cloudbot.modules.health.triage.service.TriageSessionResult;
import com.github.wechat.cloudbot.platform.agent.AgentTool;
import com.github.wechat.cloudbot.platform.agent.ToolArgs;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolDefinition;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import com.github.wechat.cloudbot.platform.model.ChatMessage;
import com.github.wechat.cloudbot.platform.persistence.ChatMemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 症状导诊工具。
 *
 * <p>该工具负责把用户的症状描述交给导诊服务，并输出适合微信展示的结果，
 * 包括建议科室、常见检查、追问问题和免责声明。</p>
 */
@Component
public class TriageDepartmentTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(TriageDepartmentTool.class);

    private final TriageService triageService;
    private final ObjectMapper objectMapper;
    private final ChatMemoryRepository memoryStore;

    /** 话术变体随机选择器，避免回复过于模板化。 */
    private final java.util.Random responseVariantRandom = new java.util.Random();

    public TriageDepartmentTool(TriageService triageService, ObjectMapper objectMapper,
                                 ChatMemoryRepository memoryStore) {
        this.triageService = triageService;
        this.objectMapper = objectMapper;
        this.memoryStore = memoryStore;
    }

    @Override
    public ToolDefinition definition() {
        String jsonSchema = """
            {
              "type": "object",
              "properties": {
                "symptoms": {
                  "type": "string",
                  "description": "用户当前这次描述的症状或身体不适。只传当前症状，不要拼接历史对话中的症状"
                },
                "followUpRound": {
                  "type": "integer",
                  "description": "追问轮次。首次调用或用户描述全新症状时传0；追问同一症状时传上次返回的followUpRound值",
                  "minimum": 0
                },
                "sameAsBeforeConfirmed": {
                  "type": "boolean",
                  "description": "当工具返回 needAskSameAsBefore=true 后，用户回复一样/差不多/都一样时传 true"
                }
              },
              "required": ["symptoms"]
            }
            """;

        return new ToolDefinition(
                HealthToolNames.TRIAGE_DEPARTMENT,
                "当用户描述身体不适、症状，或询问应该挂什么科、看哪个科室时，调用此工具进行分科导诊建议。",
                jsonSchema);
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            JsonNode args = objectMapper.readTree(call.argumentsJson() == null ? "{}" : call.argumentsJson());
            String symptoms = args.has("symptoms") ? args.get("symptoms").asText() : "";
            if (symptoms.isBlank()) {
                return ToolResult.fail(call.id(), call.name(), "症状描述不能为空");
            }

            int followUpRound = args.has("followUpRound") ? args.get("followUpRound").asInt(0) : 0;
            boolean sameAsBeforeConfirmed = args.has("sameAsBeforeConfirmed") && args.get("sameAsBeforeConfirmed").asBoolean(false);

            log.info("导诊工具触发 symptoms={}, followUpRound={}, sameAsBeforeConfirmed={}", symptoms, followUpRound, sameAsBeforeConfirmed);

            String userId = ToolArgs.resolveUserId(call, args);

            int actualFollowUpCount = countActualFollowUpRounds(userId);
            boolean forceFinalResult = actualFollowUpCount >= TriageService.MAX_FOLLOW_UP_ROUNDS;

            if (!sameAsBeforeConfirmed && !forceFinalResult) {
                if (userId != null && !userId.isBlank() && memoryStore != null) {
                    List<ChatMessage> history = memoryStore.getHistory(userId);
                    if (history != null && !history.isEmpty()) {
                        if (hasUserAlreadyAnsweredSameAsBefore(history)
                                || isSameAsBeforeReply(symptoms)) {
                            if (isNotSameAsBeforeReply(symptoms) && !sameAsBeforeConfirmed) {
                                String actualSymptoms = stripSameAsBeforeKeywords(symptoms);
                                if (actualSymptoms.isBlank() || actualSymptoms.length() < 2) {
                                    ObjectNode askNode = objectMapper.createObjectNode();
                                    askNode.put("needsMoreInfo", true);
                                    askNode.put("needAskCurrentSymptoms", true);
                                    askNode.put("formattedResponse",
                                            "好的，那这次具体是哪里不舒服？\n\n" +
                                            "· 请描述一下这次的症状\n" +
                                            "· 跟上次相比有什么不一样？");
                                    askNode.put("followUpRound", 0);
                                    askNode.set("followUpQuestions", objectMapper.valueToTree(List.of("这次具体哪里不舒服？", "跟上次相比有什么不一样？")));
                                    return ToolResult.ok(call.id(), call.name(), objectMapper.writeValueAsString(askNode));
                                }
                                TriageSessionResult sessionResult = triageService.triage(actualSymptoms, 0, List.of());
                                String formattedResponse = buildFormattedResponse(sessionResult);
                                ObjectNode resultNode = objectMapper.createObjectNode();
                                resultNode.put("needsMoreInfo", sessionResult.needsMoreInfo());
                                resultNode.put("formattedResponse", formattedResponse);
                                resultNode.put("followUpRound", sessionResult.followUpRound());
                                if (sessionResult.needsMoreInfo()) {
                                    resultNode.set("followUpQuestions", objectMapper.valueToTree(sessionResult.followUpQuestions()));
                                }
                                return ToolResult.ok(call.id(), call.name(), objectMapper.writeValueAsString(resultNode));
                            }
                        } else {
                            boolean skipRevisitCheck = false;
                            if (isLastBotMessageAskingSameAsBefore(history)) {
                                if (hasActualSymptomContent(symptoms)) {
                                    skipRevisitCheck = true;
                                } else {
                                    ObjectNode askNode = objectMapper.createObjectNode();
                                    askNode.put("needsMoreInfo", true);
                                    askNode.put("needAskCurrentSymptoms", true);
                                    askNode.put("formattedResponse",
                                            "好的，那这次具体是哪里不舒服？\n\n" +
                                            "· 请描述一下这次的症状\n" +
                                            "· 跟上次相比有什么不一样？");
                                    askNode.put("followUpRound", 0);
                                    askNode.set("followUpQuestions", objectMapper.valueToTree(List.of("这次具体哪里不舒服？", "跟上次相比有什么不一样？")));
                                    return ToolResult.ok(call.id(), call.name(), objectMapper.writeValueAsString(askNode));
                                }
                            }

                            boolean alreadyInFollowUp = isInFollowUpPhase(history);
                            if (!alreadyInFollowUp && !skipRevisitCheck) {
                                String previousSymptom = findPreviousSymptomMention(symptoms, history);
                                if (previousSymptom != null) {
                                    if (!hasSubstantialNewDetail(symptoms, previousSymptom)) {
                                        ObjectNode askNode = objectMapper.createObjectNode();
                                        askNode.put("needsMoreInfo", true);
                                        askNode.put("needAskSameAsBefore", true);
                                        askNode.put("formattedResponse", getRandomSameAsBeforeQuestion(previousSymptom));
                                        askNode.put("followUpRound", followUpRound);
                                        return ToolResult.ok(call.id(), call.name(), objectMapper.writeValueAsString(askNode));
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (sameAsBeforeConfirmed && !forceFinalResult) {
                TriageSessionResult sessionResult = triageService.triage(symptoms, followUpRound, List.of());
                sessionResult = new TriageSessionResult(
                        sessionResult.triageResult(), false, List.of(), sessionResult.followUpRound());
                String formattedResponse = buildFormattedResponse(sessionResult);
                ObjectNode resultNode = objectMapper.createObjectNode();
                resultNode.put("needsMoreInfo", false);
                resultNode.put("formattedResponse", formattedResponse);
                resultNode.put("followUpRound", sessionResult.followUpRound());
                return ToolResult.ok(call.id(), call.name(), objectMapper.writeValueAsString(resultNode));
            }

            String accumulatedSymptoms = accumulateRecentUserAnswers(symptoms, userId);
            int effectiveFollowUpRound = Math.max(followUpRound, actualFollowUpCount);

            TriageSessionResult sessionResult = triageService.triage(accumulatedSymptoms, effectiveFollowUpRound, List.of());
            TriageResult result = sessionResult.triageResult();
            String formattedResponse = buildFormattedResponse(sessionResult);

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("needsMoreInfo", sessionResult.needsMoreInfo());
            resultNode.put("formattedResponse", formattedResponse);
            resultNode.put("followUpRound", sessionResult.followUpRound());

            if (sessionResult.needsMoreInfo()) {
                resultNode.set("followUpQuestions", objectMapper.valueToTree(sessionResult.followUpQuestions()));
            }

            return ToolResult.ok(call.id(), call.name(), objectMapper.writeValueAsString(resultNode));
        } catch (Exception e) {
            log.error("导诊工具执行失败: {}", e.getMessage(), e);
            return ToolResult.fail(call.id(), call.name(), "导诊服务暂时不可用: " + e.getMessage());
        }
    }

    private static final String[] FINAL_OPENINGS = {
            "根据你的描述，帮你整理了以下建议：",
            "了解了你的情况，以下是我的建议：",
            "根据你说的症状，帮你梳理了一下：",
            "听完你的描述，建议参考以下方向：",
            "结合你提到的不适，整理了这些建议："
    };

    private static final String[] FOLLOWUP_OPENINGS = {
            "别着急，为了帮你更准确地判断，想再了解几个情况：",
            "别担心，再确认几个细节，这样建议更精准：",
            "为了给你更靠谱的建议，想多了解一点：",
            "别急，帮我再补充几个信息：",
            "别着急，多了解一点情况才好给你更合适的建议："
    };

    private static final String[] NAVIGATION_QUESTIONS = {
            "🗺️ 需要帮你查找附近的医院或诊所吗？\n· 回复「需要」并告诉我你的城市和区域\n· 也可以直接在微信搜索「附近医院」",
            "🗺️ 要帮你看看附近有哪些医院吗？\n· 回复「需要」+ 你的城市区域\n· 或者直接微信搜「附近医院」也行",
            "🗺️ 需要附近医院导航的话告诉我哦\n· 说「需要」加上你所在的城市和区域\n· 也可以直接在微信里搜「附近医院」"
    };

    private String getRandomFinalOpening() {
        return FINAL_OPENINGS[responseVariantRandom.nextInt(FINAL_OPENINGS.length)];
    }

    private String getRandomFollowUpOpening() {
        return FOLLOWUP_OPENINGS[responseVariantRandom.nextInt(FOLLOWUP_OPENINGS.length)];
    }

    private String getRandomNavigationQuestion() {
        return NAVIGATION_QUESTIONS[responseVariantRandom.nextInt(NAVIGATION_QUESTIONS.length)];
    }

    private String getRandomSameAsBeforeQuestion(String previousSymptom) {
        String[] templates = {
                "你之前也提到过「%s」，这次和上次一样吗？\n\n· 一样的话直接回复「一样」\n· 不一样的话简单说说这次的症状",
                "你之前也提到过「%s」，这次的情况和上次差不多吗？\n\n· 差不多的话回复「一样」\n· 有变化的话简单说说这次怎么不舒服",
                "之前你说起过「%s」，这次是同样的情况吗？\n\n· 同样的回复「一样」就行\n· 不一样的话告诉我这次的症状"
        };
        return String.format(templates[responseVariantRandom.nextInt(templates.length)], previousSymptom);
    }

    private String buildFormattedResponse(TriageSessionResult sessionResult) {
        TriageResult result = sessionResult.triageResult();
        if (sessionResult.needsMoreInfo()) {
            return buildFollowUpResponse(result, sessionResult.followUpQuestions());
        }
        return buildFinalResponse(result);
    }

    private String buildFollowUpResponse(TriageResult result, List<String> followUpQuestions) {
        if (followUpQuestions.isEmpty()) {
            return getRandomFollowUpOpening() + "\n\n· 具体哪里不舒服？\n· 症状持续多久了？";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(getRandomFollowUpOpening()).append("\n\n");
        for (String q : followUpQuestions) {
            sb.append("· ").append(q).append("\n");
        }
        return sb.toString().trim();
    }

    private String buildFinalResponse(TriageResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append(getRandomFinalOpening()).append("\n\n");
        sb.append("🏥 建议科室\n");
        for (String dept : result.suggestedDepartments()) {
            sb.append("· ").append(dept).append("\n");
        }
        sb.append("\n");

        if (!result.commonExams().isEmpty()) {
            sb.append("🔬 常见检查项目\n");
            for (String exam : result.commonExams()) {
                sb.append("· ").append(exam).append("\n");
            }
            sb.append("\n");
        }

        sb.append("💡 温馨提示\n");
        for (String tip : buildDepartmentTips(result.suggestedDepartments())) {
            sb.append("· ").append(tip).append("\n");
        }
        sb.append("\n");
        sb.append("⚠️ ").append(result.disclaimer());
        sb.append("\n\n");
        sb.append(getRandomNavigationQuestion());
        return sb.toString();
    }

    private List<String> buildDepartmentTips(List<String> departments) {
        List<String> tips = new ArrayList<>();
        for (String dept : departments) {
            switch (dept) {
                case "呼吸内科" -> {
                    tips.add("注意保暖，避免受凉");
                    tips.add("多喝温水，保持室内通风");
                }
                case "消化内科" -> {
                    tips.add("饮食清淡，避免辛辣油腻");
                    tips.add("少食多餐，细嚼慢咽");
                }
                case "心血管内科" -> {
                    tips.add("避免剧烈运动和情绪激动");
                    tips.add("低盐低脂饮食，规律作息");
                }
                case "神经内科" -> {
                    tips.add("保证充足睡眠，避免熬夜");
                    tips.add("减少电子屏幕使用时间");
                }
                case "内分泌科" -> {
                    tips.add("控制饮食，适量运动");
                    tips.add("定期监测血糖或甲状腺功能");
                }
                case "骨科" -> {
                    tips.add("避免负重和剧烈活动");
                    tips.add("注意休息，适当热敷缓解");
                }
                case "皮肤科" -> {
                    tips.add("避免抓挠，忌辛辣海鲜");
                    tips.add("保持皮肤清洁干燥");
                }
                case "眼科" -> {
                    tips.add("注意用眼卫生，避免长时间看屏幕");
                    tips.add("不要揉眼，保持眼部清洁");
                }
                case "耳鼻喉科" -> {
                    tips.add("避免用力擤鼻，保持鼻腔湿润");
                    tips.add("多喝温水，减少刺激性食物");
                }
                case "口腔科" -> {
                    tips.add("避免过冷过热食物");
                    tips.add("保持口腔卫生，饭后漱口");
                }
                case "泌尿外科" -> {
                    tips.add("多喝水，不憋尿");
                    tips.add("注意个人卫生，避免辛辣食物");
                }
                case "儿科" -> {
                    tips.add("注意观察孩子精神状态和体温");
                    tips.add("保证充足饮水，饮食清淡易消化");
                }
                case "妇产科" -> {
                    tips.add("注意个人卫生，避免劳累");
                    tips.add("如有异常出血或腹痛，请及时就诊");
                }
                case "普外科" -> {
                    tips.add("避免按压腹部，注意观察疼痛变化");
                    tips.add("如疼痛突然加剧或发烧，请立即就医");
                }
                case "急诊科" -> {
                    tips.add("请立即前往最近的急诊就诊");
                    tips.add("不要自行用药，保持患者安静");
                }
                case "血液科" -> {
                    tips.add("避免磕碰，注意观察出血情况");
                    tips.add("均衡饮食，补充富含铁的食物");
                }
                case "肾内科" -> {
                    tips.add("控制盐分和蛋白质摄入");
                    tips.add("适量饮水，避免熬夜劳累");
                }
                case "精神心理科" -> {
                    tips.add("保持规律作息，适度运动");
                    tips.add("尝试与亲友倾诉，必要时寻求专业帮助");
                }
                case "中医科" -> {
                    tips.add("规律饮食，避免生冷寒凉");
                    tips.add("适当运动，保持心情舒畅");
                }
                case "康复科" -> {
                    tips.add("遵医嘱进行康复训练，循序渐进");
                    tips.add("避免过度劳累，注意休息");
                }
                case "肿瘤科" -> {
                    tips.add("保持积极心态，配合医生治疗");
                    tips.add("均衡营养，定期复查");
                }
                case "全科" -> {
                    tips.add("注意休息，多喝温水");
                    tips.add("观察症状变化，必要时及时就医");
                }
                case "风湿免疫科" -> {
                    tips.add("注意关节保暖，避免受凉潮湿");
                    tips.add("规律作息，适当进行关节活动");
                }
                case "感染科" -> {
                    tips.add("注意个人卫生，勤洗手");
                    tips.add("避免与他人共用餐具，饮食清淡");
                }
                case "神经外科" -> {
                    tips.add("避免剧烈活动和头部碰撞");
                    tips.add("如出现剧烈头痛或呕吐，请立即就医");
                }
                case "胸外科" -> {
                    tips.add("避免剧烈咳嗽和重体力活动");
                    tips.add("注意呼吸变化，保持呼吸道通畅");
                }
                case "血管外科" -> {
                    tips.add("避免久站久坐，适当活动促进血液循环");
                    tips.add("注意肢体温度变化，做好保暖");
                }
                case "疼痛科" -> {
                    tips.add("注意休息，避免诱发疼痛的体位和动作");
                    tips.add("可适当热敷缓解，疼痛持续加重请就医");
                }
                default -> {
                    tips.add("注意休息，观察症状变化");
                    tips.add("如症状加剧，请及时就医");
                }
            }
        }
        return tips.stream().distinct().limit(5).toList();
    }

    private boolean hasUserAlreadyAnsweredSameAsBefore(List<ChatMessage> history) {
        int startIndex = Math.max(0, history.size() - 10);
        boolean askedBefore = false;
        int answeredIndex = -1;
        for (int i = startIndex; i < history.size(); i++) {
            ChatMessage msg = history.get(i);
            String role = msg.role();
            if (!(msg.content() instanceof String text)) {
                continue;
            }
            if ("assistant".equals(role)) {
                if (text.contains("和上次一样") || text.contains("上次一样")) {
                    askedBefore = true;
                }
            }
            if (askedBefore && "user".equals(role)) {
                if (text.contains("一样") || text.contains("不一样")
                        || text.contains("不太一样") || text.contains("不完全一样")
                        || text.contains("不同") || text.contains("差不多")
                        || text.contains("都一样") || text.contains("不太像")
                        || text.contains("不是") || text.contains("有变化")
                        || text.contains("变了") || text.contains("不一样了")
                        || text.contains("有不一样") || text.contains("新的症状")
                        || text.contains("不完全相同")) {
                    answeredIndex = i;
                }
            }
        }

        if (answeredIndex >= 0) {
            for (int i = answeredIndex + 1; i < history.size(); i++) {
                ChatMessage msg = history.get(i);
                if (!"assistant".equals(msg.role())) {
                    continue;
                }
                if (!(msg.content() instanceof String text)) {
                    continue;
                }
                if (text.contains("建议科室") || text.contains("帮你整理")
                        || text.contains("常见检查项目")) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private boolean isLastBotMessageAskingSameAsBefore(List<ChatMessage> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage msg = history.get(i);
            if (!"assistant".equals(msg.role())) {
                continue;
            }
            if (!(msg.content() instanceof String text)) {
                continue;
            }
            if (text.contains("和上次一样") || text.contains("上次一样")
                    || text.contains("差不多") || text.contains("同样的情况")) {
                return true;
            }
            return false;
        }
        return false;
    }

    private boolean isInFollowUpPhase(List<ChatMessage> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage msg = history.get(i);
            if (!"assistant".equals(msg.role())) {
                continue;
            }
            if (!(msg.content() instanceof String text)) {
                continue;
            }
            if (text.contains("别着急") || text.contains("别担心") || text.contains("别急")
                    || text.contains("更准确") || text.contains("具体说说")
                    || text.contains("确认几个") || text.contains("了解几个")
                    || text.contains("补充几个") || text.contains("想多了解")
                    || text.contains("多了解一点") || text.contains("更靠谱")
                    || text.contains("几个细节") || text.contains("几个信息")
                    || text.contains("几个情况") || text.contains("分科建议")
                    || text.contains("帮您分析") || text.contains("帮你分析")
                    || text.contains("再帮您") || text.contains("再帮你")) {
                return true;
            }
            if (text.contains("和上次一样") || text.contains("上次一样")) {
                return false;
            }
            if (text.contains("建议科室") || text.contains("帮你整理")
                    || text.contains("常见检查项目")) {
                return false;
            }
        }
        return false;
    }

    private int countActualFollowUpRounds(String userId) {
        if (userId == null || userId.isBlank() || memoryStore == null) {
            return 0;
        }
        List<ChatMessage> history = memoryStore.getHistory(userId);
        if (history == null || history.isEmpty()) {
            return 0;
        }

        int count = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage msg = history.get(i);
            if (!"assistant".equals(msg.role())) {
                continue;
            }
            if (!(msg.content() instanceof String text)) {
                continue;
            }
            if (text.contains("和上次一样") || text.contains("上次一样")
                    || text.contains("同样的情况") || text.contains("差不多")) {
                break;
            }
            if (text.contains("建议科室") || text.contains("帮你整理")
                    || text.contains("常见检查项目") || text.contains("温馨提示")
                    || text.contains("根据你的描述") || text.contains("了解了你的情况")
                    || text.contains("根据你说的症状") || text.contains("听完你的描述")
                    || text.contains("结合你提到的不适")) {
                break;
            }
            if (isTriageFollowUpMessage(text)) {
                count++;
            }
        }
        return count;
    }

    private boolean isTriageFollowUpMessage(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (text.contains("别着急") || text.contains("别担心") || text.contains("别急")
                || text.contains("更准确") || text.contains("具体说说")
                || text.contains("确认几个") || text.contains("了解几个")
                || text.contains("补充几个") || text.contains("想多了解")
                || text.contains("多了解一点") || text.contains("更靠谱")
                || text.contains("几个细节") || text.contains("几个信息")
                || text.contains("几个情况") || text.contains("帮您分析")
                || text.contains("帮你分析") || text.contains("再帮您")
                || text.contains("再帮你")) {
            return true;
        }
        if (text.contains("这次具体") || text.contains("具体是哪里不舒服")) {
            return true;
        }
        if (text.contains("？") || text.contains("?")) {
            String[] triageKeywords = {
                "症状", "不舒服", "多久了", "有没有", "是不是", "位置",
                "上腹", "下腹", "持续性", "阵发性", "发烧", "咳嗽",
                "腹泻", "恶心", "呕吐", "便血", "反酸", "烧心",
                "头晕", "头痛", "胸闷", "心慌", "气短",
                "皮疹", "瘙痒", "牙痛", "鼻塞", "耳鸣"
            };
            for (String kw : triageKeywords) {
                if (text.contains(kw)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isSameAsBeforeReply(String symptoms) {
        if (symptoms == null || symptoms.isBlank()) {
            return false;
        }
        return symptoms.contains("不一样") || symptoms.contains("不太一样")
                || symptoms.contains("不完全一样") || symptoms.contains("不太相同")
                || symptoms.contains("不同") || symptoms.contains("差不多")
                || symptoms.contains("都一样") || symptoms.contains("不太像")
                || symptoms.contains("有变化") || symptoms.contains("变了")
                || symptoms.contains("不一样了") || symptoms.contains("有不一样")
                || symptoms.trim().equals("一样") || symptoms.contains("不是一样的")
                || symptoms.contains("新的症状") || symptoms.contains("不完全相同");
    }

    private boolean isNotSameAsBeforeReply(String symptoms) {
        if (symptoms == null || symptoms.isBlank()) {
            return false;
        }
        return symptoms.contains("不一样") || symptoms.contains("不太一样")
                || symptoms.contains("不完全一样") || symptoms.contains("不太相同")
                || symptoms.contains("不同") || symptoms.contains("不太像")
                || symptoms.contains("有变化") || symptoms.contains("变了")
                || symptoms.contains("不一样了") || symptoms.contains("有不一样")
                || symptoms.contains("不是一样的") || symptoms.contains("新的症状")
                || symptoms.contains("不完全相同");
    }

    private String stripSameAsBeforeKeywords(String symptoms) {
        if (symptoms == null || symptoms.isBlank()) {
            return "";
        }
        String result = symptoms;
        String[] keywords = {
                "和之前不一样", "和上次不一样", "跟之前不一样", "跟上次不一样",
                "不一样了", "有不一样", "不是一样的", "不完全一样",
                "不太一样", "不完全相同", "不太相同", "不太像",
                "不一样", "有变化", "变了", "新的症状", "不同"
        };
        for (String kw : keywords) {
            result = result.replace(kw, " ");
        }
        result = result.replaceAll("[，,、。！!？?\\s]+", " ").trim();
        return result;
    }

    private String accumulateRecentUserAnswers(String currentSymptoms, String userId) {
        if (userId == null || userId.isBlank() || memoryStore == null) {
            return currentSymptoms;
        }
        List<ChatMessage> history = memoryStore.getHistory(userId);
        if (history == null || history.isEmpty()) {
            return currentSymptoms;
        }

        int sessionStartIndex = -1;
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage msg = history.get(i);
            if ("assistant".equals(msg.role()) && msg.content() instanceof String text) {
                if (text.contains("和上次一样") || text.contains("上次一样")) {
                    sessionStartIndex = i;
                    break;
                }
                if (text.contains("建议科室") || text.contains("帮你整理")
                        || text.contains("常见检查项目")) {
                    sessionStartIndex = i;
                    break;
                }
            }
        }

        if (sessionStartIndex < 0) {
            return currentSymptoms;
        }

        List<String> recentUserMsgs = new ArrayList<>();
        int startIdx = sessionStartIndex + 1;
        for (int i = startIdx; i < history.size(); i++) {
            ChatMessage msg = history.get(i);
            if (!"user".equals(msg.role())) {
                continue;
            }
            if (!(msg.content() instanceof String text) || text.isBlank()) {
                continue;
            }
            String trimmed = text.trim();
            if (isSameAsBeforeReply(trimmed)) {
                continue;
            }
            if (trimmed.length() < 2) {
                continue;
            }
            if (trimmed.equals(currentSymptoms.trim())
                    || currentSymptoms.trim().contains(trimmed)
                    || isMostlyContained(trimmed, currentSymptoms.trim())) {
                continue;
            }
            recentUserMsgs.add(trimmed);
        }

        if (recentUserMsgs.isEmpty()) {
            return currentSymptoms;
        }

        String accumulated = String.join(" ", recentUserMsgs) + " " + currentSymptoms;
        return accumulated;
    }

    private String findPreviousSymptomMention(String currentSymptoms, List<ChatMessage> history) {
        String normalizedCurrent = normalizeForMatching(currentSymptoms);
        String keyword = extractSymptomKeyword(normalizedCurrent);
        if (keyword == null || keyword.isBlank()) {
            return null;
        }

        int sessionBoundaryIndex = -1;
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage msg = history.get(i);
            if ("assistant".equals(msg.role()) && msg.content() instanceof String text) {
                if (text.contains("和上次一样") || text.contains("上次一样")) {
                    sessionBoundaryIndex = i;
                    break;
                }
                if (text.contains("建议科室") || text.contains("帮你整理")
                        || text.contains("常见检查项目")) {
                    sessionBoundaryIndex = i;
                    break;
                }
                if (text.contains("更准确") || text.contains("了解几个")
                        || text.contains("确认几个") || text.contains("补充几个")
                        || text.contains("几个细节") || text.contains("几个信息")
                        || text.contains("几个情况") || text.contains("别着急")
                        || text.contains("别担心") || text.contains("别急")
                        || text.contains("具体说说") || text.contains("更靠谱")
                        || text.contains("分科建议") || text.contains("帮您分析")
                        || text.contains("帮你分析") || text.contains("再帮您")
                        || text.contains("再帮你")) {
                    sessionBoundaryIndex = i;
                    break;
                }
            }
        }

        int scanLimit = sessionBoundaryIndex >= 0 ? sessionBoundaryIndex : 0;
        for (int i = 0; i < scanLimit; i++) {
            ChatMessage msg = history.get(i);
            if (!"user".equals(msg.role())) {
                continue;
            }
            if (!(msg.content() instanceof String text) || text.isBlank()) {
                continue;
            }
            if (isSameAsBeforeReply(text.trim())) {
                continue;
            }
            String normalizedHistory = normalizeForMatching(text);
            if (normalizedHistory.contains(keyword)) {
                return text.length() > 30 ? text.substring(0, 30) + "..." : text;
            }
        }
        return null;
    }

    private boolean isMostlyContained(String historyMsg, String currentSymptoms) {
        if (historyMsg == null || historyMsg.isBlank()
                || currentSymptoms == null || currentSymptoms.isBlank()) {
            return false;
        }
        String[] segments = historyMsg.split("[\\s，,、。！！？?]+");
        int total = 0;
        int contained = 0;
        for (String seg : segments) {
            if (seg.length() < 2) {
                continue;
            }
            total++;
            if (currentSymptoms.contains(seg)) {
                contained++;
            } else {
                String stripped = seg.replaceAll("[了的也吧呢啊呀哦嘛]", "");
                if (stripped.length() >= 2 && currentSymptoms.contains(stripped)) {
                    contained++;
                }
            }
        }
        return total > 0 && contained >= total * 0.5;
    }

    private boolean hasActualSymptomContent(String symptoms) {
        if (symptoms == null || symptoms.length() < 3) {
            return false;
        }
        String[] symptomPatterns = {
            "痛", "疼", "烧", "咳", "吐", "晕", "泻", "痒", "肿", "血",
            "热", "冷", "闷", "悸", "麻", "不舒服", "难受", "恶心",
            "发烧", "咳嗽", "呕吐", "腹泻", "便秘", "失眠", "皮疹",
            "鼻塞", "流涕", "咽痛", "腰酸", "乏力", "头晕", "心慌",
            "气短", "喘", "尿频", "尿急", "尿痛", "出血", "黄疸",
            "淋巴结", "贫血", "食欲", "体重", "口渴", "多饮", "多尿"
        };
        for (String p : symptomPatterns) {
            if (symptoms.contains(p)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasSubstantialNewDetail(String symptoms, String previousSymptom) {
        if (symptoms == null || symptoms.isBlank()) {
            return false;
        }

        String remaining = symptoms;
        if (previousSymptom != null && !previousSymptom.isBlank()) {
            remaining = remaining.replace(previousSymptom, " ");
            String normalizedPrev = previousSymptom.replace("疼痛", "痛").replace("疼", "痛");
            remaining = remaining.replace(normalizedPrev, " ");
            remaining = remaining.replace(previousSymptom.replace("痛", "疼"), " ");
        }
        remaining = remaining.replaceAll("[，,、。！!？?\\s]+", " ").trim();
        if (remaining.length() >= 4) {
            return true;
        }

        String[] detailPatterns = {
            "天", "小时", "周", "月", "分钟",
            "持续", "阵发", "隐隐", "剧烈", "胀痛", "刺痛", "钝痛",
            "饭后", "吃完", "运动", "晚上", "早晨", "夜间", "睡前",
            "加重", "缓解", "特别", "受不了", "越来越",
            "低烧", "发烧", "恶心", "呕吐", "头晕", "乏力",
            "左边", "右边", "上面", "下面", "前胸", "后背"
        };
        for (String pattern : detailPatterns) {
            if (symptoms.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeForMatching(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        result = result.replace("疼痛", "痛");
        result = result.replace("疼", "痛");
        return result;
    }

    private String extractSymptomKeyword(String normalizedSymptom) {
        if (normalizedSymptom == null || normalizedSymptom.isBlank()) {
            return null;
        }
        String trimmed = normalizedSymptom.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '，' || c == ',' || c == ' ' || c == '、') {
                return trimmed.substring(0, Math.min(i, 4));
            }
        }
        return trimmed.substring(0, Math.min(trimmed.length(), 3));
    }
}