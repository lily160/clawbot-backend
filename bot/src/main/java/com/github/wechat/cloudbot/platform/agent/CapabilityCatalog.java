package com.github.wechat.cloudbot.platform.agent;

import java.util.Collection;
import java.util.regex.Pattern;

/**
 * 面向用户的能力说明（微信口吻）。与 Agent Tools / 口令能力对齐，避免模型凭空编造。
 */
public final class CapabilityCatalog {

    private static final Pattern ASKS_CAPABILITIES =
            Pattern.compile(
                    ".*(有什么功能|有些什么功能|有哪些功能|都有什么功能|都有哪些功能|"
                            + "功能列表|功能介绍|你会什么|能做什么|支持什么|"
                            + "你会干嘛|有啥本事|介绍一下你自己|你能帮我做什么|"
                            + "你会哪些|有哪些能力|能力介绍).*",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private CapabilityCatalog() {}

    public static boolean looksLikeCapabilityQuery(String userContent) {
        if (userContent == null || userContent.isBlank()) {
            return false;
        }
        String text = userContent.trim();
        if (text.length() > 80) {
            return false;
        }
        return ASKS_CAPABILITIES.matcher(text).matches()
                || "功能".equals(text)
                || "帮助".equals(text)
                || "help".equalsIgnoreCase(text)
                || "菜单".equals(text);
    }

    /** 完整能力说明（不含动态工具列表）。 */
    public static String userFacingSummary() {
        return userFacingSummary(null);
    }

    /**
     * @param enabledToolNames 当前启用的工具名；null 表示不追加动态列表
     */
    public static String userFacingSummary(Collection<String> enabledToolNames) {
        StringBuilder sb = new StringBuilder();
        sb.append(
                """
                我是微信 Cloudbot，目前可以帮你这些事：

                💬 聊天与人设
                · 多轮对话、换话题自动清历史
                · 发「变身」或「变身：知心女友」等切换人格（毒舌室友/古风仙人/赛博猫/知心女友/优秀男友）

                🔍 查信息
                · 天气（高德）· 联网搜索新闻/赛事/股价等
                · 周边地点/商户、地址转坐标 · 打开网页链接总结

                🏥 健康管家
                · 建档：创建和管理个人健康档案（过敏史、慢性病、既往病史）
                · 导诊：描述症状后智能推荐就诊科室（支持多轮追问）
                · 挂号：根据导诊结果推荐医院号源、一键跳转小程序挂号
                · 报告解析：上传体检报告 PDF，自动识别指标、标出异常项、历史趋势对比
                · 用药提醒：添加用药计划、OTC 药物冲突检测、定时提醒服药
                · 体检计划：根据年龄/性别/慢性病等生成个性化体检建议
                · 每日打卡：喝水/运动/睡眠记录，保持健康习惯

                🧮 小工具
                · 精确计算 · 翻译 · 提醒（添加/查看/删除）
                · 检索我们之前聊过的内容（关键词/语义）

                📁 文件
                · 发我 txt/md/docx/xlsx/zip，我可以读
                · 需要时我也能回传文本附件，或导出 Word/Excel/zip

                🎨 多媒体
                · 识图 / 理解视频 · 文生图 · 文生视频
                · 语音转写与语音回复 · 「切换音色」「自定义音色」做声音复刻

                🎲 轻松一下
                · 笑话/谜语/名言 · 掷骰子/抽签/运势 · 猜数字

                📝 灵感
                · 「帮我存到知识库」可保存灵感笔记（需配置 GitHub Gist）

                直接说你想做什么就行，比如「杭州明天天气」「我头疼该挂什么科」「帮我分析这份体检报告」。
                """
                        .strip());

        return sb.toString();
    }
}
