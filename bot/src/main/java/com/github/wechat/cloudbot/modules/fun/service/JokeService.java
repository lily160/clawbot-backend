package com.github.wechat.cloudbot.modules.fun.service;

import org.springframework.stereotype.Service;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 笑话、谜语、一言数据与随机选取。
 */
@Service
public class JokeService {

    private static final List<String> JOKES = List.of(
            "为什么程序员总是把万圣节和圣诞节搞混？因为 Oct 31 == Dec 25。",
            "有一天 0 和 1 在聊天，0 说：「我好累，感觉自己没什么用。」1 说：「别这么说，没有你，我就只是个 1。」",
            "程序员 A：「我昨天写了 500 行代码。」程序员 B：「我昨天删了 500 行代码，大家都叫我架构师。」",
            "产品经理：「这个需求很简单，怎么实现我不管。」程序员卒。",
            "Debug 就像是在黑暗的房间里找一只不存在的黑猫。Code Review 是开着灯找。",
            "「这段代码是谁写的？」「是我。」「那为什么有 bug？」「因为那时候的我还不认识现在的我。」",
            "Java 程序员和 C 程序员一起跳楼。Java 程序员先跳，结果掉到了 10 层的 catch 块里。",
            "「你对象呢？」「new 一个就有了。」",
            "为什么鲨鱼不吃程序员？因为程序员的代码里有太多 bug。",
            "世界上有两种程序员：一种已经用上了 AI 写代码，另一种正在 Google 怎么装 Copilot。"
    );

    private static final List<String> RIDDLES = List.of(
            "什么东西越洗越脏？(答案：水)",
            "什么东西你有，别人也有，但别人用的时候你却不能碰？(答案：你的名字)",
            "什么东西越削越大？(答案：洞)",
            "什么瓜不能吃？(答案：傻瓜)",
            "什么动物最容易摔跤？(答案：狐狸，因为它很狡猾——脚滑)"
    );

    private static final List<String> QUOTES = List.of(
            "Talk is cheap. Show me the code. — Linus Torvalds",
            "任何足够先进的技术，都与魔法无异。 — Arthur C. Clarke",
            "简单是可靠的前置条件。 — Edsger Dijkstra",
            "人生苦短，我用 Python。",
            "代码如诗。"
    );

    private static final ThreadLocalRandom RNG = ThreadLocalRandom.current();

    public String randomJoke() { return JOKES.get(RNG.nextInt(JOKES.size())); }

    public String randomRiddle() { return RIDDLES.get(RNG.nextInt(RIDDLES.size())); }

    public String randomQuote() { return QUOTES.get(RNG.nextInt(QUOTES.size())); }

    public String random(String type) {
        if (type == null || type.isBlank()) return randomJoke();
        return switch (type.trim().toLowerCase()) {
            case "riddle" -> randomRiddle();
            case "quote" -> randomQuote();
            default -> randomJoke();
        };
    }
}
