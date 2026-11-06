package com.github.wechat.cloudbot.platform.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolUsageTrackerTest {

    private final ToolUsageTracker tracker = new ToolUsageTracker();

    @Test
    void testInitialStatsEmpty() {
        var stats = tracker.getStats("no_calls");
        assertEquals(0, stats.calls());
        assertEquals(0, stats.failures());
        assertEquals(0, stats.totalDurationMs());
        assertEquals(0.0, stats.avgDurationMs());
        assertEquals(1.0, stats.successRate());
        assertTrue(stats.summary().contains("调用=0"));
    }

    @Test
    void testRecordCall() {
        tracker.recordCall("tool_a", true, 100);
        var stats = tracker.getStats("tool_a");
        assertEquals(1, stats.calls());
        assertEquals(0, stats.failures());
        assertEquals(100, stats.totalDurationMs());
        assertEquals(100.0, stats.avgDurationMs(), 0.001);
        assertEquals(1.0, stats.successRate(), 0.001);
    }

    @Test
    void testRecordMultipleCalls() {
        tracker.recordCall("tool_b", true, 50);
        tracker.recordCall("tool_b", false, 150);
        tracker.recordCall("tool_b", true, 100);

        var stats = tracker.getStats("tool_b");
        assertEquals(3, stats.calls());
        assertEquals(1, stats.failures());
        assertEquals(300, stats.totalDurationMs());
        assertEquals(100.0, stats.avgDurationMs(), 0.001);
        assertEquals(2.0 / 3.0, stats.successRate(), 0.001);
    }

    @Test
    void testAllStats() {
        tracker.recordCall("x", true, 10);
        tracker.recordCall("y", false, 20);

        var all = tracker.allStats();
        assertEquals(2, all.size());
        assertTrue(all.containsKey("x"));
        assertTrue(all.containsKey("y"));
    }

    @Test
    void testAllStatsReturnsCopy() {
        tracker.recordCall("a", true, 1);
        var all = tracker.allStats();
        assertThrows(UnsupportedOperationException.class, () -> all.put("b", null));
    }

    @Test
    void testReset() {
        tracker.recordCall("keep", true, 99);
        tracker.reset();
        assertTrue(tracker.allStats().isEmpty());

        var stats = tracker.getStats("keep");
        assertEquals(0, stats.calls(), "重置后统计归零");
    }

    @Test
    void testMultipleTools() {
        tracker.recordCall("alpha", true, 200);
        tracker.recordCall("beta", true, 300);
        tracker.recordCall("alpha", false, 100);

        var alpha = tracker.getStats("alpha");
        assertEquals(2, alpha.calls());
        assertEquals(1, alpha.failures());
        assertEquals(300, alpha.totalDurationMs());

        var beta = tracker.getStats("beta");
        assertEquals(1, beta.calls());
        assertEquals(0, beta.failures());
    }

    @Test
    void testRecordAllFailures() {
        tracker.recordCall("unstable", false, 10);
        tracker.recordCall("unstable", false, 20);

        var stats = tracker.getStats("unstable");
        assertEquals(2, stats.calls());
        assertEquals(2, stats.failures());
        assertEquals(0.0, stats.successRate(), 0.001);
    }

    // ========== ToolStats 记录 ==========

    @Test
    void testToolStatsRecordGetters() {
        var s = new ToolUsageTracker.ToolStats(10, 2, 5000);
        assertEquals(10, s.calls());
        assertEquals(2, s.failures());
        assertEquals(5000, s.totalDurationMs());
    }

    @Test
    void testToolStatsAvgDuration() {
        var s = new ToolUsageTracker.ToolStats(4, 0, 1000);
        assertEquals(250.0, s.avgDurationMs(), 0.001);
    }

    @Test
    void testToolStatsAvgDurationZeroCalls() {
        var s = new ToolUsageTracker.ToolStats(0, 0, 0);
        assertEquals(0.0, s.avgDurationMs(), 0.001);
    }

    @Test
    void testToolStatsSuccessRate() {
        var s = new ToolUsageTracker.ToolStats(10, 2, 0);
        assertEquals(0.8, s.successRate(), 0.001);
    }

    @Test
    void testToolStatsSuccessRateZeroCalls() {
        var s = new ToolUsageTracker.ToolStats(0, 0, 0);
        assertEquals(1.0, s.successRate(), 0.001);
    }

    @Test
    void testToolStatsSummary() {
        var s = new ToolUsageTracker.ToolStats(5, 1, 2500);
        var summary = s.summary();
        assertTrue(summary.contains("调用=5"));
        assertTrue(summary.contains("失败=1"));
        assertTrue(summary.contains("成功率=80.0%"));
        assertTrue(summary.contains("平均=500ms"));
    }

    @Test
    void testToolStatsSummaryZeroCalls() {
        var s = new ToolUsageTracker.ToolStats(0, 0, 0);
        assertTrue(s.summary().contains("调用=0"));
    }
}
