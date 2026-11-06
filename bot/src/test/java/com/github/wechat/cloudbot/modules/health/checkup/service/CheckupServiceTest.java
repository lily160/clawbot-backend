package com.github.wechat.cloudbot.modules.health.checkup.service;

import com.github.wechat.cloudbot.modules.health.checkup.infra.CheckupProperties;
import com.github.wechat.cloudbot.modules.health.checkup.service.CheckupService.CheckupPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CheckupServiceTest {

    private CheckupService service;

    @BeforeEach
    void setUp() {
        service = new CheckupService(checkupProps());
        service.init();
    }

    @Test
    void youngAdultPlan() {
        CheckupPlan plan = service.generatePlan(25, "male", List.of(), "none");
        assertTrue(plan.planName().contains("青年"));
        assertTrue(plan.items().stream().anyMatch(i -> i.contains("血常规")));
        assertTrue(plan.items().stream().anyMatch(i -> i.contains("心电图")));
    }

    @Test
    void middleAgedPlan() {
        CheckupPlan plan = service.generatePlan(50, "male", List.of(), "none");
        assertTrue(plan.planName().contains("中老年") || plan.planName().contains("深度"));
        assertTrue(plan.items().stream().anyMatch(i -> i.contains("胸部CT")));
    }

    @Test
    void middleAgedWithHypertension() {
        CheckupPlan plan = service.generatePlan(55, "male", List.of("高血压"), "none");
        assertTrue(plan.items().stream().anyMatch(i -> i.contains("动态血压监测")));
        assertTrue(plan.items().stream().anyMatch(i -> i.contains("心脏彩超")));
    }

    @Test
    void middleAgedWithDiabetes() {
        CheckupPlan plan = service.generatePlan(55, "female", List.of("糖尿病"), "none");
        assertTrue(plan.items().stream().anyMatch(i -> i.contains("糖化血红蛋白")));
        assertTrue(plan.items().stream().anyMatch(i -> i.contains("眼底检查")));
    }

    @Test
    void elderlyHasBoneDensity() {
        CheckupPlan plan = service.generatePlan(65, "female", List.of(), "none");
        assertTrue(plan.items().stream().anyMatch(i -> i.contains("骨密度")));
    }

    @Test
    void pregnancyPlan() {
        CheckupPlan plan = service.generatePlan(28, "female", List.of(), "pregnant");
        assertTrue(plan.planName().contains("孕期") || plan.planName().contains("备孕"));
        assertTrue(plan.items().stream().anyMatch(i -> i.contains("TORCH")));
        // 已确认怀孕条件项应生效
        assertTrue(plan.items().stream().anyMatch(i -> i.contains("HCG")),
                "已怀孕时应包含 HCG 定量");
        assertTrue(plan.items().stream().anyMatch(i -> i.contains("唐筛") || i.contains("NT")),
                "已怀孕时应包含早中孕期筛查");
    }

    @Test
    void pregnancyPrepStage() {
        CheckupPlan plan = service.generatePlan(28, "female", List.of(), "prep");
        assertTrue(plan.planName().contains("孕期") || plan.planName().contains("备孕"));
        // 备孕期条件项应生效，而非已怀孕项
        assertTrue(plan.items().stream().anyMatch(i -> i.contains("叶酸")),
                "备孕时应包含叶酸水平检测");
        assertTrue(plan.items().stream().anyMatch(i -> i.contains("AMH")),
                "备孕时应包含 AMH");
    }

    @Test
    void teenPlan() {
        CheckupPlan plan = service.generatePlan(12, "female", List.of(), "none");
        assertTrue(plan.planName().contains("青少年"), "7-17岁应落到青少年套餐，实际=" + plan.planName());
        assertTrue(plan.items().stream().anyMatch(i -> i.contains("眼科") || i.contains("视力")));
        assertTrue(plan.items().stream().anyMatch(i -> i.contains("脊柱")));
    }

    @Test
    void veryYoungAgeDefaults() {
        CheckupPlan plan = service.generatePlan(10, "female", List.of(), "none");
        // 7-17 岁应匹配青少年分类
        assertNotNull(plan);
        assertTrue(plan.planName().contains("青少年"), "10岁应落到青少年套餐，实际=" + plan.planName());
        assertFalse(plan.items().isEmpty());
    }

    @Test
    void planHasDisclaimerInDisplay() {
        CheckupPlan plan = service.generatePlan(30, "female", List.of(), "none");
        String text = plan.displayText();
        assertTrue(text.contains("推荐检查项目"));
    }

    @Test
    void compoundConditionAgeAndGender() {
        // 年龄≥60 且 性别为男 → 应包含 PSA 检查
        CheckupPlan plan = service.generatePlan(60, "male", List.of(), "none");
        assertTrue(plan.items().stream().anyMatch(i -> i.contains("PSA")),
                "60岁男性应包含 PSA 检查");
    }

    @Test
    void compoundConditionNotMetByAgeOnly() {
        // 45岁女性不应包含 PSA
        CheckupPlan plan = service.generatePlan(45, "female", List.of(), "none");
        assertTrue(plan.items().stream().noneMatch(i -> i.contains("PSA")),
                "45岁女性不应包含 PSA");
    }

    @Test
    void nullInputsDoNotThrow() {
        CheckupPlan plan = service.generatePlan(25, null, null, null);
        assertNotNull(plan);
        assertFalse(plan.items().isEmpty());
        assertTrue(plan.planName().contains("青年"), "性别为空时应落到青年套餐，实际=" + plan.planName());
    }

    @Test
    void ageBelowTeenRangeFallsToChildPlan() {
        CheckupPlan plan = service.generatePlan(6, "male", List.of(), "none");
        assertTrue(plan.planName().contains("儿童"), "0-6岁应落到儿童套餐，实际=" + plan.planName());
    }

    @Test
    void ageAboveMaxFallsBackToYoungAdult() {
        CheckupPlan plan = service.generatePlan(120, "male", List.of(), "none");
        assertTrue(plan.planName().contains("青年"), "120岁超出范围应兜底到青年套餐，实际=" + plan.planName());
    }

    @Test
    void genderPregnancyWordMapsToPregnancyPlan() {
        CheckupPlan plan = service.generatePlan(28, "孕", List.of(), "none");
        assertTrue(plan.planName().contains("孕期") || plan.planName().contains("备孕"),
                "性别填「孕」应命中孕期套餐，实际=" + plan.planName());
        assertTrue(plan.items().stream().anyMatch(i -> i.contains("TORCH")));
    }

    @Test
    void blankGenderDoesNotMatchPregnancyPlan() {
        // 未填性别（如只提供年龄）不应误命中孕期套餐
        CheckupPlan plan = service.generatePlan(28, "", List.of(), "none");
        assertFalse(plan.planName().contains("孕期") || plan.planName().contains("备孕"),
                "空性别不应命中孕期套餐，实际=" + plan.planName());
        assertTrue(plan.planName().contains("青年"),
                "28岁空性别应落到青年套餐，实际=" + plan.planName());
    }

    @Test
    void stageVariantsNormalize() {
        // 已怀孕 → HCG
        CheckupPlan pregnant = service.generatePlan(28, "female", List.of(), "已怀孕");
        assertTrue(pregnant.items().stream().anyMatch(i -> i.contains("HCG")));
        // 孕期 → HCG
        CheckupPlan pregnant2 = service.generatePlan(28, "female", List.of(), "孕期");
        assertTrue(pregnant2.items().stream().anyMatch(i -> i.contains("HCG")));
        // 备孕期 → 叶酸
        CheckupPlan prep = service.generatePlan(28, "female", List.of(), "备孕期");
        assertTrue(prep.items().stream().anyMatch(i -> i.contains("叶酸")));
    }

    @Test
    void displayTextListsChronicConditions() {
        CheckupPlan plan = service.generatePlan(55, "male", List.of("高血压", "糖尿病"), "none");
        String text = plan.displayText();
        assertTrue(text.contains("已考虑您的慢性病史"));
        assertTrue(text.contains("高血压"));
        assertTrue(text.contains("糖尿病"));
    }

    @Test
    void hyperlipidemiaAddsLipoproteinA() {
        CheckupPlan plan = service.generatePlan(55, "male", List.of("高血脂"), "none");
        assertTrue(plan.items().stream().anyMatch(i -> i.contains("脂蛋白(a)")),
                "高血脂应追加脂蛋白(a)");
    }

    @Test
    void coronaryHistoryAddsCoronaryCt() {
        CheckupPlan plan = service.generatePlan(60, "male", List.of("冠心病"), "none");
        assertTrue(plan.items().stream().anyMatch(i -> i.contains("冠脉CT")),
                "冠心病史应追加冠脉CT检查");
    }

    @Test
    void elderlyFemaleGetsGynecologyNotPSA() {
        CheckupPlan plan = service.generatePlan(60, "female", List.of(), "none");
        assertTrue(plan.items().stream().anyMatch(i -> i.contains("乳腺B超") || i.contains("钼靶")),
                "60岁女性应追加乳腺/钼靶检查");
        assertTrue(plan.items().stream().noneMatch(i -> i.contains("PSA")),
                "女性不应包含PSA检查");
    }

    @Test
    void teenFamilyHypertensionHistory() {
        CheckupPlan plan = service.generatePlan(12, "male", List.of("高血压"), "none");
        assertTrue(plan.items().stream().anyMatch(i -> i.contains("动态血压监测")),
                "青少年家族高血压史应追加动态血压监测");
    }

    @Test
    void teenFemaleAdvisory() {
        CheckupPlan plan = service.generatePlan(12, "female", List.of(), "none");
        assertTrue(plan.items().stream().anyMatch(i -> i.contains("青春期门诊")),
                "青少年女性应附加青春期门诊评估建议");
    }

    @Test
    void emptyCategoriesFallsBackToGenericPlan() {
        // 不调用 init()，categories 保持为空 → 走通用兜底
        CheckupService uninitialized = new CheckupService(checkupProps());
        CheckupPlan plan = uninitialized.generatePlan(30, "male", List.of(), "none");
        assertEquals("通用体检建议", plan.planName());
        assertFalse(plan.items().isEmpty());
    }

    @Test
    void templateCategoryBoundaries() {
        // 0-6 儿童、7-17 青少年、18-39 青年、40-59 中老年、60-100 老年 分段边界
        assertTrue(categoryName(0).contains("儿童"), "实际=" + categoryName(0));
        assertTrue(categoryName(6).contains("儿童"), "实际=" + categoryName(6));
        assertTrue(categoryName(7).contains("青少年"), "实际=" + categoryName(7));
        assertTrue(categoryName(17).contains("青少年"), "实际=" + categoryName(17));
        assertTrue(categoryName(18).contains("青年"), "实际=" + categoryName(18));
        assertTrue(categoryName(39).contains("青年"), "实际=" + categoryName(39));
        assertTrue(categoryName(40).contains("中老年"), "实际=" + categoryName(40));
        assertTrue(categoryName(59).contains("中老年"), "实际=" + categoryName(59));
        assertTrue(categoryName(60).contains("老年"), "实际=" + categoryName(60));
        assertTrue(categoryName(100).contains("老年"), "实际=" + categoryName(100));
    }

    private String categoryName(int age) {
        return service.generatePlan(age, "male", List.of(), "none").planName();
    }

    @Test
    void duplicateItemsFromMultipleConditionsAreDeduplicated() {
        // 高血压与糖尿病条件都含「眼底检查」，同时命中时不应重复展示
        CheckupPlan plan = service.generatePlan(55, "male", List.of("高血压", "糖尿病"), "none");
        long eyeCount = plan.items().stream().filter(i -> i.contains("眼底检查")).count();
        assertEquals(1, eyeCount, "眼底检查不应重复，实际出现 " + eyeCount + " 次");
    }

    @Test
    void childPlan() {
        CheckupPlan plan = service.generatePlan(4, "female", List.of(), "none");
        assertTrue(plan.planName().contains("儿童"), "0-6岁应落到儿童套餐，实际=" + plan.planName());
        assertTrue(plan.items().stream().anyMatch(i -> i.contains("发育")), "儿童套餐应包含发育行为评估");
    }

    @Test
    void elderlyPlanHasBoneDensityAndCognitive() {
        CheckupPlan plan = service.generatePlan(70, "male", List.of(), "none");
        assertTrue(plan.planName().contains("老年"), "60岁以上应落到老年套餐，实际=" + plan.planName());
        assertTrue(plan.items().stream().anyMatch(i -> i.contains("骨密度")), "老年套餐应包含骨密度检测");
        assertTrue(plan.items().stream().anyMatch(i -> i.contains("认知")), "老年套餐应包含认知功能筛查");
    }

    @Test
    void obeseBmiAddsFattyLiverScreening() {
        CheckupPlan plan = service.generatePlan(45, "male", List.of(), "none",
                new CheckupService.CheckupContext(28.5, null, null, List.of(), List.of()));
        assertTrue(plan.items().stream().anyMatch(i -> i.contains("脂肪肝")),
                "肥胖(BMI≥28)应追加脂肪肝筛查，实际=" + plan.items());
    }

    @Test
    void overweightBmiAddsHbA1c() {
        CheckupPlan plan = service.generatePlan(45, "male", List.of(), "none",
                new CheckupService.CheckupContext(26.0, null, null, List.of(), List.of()));
        assertTrue(plan.items().stream().anyMatch(i -> i.contains("同型半胱氨酸")),
                "超重(24≤BMI<28)应追加同型半胱氨酸");
    }

    @Test
    void smokerAddsLungFunction() {
        CheckupPlan plan = service.generatePlan(35, "male", List.of(), "none",
                new CheckupService.CheckupContext(null, true, null, List.of(), List.of()));
        assertTrue(plan.items().stream().anyMatch(i -> i.contains("肺功能")),
                "吸烟者应追加肺功能检测");
    }

    @Test
    void drinkerAddsGgt() {
        CheckupPlan plan = service.generatePlan(30, "female", List.of(), "none",
                new CheckupService.CheckupContext(null, null, true, List.of(), List.of()));
        assertTrue(plan.items().stream().anyMatch(i -> i.contains("GGT")),
                "饮酒者应追加 GGT 检测");
    }

    @Test
    void familyDiabetesAddsHbA1c() {
        CheckupPlan plan = service.generatePlan(30, "female", List.of(), "none",
                new CheckupService.CheckupContext(null, null, null, List.of("糖尿病"), List.of()));
        assertTrue(plan.items().stream().anyMatch(i -> i.contains("糖化血红蛋白")),
                "家族糖尿病史应追加糖化血红蛋白");
    }

    @Test
    void sedentaryLifestyleAddsSpineXray() {
        CheckupPlan plan = service.generatePlan(30, "male", List.of(), "none",
                new CheckupService.CheckupContext(null, null, null, List.of(), List.of("sedentary")));
        assertTrue(plan.items().stream().anyMatch(i -> i.contains("颈椎X线")),
                "久坐族应追加颈椎/腰椎X线");
    }

    @Test
    void emptyContextIsNoOp() {
        // 4 参便捷重载等价于空上下文，不应抛出
        CheckupPlan plan = service.generatePlan(45, "male", List.of("高血压"), "none");
        assertTrue(plan.items().stream().anyMatch(i -> i.contains("动态血压监测")));
    }

    private CheckupProperties checkupProps() {
        CheckupProperties props = new CheckupProperties();
        props.setEnabled(true);
        return props;
    }
}
