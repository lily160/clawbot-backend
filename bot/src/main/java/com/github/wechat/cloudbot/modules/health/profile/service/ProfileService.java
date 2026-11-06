package com.github.wechat.cloudbot.modules.health.profile.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.wechat.cloudbot.modules.health.common.PatientRef;
import com.github.wechat.cloudbot.modules.health.profile.api.ProfileServicePort;
import com.github.wechat.cloudbot.modules.health.profile.infra.PatientProfileEntity;
import com.github.wechat.cloudbot.modules.health.profile.infra.PatientProfileMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Year;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileService implements ProfileServicePort {

    private final PatientProfileMapper profileMapper;

    /**
     * 处理建档/更新的二次确认流。
     * <p>允许在已有生效档案时重新建档：新草稿覆盖旧草稿，确认时按 relation 替换生效行，
     * 从而支持「更新档案」流程（原实现确认后再次录入会抛"已存在"死锁）。
     * <p>确认路径的删除+升级包在同一个事务内，避免中间态丢失档案。
     */
    @Transactional
    public String handleUpsert(PatientProfileEntity inputData, String wechatUserId, boolean isConfirm) {

        if (!isConfirm) {
            if (inputData.getRelation() == null || inputData.getRelation().trim().isEmpty()) {
                throw new IllegalArgumentException("缺少必填字段: relation");
            }

            inputData.setWechatUserId(wechatUserId);
            inputData.setStatus(0);

            profileMapper.physicalDeletePending(wechatUserId, inputData.getRelation());
            profileMapper.insert(inputData);

            return String.format("已为您整理好【%s】的档案。过敏史：%s，慢性病：%s。请核对信息，回复“确认”为您保存，或直接告诉我需要修改的地方。",
                    inputData.getRelation(),
                    inputData.getAllergies() != null ? inputData.getAllergies() : "无",
                    inputData.getChronicDiseases() != null ? inputData.getChronicDiseases() : "无");

        } else {
            LambdaQueryWrapper<PatientProfileEntity> query = new LambdaQueryWrapper<>();
            query.eq(PatientProfileEntity::getWechatUserId, wechatUserId)
                    .eq(PatientProfileEntity::getRelation, inputData.getRelation())
                    .eq(PatientProfileEntity::getStatus, 0)
                    .orderByDesc(PatientProfileEntity::getCreatedAt)
                    .last("LIMIT 1");

            PatientProfileEntity pendingRecord = profileMapper.selectOne(query);

            if (pendingRecord != null) {
                profileMapper.physicalDeleteActive(wechatUserId, inputData.getRelation(), pendingRecord.getName());

                pendingRecord.setStatus(1);
                profileMapper.updateById(pendingRecord);
                return "✅ 档案已成功保存！您可以随时对我说“查看档案”或继续挂号、导诊。";
            } else {
                return "抱歉，没有找到等待确认的档案信息。请重新告诉我您要记录的内容。";
            }
        }
    }

    /**
     * 查询档案列表（严格过滤 status = 1）
     */
    public List<PatientRef> getProfiles(String wechatUserId) {
        LambdaQueryWrapper<PatientProfileEntity> query = new LambdaQueryWrapper<>();
        query.eq(PatientProfileEntity::getWechatUserId, wechatUserId)
                .eq(PatientProfileEntity::getStatus, 1);

        List<PatientProfileEntity> entities = profileMapper.selectList(query);

        return entities.stream().map(this::convertToRef).collect(Collectors.toList());
    }

    /**
     * Entity 转 Record 防腐层转换
     */
    private PatientRef convertToRef(PatientProfileEntity entity) {
        if (entity == null) return null;
        Integer currentAge = entity.getBirthYear() != null ? (Year.now().getValue() - entity.getBirthYear()) : null;

        return new PatientRef(
                String.valueOf(entity.getId()),
                entity.getName(),
                entity.getRelation(),
                entity.getGender(),
                currentAge,
                entity.getAllergies(),
                entity.getChronicDiseases(),
                entity.getMedicalHistory()
        );
    }

    @Override
    public Optional<PatientRef> findById(String patientId) {
        if (patientId == null || patientId.isBlank() || "null".equals(patientId)) {
            return Optional.empty();
        }

        Long id = parseId(patientId);
        if (id == null) {
            return Optional.empty();
        }

        PatientProfileEntity entity = profileMapper.selectById(id);

        if (entity == null || entity.getStatus() != 1) {
            return Optional.empty();
        }

        return Optional.of(convertToRef(entity));
    }

    @Override
    public PatientRef upsert(PatientRef patientRef, String wechatUserId) {
        if (patientRef == null) {
            throw new IllegalArgumentException("patientRef 不能为空");
        }
        if (wechatUserId == null || wechatUserId.isBlank()) {
            throw new IllegalArgumentException("wechatUserId 不能为空（upsert 建档必须归属到具体用户）");
        }

        PatientProfileEntity entity = new PatientProfileEntity();
        entity.setWechatUserId(wechatUserId);

        boolean isUpdate = patientRef.id() != null && !patientRef.id().isBlank() && !"null".equals(patientRef.id());
        if (isUpdate) {
            Long id = parseId(patientRef.id());
            if (id == null) {
                throw new IllegalArgumentException("patientRef.id 不是合法的数字 ID: " + patientRef.id());
            }
            // 更新前校验归属，避免越权改写他人档案
            PatientProfileEntity existing = profileMapper.selectById(id);
            if (existing == null || !wechatUserId.equals(existing.getWechatUserId())) {
                throw new IllegalArgumentException("无法更新档案：目标档案不存在或不属于当前用户");
            }
            entity.setId(id);
        }

        entity.setName(patientRef.displayName());
        entity.setRelation(patientRef.relation());
        entity.setGender(patientRef.gender());
        entity.setAllergies(patientRef.allergySummary());
        entity.setChronicDiseases(patientRef.chronicConditionSummary());
        entity.setMedicalHistory(patientRef.medicalHistory());

        if (patientRef.age() != null) {
            entity.setBirthYear(Year.now().getValue() - patientRef.age());
        }

        entity.setStatus(1);

        if (isUpdate) {
            profileMapper.updateById(entity);
        } else {
            profileMapper.insert(entity);
        }

        return convertToRef(profileMapper.selectById(entity.getId()));
    }

    /**
     * 将字符串 ID 安全解析为 Long；非法输入返回 null，
     * 避免大模型传入非数字 ID 时抛 NumberFormatException 中断整个会话。
     */
    private Long parseId(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(id.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
