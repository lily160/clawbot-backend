package com.github.wechat.cloudbot.modules.health.common;

import com.github.wechat.cloudbot.modules.health.common.skill.HealthSkillCatalog;

import java.util.List;

/**
 * 生成组长可直接转发的健康总览文案。
 */
public final class HealthContractOverview {

    private HealthContractOverview() {
    }

    public static HealthContractStatus status() {
        return new HealthContractStatus(
                "组长",
                HealthSkillCatalog.MAIN_FLOW,
                List.of(
                        "共享 DTO：PatientRef、TriageResult、SlotOption、BookingReceipt、HealthFlowRequest、HealthFlowSnapshot、HealthFlowResult",
                        "共享约束：跨模块只传不可变 DTO，不传实体、Mapper、ToolCall 或 LLM 原始 JSON",
                        "共享治理：HealthDisclaimers、HealthToolNames、HealthSkillCatalog",
                        "共享编排：HealthFlowOrchestrator、DefaultHealthFlowOrchestrator",
                        "共享动作：HealthFlowAction",
                        "共享技能目录数量：" + HealthSkillCatalog.all().size()),
                List.of(
                        "modules/health/profile",
                        "modules/health/triage",
                        "modules/health/booking",
                        "modules/health/report",
                        "modules/health/medication",
                        "modules/health/checkup"),
                List.of(
                        HealthDisclaimers.MEDICAL_DISCLAIMER,
                        HealthDisclaimers.TRIAGE_DISCLAIMER,
                        HealthDisclaimers.REPORT_DISCLAIMER,
                        HealthDisclaimers.MEDICATION_DISCLAIMER,
                        "共享契约只能由组长统一演进。"));
    }

    public static String overviewText() {
        HealthContractStatus status = status();
        return "健康管家组长总览\n"
                + "负责人：" + status.owner() + "\n"
                + "主流程：" + status.roadmap() + "\n\n"
                + "共享契约：\n- " + String.join("\n- ", status.sharedContracts()) + "\n\n"
                + "模块入口：\n- " + String.join("\n- ", status.moduleEntrypoints()) + "\n\n"
                + "护栏：\n- " + String.join("\n- ", status.guardrails());
    }

    public static String contractText() {
        return "健康管家共享契约\n"
                + "- PatientRef：就诊人引用\n"
                + "- TriageResult：导诊结果\n"
                + "- SlotOption：真实号源候选（无沙箱标记）\n"
                + "- BookingReceipt：真实预约入口/回执（仅在真实平台确认成功时返回）\n"
                + "- HealthReportParseResult：报告解析结果\n"
                + "- HealthFlowRequest / HealthFlowSnapshot / HealthFlowResult：流程输入、状态与输出\n"
                + "- HealthDisclaimers：统一免责声明\n"
                + "- HealthToolNames：统一工具名表\n"
                + "- HealthFlowAction：流程阶段标记\n"
                + "- HealthFlowOrchestrator：主流程编排契约";
    }

    public static String roadmapText() {
        return "健康管家主流程\n"
                + HealthSkillCatalog.MAIN_FLOW;
    }

    public static String redlineText() {
        return "健康管家医学红线\n"
                + "- 不得输出确诊、处方、改剂量、替代医生结论。\n"
                + "- 出现危急症状必须提示立即线下就医。\n"
                + "- " + HealthDisclaimers.MEDICAL_DISCLAIMER;
    }
}
