package com.github.wechat.cloudbot.modules.health.checkin.service;

import com.github.wechat.cloudbot.modules.health.checkin.infra.HealthCheckin;
import com.github.wechat.cloudbot.modules.health.checkin.infra.HealthReminderKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CheckinServiceTest {

    private CheckinService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = h2DataSource("checkinservicetest");
        service = new CheckinService(ds);
        service.ensureTable();
    }

    @Test
    void checkinInsertsAndAccumulates() {
        HealthCheckin first = service.checkin("u1", HealthReminderKind.DRINK_WATER,
                today(), 1, "");
        HealthCheckin second = service.checkin("u1", HealthReminderKind.DRINK_WATER,
                today(), 2, "");
        assertEquals(1, first.count());
        assertEquals(3, second.count(), "同日同类应累加次数");
        List<HealthCheckin> today = service.getToday("u1");
        assertEquals(1, today.size());
        assertEquals("drink_water", today.get(0).kind());
        assertEquals(3, today.get(0).count());
    }

    @Test
    void differentKindSeparateRows() {
        service.checkin("u1", HealthReminderKind.DRINK_WATER, today(), 1, "");
        service.checkin("u1", HealthReminderKind.SLEEP_EARLY, today(), 1, "");
        assertEquals(2, service.getToday("u1").size());
    }

    @Test
    void differentDateSeparateRow() {
        service.checkin("u1", HealthReminderKind.DRINK_WATER, today(), 1, "");
        service.checkin("u1", HealthReminderKind.DRINK_WATER, today().minusDays(1), 2, "");
        assertEquals(2, service.getRecent("u1", 3).size());
    }

    @Test
    void userIsolation() {
        service.checkin("u1", HealthReminderKind.DRINK_WATER, today(), 1, "");
        service.checkin("u2", HealthReminderKind.EXERCISE, today(), 1, "");
        assertEquals(1, service.getToday("u1").size());
        assertEquals(1, service.getToday("u2").size());
    }

    @Test
    void nonCheckinKindRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.checkin("u1", HealthReminderKind.FOLLOWUP, today(), 1, ""));
    }

    @Test
    void medTakeInsertsAndAccumulates() {
        HealthCheckin first = service.medTake("u1", "阿司匹林", today());
        HealthCheckin second = service.medTake("u1", "阿司匹林", today());
        assertEquals(1, first.count());
        assertEquals(2, second.count(), "同日重复服药应累加次数");
        assertEquals("阿司匹林", second.remark());

        HealthCheckin third = service.medTake("u1", "维生素C", today());
        assertEquals(3, third.count());
        assertEquals("阿司匹林、维生素C", third.remark(), "不同药品应合并进当日清单");
        assertEquals(1, service.getToday("u1").size(), "服药打卡同日应只有一行");
    }

    @Test
    void medTakeDifferentDatesSeparateRows() {
        service.medTake("u1", "阿司匹林", today());
        service.medTake("u1", "阿司匹林", today().minusDays(1));
        assertEquals(2, service.getRecent("u1", 3).size());
    }

    @Test
    void medTakeBlankDrugThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> service.medTake("u1", " ", today()));
    }

    @Test
    void medTakeStreakCountsConsecutiveDays() {
        service.medTake("u1", "阿司匹林", today());
        service.medTake("u1", "维生素C", today().minusDays(1));
        assertEquals(2, service.medTakeStreak("u1"));
    }

    @Test
    void medTakeStreakBreaksWhenMissingDay() {
        service.medTake("u1", "阿司匹林", today().minusDays(2));
        assertEquals(0, service.medTakeStreak("u1"), "今天没打卡时连续天数应为 0");
    }

    @Test
    void medTakeAppearsInSummaryText() {
        service.medTake("u1", "阿司匹林", today());
        String text = service.summaryText(service.getToday("u1"), 1);
        assertTrue(text.contains("💊 服药"), "汇总应展示服药打卡，实际输出=" + text);
        assertTrue(text.contains("阿司匹林"));
    }

    @Test
    void blankUserIdThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> service.checkin("", HealthReminderKind.DRINK_WATER, today(), 1, ""));
    }

    @Test
    void getRecentClampsDays() {
        service.checkin("u1", HealthReminderKind.DRINK_WATER, today(), 1, "");
        assertFalse(service.getRecent("u1", 0).isEmpty(), "days<1 应按 1 处理");
    }

    @Test
    void summaryTextFormats() {
        service.checkin("u1", HealthReminderKind.DRINK_WATER, today(), 2, "");
        String text = service.summaryText(service.getToday("u1"), 1);
        assertTrue(text.contains("今日打卡"));
        assertTrue(text.contains("喝水"));
        assertTrue(text.contains("×2"));
    }

    @Test
    void emptySummaryEncourages() {
        String text = service.summaryText(service.getToday("u1"), 1);
        assertTrue(text.contains("还没有打卡"));
    }

    @Test
    void concurrentCheckinAccumulatesWithoutLostUpdates() throws Exception {
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    service.checkin("u1", HealthReminderKind.DRINK_WATER, today(), 1, "");
                } catch (Throwable t) {
                    firstError.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(15, TimeUnit.SECONDS), "并发打卡应在 15s 内完成");
        pool.shutdownNow();
        assertNull(firstError.get(), "并发打卡不应抛异常: " + firstError.get());

        List<HealthCheckin> today = service.getToday("u1");
        assertEquals(1, today.size());
        assertEquals(threads, today.get(0).count(),
                "并发 20 次打卡后 count 应为 20（原子自增，无丢失更新）");
    }

    @Test
    void concurrentMedTakeAccumulatesWithoutLostUpdates() throws Exception {
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    service.medTake("u1", "阿司匹林", today());
                } catch (Throwable t) {
                    firstError.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(15, TimeUnit.SECONDS), "并发服药打卡应在 15s 内完成");
        pool.shutdownNow();
        assertNull(firstError.get(), "并发服药打卡不应抛异常: " + firstError.get());

        List<HealthCheckin> today = service.getToday("u1");
        assertEquals(1, today.size());
        assertEquals(threads, today.get(0).count(),
                "并发 20 次服药打卡后 count 应为 20（原子自增，无丢失更新）");
    }

    private static LocalDate today() {
        return LocalDate.now();
    }

    private static DriverManagerDataSource h2DataSource(String name) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:" + name + "_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        return ds;
    }
}
