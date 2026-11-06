package com.github.wechat.cloudbot.bootstrap;

import com.github.wechat.cloudbot.modules.calc.service.ExpressionEvaluator;
import com.github.wechat.cloudbot.modules.file.service.DocumentExporter;
import com.github.wechat.cloudbot.modules.file.service.DocumentTextExtractor;
import com.github.wechat.cloudbot.modules.file.service.TextFileSupport;
import com.github.wechat.cloudbot.modules.fun.service.FortuneService;
import com.github.wechat.cloudbot.modules.fun.service.JokeService;
import com.github.wechat.cloudbot.modules.persona.service.PersonaCatalog;
import com.github.wechat.cloudbot.modules.search.service.RealtimeQueryDetector;
import com.github.wechat.cloudbot.modules.voice.service.SystemVoiceCatalog;
import com.github.wechat.cloudbot.platform.agent.AmapLinkPreserveSmokeMain;

import java.time.YearMonth;

/**
 * 离线功能冒烟：不连微信、不依赖 API Key。
 *
 * <pre>
 * mvn -q test-compile exec:java "-Dexec.classpathScope=test" \
 *   "-Dexec.mainClass=com.github.wechat.cloudbot.bootstrap.OfflineFeatureSmokeMain"
 * </pre>
 */
public final class OfflineFeatureSmokeMain {

    public static void main(String[] args) throws Exception {
        checkCalc();
        checkDocuments();
        checkFun();
        checkPersonaAndVoice();
        checkRealtimeHeuristics();
        AmapLinkPreserveSmokeMain.main(new String[0]);
        System.out.println("OFFLINE_FEATURE_SMOKE_OK");
    }

    private static void checkCalc() {
        double v = ExpressionEvaluator.evaluate("2^10 + 3*4");
        if (Math.abs(v - 1036) > 1e-9) {
            throw new IllegalStateException("calc failed: " + v);
        }
        System.out.println("CALC_OK");
    }

    private static void checkDocuments() throws Exception {
        var doc =
                DocumentExporter.export(
                        "docx", "t.docx", "冒烟正文", DocumentExporter.Limits.defaults());
        String text =
                DocumentTextExtractor.extract(
                                doc.fileName(), doc.bytes(), DocumentTextExtractor.Limits.defaults())
                        .orElseThrow();
        if (!text.contains("冒烟正文")) {
            throw new IllegalStateException("docx roundtrip failed: " + text);
        }
        if (!TextFileSupport.isTextExtension("a.md")) {
            throw new IllegalStateException("text extension check failed");
        }
        System.out.println("DOCUMENT_OK");
    }

    private static void checkFun() {
        JokeService jokes = new JokeService();
        FortuneService fortune = new FortuneService();
        String joke = jokes.randomJoke();
        String luck = fortune.todayFortune();
        if (joke == null || joke.isBlank() || luck == null || luck.isBlank()) {
            throw new IllegalStateException("fun content empty");
        }
        System.out.println("FUN_OK");
    }

    private static void checkPersonaAndVoice() {
        if (PersonaCatalog.promptOf("毒舌室友").isEmpty()) {
            throw new IllegalStateException("persona missing");
        }
        if (SystemVoiceCatalog.listHint() == null || SystemVoiceCatalog.listHint().isBlank()) {
            throw new IllegalStateException("voice catalog empty");
        }
        System.out.println("PERSONA_VOICE_OK");
    }

    private static void checkRealtimeHeuristics() {
        if (!RealtimeQueryDetector.analyze("今天NBA总决赛谁赢了", YearMonth.of(2025, 6))
                .needsSearch()) {
            throw new IllegalStateException("should need web search");
        }
        if (!RealtimeQueryDetector.likelyNeedsWeather("杭州明天天气怎么样")) {
            throw new IllegalStateException("should need weather");
        }
        System.out.println("HEURISTIC_OK");
    }
}
