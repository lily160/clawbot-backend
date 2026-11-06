package com.github.wechat.cloudbot.bootstrap;

import com.github.wechat.cloudbot.modules.health.checkin.infra.HealthReminderKind;
import com.github.wechat.cloudbot.modules.health.checkin.service.CheckinService;
import com.github.wechat.cloudbot.modules.health.common.PatientRef;
import com.github.wechat.cloudbot.modules.health.medication.service.MedicationService;
import com.github.wechat.cloudbot.modules.health.profile.service.ProfileService;
import com.github.wechat.cloudbot.modules.health.report.domain.HealthIndicator;
import com.github.wechat.cloudbot.modules.health.report.domain.HealthReportParseResult;
import com.github.wechat.cloudbot.modules.health.report.domain.IndicatorStatus;
import com.github.wechat.cloudbot.modules.health.report.service.HealthReportRepository;
import com.github.wechat.cloudbot.modules.task.service.ReminderService;
import com.github.wechat.cloudbot.platform.persistence.ChatMemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 大容量数据仿真：向本地 MySQL 批量灌入多用户健康数据，验证并发/数据一致性修复在真实库上的表现。
 *
 * <pre>
 * mvn -pl bot test-compile
 * mvn -pl bot org.codehaus.mojo:exec-maven-plugin:3.2.0:java -Dexec.classpathScope=test \
 *   "-Dexec.mainClass=com.github.wechat.cloudbot.bootstrap.DataVolumeSimulationMain"
 * </pre>
 *
 * 覆盖点：
 *  A. 多用户批量数据（100 用户 × 建档/用药/提醒/打卡/聊天/报告）
 *  B. 单用户高密度用药（25 条，接近上限）
 *  C. 并发打卡原子性（30 线程同时打卡 count 不丢失）
 *  D. 并发服药打卡原子性（30 线程 medTake 不丢失）
 *  E. 并发聊天历史 seq 不重叠（40 线程 × appendTurn，seq 严格递增且不重复）
 *  F. 疗程到期自动停用 + 提醒清理
 *  G. 报告与指标同事务落库（indicator_count 与明细行数一致）
 *  H. 清理全部仿真数据并断言清零
 */
public final class DataVolumeSimulationMain {

    private static final Logger log = LoggerFactory.getLogger(DataVolumeSimulationMain.class);

    private static final String PREFIX = "sim_vol_";
    private static final int USER_COUNT = 100;
    private static final int CONCURRENCY = 30;
    private static final int SEQ_THREADS = 40;

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        long startNanos = System.nanoTime();
        ConfigurableApplicationContext ctx =
                new SpringApplicationBuilder(WeChatBotApplication.class)
                        .web(WebApplicationType.NONE)
                        .properties(
                                "cloudbot.wechat.starter.enabled=false",
                                "spring.autoconfigure.exclude=" +
                                        "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration")
                        .run(args);

        try {
            ProfileService profileService = ctx.getBean(ProfileService.class);
            MedicationService medicationService = ctx.getBean(MedicationService.class);
            ReminderService reminderService = ctx.getBean(ReminderService.class);
            CheckinService checkinService = ctx.getBean(CheckinService.class);
            ChatMemoryRepository chatMemory = ctx.getBean(ChatMemoryRepository.class);
            HealthReportRepository reportRepository = ctx.getBean(HealthReportRepository.class);
            JdbcTemplate jdbc = new JdbcTemplate(ctx.getBean(javax.sql.DataSource.class));

            banner("A. 批量灌数：100 用户");
            bulkData(profileService, medicationService, reminderService, checkinService,
                    chatMemory, reportRepository, jdbc);

            banner("B. 单用户高密度用药");
            burstMedications(medicationService);

            banner("C. 并发打卡原子性（30 线程）");
            concurrentCheckin(checkinService, jdbc);

            banner("D. 并发服药打卡原子性（30 线程）");
            concurrentMedTake(checkinService, jdbc);

            banner("E. 并发聊天历史 seq 一致性（40 线程）");
            concurrentChatSeq(chatMemory, jdbc);

            banner("F. 疗程到期自动停用");
            expirySweep(medicationService, jdbc);

            banner("G. 报告与指标同事务落库");
            reportIntegrity(reportRepository, jdbc);

            banner("H. 清理全部仿真数据");
            cleanup(jdbc);
        } finally {
            ctx.close();
        }

        double secs = (System.nanoTime() - startNanos) / 1_000_000_000.0;
        System.out.printf("\n══════════════════════════════════════════════════════\n");
        System.out.printf("  大容量数据仿真结束：通过=%d 失败=%d 耗时=%.1fs%n", passed, failed, secs);
        System.out.printf("══════════════════════════════════════════════════════\n");
        if (failed > 0) {
            System.err.println("DATA_VOLUME_SIM_FAILED");
            System.exit(1);
        }
        System.out.println("DATA_VOLUME_SIM_OK");
    }

    // ─── A. 批量灌数 ─────────────────────────────────────────────

    private static void bulkData(ProfileService profiles, MedicationService meds,
                                 ReminderService reminders, CheckinService checkins,
                                 ChatMemoryRepository chat, HealthReportRepository reports,
                                 JdbcTemplate jdbc) {
        int errors = 0;
        for (int i = 0; i < USER_COUNT; i++) {
            String userId = PREFIX + "u" + String.format("%03d", i);
            boolean even = (i % 2 == 0);
            try {
                // 建档
                profiles.upsert(new PatientRef(null, "仿真用户" + i, "本人",
                        even ? "F" : "M", 20 + (i % 50),
                        even ? List.of("青霉素") : List.of(),
                        i % 3 == 0 ? List.of("高血压") : List.of(),
                        null), userId);
                // 用药 ×2
                meds.add(userId, "阿司匹林肠溶片", "1片", "每日一次", 30, "仿真数据");
                meds.add(userId, "维生素C", "1片", "每日一次", 0, "仿真数据");
                // 提醒
                reminders.add(userId, "仿真喝水提醒", LocalDateTime.now().plusDays(1), 0, "0 0 8 * * ?");
                // 打卡 + 服药打卡
                checkins.checkin(userId, HealthReminderKind.DRINK_WATER,
                        LocalDate.now(), 1, null);
                checkins.medTake(userId, "阿司匹林", LocalDate.now());
                // 聊天历史 ×3 轮
                for (int t = 0; t < 3; t++) {
                    chat.appendTurn(userId, "仿真问题" + t, "仿真回答" + t);
                }
                // 报告 + 指标（同事务）
                reports.saveReportWithIndicators(userId, "sim_report.pdf", sampleResult(), "{}");
            } catch (Exception e) {
                errors++;
                log.error("批量灌数失败 userId={}", userId, e);
            }
        }
        check("批量灌数 100 用户无异常", errors == 0, "errors=" + errors);
        check("100 用户档案已落库", countWhere(jdbc, "health_patient_profile", "wechat_user_id", PREFIX) == USER_COUNT,
                "profileCount=" + countWhere(jdbc, "health_patient_profile", "wechat_user_id", PREFIX));
        check("200 条用药已落库", countWhere(jdbc, "health_medication", "wechat_user_id", PREFIX) == USER_COUNT * 2L,
                "medCount=" + countWhere(jdbc, "health_medication", "wechat_user_id", PREFIX));
        // 每用户 1 条显式提醒 + 2 种药的用药提醒（每日一次各 1 条）= 3 条
        check("300 条提醒已落库（1 显式 + 2 用药提醒/人）",
                countWhere(jdbc, "scheduled_task", "user_id", PREFIX) == USER_COUNT * 3L,
                "reminderCount=" + countWhere(jdbc, "scheduled_task", "user_id", PREFIX));
        check("100 条打卡已落库", countWhere(jdbc, "health_checkin", "wechat_user_id", PREFIX) == USER_COUNT * 2L,
                "checkinCount=" + countWhere(jdbc, "health_checkin", "wechat_user_id", PREFIX));
        check("600 条聊天历史已落库", countWhere(jdbc, "chat_message", "user_id", PREFIX) == USER_COUNT * 6L,
                "chatCount=" + countWhere(jdbc, "chat_message", "user_id", PREFIX));
        check("100 份报告已落库", countWhere(jdbc, "health_report_record", "user_id", PREFIX) == USER_COUNT,
                "reportCount=" + countWhere(jdbc, "health_report_record", "user_id", PREFIX));
    }

    // ─── B. 单用户高密度用药 ─────────────────────────────────────

    private static void burstMedications(MedicationService meds) {
        String userId = PREFIX + "burst";
        try {
            for (int i = 0; i < 25; i++) {
                meds.add(userId, "仿真药品" + i, i + "片", i % 2 == 0 ? "每日一次" : "每日两次",
                        i % 5 == 0 ? 0 : 30, "高密度");
            }
        } catch (Exception e) {
            check("单用户高密度用药未触顶", false, e.toString());
            return;
        }
        int count = meds.list(userId).size();
        check("单用户 25 条用药全部存活", count == 25, "count=" + count);
    }

    // ─── C. 并发打卡原子性 ───────────────────────────────────────

    private static void concurrentCheckin(CheckinService checkins, JdbcTemplate jdbc) {
        String userId = PREFIX + "conc";
        runConcurrent(CONCURRENCY, i ->
                checkins.checkin(userId,
                        HealthReminderKind.DRINK_WATER,
                        LocalDate.now(), 1, "并发测试"));
        Integer finalCount = jdbc.queryForObject(
                "SELECT count FROM health_checkin WHERE wechat_user_id = ? AND kind = ? AND checkin_date = ?",
                Integer.class, userId, "drink_water", java.sql.Date.valueOf(LocalDate.now()));
        check("并发打卡 30 次最终 count=30（无丢失更新）", finalCount != null && finalCount == CONCURRENCY,
                "finalCount=" + finalCount);
    }

    // ─── D. 并发服药打卡原子性 ───────────────────────────────────

    private static void concurrentMedTake(CheckinService checkins, JdbcTemplate jdbc) {
        String userId = PREFIX + "conc";
        runConcurrent(CONCURRENCY, i -> checkins.medTake(userId, "阿司匹林", LocalDate.now()));
        Integer finalCount = jdbc.queryForObject(
                "SELECT count FROM health_checkin WHERE wechat_user_id = ? AND kind = ? AND checkin_date = ?",
                Integer.class, userId, "med_take", java.sql.Date.valueOf(LocalDate.now()));
        check("并发服药打卡 30 次最终 count=30（无丢失更新）", finalCount != null && finalCount == CONCURRENCY,
                "finalCount=" + finalCount);
    }

    // ─── E. 并发聊天历史 seq 一致性 ──────────────────────────────

    private static void concurrentChatSeq(ChatMemoryRepository chat, JdbcTemplate jdbc) {
        String userId = PREFIX + "seq";
        runConcurrent(SEQ_THREADS, i -> chat.appendTurn(userId, "并发问题" + i, "并发回答" + i));
        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE user_id = ?", Long.class, userId);
        // trim 按 max-history-turns(30)×2=60 条裁剪；并发写入 80 条后应落在 [60,80] 内（不应丢失到 60 以下）
        long trimFloor = 60L;
        check("并发 40 线程聊天记录数在裁剪范围内 [60,80]",
                rows != null && rows >= trimFloor && rows <= SEQ_THREADS * 2L,
                "rows=" + rows);
        List<Integer> seqs = jdbc.query(
                "SELECT seq FROM chat_message WHERE user_id = ? ORDER BY seq ASC",
                (rs, n) -> rs.getInt(1), userId);
        boolean strictlyOrdered = true;
        for (int i = 1; i < seqs.size(); i++) {
            if (seqs.get(i) - seqs.get(i - 1) != 1) {
                strictlyOrdered = false;
                break;
            }
        }
        boolean noDup = seqs.stream().distinct().count() == seqs.size();
        check("聊天 seq 严格递增且无重复（修复核心）", strictlyOrdered && noDup,
                "first=" + seqs.get(0) + " last=" + seqs.get(seqs.size() - 1) + " size=" + seqs.size());
    }

    // ─── F. 疗程到期自动停用 ─────────────────────────────────────

    private static void expirySweep(MedicationService meds, JdbcTemplate jdbc) {
        String userId = PREFIX + "exp";
        meds.add(userId, "到期药品", "1片", "每日一次", 3, "3天疗程");
        // 回拨 10 天，使其超过疗程
        jdbc.update("UPDATE health_medication SET created_at = DATE_SUB(NOW(), INTERVAL 10 DAY) " +
                        "WHERE wechat_user_id = ? AND drug_name LIKE '%到期药品%'",
                userId);
        meds.sweepExpiredMedications();
        Integer enabled = jdbc.queryForObject(
                "SELECT COUNT(*) FROM health_medication WHERE wechat_user_id = ? AND drug_name LIKE '%到期药品%' AND enabled = 1",
                Integer.class, userId);
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM health_medication WHERE wechat_user_id = ? AND drug_name LIKE '%到期药品%'",
                Integer.class, userId);
        check("疗程到期已自动停用（enabled=0）", enabled != null && enabled == 0 && total != null && total == 1,
                "enabled=" + enabled + " total=" + total);
        check("疗程到期提醒已同步清理", countWhere(jdbc, "scheduled_task", "user_id", userId) == 0,
                "reminderLeft=" + countWhere(jdbc, "scheduled_task", "user_id", userId));
    }

    // ─── G. 报告与指标同事务 ─────────────────────────────────────

    private static void reportIntegrity(HealthReportRepository reports, JdbcTemplate jdbc) {
        String userId = PREFIX + "rep";
        long reportId = reports.saveReportWithIndicators(userId, "integrity.pdf", sampleResult(), "{\"k\":\"v\"}");
        Integer indicatorRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM health_indicator_history WHERE report_id = ?", Integer.class, reportId);
        Integer declaredCount = jdbc.queryForObject(
                "SELECT indicator_count FROM health_report_record WHERE id = ?", Integer.class, reportId);
        check("报告指标数与明细行数一致（同事务落库）",
                indicatorRows != null && declaredCount != null && indicatorRows.equals(declaredCount)
                        && indicatorRows == 4,
                "indicatorRows=" + indicatorRows + " declared=" + declaredCount);
    }

    // ─── H. 清理 ─────────────────────────────────────────────────

    private static void cleanup(JdbcTemplate jdbc) {
        String[][] tables = {
                {"health_patient_profile", "wechat_user_id"},
                {"health_medication", "wechat_user_id"},
                {"scheduled_task", "user_id"},
                {"health_checkin", "wechat_user_id"},
                {"chat_message", "user_id"},
                {"health_report_record", "user_id"},
                {"health_indicator_history", "user_id"},
        };
        for (String[] t : tables) {
            jdbc.update("DELETE FROM " + t[0] + " WHERE " + t[1] + " LIKE ?", PREFIX + "%");
        }
        long left = 0;
        for (String[] t : tables) {
            left += countWhere(jdbc, t[0], t[1], PREFIX);
        }
        check("仿真数据已全部清理", left == 0, "left=" + left);
    }

    // ─── 辅助 ────────────────────────────────────────────────────

    private static long countWhere(JdbcTemplate jdbc, String table, String column, String prefix) {
        if (jdbc == null) {
            return -1;
        }
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " LIKE ?",
                Long.class, prefix + "%");
        return c == null ? 0 : c;
    }

    private static void runConcurrent(int threads, java.util.function.IntConsumer action) {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    action.accept(idx);
                } catch (Exception e) {
                    errors.incrementAndGet();
                    log.error("并发任务异常", e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        try {
            boolean finished = done.await(60, TimeUnit.SECONDS);
            check("并发任务在 60s 内完成", finished && errors.get() == 0,
                    "finished=" + finished + " errors=" + errors.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            check("并发任务被中断", false, e.toString());
        } finally {
            pool.shutdownNow();
        }
    }

    private static HealthReportParseResult sampleResult() {
        List<HealthIndicator> indicators = List.of(
                new HealthIndicator("血红蛋白", "128", "g/L", "130-175", IndicatorStatus.LOW, 1, "", null),
                new HealthIndicator("空腹血糖", "7.0", "mmol/L", "3.9-6.1", IndicatorStatus.HIGH, 1, "", null),
                new HealthIndicator("血小板", "180", "10^9/L", "125-350", IndicatorStatus.NORMAL, 1, "", null),
                new HealthIndicator("尿酸", "480", "umol/L", "150-440", IndicatorStatus.HIGH, 1, "", null));
        return new HealthReportParseResult(
                "仿真体检报告", "sim_report.pdf", 1, indicators, indicators,
                "本结果仅供参考，不能替代医生诊断。", "请结合原始报告并咨询专业医生复核。");
    }

    private static void banner(String title) {
        System.out.println("\n══════════════════════════════════════════════════════");
        System.out.println("  " + title);
        System.out.println("══════════════════════════════════════════════════════");
    }

    private static void check(String label, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("✅ " + label + "  [" + detail + "]");
        } else {
            failed++;
            System.out.println("❌ " + label + "  [" + detail + "]");
        }
    }
}
