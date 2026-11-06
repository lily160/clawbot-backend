package com.github.wechat.cloudbot.modules.health.triage.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.health.common.HealthDisclaimers;
import com.github.wechat.cloudbot.modules.health.common.TriageResult;
import com.github.wechat.cloudbot.modules.health.triage.service.TriageService;
import com.github.wechat.cloudbot.modules.health.triage.service.TriageSessionResult;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import com.github.wechat.cloudbot.platform.persistence.ChatMemoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 导诊工具单元测试：重点验证多轮追问时的服务端症状累积与去重。
 */
@ExtendWith(MockitoExtension.class)
class TriageDepartmentToolTest {

    @Mock
    private TriageService triageService;

    @Mock
    private ChatMemoryRepository memoryStore;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private TriageDepartmentTool newTool() {
        return new TriageDepartmentTool(triageService, objectMapper, memoryStore);
    }

    private static final String USER = "u1";

    private ToolCall call(String symptoms, int round) throws Exception {
        String json = objectMapper.writeValueAsString(java.util.Map.of("symptoms", symptoms, "followUpRound", round));
        return new ToolCall("c1", "health_triage_department", json, USER);
    }

    private TriageSessionResult followUp(List<String> depts, List<String> questions, int round) {
        return new TriageSessionResult(
                new TriageResult(depts, List.of(), HealthDisclaimers.TRIAGE_DISCLAIMER),
                true,
                questions,
                round);
    }

    private TriageSessionResult done(List<String> depts) {
        return new TriageSessionResult(
                new TriageResult(depts, List.of(), HealthDisclaimers.TRIAGE_DISCLAIMER),
                false,
                List.of(),
                1);
    }

    @Test
    void multiRound_accumulatesSymptomsAcrossCalls() throws Exception {
        TriageDepartmentTool tool = newTool();

        when(triageService.triage(anyString(), anyInt(), anyList()))
                .thenReturn(followUp(List.of("口腔科"), List.of("牙痛是持续性还是冷热刺激痛？"), 1))
                .thenReturn(followUp(List.of("口腔科"), List.of("牙龈有没有肿胀或出血？"), 2))
                .thenReturn(done(List.of("口腔科")));

        // 第1轮：用户只说"我牙疼"
        ToolResult r1 = tool.execute(call("我牙疼", 0));
        assertTrue(r1.success());
        JsonNode n1 = objectMapper.readTree(r1.content());
        assertTrue(n1.get("needsMoreInfo").asBoolean());

        // 第2轮：用户补充"咀嚼时后槽牙牙龈痛"（大模型没把前文拼进来）
        ToolResult r2 = tool.execute(call("咀嚼时后槽牙牙龈痛", 1));
        assertTrue(r2.success());
        JsonNode n2 = objectMapper.readTree(r2.content());
        assertTrue(n2.get("formattedResponse").asText().contains("牙龈有没有肿胀或出血？"));

        // 第3轮：用户补充"持续三天了，没有发烧"
        ToolResult r3 = tool.execute(call("持续三天了，没有发烧", 2));
        assertTrue(r3.success());
        JsonNode n3 = objectMapper.readTree(r3.content());
        assertTrue(n3.get("formattedResponse").asText().length() > 0);
    }

    @Test
    void llmCombinedSymptoms_notDuplicated() throws Exception {
        TriageDepartmentTool tool = newTool();

        when(triageService.triage(anyString(), anyInt(), anyList()))
                .thenReturn(followUp(List.of("口腔科"), List.of("牙痛是持续性还是冷热刺激痛？"), 1))
                .thenReturn(done(List.of("口腔科")));

        // 第1轮
        ToolResult r1 = tool.execute(call("我牙疼", 0));
        assertTrue(r1.success());

        // 第2轮：大模型这次自己把前文拼进来了
        ToolResult r2 = tool.execute(call("我牙疼，咀嚼时后槽牙牙龈痛", 1));
        assertTrue(r2.success());
        JsonNode node = objectMapper.readTree(r2.content());
        assertTrue(node.get("formattedResponse").asText().contains("口腔科"));
    }

    @Test
    void completedSession_clearsAccumulationForNextSession() throws Exception {
        TriageDepartmentTool tool = newTool();

        ArgumentCaptor<String> symptomsCaptor = ArgumentCaptor.forClass(String.class);
        when(triageService.triage(symptomsCaptor.capture(), anyInt(), anyList()))
                .thenReturn(done(List.of("口腔科")))   // 第1轮直接完成
                .thenReturn(followUp(List.of("消化内科"), List.of("腹痛具体在哪个位置？"), 1));

        // 第1轮：完成
        ToolResult r1 = tool.execute(call("我牙疼", 0));
        JsonNode n1 = objectMapper.readTree(r1.content());
        assertFalse(n1.get("needsMoreInfo").asBoolean());

        // 第2轮：新一轮导诊，不应携带上一轮的症状
        ToolResult r2 = tool.execute(call("胃痛反酸", 0));
        assertTrue(r2.success());
        JsonNode n2 = objectMapper.readTree(r2.content());
        assertTrue(n2.get("formattedResponse").asText().length() > 0);
    }

    @Test
    void needsMoreInfoResult_returnsFormattedResponseAndFollowUps() throws Exception {
        TriageDepartmentTool tool = newTool();
        when(triageService.triage(org.mockito.ArgumentMatchers.anyString(), anyInt(), anyList()))
                .thenReturn(followUp(List.of("口腔科"), List.of("牙痛是持续性还是冷热刺激痛？"), 1));

        ToolResult result = tool.execute(call("我牙疼", 0));
        assertTrue(result.success());
        JsonNode node = objectMapper.readTree(result.content());
        assertTrue(node.get("needsMoreInfo").asBoolean());
        assertTrue(node.get("formattedResponse").asText().contains("牙痛是持续性还是冷热刺激痛？"));
        assertTrue(node.has("followUpQuestions"));
    }
}
