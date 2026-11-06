package com.github.wechat.cloudbot.platform.channel.wechat;

import com.github.wechat.cloudbot.platform.config.ErrorCode;
import com.github.wechat.cloudbot.platform.config.Strings;
import com.github.wechat.ilink.sdk.ILinkClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 出站网关：封装对 {@link ILinkClient} 的发送调用。
 *
 * <p>router / feature 经由此类发消息，避免散落直接调 SDK。
 */
@Component
public class OutboundGateway {

    private static final Logger log = LoggerFactory.getLogger(OutboundGateway.class);

    public void sendText(ILinkClient client, String toUserId, String text) {
        try {
            String cleaned = com.github.wechat.cloudbot.platform.agent.AgentOrchestrator.sanitizeAssistantReply(text);
            client.sendText(toUserId, cleaned);
            log.info("发送文字 to {}: {}", toUserId, Strings.summarizeOneLine(cleaned));
        } catch (IOException e) {
            log.error("{}发送文字失败 to {}: {}", ErrorCode.CHANNEL_SEND_ERROR.prefix(), toUserId, e.getMessage(), e);
        }
    }

    public void sendImage(
            ILinkClient client, String toUserId, byte[] imageBytes, String fileName, String caption)
            throws IOException {
        client.sendImage(toUserId, imageBytes, fileName, caption);
    }

    public void sendVideo(
            ILinkClient client,
            String toUserId,
            byte[] videoBytes,
            String fileName,
            Integer playLengthMs,
            String caption)
            throws IOException {
        client.sendVideo(toUserId, videoBytes, fileName, playLengthMs, caption);
    }

    public void sendFile(
            ILinkClient client, String toUserId, byte[] bytes, String fileName, String caption)
            throws IOException {
        client.sendFile(toUserId, bytes, fileName, caption);
    }

    public void sendVoice(
            ILinkClient client,
            String toUserId,
            byte[] silkBytes,
            String fileName,
            Integer playLengthMs,
            Integer sampleRate,
            String textHint,
            Integer encodeType,
            Integer bitsPerSample,
            String caption)
            throws IOException {
        client.sendVoice(
                toUserId,
                silkBytes,
                fileName,
                playLengthMs,
                sampleRate,
                textHint,
                encodeType,
                bitsPerSample,
                caption);
    }
}
