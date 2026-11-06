package com.github.wechat.cloudbot.modules.health.triage.infra;

import java.util.List;

/**
 * 分科目录条目，对应 departments.json 的结构。
 *
 * @param department         科室名称
 * @param aliases           科室别名（用户口语化称呼）
 * @param symptomKeywords    症状关键词（用于匹配用户描述，权重 1）
 * @param highWeightKeywords 特异性高的症状关键词（权重 2，如"咳痰""心绞痛"等跨科室极少误命中的词）
 * @param commonExams        该科室常见检查项目名称
 * @param followUpQuestions  初步匹配后需要追问用户的问题（用于多轮细化症状）
 * @param notes              科普说明，固定为"科普指引，非诊断"
 */
public record DepartmentEntry(
        String department,
        List<String> aliases,
        List<String> symptomKeywords,
        List<String> highWeightKeywords,
        List<String> commonExams,
        List<String> followUpQuestions,
        String notes) {

    /**
     * 紧凑构造器：对所有列表字段做防御性拷贝，避免外部修改。
     */
    public DepartmentEntry {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        symptomKeywords = symptomKeywords == null ? List.of() : List.copyOf(symptomKeywords);
        highWeightKeywords = highWeightKeywords == null ? List.of() : List.copyOf(highWeightKeywords);
        commonExams = commonExams == null ? List.of() : List.copyOf(commonExams);
        followUpQuestions = followUpQuestions == null ? List.of() : List.copyOf(followUpQuestions);
        notes = notes == null ? "" : notes;
    }
}
