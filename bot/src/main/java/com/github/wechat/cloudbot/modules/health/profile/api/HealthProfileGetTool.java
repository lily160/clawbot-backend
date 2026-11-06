package com.github.wechat.cloudbot.modules.health.profile.api;

import com.github.wechat.cloudbot.modules.health.common.PatientRef;
import com.github.wechat.cloudbot.modules.health.profile.service.ProfileService;
import com.github.wechat.cloudbot.platform.agent.AgentTool;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolDefinition;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 健康档案查询工具。
 *
 * <p>用于导诊、挂号和体检前拉取当前微信用户下已生效的档案列表。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HealthProfileGetTool implements AgentTool {

    private final ProfileService profileService;

    @Override
    public ToolDefinition definition() {
        String schema = """
                {
                  "type": "object",
                  "properties": {}
                }
                """;
        return new ToolDefinition(
                "health_profile_get",
                "获取当前微信用户下已保存（已生效）的所有健康档案列表。当用户要求查看档案、或者挂号和体检前需要拉取患者列表时，调用此工具。",
                schema);
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            String wechatUserId = call.userId();
            if (wechatUserId == null || wechatUserId.isBlank()) {
                return ToolResult.fail(call.id(), call.name(), "执行失败：无法获取当前用户信息");
            }

            List<PatientRef> profiles = profileService.getProfiles(wechatUserId);
            if (profiles == null || profiles.isEmpty()) {
                return ToolResult.ok(call.id(), call.name(), "未查询到任何已生效的健康档案。");
            }

            StringBuilder sb = new StringBuilder("查询到以下档案信息：\n");
            for (PatientRef ref : profiles) {
                String allergies = (ref.allergySummary() == null || ref.allergySummary().isEmpty())
                        ? "无" : String.join("、", ref.allergySummary());
                String chronic = (ref.chronicConditionSummary() == null || ref.chronicConditionSummary().isEmpty())
                        ? "无" : String.join("、", ref.chronicConditionSummary());

                sb.append(String.format("- 【%s】(%s): %s岁, 性别:%s, 过敏史:%s, 慢病史:%s\n",
                        ref.relation() != null ? ref.relation() : "未知关系",
                        ref.displayName() != null ? ref.displayName() : "未命名",
                        ref.age() != null ? ref.age() : "未知",
                        ref.gender() != null ? ref.gender() : "未知",
                        allergies,
                        chronic));
            }

            return ToolResult.ok(call.id(), call.name(), sb.toString());
        } catch (Exception e) {
            log.error("执行获取健康档案工具异常", e);
            return ToolResult.fail(call.id(), call.name(), "执行异常：" + e.getMessage());
        }
    }
}