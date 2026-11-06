package com.github.wechat.cloudbot.modules.health.common.skill;

import com.github.wechat.cloudbot.modules.health.common.HealthDisclaimers;
import com.github.wechat.cloudbot.modules.health.common.HealthToolNames;

import java.util.List;

/**
 * 统一收口健康管家的 skill 目录，避免散落在各模块里后期难以整合。
 */
public final class HealthSkillCatalog {

    public static final HealthSkillDescriptor SHARED =
            new HealthSkillDescriptor(
                    "health.shared",
                    "健康管家通用基础",
                    HealthSkillScope.SHARED,
                    "沉淀健康管家通用约束、免责声明、工具命名和跨模块共享契约。",
                    List.of("统一免责声明", "全局工具命名", "跨模块共享 DTO 与适配器契约"),
                    List.of("platform.agent", "modules.health.common"),
                    List.of(
                            HealthDisclaimers.MEDICAL_DISCLAIMER,
                            "不得输出确诊、处方、改剂量、替代医生结论。",
                            "所有健康相关回复都应优先保持可追溯、可回滚、可复用。"),
                    List.of("health/common", "health/package-info.java"));

    public static final HealthSkillDescriptor PROFILE =
            new HealthSkillDescriptor(
                    "health.profile",
                    "家庭健康档案",
                    HealthSkillScope.PROFILE,
                    "面向本人或家人做健康档案录入、查询和敏感信息确认。",
                    List.of("建档", "档案查询", "敏感字段二次确认", "家庭成员隔离"),
                    List.of(HealthToolNames.PROFILE_UPSERT, HealthToolNames.PROFILE_GET),
                    List.of(
                            HealthDisclaimers.MEDICAL_DISCLAIMER,
                            "敏感信息保存前必须显式确认。",
                            "档案仅用于健康管理，不作为诊疗结论。"),
                    List.of("modules/health/profile"));

    public static final HealthSkillDescriptor TRIAGE =
            new HealthSkillDescriptor(
                    "health.triage",
                    "症状导诊",
                    HealthSkillScope.TRIAGE,
                    "仅基于公立医院分科目录给出就诊科室与常见检查建议。",
                    List.of("症状 → 科室建议", "常见检查提示", "分科目录 RAG"),
                    List.of(HealthToolNames.TRIAGE_DEPARTMENT),
                    List.of(
                            HealthDisclaimers.TRIAGE_DISCLAIMER,
                            "只提供科室与检查名称，不给出诊断。",
                            "出现危急症状时应立即线下就医。"),
                    List.of("modules/health/triage"));

    public static final HealthSkillDescriptor BOOKING =
            new HealthSkillDescriptor(
                    "health.booking",
                    "真实挂号候选",
                    HealthSkillScope.BOOKING,
                    "把导诊结果转成医院优先级排序、候选号源和真实预约入口。",
                    List.of("优先级排序", "候选号源", "真实预约入口", "平台抽象适配器"),
                    List.of(HealthToolNames.BOOKING_RECOMMEND, HealthToolNames.BOOKING_CONFIRM),
                    List.of(
                            HealthDisclaimers.MEDICAL_DISCLAIMER,
                            "未接入真实医院平台时，不应伪造已预约回执。",
                            "真实平台适配必须通过独立适配器接入。"),
                    List.of("modules/health/booking"));

    public static final HealthSkillDescriptor REPORT =
            new HealthSkillDescriptor(
                    "health.report",
                    "体检报告解析",
                    HealthSkillScope.REPORT,
                    "解析体检 PDF 并标注偏离参考范围的结果，供复诊复核。",
                    List.of("PDF 解析", "偏离项标注", "复诊摘要"),
                    List.of(HealthToolNames.REPORT_PARSE),
                    List.of(
                            HealthDisclaimers.REPORT_DISCLAIMER,
                            "不对异常结果做临床诊断。",
                            "所有异常应提示与医生复核。"),
                    List.of("modules/health/report"));

    public static final HealthSkillDescriptor MEDICATION =
            new HealthSkillDescriptor(
                    "health.medication",
                    "用药提醒与轻量冲突提示",
                    HealthSkillScope.MEDICATION,
                    "记录药名、频次和疗程，并支持定时提醒与小样本冲突提示。",
                    List.of("用药记录", "定时提醒", "OTC 小样本冲突提示", "日常提醒"),
                    List.of(HealthToolNames.MED_ADD, HealthToolNames.MED_LIST),
                    List.of(
                            HealthDisclaimers.MEDICATION_DISCLAIMER,
                            "冲突提示仅作提醒，不构成用药建议。",
                            "不修改剂量，不替代药师或医生意见。"),
                    List.of("modules/health/medication"));

    public static final HealthSkillDescriptor CHECKUP =
            new HealthSkillDescriptor(
                    "health.checkup",
                    "体检模板",
                    HealthSkillScope.CHECKUP,
                    "输出青年、中老年、孕期等体检套餐模板，辅助沟通与选项整理。",
                    List.of("体检套餐模板", "年龄/场景建议", "套餐清单"),
                    List.of(HealthToolNames.CHECKUP_PLAN),
                    List.of(
                            HealthDisclaimers.MEDICAL_DISCLAIMER,
                            "模板仅供整理，不作为医学建议或体检结论。"),
                    List.of("modules/health/checkup"));

    public static final String MAIN_FLOW = "建档 → 导诊 → 挂号 → 报告 → 提醒";

    private static final List<HealthSkillDescriptor> ALL =
            List.of(SHARED, PROFILE, TRIAGE, BOOKING, REPORT, MEDICATION, CHECKUP);

    private HealthSkillCatalog() {
    }

    public static List<HealthSkillDescriptor> all() {
        return ALL;
    }
}
