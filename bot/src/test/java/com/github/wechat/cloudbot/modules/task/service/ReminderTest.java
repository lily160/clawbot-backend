package com.github.wechat.cloudbot.modules.task.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class ReminderTest {

    @Test
    void testCreateMinimal() {
        Reminder r = new Reminder("id1", "user1", "测试提醒",
                LocalDateTime.of(2026, 7, 24, 10, 0), null, 5, null, true);
        assertEquals("id1", r.id());
        assertEquals("user1", r.userId());
        assertEquals("测试提醒", r.content());
        assertNull(r.cronExpression());
        assertEquals(5, r.advanceMinutes());
        assertNotNull(r.createdAt());
        assertTrue(r.enabled());
    }

    @Test
    void testDefaultCreatedAt() {
        Reminder r = new Reminder("id1", "user1", "test",
                LocalDateTime.of(2026, 7, 24, 10, 0), null, 0, null, true);
        assertNotNull(r.createdAt());
    }

    @Test
    void testNotifyTime() {
        LocalDateTime trigger = LocalDateTime.of(2026, 7, 24, 10, 0);
        Reminder r = new Reminder("id1", "user1", "test", trigger, null, 5, null, true);
        assertEquals(LocalDateTime.of(2026, 7, 24, 9, 55), r.notifyTime());
    }

    @Test
    void testNotifyTimeZeroAdvance() {
        LocalDateTime trigger = LocalDateTime.of(2026, 7, 24, 10, 0);
        Reminder r = new Reminder("id1", "user1", "test", trigger, null, 0, null, true);
        assertEquals(trigger, r.notifyTime());
    }

    @Test
    void testNextTriggerTimeOnce() {
        Reminder r = new Reminder("id1", "user1", "test",
                LocalDateTime.of(2026, 7, 24, 10, 0), null, 0, null, true);
        assertNull(r.nextTriggerTime());
    }

    @Test
    void testNextTriggerTimeDaily() {
        LocalDateTime trigger = LocalDateTime.of(2026, 7, 24, 10, 0);
        Reminder r = new Reminder("id1", "user1", "test", trigger, "0 0 10 * * ?", 0, null, true);
        LocalDateTime next = r.nextTriggerTime();
        assertNotNull(next);
        assertTrue(next.isAfter(LocalDateTime.now()));
        assertEquals(10, next.getHour());
        assertEquals(0, next.getMinute());
    }

    @Test
    void testNextTriggerTimeWeekly() {
        LocalDateTime trigger = LocalDateTime.of(2026, 7, 24, 10, 0);
        // Cron: every Monday at 10:00
        Reminder r = new Reminder("id1", "user1", "test", trigger, "0 0 10 * * 1", 0, null, true);
        LocalDateTime next = r.nextTriggerTime();
        assertNotNull(next);
        assertTrue(next.isAfter(LocalDateTime.now()));
        assertEquals(10, next.getHour());
        assertEquals(0, next.getMinute());
        assertEquals(1, next.getDayOfWeek().getValue());
    }

    @Test
    void testIsOneTime() {
        Reminder once = new Reminder("id1", "user1", "test",
                LocalDateTime.of(2026, 7, 24, 10, 0), null, 0, null, true);
        assertTrue(once.isOneTime());
        assertFalse(once.isRecurring());

        Reminder recurring = new Reminder("id1", "user1", "test",
                LocalDateTime.of(2026, 7, 24, 10, 0), "0 0 10 * * ?", 0, null, true);
        assertFalse(recurring.isOneTime());
        assertTrue(recurring.isRecurring());
    }

    @Test
    void testEquality() {
        LocalDateTime t = LocalDateTime.of(2026, 7, 24, 10, 0);
        Reminder r1 = new Reminder("id1", "user1", "test", t, null, 0, t, true);
        Reminder r2 = new Reminder("id1", "user1", "test", t, null, 0, t, true);
        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
    }

    @Test
    void testToString() {
        Reminder r = new Reminder("id1", "user1", "测试提醒",
                LocalDateTime.of(2026, 7, 24, 10, 0), null, 5, null, true);
        String str = r.toString();
        assertTrue(str.contains("测试提醒"));
        assertTrue(str.contains("null")); // cronExpression is null
    }
}
