package com.github.wechat.cloudbot.modules.health.profile.api;

import com.github.wechat.cloudbot.modules.health.common.PatientRef;

import java.util.Optional;

/**
 * 健康档案的最小对外契约，后续由组员在 profile 模块实现建档与查询。
 */
public interface ProfileServicePort {

    /**
     * 直接保存档案（内部 RPC 用，跳过确认步骤）。
     *
     * @param wechatUserId 档案归属的微信用户 ID，必须提供，否则行会成为孤儿数据（任何用户都查不到）
     */
    PatientRef upsert(PatientRef patientRef, String wechatUserId);

    Optional<PatientRef> findById(String patientId);
}
