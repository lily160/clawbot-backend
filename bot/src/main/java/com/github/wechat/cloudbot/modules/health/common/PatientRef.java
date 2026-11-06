package com.github.wechat.cloudbot.modules.health.common;

import java.util.List;

/**
 * 健康档案与挂号场景共用的就诊人引用。
 * 供 挂号(booking)、体检(checkup) 等模块跨域使用，保证数据不可变。
 *
 * @param id                      档案唯一ID（查库、沙箱挂号传参用）
 * @param displayName             姓名或称呼（对应展示与第三方系统）
 * @param relation                就诊人关系（如：本人、妈妈、爸爸，主要用于展示）
 * @param gender                  性别（如 男/女 或 M/F，部分挂号接口强校验性别）
 * @param age                     年龄（成员C生成体检模板强依赖此字段划分人群）
 * @param allergySummary          过敏史摘要（如：["青霉素", "海鲜"]，挂号前免责提示用）
 * @param chronicConditionSummary 慢性病史摘要（如：["高血压", "糖尿病"]，成员C判断体检项目用）
 * @param medicalHistory          其他既往史或备注信息（扩展备用）
 */
public record PatientRef(
        String id,
        String displayName,
        String relation,
        String gender,
        Integer age,
        List<String> allergySummary,
        List<String> chronicConditionSummary,
        String medicalHistory
) {
    public PatientRef {
        allergySummary = allergySummary == null ? List.of() : List.copyOf(allergySummary);
        chronicConditionSummary = chronicConditionSummary == null ? List.of() : List.copyOf(chronicConditionSummary);
    }
}
