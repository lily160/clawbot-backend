package com.github.wechat.cloudbot.platform.agent;

import com.github.wechat.cloudbot.platform.model.ChatMessage;

import java.util.List;

/** 本地冒烟：高德链接统一为 Markdown「[查看地图](url)」。 */
public final class AmapLinkPreserveSmokeMain {

    public static void main(String[] args) {
        String toolBody =
                "1. 瑞幸\n"
                        + "   地图：[查看地图](https://uri.amap.com/marker?poiid=B0LRAL0MCT&name=a&src=cloudbot&callnative=1)\n"
                        + "2. 西溪\n"
                        + "   地图：[查看地图](https://uri.amap.com/marker?poiid=B0AAAA1111&name=b&src=cloudbot&callnative=1)\n";

        List<ChatMessage> messages =
                List.of(
                        ChatMessage.user("附近哪有瑞幸"),
                        ChatMessage.assistantWithToolCalls(
                                null,
                                List.of(
                                        new ChatMessage.FunctionCall(
                                                "c1", "query_poi", "{\"keywords\":\"瑞幸\"}"))),
                        ChatMessage.tool("c1", toolBody));

        String llmDropped =
                "杭州余杭区的瑞幸还挺多！\n"
                        + "1. 浙大校友企业总部二期店\n"
                        + "2. 西溪印象城店\n"
                        + "点击上面的链接～";

        String fixed = AgentOrchestrator.ensureAmapLinksInReply(llmDropped, messages);
        assertContains(fixed, "[查看地图](https://uri.amap.com/marker?poiid=B0LRAL0MCT");
        assertContains(fixed, "[查看地图](https://uri.amap.com/marker?poiid=B0AAAA1111");
        if (fixed.contains("https://uri.amap.com") && !fixed.contains("[查看地图](")) {
            throw new IllegalStateException("should use markdown:\n" + fixed);
        }
        System.out.println("RESTORE_MD_OK\n----\n" + fixed);

        String bare =
                "1. 瑞幸\nhttps://uri.amap.com/marker?poiid=B0LRAL0MCT&name=%E7%91%9E&src=cloudbot&callnative=1\n"
                        + "2. 已有短链 [查看地图](https://uri.amap.com/marker?poiid=B0AAAA1111&name=x&src=cloudbot&callnative=1)";
        String beautified = AgentOrchestrator.ensureAmapLinksInReply(bare, messages);
        assertContains(
                beautified,
                "[查看地图](https://uri.amap.com/marker?poiid=B0LRAL0MCT&name=%E7%91%9E&src=cloudbot&callnative=1)");
        // 已是 Markdown 的不要变成 [[查看地图](url)](...)
        if (beautified.contains("[[查看地图]")) {
            throw new IllegalStateException("double-wrapped markdown:\n" + beautified);
        }
        int count = countOccurrences(beautified, "[查看地图](https://uri.amap.com/marker?poiid=B0AAAA1111");
        if (count != 1) {
            throw new IllegalStateException("expected one short link for B0AAAA1111, got " + count + ":\n" + beautified);
        }
        System.out.println("BEAUTIFY_OK\n----\n" + beautified);

        String noTool = AgentOrchestrator.ensureAmapLinksInReply(llmDropped, List.of(ChatMessage.user("hi")));
        if (!noTool.equals(llmDropped)) {
            throw new IllegalStateException("should not append without tool links");
        }
        System.out.println("NO_TOOL_OK");
        System.out.println("SMOKE_OK");
    }

    private static void assertContains(String hay, String needle) {
        if (hay == null || !hay.contains(needle)) {
            throw new IllegalStateException("missing [" + needle + "] in:\n" + hay);
        }
    }

    private static int countOccurrences(String hay, String needle) {
        int n = 0;
        int from = 0;
        while (true) {
            int i = hay.indexOf(needle, from);
            if (i < 0) {
                return n;
            }
            n++;
            from = i + needle.length();
        }
    }
}
