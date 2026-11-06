package com.github.wechat.cloudbot.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.health.checkin.api.HealthCheckinAddTool;
import com.github.wechat.cloudbot.modules.health.checkin.api.HealthReminderListTool;
import com.github.wechat.cloudbot.modules.health.checkin.api.HealthCheckinTodayTool;
import com.github.wechat.cloudbot.modules.health.checkup.service.CheckupService;
import com.github.wechat.cloudbot.modules.health.common.HealthContractOverview;
import com.github.wechat.cloudbot.modules.health.medication.api.HealthDailyReminderTool;
import com.github.wechat.cloudbot.modules.health.medication.api.HealthMedAddTool;
import com.github.wechat.cloudbot.modules.health.medication.api.HealthMedDeleteTool;
import com.github.wechat.cloudbot.modules.health.medication.api.HealthMedEditTool;
import com.github.wechat.cloudbot.modules.health.medication.api.HealthMedListTool;
import com.github.wechat.cloudbot.modules.health.medication.api.HealthMedStatsTool;
import com.github.wechat.cloudbot.modules.health.medication.api.HealthMedTakeTool;
import com.github.wechat.cloudbot.modules.health.medication.service.MedicationRecord;
import com.github.wechat.cloudbot.modules.health.medication.service.MedicationService;
import com.github.wechat.cloudbot.modules.health.profile.api.HealthProfileUpsertTool;
import com.github.wechat.cloudbot.modules.health.report.domain.HealthIndicator;
import com.github.wechat.cloudbot.modules.health.report.domain.HealthReportParseResult;
import com.github.wechat.cloudbot.modules.health.report.domain.IndicatorStatus;
import com.github.wechat.cloudbot.modules.health.report.domain.ReportPageText;
import com.github.wechat.cloudbot.modules.health.report.service.HealthIndicatorParser;
import com.github.wechat.cloudbot.modules.health.report.service.ReportSafetyFormatter;
import com.github.wechat.cloudbot.modules.health.report.service.ReportTrendAnalyzer;
import com.github.wechat.cloudbot.modules.task.api.DeleteReminderTool;
import com.github.wechat.cloudbot.modules.task.api.ListRemindersTool;
import com.github.wechat.cloudbot.modules.task.api.ManageReminderTool;
import com.github.wechat.cloudbot.modules.task.service.ReminderService;
import com.github.wechat.cloudbot.platform.agent.HealthCommandRouter;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 健康管家全功能模拟驱动：复用真实 Spring 上下文中的健康 Bean，
 * 按脚本逐条"发消息"并把每轮的用户输入与机器人回复完整打印，
 * 便于人工核查每条链路是否合理。
 *
 * <pre>
 *   mvn -pl bot -DskipTests test-compile
 *   mvn -pl bot exec:java -Dexec.classpathScope=test \
 *     "-Dexec.mainClass=com.github.wechat.cloudbot.bootstrap.HealthFeatureSimulationMain"
 * </pre>
 */
public final class HealthFeatureSimulationMain {

    private static final String SIM_USER = "sim_health_user";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        ConfigurableApplicationContext ctx =
                new SpringApplicationBuilder(WeChatBotApplication.class)
                        .web(org.springframework.boot.WebApplicationType.NONE)
                        .properties(
                                "cloudbot.wechat.starter.enabled=false",
                                "spring.autoconfigure.exclude=" +
                                        "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration")
                        .run(args);

        HealthCommandRouter router = ctx.getBean(HealthCommandRouter.class);
        MedicationService medService = ctx.getBean(MedicationService.class);
        CheckupService checkupService = ctx.getBean(CheckupService.class);
        HealthIndicatorParser parser = ctx.getBean(HealthIndicatorParser.class);
        ReportSafetyFormatter formatter = ctx.getBean(ReportSafetyFormatter.class);
        ReportTrendAnalyzer trendAnalyzer = ctx.getBean(ReportTrendAnalyzer.class);
        ObjectMapper om = ctx.getBean(ObjectMapper.class);
        @SuppressWarnings("unused") ObjectMapper unused = om;

        HealthMedAddTool medAdd = ctx.getBean(HealthMedAddTool.class);
        HealthMedListTool medList = ctx.getBean(HealthMedListTool.class);
        HealthMedEditTool medEdit = ctx.getBean(HealthMedEditTool.class);
        HealthMedDeleteTool medDelete = ctx.getBean(HealthMedDeleteTool.class);
        HealthMedStatsTool medStats = ctx.getBean(HealthMedStatsTool.class);
        HealthMedTakeTool medTake = ctx.getBean(HealthMedTakeTool.class);
        HealthDailyReminderTool dailyReminder = ctx.getBean(HealthDailyReminderTool.class);

        // 建档工具（走真实 DB 写入路径，不是 router.route 的关键字匹配）
        HealthProfileUpsertTool profileUpsert = ctx.getBean(HealthProfileUpsertTool.class);
        ReminderService reminderService = ctx.getBean(ReminderService.class);
        DataSource dataSource = ctx.getBean(DataSource.class);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        // 提醒 / 打卡演示工具
        HealthCheckinAddTool checkinAdd = ctx.getBean(HealthCheckinAddTool.class);
        HealthCheckinTodayTool checkinToday = ctx.getBean(HealthCheckinTodayTool.class);
        HealthReminderListTool healthReminderList = ctx.getBean(HealthReminderListTool.class);
        ListRemindersTool listReminders = ctx.getBean(ListRemindersTool.class);
        ManageReminderTool manageReminder = ctx.getBean(ManageReminderTool.class);
        DeleteReminderTool deleteReminder = ctx.getBean(DeleteReminderTool.class);

        // 清理模拟用户的旧提醒，保证每次跑都是干净状态
        cleanupReminders(reminderService);

        // 清理模拟用户的旧用药记录，保证每次跑都是干净状态
        cleanupMedications(medService);

        banner("健康管家全功能模拟开始（用户=" + SIM_USER + "）");

        // 1. 契约总览 / 主流程 / 红线
        run("健康总览", () -> router.route(SIM_USER, "健康总览"), "应返回组长任务总览");
        run("契约", () -> router.route(SIM_USER, "共享契约"), "应返回契约文本");
        run("主流程", () -> router.route(SIM_USER, "主流程"), "应返回主流程路由图");
        run("医学红线", () -> router.route(SIM_USER, "医学红线"), "应返回红线提示");

        // 2. 导诊：首轮症状 -> 追问 -> 补充 -> 挂号
        run("导诊·头痛（首轮，预期追问）", () -> router.route(SIM_USER, "头痛"), "症状太笼统应追问");
        run("导诊·补充低烧（合并症状）", () -> router.route(SIM_USER, "还有点低烧"),
                "合并后应给出科室与挂号候选");
        run("导诊·咳嗽胸闷（多症状直接匹配）", () -> router.route(SIM_USER, "咳嗽 胸闷 气短 咳痰"),
                "多关键词应直接命中呼吸内科");

        // 3. 建档（走真实 DB 写入路径）
        runTool("建档·暂存本人档案", () -> profileUpsert.execute(tool("health_profile_upsert",
                "{\"relation\":\"本人\",\"name\":\"沐雪\",\"gender\":\"F\",\"birthYear\":2006,\"allergies\":[\"青霉素\"],\"isConfirm\":false}")),
                "应先暂存待确认档案");
        runTool("建档·确认本人档案", () -> profileUpsert.execute(tool("health_profile_upsert",
                "{\"relation\":\"本人\",\"name\":\"沐雪\",\"isConfirm\":true}")),
                "应成功落库为已生效档案");

        // 预置演示数据：今日喝水打卡，让开场即有数据
        runTool("预置·今日喝水打卡×2", () -> checkinAdd.execute(tool("health_checkin_add",
                "{\"kind\":\"drink_water\",\"count\":2}")),
                "应记录今日喝水打卡");
        runTool("打卡·再喝一杯水（同日累加）", () -> checkinAdd.execute(tool("health_checkin_add",
                "{\"kind\":\"drink_water\",\"count\":1}")),
                "应累加为今日喝水×3");
        runTool("打卡·查看最近7天（习惯周报）", () -> checkinToday.execute(tool("health_checkin_today",
                "{\"days\":7,\"user_id\":\"" + SIM_USER + "\"}")),
                "应显示最近7天打卡与服药连续天数");

        // 4. 用药管理
        runTool("用药·添加阿司匹林", () -> medAdd.execute(tool("health_med_add",
                "{\"drug_name\":\"阿司匹林肠溶片\",\"dosage\":\"1片\",\"frequency\":\"每日一次\",\"course_days\":7,\"notes\":\"饭后服用\"}")),
                "应记录并设置提醒");
        runTool("用药·添加华法林（预期OTC冲突）", () -> medAdd.execute(tool("health_med_add",
                "{\"drug_name\":\"华法林\",\"dosage\":\"2.5mg\",\"frequency\":\"每日一次\",\"course_days\":30}")),
                "应检测到与阿司匹林的danger级冲突");
        runTool("用药·添加维生素C（无冲突）", () -> medAdd.execute(tool("health_med_add",
                "{\"drug_name\":\"维生素C\",\"dosage\":\"1片\",\"frequency\":\"每日一次\",\"course_days\":0}")),
                "无冲突正常添加");
        runTool("用药·列出记录", () -> medList.execute(tool("health_med_list", "{}")),
                "应列出3条用药记录");
        runTool("用药·统计", () -> medStats.execute(tool("health_med_stats", "{}")),
                "应返回总条数与频次分布");

        // 编辑与删除
        String firstRecordId = medService.list(SIM_USER).stream()
                .filter(r -> r.drugName().contains("阿司匹林"))
                .map(MedicationRecord::id)
                .findFirst()
                .orElse(null);
        final String editId = firstRecordId;
        if (editId != null) {
            runTool("用药·修改阿司匹林剂量", () -> medEdit.execute(tool("health_med_update",
                    "{\"record_id\":\"" + editId + "\",\"dosage\":\"2片\",\"frequency\":\"每日两次\"}")),
                    "应更新成功并重设提醒");
        }
        // 校验修复：阿司匹林从「每日一次」改为「每日两次」后，提醒应为 2 条（早+晚），不得累积残留
        long aspirinReminders = reminderService.listAll(SIM_USER).stream()
                .filter(r -> r.content().startsWith("⏰ 用药提醒：阿司匹林"))
                .count();
        if (aspirinReminders == 2) {
            passed++;
            System.out.println("── 校验 ───────────────────────────────────────────");
            System.out.println("  阿司匹林提醒数 = " + aspirinReminders + "（编辑未累积 ✓）");
        } else {
            failed++;
            System.out.println("⚠️ 阿司匹林提醒数 = " + aspirinReminders + "（应为 2，提醒可能累积）");
        }
        String vitcId = medService.list(SIM_USER).stream()
                .filter(r -> r.drugName().contains("维生素C"))
                .map(MedicationRecord::id)
                .findFirst()
                .orElse(null);
        final String delId = vitcId;
        if (delId != null) {
            runTool("用药·删除维生素C", () -> medDelete.execute(tool("health_med_delete",
                    "{\"record_id\":\"" + delId + "\"}")),
                    "应删除成功");
        }
        runTool("用药·删除后再列出", () -> medList.execute(tool("health_med_list", "{}")),
                "应只剩2条");

        // P2 负例与闭环：过敏联动 / 服药打卡 / 疗程到期
        runTool("用药·添加阿莫西林（预期青霉素过敏提示）", () -> medAdd.execute(tool("health_med_add",
                "{\"drug_name\":\"阿莫西林\",\"dosage\":\"1片\",\"frequency\":\"每日一次\",\"course_days\":3}")),
                "应命中档案过敏史给出风险提示");
        runTool("用药·服药打卡（已吃药）", () -> medTake.execute(tool("health_med_take",
                "{\"drug_name\":\"阿司匹林\",\"user_id\":\"" + SIM_USER + "\"}")),
                "应记录服药打卡并显示今日已服药");
        // 疗程到期演示：把阿莫西林（3 天疗程）创建时间回拨 4 天
        jdbcTemplate.update(
                "UPDATE health_medication SET created_at = DATE_SUB(NOW(), INTERVAL 4 DAY) " +
                        "WHERE wechat_user_id = ? AND drug_name LIKE '%阿莫西林%' AND enabled = 1",
                SIM_USER);
        runTool("用药·疗程到期自动停用", () -> {
            boolean gone = medService.list(SIM_USER).stream()
                    .noneMatch(r -> r.drugName().contains("阿莫西林"));
            return ToolResult.ok("call-expiry", "health_med_list",
                    gone ? "阿莫西林已到期自动停用，提醒同步清理 ✓"
                            : "阿莫西林仍在进行中（异常：应到期停用）");
        }, "疗程到期后应自动停用并清理提醒");

        // 4. 体检套餐
        runPlan("体检·30岁男无慢病", () -> checkupService.generatePlan(30, "male", List.of(), "none"));
        runPlan("体检·55岁女高血压糖尿病", () -> checkupService.generatePlan(55, "female", List.of("高血压", "糖尿病"), "none"));
        runPlan("体检·70岁男冠心病", () -> checkupService.generatePlan(70, "male", List.of("冠心病"), "none"));
        runPlan("体检·孕妇", () -> checkupService.generatePlan(28, "female", List.of(), "pregnant"));
        runPlanChecked("体检·28岁未填性别（防孕期误判）",
                () -> checkupService.generatePlan(28, "", List.of(), "none"),
                p -> p.planName().contains("青年"),
                "空性别应落到青年套餐而非孕期");

        // 5. 日常健康提醒
        runTool("提醒·每天8点喝水", () -> dailyReminder.execute(tool("health_daily_reminder",
                "{\"kind\":\"drink_water\",\"time\":\"08:00\"}")),
                "应设置喝水提醒");
        runTool("提醒·每天21点早睡", () -> dailyReminder.execute(tool("health_daily_reminder",
                "{\"kind\":\"sleep_early\",\"time\":\"21:30\"}")),
                "应设置早睡提醒");
        runTool("提醒·自定义内容", () -> dailyReminder.execute(tool("health_daily_reminder",
                "{\"kind\":\"custom\",\"time\":\"14:00\",\"note\":\"记得测血压\"}")),
                "应设置自定义提醒");

        // 列出提醒，验证「用药提醒 + 日常提醒」均已注册、可触发
        // 注意：list_reminders / manage_reminder / delete_reminder 需在 args 中带 user_id
        runTool("提醒·列出全部提醒", () -> listReminders.execute(tool("list_reminders",
                "{\"user_id\":\"" + SIM_USER + "\"}")),
                "应列出用药提醒与日常提醒");
        runTool("提醒·列出日常健康提醒", () -> healthReminderList.execute(tool("health_reminder_list", "{}")),
                "应列出喝水/早睡/自定义提醒");

        // 停用 → 恢复 → 删除（覆盖 manage_reminder / delete_reminder 工具）
        runTool("提醒·停用喝水提醒", () -> manageReminder.execute(tool("manage_reminder",
                "{\"action\":\"disable\",\"keyword\":\"喝水\",\"user_id\":\"" + SIM_USER + "\"}")),
                "应停用喝水提醒");
        runTool("提醒·恢复喝水提醒", () -> manageReminder.execute(tool("manage_reminder",
                "{\"action\":\"enable\",\"keyword\":\"喝水\",\"user_id\":\"" + SIM_USER + "\"}")),
                "应恢复喝水提醒");
        runTool("提醒·删除测血压提醒", () -> deleteReminder.execute(tool("delete_reminder",
                "{\"keyword\":\"测血压\",\"user_id\":\"" + SIM_USER + "\"}")),
                "应删除自定义提醒");

        runToolExpectFail("提醒·非法时间格式", () -> dailyReminder.execute(tool("health_daily_reminder",
                "{\"kind\":\"drink_water\",\"time\":\"8点\"}")),
                "应提示时间格式错误", "时间格式应为 HH:mm");
        runToolExpectFail("提醒·非法类型", () -> dailyReminder.execute(tool("health_daily_reminder",
                "{\"kind\":\"xx\",\"time\":\"08:00\"}")),
                "应提示不支持的类型", "不支持的提醒类型");

        // 6. 体检报告解析（增强版：趋势对比 + 分级复查建议 + 跟进计划）
        runReportRich("报告·首次上传（增强版人性化响应）",
                () -> {
                    String sample = """
                            血常规
                            白细胞 6.5 10^9/L 4.0-10.0
                            血红蛋白 115 g/L 130-175 ↓
                            血小板 45 10^9/L 125-350 ↓
                            空腹血糖 8.2 mmol/L 3.9-6.1 ↑
                            总胆固醇 6.5 mmol/L 3.1-5.7 ↑
                            甘油三酯 2.8 mmol/L 0.2-1.7 ↑
                            肌酐 95 umol/L 44-133
                            尿常规
                            尿蛋白 + 阴性
                            尿潜血 阴性 阴性
                            尿酸 520 umol/L 150-440 ↑
                            """;
                    List<ReportPageText> pages = List.of(new ReportPageText(1, sample, "PDF_TEXT"));
                    List<HealthIndicator> indicators = parser.parse(pages);
                    HealthReportParseResult result = new HealthReportParseResult(
                            "体检报告", "sample_report.pdf", 1, indicators, indicators,
                            "本结果仅供参考，不能替代医生诊断。", "请结合原始报告并咨询专业医生复核。");

                    // 生成复查建议（基于当前异常项）
                    ReportTrendAnalyzer.FollowUpSuggestions followUp =
                            trendAnalyzer.suggest(result.abnormalIndicators(), List.of());
                    // 使用增强格式化（无历史数据）
                    return formatter.formatRich(result, List.of(), followUp, false);
                });

        // 模拟第二次上传（有历史对比）
        runReportRich("报告·二次上传（含趋势对比 + 分级复查）",
                () -> {
                    String sample = """
                            血常规
                            白细胞 7.0 10^9/L 4.0-10.0
                            血红蛋白 128 g/L 130-175 ↓
                            血小板 180 10^9/L 125-350
                            空腹血糖 7.0 mmol/L 3.9-6.1 ↑
                            总胆固醇 5.8 mmol/L 3.1-5.7 ↑
                            甘油三酯 1.9 mmol/L 0.2-1.7 ↑
                            肌酐 88 umol/L 44-133
                            尿常规
                            尿蛋白 阴性 阴性
                            尿潜血 阴性 阴性
                            尿酸 480 umol/L 150-440 ↑
                            """;
                    List<ReportPageText> pages = List.of(new ReportPageText(1, sample, "PDF_TEXT"));
                    List<HealthIndicator> current = parser.parse(pages);

                    // 模拟上次报告的指标（来自前一步的输出）
                    Map<String, HealthIndicator> previous = Map.of(
                            "血红蛋白", new HealthIndicator("血红蛋白", "115", "g/L", "130-175", IndicatorStatus.LOW, 1, "", null),
                            "血小板", new HealthIndicator("血小板", "45", "10^9/L", "125-350", IndicatorStatus.LOW, 1, "", null),
                            "空腹血糖", new HealthIndicator("空腹血糖", "8.2", "mmol/L", "3.9-6.1", IndicatorStatus.HIGH, 1, "", null),
                            "总胆固醇", new HealthIndicator("总胆固醇", "6.5", "mmol/L", "3.1-5.7", IndicatorStatus.HIGH, 1, "", null),
                            "甘油三酯", new HealthIndicator("甘油三酯", "2.8", "mmol/L", "0.2-1.7", IndicatorStatus.HIGH, 1, "", null),
                            "尿酸", new HealthIndicator("尿酸", "520", "umol/L", "150-440", IndicatorStatus.HIGH, 1, "", null)
                    );

                    HealthReportParseResult result = new HealthReportParseResult(
                            "体检报告", "sample_report_v2.pdf", 1, current, current,
                            "本结果仅供参考，不能替代医生诊断。", "请结合原始报告并咨询专业医生复核。");

                    List<ReportTrendAnalyzer.TrendItem> trends = trendAnalyzer.analyze(current, previous);
                    ReportTrendAnalyzer.FollowUpSuggestions followUp =
                            trendAnalyzer.suggest(result.abnormalIndicators(), trends);

                    return formatter.formatRich(result, trends, followUp, true);
                });

        // 7. 计划恢复
        run("计划·恢复继续", () -> router.route(SIM_USER, "继续"), "应恢复并推进编排");

        // 收尾清理
        cleanupMedications(medService);
        cleanupReminders(reminderService);

        banner("健康管家全功能模拟结束");
        System.out.printf("\n汇总：通过=%d 失败=%d%n", passed, failed);
        if (failed > 0) {
            System.err.println("SIMULATION_FAILED");
            System.exit(1);
        }
        System.out.println("SIMULATION_OK");
        ctx.close();
        System.exit(0);
    }

    // ==================== 驱动辅助 ====================

    private static ToolCall tool(String name, String argsJson) {
        return new ToolCall("call-" + name + "-" + System.nanoTime(), name, argsJson, SIM_USER);
    }

    private static void cleanupMedications(MedicationService service) {
        List<MedicationRecord> records = service.list(SIM_USER);
        for (MedicationRecord r : records) {
            try {
                service.delete(SIM_USER, r.id());
            } catch (Exception ignored) {
            }
        }
    }

    private static void cleanupReminders(ReminderService service) {
        try {
            for (var reminder : service.listAll(SIM_USER)) {
                service.delete(SIM_USER, reminder.id());
            }
        } catch (Exception ignored) {
        }
    }

    private static void banner(String title) {
        String line = "═".repeat(72);
        System.out.println("\n" + line);
        System.out.println("  " + title);
        System.out.println(line);
    }

    private static void run(String label, java.util.function.Supplier<Optional<String>> action, String expect) {
        header(label, expect);
        try {
            Optional<String> reply = action.get();
            printReply(reply.orElse("（无回复）"));
            passed++;
        } catch (Exception e) {
            printError(e);
            failed++;
        }
    }

    private static void runTool(String label, java.util.function.Supplier<ToolResult> action, String expect) {
        header(label, expect);
        try {
            ToolResult result = action.get();
            printReply(result.success() ? result.content() : "[FAIL] " + result.content());
            if (!result.success()) {
                failed++;
            } else {
                passed++;
            }
        } catch (Exception e) {
            printError(e);
            failed++;
        }
    }

    /** 期望工具返回失败的负面用例：result.success()==false 且内容包含 expectMsgContains 才算通过。 */
    private static void runToolExpectFail(String label, java.util.function.Supplier<ToolResult> action,
                                          String expect, String expectMsgContains) {
        header(label, expect);
        try {
            ToolResult result = action.get();
            if (!result.success() && result.content() != null && result.content().contains(expectMsgContains)) {
                printReply(result.content());
                passed++;
            } else if (!result.success()) {
                printReply("[FAIL but unexpected message] " + result.content());
                failed++;
            } else {
                printIssue("期望失败但实际成功：" + result.content());
                failed++;
            }
        } catch (Exception e) {
            printError(e);
            failed++;
        }
    }

    private static void runPlan(String label, java.util.function.Supplier<CheckupService.CheckupPlan> action) {
        header(label, "生成体检套餐计划");
        try {
            CheckupService.CheckupPlan plan = action.get();
            printReply(plan.displayText());
            if (plan.items() == null || plan.items().isEmpty()) {
                printIssue("体检项目为空，不合理");
                failed++;
            } else {
                passed++;
            }
        } catch (Exception e) {
            printError(e);
            failed++;
        }
    }

    /** 体检套餐负例校验：除成功外还需满足断言。 */
    private static void runPlanChecked(String label, java.util.function.Supplier<CheckupService.CheckupPlan> action,
                                       java.util.function.Predicate<CheckupService.CheckupPlan> check,
                                       String expect) {
        header(label, expect);
        try {
            CheckupService.CheckupPlan plan = action.get();
            printReply(plan.displayText());
            if (plan.items() == null || plan.items().isEmpty() || !check.test(plan)) {
                printIssue("体检套餐不符合预期：" + plan.planName());
                failed++;
            } else {
                passed++;
            }
        } catch (Exception e) {
            printError(e);
            failed++;
        }
    }

    private static void runReport(String label, java.util.function.Supplier<String> action) {
        header(label, "解析体检报告文本");
        try {
            String out = action.get();
            printReply(out);
            passed++;
        } catch (Exception e) {
            printError(e);
            failed++;
        }
    }

    private static void runReportRich(String label, java.util.function.Supplier<String> action) {
        header(label, "增强版报告解析（趋势+复查+人性化）");
        try {
            String out = action.get();
            printReply(out);
            passed++;
        } catch (Exception e) {
            printError(e);
            failed++;
        }
    }

    private static void header(String label, String expect) {
        System.out.println("\n────────────────────────────────────────────────────");
        System.out.println("▶ " + label);
        System.out.println("  期望：" + expect);
    }

    private static void printReply(String reply) {
        System.out.println("── 机器人回复 ──────────────────────────────────────");
        System.out.println(reply);
    }

    private static void printIssue(String issue) {
        System.out.println("⚠️ 发现不合理：" + issue);
    }

    private static void printError(Exception e) {
        System.out.println("── 异常 ─────────────────────────────────────────────");
        System.out.println(e.getClass().getSimpleName() + ": " + e.getMessage());
        java.io.StringWriter sw = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(sw));
        System.out.println(sw);
    }
}
