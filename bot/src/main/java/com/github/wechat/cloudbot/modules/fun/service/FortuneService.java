package com.github.wechat.cloudbot.modules.fun.service;

import org.springframework.stereotype.Service;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 骰子、抽签、运势等随机娱乐。
 */
@Service
public class FortuneService {

    private static final List<String> LOTTERY = List.of(
            "大吉 🌟", "中吉 ✨", "小吉 🌈", "吉 🍀", "末吉 🌿", "凶 ⛈️", "大凶 🌪️"
    );

    private static final List<String> FORTUNES = List.of(
            "今天你会收到一条好消息 📨",
            "适合出门走走，可能会遇到贵人 🚶",
            "今天适合 coding，bug 会特别少 💻",
            "今天不适合加班，早点回家休息 🏠"
    );

    private static final ThreadLocalRandom RNG = ThreadLocalRandom.current();

    public int rollDice() { return RNG.nextInt(1, 7); }

    public String drawLottery() {
        return "🔮 你抽到了：" + LOTTERY.get(RNG.nextInt(LOTTERY.size()));
    }

    public String todayFortune() {
        int star = RNG.nextInt(1, 6);
        return "✨ 今日运势\n" + "⭐".repeat(star) + " (" + star + "/5)\n" + FORTUNES.get(RNG.nextInt(FORTUNES.size()));
    }
}
