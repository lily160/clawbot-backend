package com.github.wechat.cloudbot.platform.agent;

import com.github.wechat.cloudbot.modules.health.common.skill.HealthSkillCatalog;
import com.github.wechat.cloudbot.modules.health.common.skill.HealthSkillDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 轻量 skill 目录入口。
 *
 * <p>当前只做“可沉淀框架”：把已经明确的能力边界、免责声明、入口点统一收口，
 * 方便后续拆成独立 skill 配置或文档，不影响现有 Agent 运行链路。</p>
 */
@Component
public class SkillRegistry {

    public List<HealthSkillDescriptor> healthSkills() {
        return HealthSkillCatalog.all();
    }
}
