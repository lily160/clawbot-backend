package com.github.wechat.cloudbot.modules.persona.service;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 纯趣味文案与 ASCII 素材（登录祝福）。
 */
public final class FunFlavorTexts {

    private static final String[] WELCOME_LINES = {
        "夏令营小助手已就位，请用【微信小号】扫码入场～",
        "欢迎来到 Cloudbot 趣味实验场，扫码开启今日冒险！",
        "叮！神秘二维码已生成，扫完它，故事就开始了。",
        "像素猫说：扫码的人今天运气不错哦。",
        "喝杯水，扫个码，让我们聊聊今天发生的好事。",
        "扫码登录中……后台的小本本已经翻开啦。",
    };

    private static final String[][] PIXEL_CATS = {
        {
            "  /\\_/\\  ",
            " ( o.o ) ",
            "  > ^ <  ",
            " /|   |\\ ",
        },
        {
            "  /\\_/\\  ",
            " ( =^.^=)",
            "  (   )  ",
            "  |\" \"|  ",
        },
        {
            "   /\\_/\\   ",
            "  ( •.• )  ",
            "   /   \\   ",
            "  (_____ )  ",
        },
        {
            "  /\\_/\\  ",
            " ( >w< ) ",
            "  /| |\\  ",
            " (_| |_)",
        },
    };

    private FunFlavorTexts() {}

    public static String randomWelcome() {
        return pick(WELCOME_LINES);
    }

    public static String[] randomPixelCat() {
        return PIXEL_CATS[ThreadLocalRandom.current().nextInt(PIXEL_CATS.length)];
    }

    private static String pick(String[] options) {
        return options[ThreadLocalRandom.current().nextInt(options.length)];
    }
}
