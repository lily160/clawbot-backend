package com.github.wechat.cloudbot.modules.health.profile.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.github.wechat.cloudbot.modules.health.common.PatientRef;
import com.github.wechat.cloudbot.modules.health.profile.infra.PatientProfileEntity;
import com.github.wechat.cloudbot.modules.health.profile.infra.PatientProfileMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Year;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {

    @Mock
    private PatientProfileMapper profileMapper;

    @InjectMocks
    private ProfileService profileService;

    // ==========================================
    // 任务 1：异常处理单测（数据校验与防重）
    // ==========================================

    @Test
    @DisplayName("异常处理：保存草稿时如果 relation 字段为空，抛出 IllegalArgumentException")
    void testHandleUpsert_MissingRelation_ThrowsException() {
        PatientProfileEntity inputData = new PatientProfileEntity();
        // 故意不设置 relation 字段

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> profileService.handleUpsert(inputData, "user123", false)
        );

        assertEquals("缺少必填字段: relation", exception.getMessage());
        // 验证 Mapper 绝没有被调用过
        verify(profileMapper, never()).insert(any());
    }

    @Test
    @DisplayName("更新流程：已存在生效档案时重新录入应创建新草稿（可更新），不再抛异常死锁")
    void testHandleUpsert_RewriteOverActiveProfile_AllowsNewDraft() {
        PatientProfileEntity inputData = new PatientProfileEntity();
        inputData.setRelation("本人");
        inputData.setAllergies(Arrays.asList("青霉素"));

        // 即使已有生效档案（旧实现会抛 IllegalStateException），也应能创建新草稿以支持更新
        when(profileMapper.physicalDeletePending("user123", "本人")).thenReturn(1);
        when(profileMapper.insert(any(PatientProfileEntity.class))).thenReturn(1);

        String reply = profileService.handleUpsert(inputData, "user123", false);

        assertTrue(reply.contains("已为您整理好【本人】的档案"));
        assertEquals(0, inputData.getStatus());
        verify(profileMapper, times(1)).insert(any(PatientProfileEntity.class));
    }

    // ==========================================
    // 任务 2：大模型交互正常流程单测（写入草稿、确认、列表查询）
    // ==========================================

    @Test
    @DisplayName("正常流程：初次写入暂存档案 (isConfirm = false)")
    void testHandleUpsert_Draft_Success() {
        PatientProfileEntity inputData = new PatientProfileEntity();
        inputData.setRelation("爸爸");
        inputData.setAllergies(Arrays.asList("青霉素"));

        // 模拟删除旧草稿和插入新记录成功
        when(profileMapper.physicalDeletePending("user123", "爸爸")).thenReturn(1);
        when(profileMapper.insert(any(PatientProfileEntity.class))).thenReturn(1);

        String reply = profileService.handleUpsert(inputData, "user123", false);

        // 验证状态被正确置为 0 (待确认)
        assertEquals(0, inputData.getStatus());
        assertEquals("user123", inputData.getWechatUserId());

        // 验证提示语格式正确
        assertTrue(reply.contains("已为您整理好【爸爸】的档案"));
        assertTrue(reply.contains("青霉素"));

        // 验证执行了删除脏数据和插入新数据
        verify(profileMapper, times(1)).physicalDeletePending("user123", "爸爸");
        verify(profileMapper, times(1)).insert(any(PatientProfileEntity.class));
    }

    @Test
    @DisplayName("正常流程：确认生效档案 (isConfirm = true)")
    void testHandleUpsert_Confirm_Success() {
        PatientProfileEntity inputData = new PatientProfileEntity();
        inputData.setRelation("妈妈");

        PatientProfileEntity pendingRecord = new PatientProfileEntity();
        pendingRecord.setId(10L);
        pendingRecord.setRelation("妈妈");
        pendingRecord.setStatus(0); // 数据库里的待确认记录

        // 模拟查到了那条待确认的记录
        when(profileMapper.selectOne(any(Wrapper.class))).thenReturn(pendingRecord);
        when(profileMapper.updateById(any(PatientProfileEntity.class))).thenReturn(1);

        String reply = profileService.handleUpsert(inputData, "user123", true);

        // 验证状态被修改为了 1 (已生效)
        assertEquals(1, pendingRecord.getStatus());
        assertEquals("✅ 档案已成功保存！您可以随时对我说“查看档案”或继续挂号、导诊。", reply);

        verify(profileMapper, times(1)).updateById(pendingRecord);
    }

    @Test
    @DisplayName("正常流程：查询已生效的档案列表")
    void testGetProfiles_Success() {
        PatientProfileEntity entity1 = new PatientProfileEntity();
        entity1.setId(1L);
        entity1.setRelation("本人");
        entity1.setBirthYear(1990);

        PatientProfileEntity entity2 = new PatientProfileEntity();
        entity2.setId(2L);
        entity2.setRelation("妈妈");

        // 模拟返回两条已生效的数据
        when(profileMapper.selectList(any(Wrapper.class))).thenReturn(Arrays.asList(entity1, entity2));

        List<PatientRef> resultList = profileService.getProfiles("user123");

        assertEquals(2, resultList.size());

        // 验证字段映射
        assertEquals("1", resultList.get(0).id());
        assertEquals("本人", resultList.get(0).relation());
        // 验证动态年龄计算逻辑是否准确
        assertEquals(Year.now().getValue() - 1990, resultList.get(0).age());

        assertEquals("2", resultList.get(1).id());
        assertNull(resultList.get(1).age()); // 没有传 birthYear，应该为 null
    }

    // ==========================================
    // 任务 3：RPC 内部模块调用接口单测（供挂号、体检模块使用）
    // ==========================================

    @Test
    @DisplayName("RPC调用：通过 ID 查询已生效的患者档案 (成功命中)")
    void testFindById_Success() {
        PatientProfileEntity mockEntity = new PatientProfileEntity();
        mockEntity.setId(100L);
        mockEntity.setName("张老伯");
        mockEntity.setRelation("爸爸");
        mockEntity.setStatus(1); // 已生效

        when(profileMapper.selectById(100L)).thenReturn(mockEntity);

        Optional<PatientRef> result = profileService.findById("100");

        assertTrue(result.isPresent());
        assertEquals("张老伯", result.get().displayName());
        assertEquals("爸爸", result.get().relation());
    }

    @Test
    @DisplayName("RPC调用：通过 ID 查询档案时，如果状态未生效或不存在则返回 Empty")
    void testFindById_NotFoundOrUnconfirmed() {
        PatientProfileEntity draftEntity = new PatientProfileEntity();
        draftEntity.setStatus(0); // 还是草稿

        when(profileMapper.selectById(999L)).thenReturn(draftEntity);

        Optional<PatientRef> result = profileService.findById("999");

        // 必须被拦截，防止外部模块查到脏数据
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("RPC调用：内部系统直接跳过确认步骤，保存档案 (upsert)")
    void testUpsert_Rpc_Success() {
        // 构建外部传入的 DTO
        PatientRef inputDto = new PatientRef(null, "李阿姨", "妈妈", "女", 50, null, null, null);

        // 模拟插入成功，并重新从库里查出带 ID 的实体返回
        when(profileMapper.insert(any(PatientProfileEntity.class))).thenReturn(1);

        PatientProfileEntity savedEntity = new PatientProfileEntity();
        savedEntity.setId(300L);
        savedEntity.setName("李阿姨");
        savedEntity.setRelation("妈妈");
        savedEntity.setBirthYear(Year.now().getValue() - 50);

        when(profileMapper.selectById(any())).thenReturn(savedEntity);

        PatientRef result = profileService.upsert(inputDto, "wx_abc123");

        assertNotNull(result);
        assertEquals("李阿姨", result.displayName());
        // 验证年龄是否正确落库并转回
        assertEquals(50, result.age());
        // 验证内部 RPC 调用直接生效，调用了一次 insert，且写入行携带 wechatUserId（避免孤儿档案）
        verify(profileMapper, times(1)).insert(argThat(entity ->
                "wx_abc123".equals(entity.getWechatUserId())));
    }

    @Test
    @DisplayName("RPC upsert 缺少 wechatUserId 时拒绝写入（防止孤儿档案）")
    void testUpsert_RequiresWechatUserId() {
        PatientRef inputDto = new PatientRef(null, "李阿姨", "妈妈", "女", 50, null, null, null);
        assertThrows(IllegalArgumentException.class,
                () -> profileService.upsert(inputDto, null));
        assertThrows(IllegalArgumentException.class,
                () -> profileService.upsert(inputDto, " "));
        verify(profileMapper, never()).insert(any(PatientProfileEntity.class));
    }
}
