package com.github.wechat.cloudbot.modules.health.triage.service;

import com.github.wechat.cloudbot.modules.health.booking.service.BookingService;
import com.github.wechat.cloudbot.modules.health.common.BookingReceipt;
import com.github.wechat.cloudbot.modules.health.common.HealthDisclaimers;
import com.github.wechat.cloudbot.modules.health.common.PatientRef;
import com.github.wechat.cloudbot.modules.health.common.SlotOption;
import com.github.wechat.cloudbot.modules.health.common.TriageResult;
import com.github.wechat.cloudbot.modules.health.triage.infra.DepartmentCatalogLoader;
import com.github.wechat.cloudbot.modules.health.triage.infra.DepartmentEntry;
import com.github.wechat.cloudbot.modules.health.triage.rag.TriageRagService;
import com.github.wechat.cloudbot.platform.agent.HealthFlowCard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 导诊结果链路消费集成测试。
 *
 * <p>验证 TriageService 产出的 TriageResult 能被下游模块直接消费：
 * <ul>
 *   <li>BookingService.recommend() 能读取 suggestedDepartments 生成挂号候选</li>
 *   <li>HealthFlowCard.of() 能读取 suggestedDepartments + commonExams 生成卡片</li>
 *   <li>边界情况：空科室列表、null TriageResult、回退全科等</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class TriagePipelineIntegrationTest {

    @Mock
    private DepartmentCatalogLoader catalogLoader;

    @Mock
    private TriageRagService ragService;

    @InjectMocks
    private TriageService triageService;

    private final BookingService bookingService = new BookingService();

    /** 构造覆盖呼吸内科、消化内科、心血管内科、全科的测试目录 */
    private List<DepartmentEntry> testCatalog() {
        return List.of(
                new DepartmentEntry(
                        "呼吸内科", List.of("呼吸科", "肺科"),
                        List.of("咳嗽", "咳痰", "气短", "低烧", "胸闷", "哮喘", "感冒", "发烧"),
                        List.of("咳痰", "哮喘", "气短"),
                        List.of("血常规", "胸部X线", "C反应蛋白", "胸部CT", "痰培养", "肺功能检查"),
                        List.of("咳嗽多久了？有痰吗？", "有没有发烧？"),
                        "科普指引，非诊断"),
                new DepartmentEntry(
                        "消化内科", List.of("消化科", "肠胃科"),
                        List.of("胃痛", "腹痛", "腹泻", "便秘", "恶心", "呕吐", "反酸"),
                        List.of("腹泻", "便血", "反酸", "烧心"),
                        List.of("血常规", "便常规", "腹部B超", "胃镜", "肠镜", "肝功能检查", "幽门螺杆菌检测"),
                        List.of("腹痛具体在哪个位置？", "有没有腹泻或恶心？"),
                        "科普指引，非诊断"),
                new DepartmentEntry(
                        "心血管内科", List.of("心内科"),
                        List.of("胸闷", "胸痛", "心悸", "心慌", "气短", "高血压"),
                        List.of("心绞痛", "心悸", "心慌"),
                        List.of("心电图", "心脏彩超", "血压监测", "动态心电图", "冠脉CT", "血脂检查"),
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

    // ========== 链路一：TriageService → TriageResult → BookingService ==========

    @Test
    void pipeline_triageResult_consumedByBookingService() {
        when(catalogLoader.getCatalog()).thenReturn(testCatalog());

        // 1. 导诊服务产出结果
        TriageSessionResult session = triageService.triage("咳嗽低烧胸闷");
        TriageResult triageResult = session.triageResult();

        // 2. TriageResult 字段完整性
        assertNotNull(triageResult);
        assertFalse(triageResult.suggestedDepartments().isEmpty(), "应有建议科室");
        assertFalse(triageResult.commonExams().isEmpty(), "应有检查项目");
        assertEquals(HealthDisclaimers.TRIAGE_DISCLAIMER, triageResult.disclaimer(),
                "免责声明应使用统一常量");

        // 3. BookingService 直接消费 TriageResult；沙箱无真实适配器时不应伪造号源
        PatientRef patient = new PatientRef("1", "测试用户", "本人", "男", 30, null, null, null);
        List<SlotOption> slots = bookingService.recommend("咳嗽低烧胸闷", patient, triageResult);

        // 4. 无真实适配器（沙箱返回空）时，绝不应把伪造号源冒充真实号源
        assertTrue(slots.isEmpty(), "无真实适配器时不应伪造挂号号源");
    }

    @Test
    void pipeline_highScoreTriageResult_bookingDepartmentMatches() {
        when(catalogLoader.getCatalog()).thenReturn(testCatalog());

        // "心悸心慌" → 心血管内科（高权重+IDF加分，得分5.0）
        TriageSessionResult session = triageService.triage("心悸心慌");
        TriageResult triageResult = session.triageResult();

        assertFalse(session.needsMoreInfo(), "得分足够不应追问");
        assertEquals("心血管内科", triageResult.suggestedDepartments().get(0),
                "首选科室应为心血管内科");

        // 挂号服务消费导诊首选科室；无真实适配器时不伪造号源
        PatientRef patient = new PatientRef("1", "测试", "本人", "男", 30, null, null, null);
        List<SlotOption> slots = bookingService.recommend("心悸心慌", patient, triageResult);

        assertTrue(slots.isEmpty(), "无真实适配器时不应伪造挂号号源");
    }

    // ========== 链路二：TriageService → TriageResult → HealthFlowCard ==========

    @Test
    void pipeline_triageResult_consumedByHealthFlowCard() {
        when(catalogLoader.getCatalog()).thenReturn(testCatalog());

        TriageSessionResult session = triageService.triage("咳嗽低烧胸闷");
        TriageResult triageResult = session.triageResult();

        // HealthFlowCard.of() 直接消费 TriageResult
        HealthFlowCard card = HealthFlowCard.of(triageResult, List.of(), null, false, null);

        assertNotNull(card);
        assertEquals(triageResult.suggestedDepartments(), card.suggestedDepartments(),
                "卡片科室应与导诊结果一致");
        assertEquals(triageResult.commonExams(), card.commonExams(),
                "卡片检查项目应与导诊结果一致");
        assertTrue(card.summary().contains("呼吸内科"),
                "卡片摘要应包含科室名称");
    }

    @Test
    void pipeline_triageResultWithExams_healthFlowCardDisplaysExams() {
        when(catalogLoader.getCatalog()).thenReturn(testCatalog());

        TriageSessionResult session = triageService.triage("心悸心慌");
        TriageResult triageResult = session.triageResult();

        HealthFlowCard card = HealthFlowCard.of(triageResult, List.of(), null, false, null);

        assertTrue(card.summary().contains("心电图"), "卡片摘要应包含检查项目");
        assertTrue(card.summary().contains("心血管内科"), "卡片摘要应包含科室");
    }

    // ========== 边界情况：空结果和回退全科 ==========

    @Test
    void pipeline_emptyTriageResult_bookingDegradesGracefully() {
        // TriageResult 为空（无命中科室）时，BookingService 应安全降级且不伪造号源
        TriageResult emptyResult = new TriageResult(List.of(), List.of(), HealthDisclaimers.TRIAGE_DISCLAIMER);

        PatientRef patient = new PatientRef("1", "测试", "本人", "男", 30, null, null, null);
        List<SlotOption> slots = bookingService.recommend("查查", patient, emptyResult);

        assertTrue(slots.isEmpty(), "无真实适配器时不应伪造挂号号源");
    }

    @Test
    void pipeline_nullTriageResult_bookingDegradesGracefully() {
        // TriageResult 为 null 时，BookingService 应安全降级且不伪造号源
        PatientRef patient = new PatientRef("1", "测试", "本人", "男", 30, null, null, null);
        List<SlotOption> slots = bookingService.recommend("查查", patient, null);

        assertTrue(slots.isEmpty(), "null TriageResult 不应伪造挂号号源");
    }

    @Test
    void pipeline_nullTriageResult_healthFlowCardHandlesGracefully() {
        // HealthFlowCard.of() 传 null TriageResult 不应崩溃
        HealthFlowCard card = HealthFlowCard.of(null, List.of(), null, false, null);

        assertNotNull(card);
        assertTrue(card.suggestedDepartments().isEmpty(), "null 结果时科室列表应为空");
        assertTrue(card.commonExams().isEmpty(), "null 结果时检查项目应为空");
    }

    @Test
    void pipeline_noMatch_triageFallsBackToGeneral_bookingConsumes() {
        when(catalogLoader.getCatalog()).thenReturn(testCatalog());

        // "就是想查查" 无任何科室命中 → 回退全科
        // 追问上限后停止追问，给出全科结果
        TriageSessionResult session = triageService.triage("就是想查查", 3);
        TriageResult triageResult = session.triageResult();

        assertFalse(session.needsMoreInfo(), "达到追问上限应停止追问");
        assertTrue(triageResult.suggestedDepartments().contains("全科"),
                "无命中应回退全科");

        // 挂号服务能消费回退全科的结果；无真实适配器时不伪造号源
        PatientRef patient = new PatientRef("1", "测试", "本人", "男", 30, null, null, null);
        List<SlotOption> slots = bookingService.recommend("就是想查查", patient, triageResult);

        assertTrue(slots.isEmpty(), "无真实适配器时不应伪造挂号号源");
    }

    // ========== 全链路：TriageService → TriageResult → BookingService → BookingReceipt ==========

    @Test
    void pipeline_fullChain_triageToBookingReceipt() {
        when(catalogLoader.getCatalog()).thenReturn(testCatalog());

        // 1. 导诊
        TriageSessionResult session = triageService.triage("咳嗽低烧胸闷");
        TriageResult triageResult = session.triageResult();
        assertFalse(session.needsMoreInfo(), "三个症状+IDF加分应足够，不追问");

        // 2. 无真实适配器时，挂号推荐返回空且不伪造号源
        PatientRef patient = new PatientRef("1", "张三", "本人", "男", 30, null, null, null);
        List<SlotOption> slots = bookingService.recommend("咳嗽低烧胸闷", patient, triageResult);
        assertTrue(slots.isEmpty(), "无真实适配器时不应伪造挂号号源");

        // 3. 空号源下 confirm 不生成伪造回执
        Optional<BookingReceipt> receipt = bookingService.confirm("sandbox-order",
                slots.isEmpty() ? null : slots.get(0), patient);
        assertTrue(receipt.isEmpty(), "空号源不应生成伪造回执");

        // 4. HealthFlowCard 消费同一 TriageResult，空号源显示降级提示
        HealthFlowCard card = HealthFlowCard.of(triageResult, slots, receipt.orElse(null), false, null);
        assertTrue(card.summary().contains("呼吸内科"), "卡片应包含导诊科室");
        assertTrue(card.summary().contains("暂未获取到在线号源"), "无号源时卡片应显示降级提示");
    }

    @Test
    void pipeline_followUpPhase_bookingNotConsumed() {
        when(catalogLoader.getCatalog()).thenReturn(testCatalog());

        // 得分不足时进入追问阶段，TriageResult 仍可用（初步匹配结果）
        // 但挂号不应在追问阶段执行——这是编排层的职责，这里只验证数据契约可行
        TriageSessionResult session = triageService.triage("咳嗽");
        TriageResult triageResult = session.triageResult();

        assertTrue(session.needsMoreInfo(), "单症状得分不足应追问");
        assertFalse(triageResult.suggestedDepartments().isEmpty(),
                "追问时仍应有初步匹配科室供参考");

        // 即使在追问阶段，TriageResult 的数据格式仍能被 BookingService 安全消费（不伪造号源）
        PatientRef patient = new PatientRef("1", "测试", "本人", "男", 30, null, null, null);
        List<SlotOption> slots = bookingService.recommend("咳嗽", patient, triageResult);
        assertTrue(slots.isEmpty(), "无真实适配器时不应伪造挂号号源");
    }

    // ========== 契约验证：TriageResult 不可变性和线程安全 ==========

    @Test
    void contract_triageResultFieldsAreImmutable() {
        when(catalogLoader.getCatalog()).thenReturn(testCatalog());

        TriageSessionResult session = triageService.triage("咳嗽低烧胸闷");
        TriageResult result = session.triageResult();

        // record 自带不可变性，但验证列表字段确实被 copyOf 了
        assertDoesNotThrow(() -> {
            List<String> depts = result.suggestedDepartments();
            // 试图修改会抛出 UnsupportedOperationException（List.copyOf 返回不可变列表）
            assertThrows(UnsupportedOperationException.class, () -> depts.add(" hacked"));
        });

        assertDoesNotThrow(() -> {
            List<String> exams = result.commonExams();
            assertThrows(UnsupportedOperationException.class, () -> exams.add("hacked"));
        });
    }

    @Test
    void contract_disclaimerAlwaysPresent() {
        when(catalogLoader.getCatalog()).thenReturn(testCatalog());

        // 无论什么症状，免责声明都必须存在
        TriageSessionResult s1 = triageService.triage("咳嗽低烧胸闷");
        assertNotNull(s1.triageResult().disclaimer());
        assertEquals(HealthDisclaimers.TRIAGE_DISCLAIMER, s1.triageResult().disclaimer());

        // 无命中也应有免责声明
        TriageSessionResult s2 = triageService.triage("就是想查查", 3);
        assertNotNull(s2.triageResult().disclaimer());
        assertEquals(HealthDisclaimers.TRIAGE_DISCLAIMER, s2.triageResult().disclaimer());
    }

    @Test
    void contract_noDiagnosisFieldInTriageResult() {
        when(catalogLoader.getCatalog()).thenReturn(testCatalog());

        TriageSessionResult session = triageService.triage("咳嗽低烧胸闷");
        TriageResult result = session.triageResult();

        // TriageResult 只允许 suggestedDepartments、commonExams、disclaimer 三个字段
        // 不允许出现 diagnosis / diseaseName 等诊断字段（分工文档 4.5 明确禁止）
        // record 的字段是编译期固定的，这里验证字段列表不包含禁止字段
        java.lang.reflect.RecordComponent[] components = TriageResult.class.getRecordComponents();
        for (java.lang.reflect.RecordComponent rc : components) {
            String name = rc.getName();
            assertFalse(name.contains("diagnosis") || name.contains("disease"),
                    "TriageResult 不允许包含诊断字段: " + name);
        }
        assertEquals(3, components.length, "TriageResult 应只有3个字段");
    }
}
