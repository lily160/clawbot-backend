package com.github.wechat.cloudbot.platform.persistence;

import com.github.wechat.cloudbot.modules.chat.infra.DeepSeekProperties;
import com.github.wechat.cloudbot.modules.persona.service.PersonaService;
import com.github.wechat.cloudbot.platform.model.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 将历史消息组装为 LLM 请求 messages（人格、工具提示、识图等）。
 * file / jdbc 仓储共用，避免两套 prompt 逻辑分叉。
 */
@Component
public class ChatPromptAssembler {

    private static final String MEDIA_TOOL_HINT_PREFIX =
            "\n\n工具使用规则（必须遵守）："
                    + "用户问有什么功能/你会什么/能做什么/帮助/菜单 → 必须调用 list_capabilities，原样整理回复，禁止编造未列出的能力；"
                    + "天气/气温/是否下雨 → 必须调用 query_weather（传城市名），禁止凭记忆臆答；"
                    + "地点/周边商户 → query_poi（尽量带 street；每店用编号标题，子项用「• 📍地址/距离」「• 📞电话」「• 🔗 [查看地图](高德URL)」排版，禁止裸 URL）；"
                    + "地址转坐标 → geocode；"
                    + "用户给出具体网页链接要打开/总结 → browse_url；"
                    + "明确要求翻译 → translate；算术表达式 → calculate（汇率股价仍用 web_search）；"
                    + "明确要求纯文本附件→send_file；明确要求 Word/Excel/zip→export_document；"
                    + "其他实时/可验证事实（新闻、赛事结果、冠军、股价、当前政策、"
                    + "「今天/现在/最新/谁夺冠/比分」等），必须先调用 web_search，禁止凭记忆臆答。";
    private static final String MEDIA_TOOL_HINT_SUFFIX =
            "明确要求画图→generate_image；明确要求生成视频→generate_video。"
                    + "普通闲聊、主观建议才直接回复。"
                    + "query_weather 的 city 要具体；web_search 的 query 要具体（含年份/城市/赛事名）；"
                    + "query_poi 的 keywords/city 要具体。";

    private final PersonaService personaService;
    private final DeepSeekProperties properties;

    public ChatPromptAssembler(PersonaService personaService, DeepSeekProperties properties) {
        this.personaService = personaService;
        this.properties = properties;
    }

    /** DeepSeek 文本轮：system（含人格 + 媒体工具说明）+ history + 当前 user。 */
    public List<ChatMessage> buildMessages(
            String userId, List<ChatMessage> history, String currentUserContent) {
        List<ChatMessage> messages = new ArrayList<>();
        String systemPrompt = personaService.resolveSystemPrompt(userId);
        if (systemPrompt == null) {
            systemPrompt = "";
        }
        systemPrompt = systemPrompt + mediaToolHint();
        if (!systemPrompt.isBlank()) {
            messages.add(ChatMessage.system(systemPrompt));
        }
        messages.addAll(history);
        messages.add(ChatMessage.user(currentUserContent));
        return messages;
    }

    /** 博查润色：保留当前人格语气，但禁止用训练记忆覆盖搜索事实。 */
    public List<ChatMessage> buildSearchRefineMessages(String userId, String refineUserContent) {
        String persona = personaService.resolveSystemPrompt(userId);
        if (persona == null) {
            persona = "";
        }
        StringBuilder system = new StringBuilder();
        if (!persona.isBlank()) {
            system.append(persona.trim()).append('\n');
        }
        system.append(
                "本轮请依据用户消息中的【联网搜索结果】回答事实性问题；"
                        + "禁止用模型训练记忆覆盖搜索中的明确赛果/数据。"
                        + "语气必须符合上方人设；搜索只提供事实，不要变成百科播报口吻。"
                        + "若搜索结果与闲聊抱怨无关，可忽略结果、按人设直接安慰或吐槽。");
        List<ChatMessage> messages = new ArrayList<>(2);
        messages.add(ChatMessage.system(system.toString()));
        messages.add(ChatMessage.user(refineUserContent));
        return messages;
    }

    /**
     * 千问 VL 单轮识图（不带 DeepSeek 历史，避免把文本会话 system 混进 VL）。
     */
    public List<ChatMessage> buildVisionOnlyMessages(String dataUrl, String imagePrompt) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.userWithImage(dataUrl, imagePrompt));
        return messages;
    }

    /** 千问 VL 单轮识视频（不写 Base64 进历史）。 */
    public List<ChatMessage> buildVideoVisionOnlyMessages(
            String dataUrl, String videoPrompt, double fps) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.userWithVideo(dataUrl, videoPrompt, fps));
        return messages;
    }

    private String mediaToolHint() {
        return MEDIA_TOOL_HINT_PREFIX
                + "你的训练知识大约截止到 "
                + properties.getKnowledgeCutoffYearMonth()
                + "，此后发生的事件/赛果/新闻必须先 web_search；天气用 query_weather。"
                + MEDIA_TOOL_HINT_SUFFIX;
    }
}
