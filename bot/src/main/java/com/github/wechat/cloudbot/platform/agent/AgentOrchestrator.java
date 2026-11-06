package com.github.wechat.cloudbot.platform.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.wechat.cloudbot.modules.chat.api.LlmGateway;
import com.github.wechat.cloudbot.modules.chat.infra.DeepSeekProperties;
import com.github.wechat.cloudbot.modules.chat.service.TopicShiftDetector;
import com.github.wechat.cloudbot.modules.file.api.FileDeliveryService;
import com.github.wechat.cloudbot.modules.file.service.DocumentTextExtractor;
import com.github.wechat.cloudbot.modules.file.service.TextFileSupport;
import com.github.wechat.cloudbot.modules.media.api.MediaGateway;
import com.github.wechat.cloudbot.modules.persona.service.PersonaService;
import com.github.wechat.cloudbot.modules.search.api.SearchGateway;
import com.github.wechat.cloudbot.modules.search.service.RealtimeQueryDetector;
import com.github.wechat.cloudbot.modules.vision.infra.ImageDataUrls;
import com.github.wechat.cloudbot.modules.vision.infra.QwenClient;
import com.github.wechat.cloudbot.modules.vision.infra.QwenProperties;
import com.github.wechat.cloudbot.modules.vision.infra.VideoDataUrls;
import com.github.wechat.cloudbot.modules.voice.api.SpeechGateway;
import com.github.wechat.cloudbot.modules.voice.infra.DashScopeSpeechClient;
import com.github.wechat.cloudbot.modules.voice.infra.SilkAudioConverter;
import com.github.wechat.cloudbot.modules.voice.infra.VoiceProperties;
import com.github.wechat.cloudbot.modules.voice.service.ReplyModeService;
import com.github.wechat.cloudbot.modules.voice.service.VoiceCloneService;
import com.github.wechat.cloudbot.modules.weather.api.WeatherGateway;
import com.github.wechat.cloudbot.platform.channel.wechat.OutboundGateway;
import com.github.wechat.cloudbot.modules.health.common.HealthDisclaimers;
import com.github.wechat.cloudbot.modules.health.common.HealthToolNames;
import com.github.wechat.cloudbot.modules.health.report.domain.HealthIndicator;
import com.github.wechat.cloudbot.modules.health.report.domain.HealthReportParseRequest;
import com.github.wechat.cloudbot.modules.health.report.domain.HealthReportParseResult;
import com.github.wechat.cloudbot.modules.health.report.service.HealthReportParseService;
import com.github.wechat.cloudbot.modules.health.report.service.HealthReportRepository;
import com.github.wechat.cloudbot.modules.health.report.service.ReportTrendAnalyzer;
import com.github.wechat.cloudbot.modules.health.report.service.ReportWordExporter;
import com.github.wechat.cloudbot.platform.config.CloudbotProperties;
import com.github.wechat.cloudbot.platform.config.ErrorCode;
import com.github.wechat.cloudbot.platform.config.Strings;
import com.github.wechat.cloudbot.platform.model.ChatMessage;
import com.github.wechat.cloudbot.platform.model.LlmTurn;
import com.github.wechat.cloudbot.platform.persistence.ChatMemoryRepository;
import com.github.wechat.ilink.sdk.ILinkClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 编排层：Agent 中枢。文字走 Function Calling Agent Loop（ToolRegistry + 回灌）；
 * 识图/视频与出站 TTS 仍由本类调度。
 *
 * <p><b>警告：请使用微信小号测试。Cloudbot 存在封号风险。</b>
 */
@Service
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private static final String TOOL_GENERATE_IMAGE = "generate_image";
    private static final String TOOL_GENERATE_VIDEO = "generate_video";
    private static final String TOOL_SEND_FILE = "send_file";
    private static final String TOOL_EXPORT_DOCUMENT = "export_document";
    private static final String TOOL_WEB_SEARCH = "web_search";
    private static final String TOOL_QUERY_WEATHER = "query_weather";
    private static final Pattern AMAP_URI =
            Pattern.compile("https://uri\\.amap\\.com/[^\\s\\]）)]+");

    private final CloudbotProperties cloudbotProperties;
    private final DeepSeekProperties deepSeekProperties;
    private final QwenProperties qwenProperties;
    private final VoiceProperties voiceProperties;
    private final ReplyModeService replyModeService;
    private final LlmGateway llmGateway;
    private final QwenClient qwenClient;
    private final DashScopeSpeechClient speechClient;
    private final MediaGateway mediaGateway;
    private final FileDeliveryService fileDeliveryService;
    private final SearchGateway searchGateway;
    private final WeatherGateway weatherGateway;
    private final ToolRegistry toolRegistry;
    private final ChatMemoryRepository chatMemoryStore;
    private final PersonaService personaService;
    private final VoiceCloneService voiceCloneService;
    private final TopicShiftDetector topicShiftDetector;
    private final ExecutorService mediaGenerationExecutor;
    private final AgentProperties agentProperties;
    private final CircuitBreaker circuitBreaker;
    private final List<AgentLoopInterceptor> interceptors;
    private final OutboundGateway outboundGateway;
    private final HealthReportParseService healthReportParseService;
    private final HealthReportRepository healthReportRepository;
    private final ReportTrendAnalyzer reportTrendAnalyzer;
    private final ReportWordExporter reportWordExporter;
    private final ObjectMapper objectMapper;

    public AgentOrchestrator(
            CloudbotProperties cloudbotProperties,
            DeepSeekProperties deepSeekProperties,
            QwenProperties qwenProperties,
            VoiceProperties voiceProperties,
            ReplyModeService replyModeService,
            LlmGateway llmGateway,
            QwenClient qwenClient,
            DashScopeSpeechClient speechClient,
            MediaGateway mediaGateway,
            FileDeliveryService fileDeliveryService,
            SearchGateway searchGateway,
            WeatherGateway weatherGateway,
            ToolRegistry toolRegistry,
            ChatMemoryRepository chatMemoryStore,
            PersonaService personaService,
            VoiceCloneService voiceCloneService,
            TopicShiftDetector topicShiftDetector,
            @Qualifier("mediaGenerationExecutor") ExecutorService mediaGenerationExecutor,
            AgentProperties agentProperties,
            CircuitBreaker circuitBreaker,
            List<AgentLoopInterceptor> interceptors,
            OutboundGateway outboundGateway,
            HealthReportParseService healthReportParseService,
            HealthReportRepository healthReportRepository,
            ReportTrendAnalyzer reportTrendAnalyzer,
            ReportWordExporter reportWordExporter,
            ObjectMapper objectMapper) {
        this.cloudbotProperties = cloudbotProperties;
        this.deepSeekProperties = deepSeekProperties;
        this.qwenProperties = qwenProperties;
        this.voiceProperties = voiceProperties;
        this.replyModeService = replyModeService;
        this.llmGateway = llmGateway;
        this.qwenClient = qwenClient;
        this.speechClient = speechClient;
        this.mediaGateway = mediaGateway;
        this.fileDeliveryService = fileDeliveryService;
        this.searchGateway = searchGateway;
        this.weatherGateway = weatherGateway;
        this.toolRegistry = toolRegistry;
        this.chatMemoryStore = chatMemoryStore;
        this.personaService = personaService;
        this.voiceCloneService = voiceCloneService;
        this.topicShiftDetector = topicShiftDetector;
        this.mediaGenerationExecutor = mediaGenerationExecutor;
        this.agentProperties = agentProperties;
        this.circuitBreaker = circuitBreaker;
        this.circuitBreaker.configure(agentProperties.getCircuitBreaker());
        this.interceptors = interceptors;
        this.outboundGateway = outboundGateway;
        this.healthReportParseService = healthReportParseService;
        this.healthReportRepository = healthReportRepository;
        this.reportTrendAnalyzer = reportTrendAnalyzer;
        this.reportWordExporter = reportWordExporter;
        this.objectMapper = objectMapper;
    }

    /**
     * @param inboundWasVoice 入站是否为语音（影响 mirror 模式下是否 TTS 回复）
     */
    public void reply(
            ILinkClient client, String toUserId, String userContent, boolean inboundWasVoice) {
        Optional<String> personaAck =
                personaService.tryHandleSwitchCommand(toUserId, userContent);
        if (personaAck.isPresent()) {
            sendChatReply(client, toUserId, personaAck.get(), false);
            return;
        }

        ReplyModeService.ParsedReplyCommand parsedReplyCommand =
                replyModeService.parseEmbeddedCommand(userContent);
        if (parsedReplyCommand.modeChanged()) {
            if (parsedReplyCommand.content() == null || parsedReplyCommand.content().isBlank()) {
                String ackText =
                        parsedReplyCommand.wantsVoice()
                                ? "已切换为语音回复模式。你之后的文字回复会尽量转成语音发给你。"
                                : "已切换为文字回复模式。之后我会正常发文字。";
                sendText(client, toUserId, ackText);
                return;
            }
            userContent = parsedReplyCommand.content();
            inboundWasVoice = parsedReplyCommand.wantsVoice();
        } else {
            Optional<String> replyModeAck = replyModeService.tryHandleCommand(toUserId, userContent);
            if (replyModeAck.isPresent()) {
                sendText(client, toUserId, replyModeAck.get());
                return;
            }
            Optional<ReplyModeService.IntentResult> llmIntent = replyModeService.detectVoiceInstruction(userContent);
            if (llmIntent.isPresent()) {
                ReplyModeService.IntentResult intent = llmIntent.get();
                userContent = intent.content() == null || intent.content().isBlank() ? userContent : intent.content();
                inboundWasVoice = intent.wantsVoice();
            }
        }

        Optional<String> voiceCloneAck =
                voiceCloneService.tryHandleTextCommand(toUserId, userContent);
        if (voiceCloneAck.isPresent()) {
            sendText(client, toUserId, voiceCloneAck.get());
            return;
        }

        // 「有什么功能」等：直接回能力清单，避免模型漏调 tool 或编造
        if (CapabilityCatalog.looksLikeCapabilityQuery(userContent)) {
            String summary =
                    CapabilityCatalog.userFacingSummary(toolRegistry.enabledNames());
            chatMemoryStore.appendTurn(toUserId, userContent, summary);
            sendChatReply(client, toUserId, summary, inboundWasVoice);
            return;
        }

        Optional<String> reservedHealthReply = handleReservedHealthFlow(toUserId, userContent);
        if (reservedHealthReply.isPresent()) {
            sendText(client, toUserId, reservedHealthReply.get());
            return;
        }

        handleTextWithAgentLoop(client, toUserId, userContent, inboundWasVoice);
    }

    /** 用户发来的图片：千问识图 → DeepSeek 优化。 */
    public void replyWithImage(ILinkClient client, String toUserId, byte[] imageBytes) {
        sendText(client, toUserId, generateVisionReply(toUserId, imageBytes));
    }

    /** 用户发来的视频：千问理解 → DeepSeek 优化。 */
    public void replyWithVideo(ILinkClient client, String toUserId, byte[] videoBytes) {
        sendText(client, toUserId, "收到视频，正在理解内容，请稍候…");
        sendText(client, toUserId, generateVideoVisionReply(toUserId, videoBytes));
    }

    /**
     * 用户发来的文件：文本/docx/xlsx/zip/pdf 解析后注入对话。
     * PDF 优先按体检报告做结构化解析（指标识别 + 偏离项），解析不到指标时回退通用文本提取。
     */
    public void replyWithFile(
            ILinkClient client, String toUserId, String fileName, byte[] fileBytes) {
        CloudbotProperties.File fileCfg = cloudbotProperties.getFile();
        if (!fileCfg.isEnabled()) {
            sendText(client, toUserId, "文件功能未开启。");
            return;
        }

        String name = fileName == null || fileName.isBlank() ? "file" : fileName.trim();
        int size = fileBytes == null ? 0 : fileBytes.length;
        String sizeLabel = TextFileSupport.formatSizeKb(size);

        if (fileBytes == null || size == 0) {
            sendText(client, toUserId, "收到空文件「" + name + "」，没有可读内容。");
            return;
        }

        int maxIn = Math.max(1024, fileCfg.getMaxInboundBytes());
        if (size > maxIn) {
            sendText(
                    client,
                    toUserId,
                    "已收到文件「"
                            + name
                            + "」（约 "
                            + sizeLabel
                            + "），超过可解析上限（"
                            + TextFileSupport.formatSizeKb(maxIn)
                            + "），请缩小后再发。");
            return;
        }

        String decoded;
        try {
            if (DocumentTextExtractor.isOfficeOrZip(name)) {
                // PDF 优先走健康报告结构化解析，提取失败再兜底通用文本提取
                if ("pdf".equals(TextFileSupport.extensionOf(name))) {
                    Optional<HealthReportFile> healthResult = tryParseHealthReport(toUserId, name, fileBytes);
                    if (healthResult.isPresent()) {
                        HealthReportFile report = healthResult.get();
                        // 先发文字预览，告知用户概要
                        sendText(client, toUserId, report.textPreview());
                        // 再发 Word 文档
                        String docxName = name.replaceAll("(?i)\\.pdf$", "") + "-解析报告.docx";
                        outboundGateway.sendFile(client, toUserId, report.docxBytes(), docxName,
                                "体检报告解析报告");
                        chatMemoryStore.appendTurn(toUserId, "", "[体检报告已解析] " + report.textPreview());
                        log.info("体检报告已回复Word+文字 userId={}, name={}, docxBytes={}",
                                toUserId, name, report.docxBytes().length);
                        return;
                    }
                    // 不是体检报告或解析失败 → 继续走通用 PDF 文本提取
                }
                DocumentTextExtractor.Limits lim =
                        new DocumentTextExtractor.Limits(
                                fileCfg.getMaxZipEntries(),
                                fileCfg.getMaxZipEntryBytes(),
                                fileCfg.getMaxXlsxRows(),
                                fileCfg.getMaxXlsxCols());
                decoded =
                        DocumentTextExtractor.extract(name, fileBytes, lim).orElse(null);
                if (decoded == null || decoded.isBlank()) {
                    sendText(
                            client,
                            toUserId,
                            "已收到文件「" + name + "」，但未能提取到可读文本（可能为空或加密）。");
                    return;
                }
            } else if (TextFileSupport.isTextExtension(name)) {
                decoded = TextFileSupport.decodeText(fileBytes);
                if (decoded == null) {
                    sendText(client, toUserId, "已收到文件「" + name + "」，但无法解码文本内容。");
                    return;
                }
            } else {
                sendText(
                        client,
                        toUserId,
                        "已收到文件「"
                                + name
                                + "」（约 "
                                + sizeLabel
                                + "），暂不支持解析该格式。"
                                + "可发 txt/md/json/csv，或 pdf/docx/xlsx/zip。");
                return;
            }
        } catch (Exception e) {
            log.error("文件解析失败 name={}: {}", name, e.getMessage(), e);
            sendText(client, toUserId, "文件「" + name + "」解析失败，请稍后重试");
            return;
        }

        String body = TextFileSupport.truncateForPrompt(decoded, fileCfg.getMaxPromptChars());
        String userContent = "[用户发送了文件 " + name + "，内容如下]\n" + body;
        log.info(
                "文件注入对话 userId={}, name={}, bytes={}, promptChars={}",
                toUserId,
                name,
                size,
                body.length());
        handleTextWithAgentLoop(client, toUserId, userContent, false);
    }

    /** Word 导出结果 */
    private record HealthReportFile(byte[] docxBytes, String textPreview) {}

    /**
     * 尝试将 PDF 按体检报告做结构化解析 + 导出 Word 文档。
     * 解析不到指标时返回空，由上层回退为通用 PDF 文本提取。
     */
    private Optional<HealthReportFile> tryParseHealthReport(String toUserId, String fileName, byte[] fileBytes) {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("health-report-", ".pdf");
            Files.write(tempFile, fileBytes);

            HealthReportParseResult result = healthReportParseService.parse(
                    new HealthReportParseRequest(tempFile, fileName));

            if (result.indicators().isEmpty()) {
                log.info("健康报告解析：未提取到指标，回退通用文本 name={}", fileName);
                return Optional.empty();
            }

            // 持久化存储
            String reportJson = objectMapper.writeValueAsString(result);
            long reportId = 0;
            try {
                reportId = healthReportRepository.saveReportWithIndicators(toUserId, fileName, result, reportJson);
                log.info("报告已存储 reportId={}", reportId);
            } catch (Exception e) {
                log.warn("报告存储失败（不影响主流程）: {}", e.getMessage());
            }

            // 趋势对比
            java.util.Map<String, HealthIndicator> lastIndicators = null;
            try { lastIndicators = healthReportRepository.getLastReportIndicators(toUserId); } catch (Exception ignored) {}
            List<ReportTrendAnalyzer.TrendItem> trends = reportTrendAnalyzer.analyze(result.indicators(), lastIndicators);
            ReportTrendAnalyzer.FollowUpSuggestions followUp = reportTrendAnalyzer.suggest(result.abnormalIndicators(), trends);

            // 导出 Word（含趋势+复查建议+后续管理）
            byte[] docxBytes = reportWordExporter.export(result, trends, followUp);

            // 生成文字预览（先于 Word 发送，告知用户概要情况）
            long total = result.indicators().size();
            long abnormalCount = result.abnormalIndicators().size();
            long unknown = result.indicators().stream()
                    .filter(i -> i.status() == com.github.wechat.cloudbot.modules.health.report.domain.IndicatorStatus.UNKNOWN)
                    .count();
            long high = result.indicators().stream()
                    .filter(i -> i.status() == com.github.wechat.cloudbot.modules.health.report.domain.IndicatorStatus.HIGH).count();
            long low = result.indicators().stream()
                    .filter(i -> i.status() == com.github.wechat.cloudbot.modules.health.report.domain.IndicatorStatus.LOW).count();

            StringBuilder preview = new StringBuilder();
            preview.append("体检报告解析完成，共 ").append(result.pageCount()).append(" 页，识别 ").append(total).append(" 项指标");
            if (abnormalCount > 0) {
                preview.append("\n偏离参考范围 ").append(abnormalCount).append(" 项（偏高 ").append(high).append("、偏低 ").append(low).append("），详见 Word 文档");
            } else {
                preview.append("\n未发现明显偏离项");
            }
            if (unknown > 0) {
                preview.append("\n有 ").append(unknown).append(" 项指标缺少参考范围，无法判断是否偏离，建议人工核对");
            }
            // 趋势信息
            if (trends != null && !trends.isEmpty()) {
                long worse = trends.stream().filter(t -> "加重".equals(t.direction()) || "新增异常".equals(t.direction())).count();
                long better = trends.stream().filter(t -> "好转".equals(t.direction()) || "改善".equals(t.direction())).count();
                if (worse > 0 || better > 0) {
                    preview.append("\n与上次报告对比：");
                    if (worse > 0) preview.append(worse).append(" 项加重/新增，");
                    if (better > 0) preview.append(better).append(" 项改善，");
                    preview.append("详见 Word 文档");
                }
            }
            // 复查提醒提示（首次报告引导）
            preview.append("\n回复[设置复查提醒]可预约复查通知");
            String textPreview = preview.toString();

            log.info("健康报告解析成功 name={}, pages={}, indicators={}, abnormal={}, docxBytes={}",
                    fileName, result.pageCount(), total, abnormalCount, docxBytes.length);
            return Optional.of(new HealthReportFile(docxBytes, textPreview));
        } catch (Exception e) {
            log.warn("健康报告解析异常，回退通用文本 name={}: {}", fileName, e.getMessage());
            return Optional.empty();
        } finally {
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
            }
        }
    }

    private Optional<String> handleReservedHealthFlow(String toUserId, String userContent) {
        return Optional.empty();
    }

    private void handleTextWithAgentLoop(
            ILinkClient client, String toUserId, String userContent, boolean inboundWasVoice) {
        if (!deepSeekProperties.isEnabled() || !deepSeekProperties.hasApiKey()) {
            log.warn("DeepSeek 未启用或 api-key 为空，使用固定兜底文案");
            sendChatReply(client, toUserId, cloudbotProperties.getAutoReplyText(), inboundWasVoice);
            return;
        }

        try {
            boolean freshTopic = topicShiftDetector.clearHistoryIfNewTopic(toUserId, userContent);
            List<ChatMessage> messages =
                    new ArrayList<>(chatMemoryStore.buildMessages(toUserId, userContent));
            log.info(
                    "Agent Loop 开始 userId={}, persona={}, freshTopic={}, in={}",
                    toUserId,
                    personaService.currentPersonaId(toUserId).orElse("默认"),
                    freshTopic,
                    Strings.summarizeOneLine(userContent));

            AgentLoopContext ctx = new AgentLoopContext(
                    client, toUserId, userContent, messages,
                    agentProperties.getLoop().getMaxRounds(),
                    agentProperties.getLoop().getTimeoutMs());

            for (int round = 1; round <= ctx.getMaxRounds(); round++) {
                ctx.setCurrentRound(round);

                // 超时检查
                if (ctx.isTimeout()) {
                    log.warn("[Agent] 循环超时 userId={} round={}/{}", toUserId, round, ctx.getMaxRounds());
                    interceptors.forEach(i -> i.onComplete(ctx, ""));
                    String text = cloudbotProperties.getAutoReplyText();
                    chatMemoryStore.appendTurn(toUserId, userContent, text);
                    sendChatReply(client, toUserId, text, inboundWasVoice);
                    return;
                }

                // 熔断检查
                if (!circuitBreaker.allowRequest(toUserId)) {
                    log.warn("[Agent] 熔断阻止 LLM 调用 userId={}", toUserId);
                    String text = cloudbotProperties.getAutoReplyText();
                    chatMemoryStore.appendTurn(toUserId, userContent, text);
                    sendChatReply(client, toUserId, text, inboundWasVoice);
                    return;
                }

                boolean enableTools = round < ctx.getMaxRounds();

                // pre LLM 拦截器
                boolean proceed = true;
                for (AgentLoopInterceptor interceptor : interceptors) {
                    if (!interceptor.preLlmCall(ctx)) {
                        proceed = false;
                        break;
                    }
                }
                if (!proceed) {
                    circuitBreaker.onSuccess(toUserId);
                    String text = cloudbotProperties.getAutoReplyText();
                    chatMemoryStore.appendTurn(toUserId, userContent, text);
                    sendChatReply(client, toUserId, text, inboundWasVoice);
                    for (AgentLoopInterceptor interceptor : interceptors) {
                        interceptor.onComplete(ctx, text);
                    }
                    return;
                }

                // LLM 调用（带重试）
                LlmTurn turn = llmCallWithRetry(messages, enableTools, toUserId);

                // post LLM 拦截器
                for (AgentLoopInterceptor interceptor : interceptors) {
                    interceptor.postLlmCall(ctx, turn);
                }

                if (turn == null) {
                    // LLM 调用完全失败
                    circuitBreaker.onFailure(toUserId);
                    String text = cloudbotProperties.getAutoReplyText();
                    chatMemoryStore.appendTurn(toUserId, userContent, text);
                    sendChatReply(client, toUserId, text, inboundWasVoice);
                    return;
                }

                if (!turn.hasToolCalls()) {
                    circuitBreaker.onSuccess(toUserId);
                    if (round == 1) {
                        Optional<ToolCall> fallback = synthesizeFallbackToolCall(userContent);
                        if (fallback.isPresent()) {
                            ToolCall call = fallback.get();
                            log.warn(
                                    "LLM 未调 tool，启发式兜底 name={}, userId={}, in={}",
                                    call.name(), toUserId,
                                    Strings.summarizeOneLine(userContent));
                            if (dispatchToolCallsIntercepted(ctx, client, toUserId, userContent, messages,
                                    List.of(call), turn.content())) {
                                continue;
                            }
                            return;
                        }
                    }
                    String text = sanitizeAssistantReply(turn.content());
                    if (text == null || text.isBlank()) {
                        text = cloudbotProperties.getAutoReplyText();
                    }
                    text = ensureAmapLinksInReply(text, messages);
                    chatMemoryStore.appendTurn(toUserId, userContent, text);
                    sendChatReply(client, toUserId, text, inboundWasVoice);

                    // onComplete 拦截器
                    for (AgentLoopInterceptor interceptor : interceptors) {
                        interceptor.onComplete(ctx, text);
                    }
                    return;
                }

                log.info("Agent Loop round={} tools={} userId={}",
                        round,
                        turn.toolCalls().stream().map(ToolCall::name).toList(),
                        toUserId);

                // 分发工具（经过拦截器）
                boolean continueLoop = dispatchToolCallsIntercepted(ctx, client, toUserId,
                        userContent, messages, turn.toolCalls(), turn.content());
                if (continueLoop) {
                    circuitBreaker.onSuccess(toUserId);
                    continue;
                }
                return;
            }

            LlmTurn last = llmGateway.chatTurn(messages, false);
            // 先判空再取 content：chatTurn 重试耗尽返回 null 时走固定文案，同时正确触发 onFailure
            String text = last == null
                    ? cloudbotProperties.getAutoReplyText()
                    : sanitizeAssistantReply(last.hasText() ? last.content() : cloudbotProperties.getAutoReplyText());
            text = ensureAmapLinksInReply(text, messages);
            chatMemoryStore.appendTurn(toUserId, userContent, text);
            sendChatReply(client, toUserId, text, inboundWasVoice);

            if (last != null) {
                circuitBreaker.onSuccess(toUserId);
            } else {
                circuitBreaker.onFailure(toUserId);
            }

            for (AgentLoopInterceptor interceptor : interceptors) {
                interceptor.onComplete(ctx, text);
            }

        } catch (Exception e) {
            log.error("[{}]Agent Loop 失败 userId={}: {}",
                    ErrorCode.AGENT_LOOP_ERROR.prefix(), toUserId, e.getMessage(), e);
            for (AgentLoopInterceptor interceptor : interceptors) {
                interceptor.onError(null, e);
            }
            sendChatReply(client, toUserId, cloudbotProperties.getAutoReplyText(), inboundWasVoice);
        }
    }

    /** LLM 调用带重试逻辑。返回 null 表示全部重试都失败。 */
    private LlmTurn llmCallWithRetry(List<ChatMessage> messages, boolean enableTools, String userId) {
        int maxAttempts = agentProperties.getRetry().getMaxAttempts();
        long baseDelay = agentProperties.getRetry().getBaseDelayMs();
        Throwable lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return llmGateway.chatTurn(messages, enableTools);
            } catch (Exception e) {
                lastError = e;
                log.warn("[Agent] LLM 调用失败 attempt={}/{} userId={}: {}",
                        attempt, maxAttempts, userId, e.getMessage());
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(baseDelay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            }
        }

        log.error("[{}]LLM 调用重试耗尽 userId={}: {}",
                ErrorCode.CHAT_API_ERROR.prefix(), userId, lastError != null ? lastError.getMessage() : "unknown");
        return null;
    }

    /** 经过拦截器链的工具分发。 */
    private boolean dispatchToolCallsIntercepted(
            AgentLoopContext ctx, ILinkClient client, String toUserId,
            String userContent, List<ChatMessage> messages,
            List<ToolCall> toolCalls, String assistantContent) {

        List<ChatMessage.FunctionCall> functionCalls = new ArrayList<>(toolCalls.size());
        for (ToolCall call : toolCalls) {
            functionCalls.add(new ChatMessage.FunctionCall(call.id(), call.name(), call.argumentsJson()));
        }
        messages.add(ChatMessage.assistantWithToolCalls(assistantContent, functionCalls));

        boolean continueLoop = false;
        for (ToolCall rawCall : toolCalls) {
            ToolCall call = enrichToolCall(rawCall, userContent, toUserId);

            // preToolDispatch 拦截器
            boolean proceed = true;
            for (AgentLoopInterceptor interceptor : interceptors) {
                if (!interceptor.preToolDispatch(ctx, call)) {
                    proceed = false;
                    break;
                }
            }
            if (!proceed) continue;

            if (TOOL_GENERATE_IMAGE.equals(call.name()) || TOOL_GENERATE_VIDEO.equals(call.name())) {
                handleMediaSideEffect(client, toUserId, userContent, call);
                messages.add(ChatMessage.tool(call.id(),
                        TOOL_GENERATE_IMAGE.equals(call.name())
                                ? "图片生成任务已提交，稍后会发给用户。"
                                : "视频生成任务已提交，稍后会发给用户。"));
                for (AgentLoopInterceptor interceptor : interceptors) {
                    interceptor.postToolDispatch(ctx, call,
                            ToolResult.ok(call.id(), call.name(), "dispatched asynchronously"));
                }
                continue;
            }

            if (TOOL_SEND_FILE.equals(call.name()) || TOOL_EXPORT_DOCUMENT.equals(call.name())) {
                handleFileSideEffect(client, toUserId, userContent, call);
                messages.add(ChatMessage.tool(call.id(), "文件已发送给用户。"));
                for (AgentLoopInterceptor interceptor : interceptors) {
                    interceptor.postToolDispatch(ctx, call,
                            ToolResult.ok(call.id(), call.name(), "file sent"));
                }
                continue;
            }

            ToolResult result;
            try {
                result = toolRegistry.execute(call);
            } catch (Exception e) {
                result = ToolResult.fail(call.id(), call.name(), e.getMessage());
            }

            // postToolDispatch 拦截器
            for (AgentLoopInterceptor interceptor : interceptors) {
                interceptor.postToolDispatch(ctx, call, result);
            }

            String content = result.content() == null
                    ? (result.success() ? "" : "tool failed")
                    : result.content();
            if (!result.success()) {
                content = "工具执行失败：" + content;
            }
            messages.add(ChatMessage.tool(call.id(), content));
            continueLoop = true;
            log.info("Tool 执行完成 name={}, ok={}, preview={}",
                    call.name(), result.success(),
                    Strings.summarizeOneLine(content, 200));
        }

        boolean onlySideEffect = toolCalls.stream().allMatch(AgentOrchestrator::isOutboundSideEffectTool);
        if (onlySideEffect) return false;
        return continueLoop;
    }

    private static boolean isOutboundSideEffectTool(ToolCall c) {
        String n = c.name();
        return TOOL_GENERATE_IMAGE.equals(n)
                || TOOL_GENERATE_VIDEO.equals(n)
                || TOOL_SEND_FILE.equals(n)
                || TOOL_EXPORT_DOCUMENT.equals(n);
    }

    private void handleFileSideEffect(
            ILinkClient client, String toUserId, String userContent, ToolCall call) {
        try {
            FileDeliveryService.OutboundFile file =
                    TOOL_SEND_FILE.equals(call.name())
                            ? fileDeliveryService.buildSendFile(call.argumentsJson())
                            : fileDeliveryService.buildExportDocument(call.argumentsJson());
            outboundGateway.sendFile(
                    client, toUserId, file.bytes(), file.fileName(), file.caption());
            String memoryNote =
                    "[已发送文件] " + file.fileName() + "（" + file.bytes().length + " bytes）";
            chatMemoryStore.appendTurn(toUserId, userContent, memoryNote);
            log.info(
                    "已发送文件 to={}, name={}, bytes={}",
                    toUserId,
                    file.fileName(),
                    file.bytes().length);
        } catch (Exception e) {
            log.error("文件发送失败 to={}: {}", toUserId, e.getMessage(), e);
            sendText(client, toUserId, "文件发送失败，请稍后重试");
        }
    }

    private void handleMediaSideEffect(
            ILinkClient client, String toUserId, String userContent, ToolCall call) {
        String prompt = readArg(call, "prompt");
        if (prompt.isBlank()) {
            sendText(client, toUserId, "生成失败：缺少 prompt。");
            return;
        }

        if (TOOL_GENERATE_IMAGE.equals(call.name())) {
            if (!mediaGateway.canGenerateImage()) {
                sendText(
                        client,
                        toUserId,
                        "生图服务未配置（请设置 ARK_API_KEY 与 volcengine.image-model）。");
                return;
            }
            mediaGenerationExecutor.execute(
                    () -> {
                        try {
                            byte[] imageBytes = mediaGateway.generateImage(prompt);
                            outboundGateway.sendImage(
                                    client, toUserId, imageBytes, "gen.png", null);
                            chatMemoryStore.appendTurn(
                                    toUserId,
                                    userContent,
                                    "[已生成图片] prompt=" + Strings.summarizeOneLine(prompt));
                            log.info(
                                    "火山生图并发送成功 to={}, bytes={}",
                                    toUserId,
                                    imageBytes.length);
                        } catch (Exception e) {
                            log.error("火山生图失败 to={}: {}", toUserId, e.getMessage(), e);
                            sendText(client, toUserId, "图片生成失败，请稍后重试");
                        }
                    });
            return;
        }

        if (!mediaGateway.canGenerateVideo()) {
            sendText(
                    client,
                    toUserId,
                    "生视频服务未配置（请设置 ARK_API_KEY 与 volcengine.video-model）。");
            return;
        }
        mediaGenerationExecutor.execute(
                () -> {
                    try {
                        MediaGateway.GeneratedVideo video = mediaGateway.generateVideo(prompt);
                        outboundGateway.sendVideo(
                                client,
                                toUserId,
                                video.bytes(),
                                "gen.mp4",
                                video.playLengthMs(),
                                null);
                        chatMemoryStore.appendTurn(
                                toUserId,
                                userContent,
                                "[已生成视频] prompt=" + Strings.summarizeOneLine(prompt));
                        log.info(
                                "火山生视频并发送成功 to={}, bytes={}",
                                toUserId,
                                video.bytes().length);
                    } catch (Exception e) {
                        log.error("火山生视频失败 to={}: {}", toUserId, e.getMessage(), e);
                        sendText(client, toUserId, "视频生成失败，请稍后重试");
                    }
                });
    }

    private Optional<ToolCall> synthesizeFallbackToolCall(String userContent) {
        if (CapabilityCatalog.looksLikeCapabilityQuery(userContent)
                && toolRegistry.isEnabled(ListCapabilitiesTool.TOOL_NAME)) {
            return Optional.of(
                    syntheticCall(ListCapabilitiesTool.TOOL_NAME, objectNode -> {}));
        }

        if (weatherGateway.isAvailable()
                && RealtimeQueryDetector.likelyNeedsWeather(userContent)) {
            String city = RealtimeQueryDetector.extractWeatherCity(userContent);
            if (city != null && !city.isBlank()) {
                if (weatherGateway.looksOverseas(city)
                        || weatherGateway.looksOverseas(userContent)) {
                    if (searchGateway.isAvailable()) {
                        return Optional.of(
                                syntheticCall(
                                        TOOL_WEB_SEARCH,
                                        objectNode -> {
                                            objectNode.put("query", city + " 天气");
                                            objectNode.put("freshness", "oneDay");
                                            objectNode.put("caption", "好的，我帮你联网查一下海外天气…");
                                            objectNode.put("question", userContent);
                                        }));
                    }
                    return Optional.empty();
                }
                return Optional.of(
                        syntheticCall(
                                TOOL_QUERY_WEATHER,
                                objectNode -> {
                                    objectNode.put("city", city);
                                    objectNode.put(
                                            "extensions",
                                            RealtimeQueryDetector.guessWeatherExtensions(
                                                    userContent));
                                    objectNode.put("caption", "好的，我帮你查一下天气…");
                                    objectNode.put("question", userContent);
                                }));
            }
        }

        RealtimeQueryDetector.SearchDecision fallback =
                RealtimeQueryDetector.analyze(
                        userContent, deepSeekProperties.getKnowledgeCutoffYearMonth());
        if (searchGateway.isAvailable() && fallback.needsSearch()) {
            return Optional.of(
                    syntheticCall(
                            TOOL_WEB_SEARCH,
                            objectNode -> {
                                objectNode.put(
                                        "query",
                                        RealtimeQueryDetector.buildSearchQuery(userContent));
                                objectNode.put(
                                        "freshness",
                                        RealtimeQueryDetector.guessFreshness(userContent));
                                objectNode.put("caption", "好的，我先帮你查一下…");
                                objectNode.put("question", userContent);
                            }));
        }
        return Optional.empty();
    }

    private ToolCall syntheticCall(String name, java.util.function.Consumer<ObjectNode> filler) {
        ObjectNode node = objectMapper.createObjectNode();
        filler.accept(node);
        return new ToolCall("fallback_" + name, name, node.toString());
    }

    /** 合并 enrichWithUserId + enrichWithQuestion：一次 JSON 解析完成两类注入。 */
    private ToolCall enrichToolCall(ToolCall call, String userContent, String userId) {
        try {
            JsonNode root =
                    objectMapper.readTree(
                            call.argumentsJson() == null ? "{}" : call.argumentsJson());
            ObjectNode obj =
                    root.isObject()
                            ? (ObjectNode) root
                            : objectMapper.createObjectNode();
            if (!obj.hasNonNull("question") || obj.get("question").asText("").isBlank()) {
                obj.put("question", userContent);
            }
            if (!obj.hasNonNull("user_id") || obj.get("user_id").asText("").isBlank()) {
                obj.put("user_id", userId);
            }
            return new ToolCall(call.id(), call.name(), objectMapper.writeValueAsString(obj), userId);
        } catch (Exception e) {
            return new ToolCall(call.id(), call.name(), call.argumentsJson(), userId);
        }
    }

    private String readArg(ToolCall call, String field) {
        try {
            JsonNode args =
                    objectMapper.readTree(
                            call.argumentsJson() == null ? "{}" : call.argumentsJson());
            JsonNode n = args.get(field);
            return n == null || n.isNull() ? "" : n.asText("").trim();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 按 reply-mode 发送文字或 TTS 音频。
     * 默认 TTS → sendFile（音频附件）；iLink 语音气泡常被平台静默丢弃。
     */
    private void sendChatReply(
            ILinkClient client, String toUserId, String replyText, boolean inboundWasVoice) {
        boolean wantVoice =
                voiceProperties.isTtsEnabled()
                        && (replyModeService.shouldReplyInVoice(toUserId)
                                || voiceProperties.preferVoiceReply(inboundWasVoice));
        if (!wantVoice) {
            sendText(client, toUserId, replyText);
            return;
        }

        try {
            Optional<VoiceCloneService.StoredCustomVoice> custom =
                    voiceCloneService.getCustomVoice(toUserId);
            SpeechGateway.SynthesizedSpeech speech;
            if (custom.isPresent()) {
                VoiceCloneService.StoredCustomVoice cv = custom.get();
                speech =
                        speechClient.synthesizeWithCustomVoice(
                                replyText, cv.voiceId(), cv.targetModel());
            } else {
                String ttsInstruct = personaService.resolveTtsInstructions(toUserId).orElse(null);
                String systemVoice = voiceCloneService.resolveSystemVoice(toUserId);
                speech = speechClient.synthesize(replyText, ttsInstruct, systemVoice);
            }
            if (voiceProperties.useFileOutbound()) {
                String fileName = guessAudioFileName(speech.bytes());
                outboundGateway.sendFile(client, toUserId, speech.bytes(), fileName, replyText);
                log.info(
                        "发送语音附件回复 to {}，persona={}, systemVoice={}, customVoice={}, file={}, bytes={}, alsoText={}",
                        toUserId,
                        personaService.currentPersonaId(toUserId).orElse("默认"),
                        voiceCloneService.resolveSystemVoice(toUserId),
                        custom.map(VoiceCloneService.StoredCustomVoice::voiceId).orElse("-"),
                        fileName,
                        speech.bytes().length,
                        voiceProperties.isAlsoSendText());
            } else {
                SilkAudioConverter.SilkVoice silk =
                        SilkAudioConverter.toSilk(
                                speech.bytes(), speech.sampleRate(), speech.playLengthMs());
                outboundGateway.sendVoice(
                        client,
                        toUserId,
                        silk.silkBytes(),
                        "reply.silk",
                        silk.playLengthMs(),
                        silk.sampleRate(),
                        null,
                        voiceProperties.getEncodeType(),
                        16,
                        replyText);
                log.info(
                        "发送语音气泡回复 to {}，silkBytes={}, playMs={}（平台可能不显示）",
                        toUserId,
                        silk.silkBytes().length,
                        silk.playLengthMs());
            }
            if (voiceProperties.isAlsoSendText()) {
                sendText(client, toUserId, replyText);
            }
        } catch (Exception e) {
            log.error("TTS/语音发送失败，回退文字 to={}: {}", toUserId, e.getMessage(), e);
            sendText(client, toUserId, replyText);
        }
    }

    private static String guessAudioFileName(byte[] audio) {
        if (audio != null && audio.length >= 4) {
            if (audio[0] == 'R' && audio[1] == 'I' && audio[2] == 'F' && audio[3] == 'F') {
                return "reply.wav";
            }
            if (audio[0] == 'I' && audio[1] == 'D' && audio[2] == '3') {
                return "reply.mp3";
            }
            if ((audio[0] & 0xFF) == 0xFF && ((audio[1] & 0xE0) == 0xE0)) {
                return "reply.mp3";
            }
            if (audio[0] == 'O' && audio[1] == 'g' && audio[2] == 'g' && audio[3] == 'S') {
                return "reply.ogg";
            }
        }
        return "reply.wav";
    }

    private void sendText(ILinkClient client, String toUserId, String replyText) {
        outboundGateway.sendText(client, toUserId, replyText);
    }

    /**
     * 保证回复含高德导航，且统一为 Markdown 短链 {@code [查看地图](url)}（微信可渲染为蓝色短文案）。
     * <ul>
     *   <li>若模型漏贴链接：从本轮 tool 结果补回；
     *   <li>若模型贴了裸 URL：改写成短链，避免长串 percent-encode 刷屏。
     * </ul>
     */
    static String ensureAmapLinksInReply(String reply, List<ChatMessage> messages) {
        List<String> links = extractAmapLinksFromToolMessages(messages);
        String text = reply == null ? "" : reply;
        if (!text.contains("uri.amap.com") && !links.isEmpty()) {
            StringBuilder sb = new StringBuilder(text.trim());
            sb.append("\n\n导航：");
            int i = 1;
            for (String link : links) {
                sb.append('\n')
                        .append(i++)
                        .append(". ")
                        .append(toMarkdownAmapLink(link));
                if (i > 10) {
                    break;
                }
            }
            log.info("已补回模型漏写的高德短链 count={}", Math.min(links.size(), 10));
            text = sb.toString();
        }
        return beautifyBareAmapUrls(text);
    }

    /** 把正文里的裸 {@code uri.amap.com} 改成 {@code [查看地图](url)}；已是 Markdown 的不改。 */
    static String beautifyBareAmapUrls(String text) {
        if (text == null || text.isBlank() || !text.contains("uri.amap.com")) {
            return text;
        }
        Matcher m = AMAP_URI.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            int start = m.start();
            out.append(text, last, start);
            String url = m.group();
            boolean alreadyMarkdown = start >= 2 && text.startsWith("](", start - 2);
            if (alreadyMarkdown) {
                out.append(url);
            } else {
                out.append(toMarkdownAmapLink(url));
            }
            last = m.end();
        }
        out.append(text, last, text.length());
        return out.toString();
    }

    static String toMarkdownAmapLink(String url) {
        return "[查看地图](" + url + ")";
    }

    private static List<String> extractAmapLinksFromToolMessages(List<ChatMessage> messages) {
        Set<String> ordered = new LinkedHashSet<>();
        if (messages == null) {
            return List.of();
        }
        for (ChatMessage msg : messages) {
            if (msg == null || !"tool".equalsIgnoreCase(msg.role())) {
                continue;
            }
            Object raw = msg.content();
            if (!(raw instanceof String content) || content.isBlank()) {
                continue;
            }
            Matcher m = AMAP_URI.matcher(content);
            while (m.find()) {
                ordered.add(m.group());
            }
        }
        return List.copyOf(ordered);
    }

    /**
     * 清洗上游模型/工具泄漏的内部标记，避免把 tool_calls、DSML 之类协议内容发给用户。
     */
    public static String sanitizeAssistantReply(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String cleaned = text;
        cleaned = cleaned.replaceAll("(?is)<\\s*\\|\\s*DSML\\s*\\|[^>]*>", " ");
        cleaned = cleaned.replaceAll("(?is)\\btool_calls?\\b", " ");
        cleaned = cleaned.replaceAll("(?is)\\binvoke name=\\\"[^\\\"]*\\\"", " ");
        cleaned = cleaned.replaceAll("(?is)\\bparameter name=\\\"[^\\\"]*\\\"", " ");
        cleaned = cleaned.replaceAll("(?is)\\bstring=\\\"true\\\"", " ");
        cleaned = cleaned.replaceAll("(?is)[ \\t]+", " ");
        cleaned = cleaned.replaceAll("(?is)\\n{3,}", "\n\n").trim();
        return cleaned;
    }

    private String generateVisionReply(String userId, byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            log.warn("图片字节为空，回退固定文案");
            return cloudbotProperties.getAutoReplyText();
        }

        String visionText;
        try {
            visionText = runQwenVision(imageBytes);
            log.info("千问识图完成 userId={}, out={}", userId, Strings.summarizeOneLine(visionText));
        } catch (Exception e) {
            log.error("千问识图失败，回退固定文案: {}", e.getMessage());
            return cloudbotProperties.getAutoReplyText();
        }

        return refineVisionWithDeepSeek(
                userId,
                ChatMemoryRepository.IMAGE_HISTORY_PLACEHOLDER,
                visionText,
                deepSeekProperties.getVisionRefinePrompt());
    }

    private String generateVideoVisionReply(String userId, byte[] videoBytes) {
        if (videoBytes == null || videoBytes.length == 0) {
            log.warn("视频字节为空，回退固定文案");
            return cloudbotProperties.getAutoReplyText();
        }

        long maxBytes = qwenProperties.getMaxVideoBytes();
        if (maxBytes > 0 && videoBytes.length > maxBytes) {
            String msg =
                    "视频有点大（"
                            + (videoBytes.length / (1024 * 1024))
                            + "MB），当前上限约 "
                            + (maxBytes / (1024 * 1024))
                            + "MB，请发更短的视频再试。";
            log.warn("视频超限 userId={}, size={}, max={}", userId, videoBytes.length, maxBytes);
            chatMemoryStore.appendTurn(userId, ChatMemoryRepository.VIDEO_HISTORY_PLACEHOLDER, msg);
            return msg;
        }

        String visionText;
        try {
            visionText = runQwenVideoVision(videoBytes);
            log.info("千问识视频完成 userId={}, out={}", userId, Strings.summarizeOneLine(visionText));
        } catch (Exception e) {
            log.error("千问识视频失败，回退固定文案: {}", e.getMessage(), e);
            return "视频理解失败，请稍后重试";
        }

        return refineVisionWithDeepSeek(
                userId,
                ChatMemoryRepository.VIDEO_HISTORY_PLACEHOLDER,
                visionText,
                deepSeekProperties.getVideoRefinePrompt());
    }

    private String refineVisionWithDeepSeek(
            String userId, String historyPlaceholder, String visionText, String refineTemplate) {
        if (!deepSeekProperties.isEnabled() || !deepSeekProperties.hasApiKey()) {
            log.warn("DeepSeek 不可用，直接返回千问视觉结果");
            chatMemoryStore.appendTurn(userId, historyPlaceholder, visionText);
            return visionText;
        }

        try {
            String refineUser = refineTemplate.replace("{vision}", visionText);
            List<ChatMessage> messages = chatMemoryStore.buildMessages(userId, refineUser);
            String refined = llmGateway.chat(messages);
            chatMemoryStore.appendTurn(userId, historyPlaceholder, refined);
            log.info(
                    "DeepSeek 优化视觉结果完成 userId={}, vision={}, refined={}",
                    userId,
                    Strings.summarizeOneLine(visionText),
                    Strings.summarizeOneLine(refined));
            return refined;
        } catch (Exception e) {
            log.error("DeepSeek 优化视觉结果失败，回退千问原文: {}", e.getMessage());
            chatMemoryStore.appendTurn(userId, historyPlaceholder, visionText);
            return visionText;
        }
    }

    private String runQwenVision(byte[] imageBytes) {
        if (!qwenProperties.isEnabled() || !qwenProperties.hasApiKey()) {
            throw new IllegalStateException("Qwen 未启用或 DASHSCOPE_API_KEY 为空");
        }
        String dataUrl = ImageDataUrls.toDataUrl(imageBytes);
        List<ChatMessage> messages =
                chatMemoryStore.buildVisionOnlyMessages(
                        dataUrl, qwenProperties.getImagePrompt());
        return qwenClient.chatVision(messages);
    }

    private String runQwenVideoVision(byte[] videoBytes) {
        if (!qwenProperties.isEnabled() || !qwenProperties.hasApiKey()) {
            throw new IllegalStateException("Qwen 未启用或 DASHSCOPE_API_KEY 为空");
        }
        String dataUrl = VideoDataUrls.toDataUrl(videoBytes);
        List<ChatMessage> messages =
                chatMemoryStore.buildVideoVisionOnlyMessages(
                        dataUrl, qwenProperties.getVideoPrompt(), qwenProperties.getVideoFps());
        return qwenClient.chatVision(messages);
    }
}
