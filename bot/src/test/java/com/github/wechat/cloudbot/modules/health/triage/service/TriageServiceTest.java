package com.github.wechat.cloudbot.modules.health.triage.service;

import com.github.wechat.cloudbot.modules.health.common.HealthDisclaimers;
import com.github.wechat.cloudbot.modules.health.common.TriageResult;
import com.github.wechat.cloudbot.modules.health.triage.infra.DepartmentCatalogLoader;
import com.github.wechat.cloudbot.modules.health.triage.infra.DepartmentEntry;
import com.github.wechat.cloudbot.modules.health.triage.rag.TriageRagService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 分科导诊服务单元测试。
 */
@ExtendWith(MockitoExtension.class)
class TriageServiceTest {

    @Mock
    private DepartmentCatalogLoader catalogLoader;

    @Mock
    private TriageRagService ragService;

    @InjectMocks
    private TriageService triageService;

    /** 构造一份覆盖常见科室的测试目录（含 highWeightKeywords + followUpQuestions） */
    private List<DepartmentEntry> testCatalog() {
        return List.of(
                new DepartmentEntry(
                        "呼吸内科", List.of("呼吸科", "肺科"),
                        List.of("咳嗽", "咳痰", "气短", "低烧", "胸闷", "哮喘", "感冒", "发烧"),
                        List.of("咳痰", "哮喘", "气短"),
                        List.of("血常规", "胸部X线", "C反应蛋白"),
                        List.of("咳嗽多久了？有痰吗？", "有没有发烧？"),
                        "科普指引，非诊断"),
                new DepartmentEntry(
                        "消化内科", List.of("消化科", "肠胃科"),
                        List.of("胃痛", "腹痛", "腹泻", "便秘", "恶心", "呕吐", "反酸"),
                        List.of("腹泻", "便血", "反酸", "烧心"),
                        List.of("血常规", "便常规", "腹部B超", "胃镜"),
                        List.of("腹痛具体在哪个位置？", "有没有腹泻或恶心？"),
                        "科普指引，非诊断"),
                new DepartmentEntry(
                        "心血管内科", List.of("心内科"),
                        List.of("胸闷", "胸痛", "心悸", "心慌", "气短", "高血压"),
                        List.of("心绞痛", "心悸", "心慌"),
                        List.of("心电图", "心脏彩超", "血压监测"),
                        List.of("胸闷是持续还是间歇？", "活动后加重吗？"),
                        "科普指引，非诊断"),
                new DepartmentEntry(
                        "全科", List.of("全科医学科", "综合科"),
                        List.of("发烧", "乏力", "疲劳", "不适", "症状不明"),
                        List.of(),
                        List.of("血常规", "尿常规", "肝肾功能"),
                        List.of("具体哪里不舒服？", "症状持续多久了？"),
                        "科普指引，非诊断")
        );
    }

    @Test
    void triage_coughAndFever_hitsRespiratory() {
        when(catalogLoader.getCatalog()).thenReturn(testCatalog());
        TriageSessionResult session = triageService.triage("咳嗽三天低烧");
        TriageResult result = session.triageResult();
        assertTrue(result.suggestedDepartments().contains("呼吸内科"));
    }

    @Test
    void triage_resultAlwaysContainsDisclaimer() {
        when(catalogLoader.getCatalog()).thenReturn(testCatalog());
        TriageSessionResult session = triageService.triage("咳嗽低烧");
        assertEquals(HealthDisclaimers.TRIAGE_DISCLAIMER, session.triageResult().disclaimer());
    }

    @Test
    void triage_vagueSymptoms_triggersFollowUp() {
        when(catalogLoader.getCatalog()).thenReturn(testCatalog());
        TriageSessionResult session = triageService.triage("头痛");
        assertTrue(session.needsMoreInfo());
        assertFalse(session.followUpQuestions().isEmpty());
    }

    @Test
    void triage_followUpQuestions_matchDepartment() {
        when(catalogLoader.getCatalog()).thenReturn(testCatalog());
        TriageSessionResult session = triageService.triage("胃痛");
        assertTrue(session.triageResult().suggestedDepartments().contains("消化内科"));
        // "腹痛具体在哪个位置？"被智能过滤掉（用户已说"胃痛"），但"有没有腹泻或恶心？"应保留
        assertTrue(session.followUpQuestions().contains("有没有腹泻或恶心？"));
    }

    // ========== 验收点5：高权重关键词加分 ==========

    @Test
    void triage_highWeightKeyword_scoresHigherThanNormal() {
        when(catalogLoader.getCatalog()).thenReturn(testCatalog());

        // "心悸"是心血管内科的高权重词（权重2），单独一个高权重词得分=2 < 3，仍触发追问
        TriageSessionResult session1 = triageService.triage("心悸");
        assertTrue(session1.needsMoreInfo(), "单个高权重词得分2，仍应触发追问");

        // "胸闷心悸"：胸闷(1) + 心悸(2) = 3 分 >= 3，不追问
        TriageSessionResult session2 = triageService.triage("胸闷心悸");
        assertFalse(session2.needsMoreInfo(), "普通词+高权重词得分3，不应追问");
        assertTrue(session2.triageResult().suggestedDepartments().contains("心血管内科"),
                "应命中心血管内科");
    }

    @Test
    void triage_twoHighWeightKeywords_noFollowUp() {
        when(catalogLoader.getCatalog()).thenReturn(testCatalog());

        // "心悸心慌"：心悸(2) + 心慌(2) = 4 分 >= 3，不追问
        TriageSessionResult session = triageService.triage("心悸心慌");
        assertFalse(session.needsMoreInfo(), "两个高权重词得分4，不应追问");
        assertTrue(session.triageResult().suggestedDepartments().contains("心血管内科"),
                "应命中心血管内科");
    }

    @Test
    void triage_normalKeywordsOnly_needThreeForConfidence() {
        when(catalogLoader.getCatalog()).thenReturn(testCatalog());

        // "咳嗽低烧"：两个普通词各1分 = 2分 < 3，应触发追问
        TriageSessionResult session1 = triageService.triage("咳嗽低烧");
        assertTrue(session1.needsMoreInfo(), "两个普通词得分2，应触发追问");

        // "咳嗽低烧胸闷"：三个普通词各1分 = 3分 >= 3，不追问
        TriageSessionResult session2 = triageService.triage("咳嗽低烧胸闷");
        assertFalse(session2.needsMoreInfo(), "三个普通词得分3，不应追问");
    }

    // ========== 验收点6：否定语义排除 ==========

    @Test
    void triage_negatedKeyword_notCounted() {
        when(catalogLoader.getCatalog()).thenReturn(testCatalog());

        // "不咳嗽发烧"：咳嗽被否定不计分，发烧(1) = 1分 < 3，触发追问
        TriageSessionResult session = triageService.triage("不咳嗽发烧");
        TriageResult result = session.triageResult();

        // 发烧(1)命中呼吸内科和全科，但得分都不够
        assertTrue(session.needsMoreInfo(), "否定词不应计分，得分不足应触发追问");
    }

    @Test
    void triage_negatedWithMeiYou_notCounted() {
        when(catalogLoader.getCatalog()).thenReturn(testCatalog());

        // "没有发烧咳嗽"：发烧被"没有"否定不计分，咳嗽(1) = 1分 < 3，触发追问
        TriageSessionResult session = triageService.triage("没有发烧咳嗽");
        assertTrue(session.needsMoreInfo(), "没有+关键词应被否定，得分不足应触发追问");
    }

    @Test
    void triage_partialNegation_onlyNegatedPartSkipped() {
        when(catalogLoader.getCatalog()).thenReturn(testCatalog());

        // "不咳嗽但是胸闷心悸"：咳嗽被否定，胸闷(1)+心悸(2) = 3 >= 3，不追问
        TriageSessionResult session = triageService.triage("不咳嗽但是胸闷心悸");
        assertFalse(session.needsMoreInfo(), "否定部分不计分，剩余部分得分足够不应追问");
        assertTrue(session.triageResult().suggestedDepartments().contains("心血管内科"),
                "应命中心血管内科");
    }

    // ========== 验收点7：追问轮数兜底 ==========

    @Test
    void triage_longSymptoms_noFollowUp() {
        when(catalogLoader.getCatalog()).thenReturn(testCatalog());

        // 超过 120 字符的长描述（MAX_SYMPTOM_LENGTH_FOR_FOLLOWUP=120），即使得分不足也不再追问
        String longSymptoms = "就是感觉不太舒服但说不清楚具体哪里有问题反正就是浑身难受已经持续好几天了也没有什么特别明显的症状就是偶尔有点不舒服去看了医生也没说出什么所以然来就一直在家休息多喝水但是好像也没什么用现在已经持续了大概一个多星期了还是这样真的很担心会不会有什么大问题";
        TriageSessionResult session = triageService.triage(longSymptoms);

        assertFalse(session.needsMoreInfo(), "症状描述过长时应触发兜底，不再追问");
    }

    @Test
    void triage_longSymptomsButLowScore_returnsBestMatch() {
        when(catalogLoader.getCatalog()).thenReturn(testCatalog());

        // 长描述但得分很低，兜底后应返回最佳匹配（可能回退全科）
        String longSymptoms = "就是感觉不太舒服但说不清楚具体哪里有问题反正就是浑身难受已经持续好几天了也没有什么特别明显的症状就是偶尔有点不舒服去看了医生也没说出什么所以然来就一直在家休息多喝水但是好像也没什么用现在已经持续了大概一个多星期了还是这样真的很担心会不会有什么大问题";
        TriageSessionResult session = triageService.triage(longSymptoms);
        TriageResult result = session.triageResult();

        assertNotNull(result.suggestedDepartments(), "兜底时应返回建议科室");
        assertFalse(result.suggestedDepartments().isEmpty(), "兜底时不应返回空科室列表");
    }

    // ========== 验收点8：追问次数兜底（最多追问3次） ==========

    @Test
    void triage_followUpRoundZero_triggersFollowUpWhenLowScore() {
        when(catalogLoader.getCatalog()).thenReturn(testCatalog());

        // 首轮（followUpRound=0），得分不足（"咳嗽"=1分 < 3），应触发追问
        TriageSessionResult session = triageService.triage("咳嗽", 0);

        assertTrue(session.needsMoreInfo(), "首轮得分不足应触发追问");
        assertEquals(1, session.followUpRound(), "返回的 followUpRound 应为 1（下次调用时传入）");
    }

    @Test
    void triage_followUpRoundIncrementsEachRound() {
        when(catalogLoader.getCatalog()).thenReturn(testCatalog());

        // 模拟多轮追问：每轮得分都不足，followUpRound 递增
        // 第0轮 -> 返回 followUpRound=1
        TriageSessionResult r0 = triageService.triage("咳嗽", 0);
        assertTrue(r0.needsMoreInfo());
        assertEquals(1, r0.followUpRound());

        // 第1轮 -> 返回 followUpRound=2
        TriageSessionResult r1 = triageService.triage("咳嗽", 1);
        assertTrue(r1.needsMoreInfo());
        assertEquals(2, r1.followUpRound());

        // 第2轮（最后一次） -> 返回 followUpRound=3
        TriageSessionResult r2 = triageService.triage("咳嗽", 2);
        assertTrue(r2.needsMoreInfo(), "第2轮（最后一次）仍应追问");
        assertEquals(3, r2.followUpRound());
    }

    @Test
    void triage_followUpRoundThree_stopsFollowUp() {
        when(catalogLoader.getCatalog()).thenReturn(testCatalog());

        // 达到追问上限（followUpRound=3），即使得分不足也不再追问
        // "咳嗽"得分=1 < 3，但 followUpRound=3 >= MAX_FOLLOW_UP_ROUNDS
        TriageSessionResult session = triageService.triage("咳嗽", 3);

        assertFalse(session.needsMoreInfo(), "达到追问上限(3次)后应强制停止追问");
        assertTrue(session.followUpQuestions().isEmpty(), "停止追问后不应返回追问问题");
        assertFalse(session.triageResult().suggestedDepartments().isEmpty(),
                "停止追问后仍应返回最佳匹配科室");
    }

    @Test
    void triage_followUpRoundExceedsMax_stopsFollowUp() {
        when(catalogLoader.getCatalog()).thenReturn(testCatalog());

        // 超过上限（followUpRound=99），同样停止追问
        TriageSessionResult session = triageService.triage("咳嗽", 99);

        assertFalse(session.needsMoreInfo(), "超过追问上限后应停止追问");
    }

    @Test
    void triage_followUpRoundThree_noMatch_stopsGeneralFollowUp() {
        when(catalogLoader.getCatalog()).thenReturn(testCatalog());

        // 无命中 + 达到追问上限：回退全科但不追问
        TriageSessionResult session = triageService.triage("就是想查查", 3);

        assertFalse(session.needsMoreInfo(), "无命中且达到追问上限时不应追问");
        assertTrue(session.triageResult().suggestedDepartments().contains("全科"),
                "应回退到全科");
        assertTrue(session.followUpQuestions().isEmpty(), "停止追问后不应返回追问问题");
    }

    @Test
    void triage_followUpRoundTwo_stillFollowsUp() {
        when(catalogLoader.getCatalog()).thenReturn(testCatalog());

        // 第2轮（followUpRound=2 < 3），得分不足时仍应追问
        TriageSessionResult session = triageService.triage("咳嗽", 2);

        assertTrue(session.needsMoreInfo(), "第2轮（未达上限）得分不足时仍应追问");
        assertEquals(3, session.followUpRound(), "返回的 followUpRound 应为 3");
    }

    @Test
    void triage_highScoreSkipsFollowUp_regardlessOfRound() {
        when(catalogLoader.getCatalog()).thenReturn(testCatalog());

        // 得分足够时（"咳嗽低烧胸闷"=3分+IDF加分），无论追问轮次如何都不追问
        TriageSessionResult session = triageService.triage("咳嗽低烧胸闷", 3);

        assertFalse(session.needsMoreInfo(), "得分足够时不应追问");
        assertEquals(3, session.followUpRound(), "得分足够时 followUpRound 不变");
    }

    // ========== 排序优化：IDF 加权验证 ==========

    @Test
    void triage_idfBonus_uniqueKeywordScoresHigher() {
        when(catalogLoader.getCatalog()).thenReturn(testCatalog());

        // "心悸心慌"：两个高权重词，各+2分=4分
        // 加上 IDF 加分（心悸、心慌都只出现在心血管内科 → 各+0.5）
        // 总分 = 4 + 1.0 = 5.0，远超阈值4.0
        TriageSessionResult session = triageService.triage("心悸心慌");
        assertFalse(session.needsMoreInfo(), "双高权重+IDF加分应远超阈值，不追问");
        assertTrue(session.triageResult().suggestedDepartments().contains("心血管内科"),
                "应命中心血管内科");
    }

    @Test
    void triage_generalDeptPenalty_specialtyRankHigher() {
        when(catalogLoader.getCatalog()).thenReturn(testCatalog());

        // "发烧咳嗽"同时命中呼吸内科和全科
        // 呼吸内科：咳嗽(+1+IDF0.5) + 发烧(+1) = 2.5
        // 全科：发烧(+1) - 0.5(全科降权) = 0.5
        // 呼吸内科应排在全科前面
        TriageSessionResult session = triageService.triage("发烧咳嗽胸闷");
        assertFalse(session.triageResult().suggestedDepartments().isEmpty(),
                "应有匹配科室");
        if (session.triageResult().suggestedDepartments().size() > 1) {
            assertEquals("呼吸内科", session.triageResult().suggestedDepartments().get(0),
                    "专科应排在全科前面");
        }
    }

    @Test
    void triage_idfBonus_improvesConfidence() {
        when(catalogLoader.getCatalog()).thenReturn(testCatalog());

        // "胸闷心悸"：原得分=3（胸闷1+心悸2），加上IDF加分后应>=4.0
        // 胸闷出现在2个科室→+0.5，心悸出现在1个科室→+0.5，总分=4.0
        TriageSessionResult session = triageService.triage("胸闷心悸");
        assertFalse(session.needsMoreInfo(), "IDF加分后得分应达到阈值，不追问");
        assertTrue(session.triageResult().suggestedDepartments().contains("心血管内科"),
                "应命中心血管内科");
    }
}
