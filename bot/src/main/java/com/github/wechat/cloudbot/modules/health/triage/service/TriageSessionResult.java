package com.github.wechat.cloudbot.modules.health.triage.service;

import com.github.wechat.cloudbot.modules.health.common.TriageResult;

import java.util.List;

/**
 * 导诊会话结果：在 TriageResult 契约之上扩展追问状态。
 *
 * <p>当 {@link #needsMoreInfo()} 为 true 时，大模型应向用户提出
 * {@link #followUpQuestions()} 中的问题，收集更多症状信息后再次调用工具。
 * 当为 false 时，直接使用 {@link #triageResult()} 给出最终建议。</p>
 *
 * @param triageResult      导诊结果（建议科室 + 常见检查 + 免责声明）
 * @param needsMoreInfo     是否需要继续追问以细化症状
 * @param followUpQuestions 需要追问用户的问题列表（needsMoreInfo=false 时为空）
 * @param followUpRound     当前已追问轮次（0=首次调用尚未追问，1=已追问1次…）
 *                          <p>大模型下次调用工具时应将此值传入 followUpRound 参数，
 *                          达到 {@code MAX_FOLLOW_UP_ROUNDS} 后 Service 将强制停止追问。</p>
 */
public record TriageSessionResult(
        TriageResult triageResult,
        boolean needsMoreInfo,
        List<String> followUpQuestions,
        int followUpRound) {

    public TriageSessionResult {
        followUpQuestions = followUpQuestions == null ? List.of() : List.copyOf(followUpQuestions);
    }

    /**
     * 兼容构造器：不传 followUpRound 时默认为 0（首次调用）。
     */
    public TriageSessionResult(TriageResult triageResult, boolean needsMoreInfo, List<String> followUpQuestions) {
        this(triageResult, needsMoreInfo, followUpQuestions, 0);
    }
}
