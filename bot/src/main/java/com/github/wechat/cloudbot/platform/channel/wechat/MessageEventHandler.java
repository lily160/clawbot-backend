package com.github.wechat.cloudbot.platform.channel.wechat;

import com.github.wechat.cloudbot.platform.config.CloudbotProperties;
import com.github.wechat.cloudbot.platform.config.ErrorCode;
import com.github.wechat.cloudbot.modules.voice.infra.VoiceProperties;
import com.github.wechat.cloudbot.modules.voice.infra.AudioDataUrls;
import com.github.wechat.cloudbot.modules.voice.infra.DashScopeSpeechClient;
import com.github.wechat.cloudbot.modules.voice.service.VoiceCloneService;
import com.github.wechat.cloudbot.platform.agent.InboundMessageCoalescer;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.model.ImageItem;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.TextItem;
import com.github.wechat.ilink.sdk.core.model.VideoItem;
import com.github.wechat.ilink.sdk.core.model.VoiceItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 消息事件处理器：文字/语音经短时合并后走 DeepSeek；图片/视频走千问 VL；
 * 语音可下载后走 ASR，再按策略 TTS 回复。
 *
 * <p><b>警告：请使用微信小号测试。Cloudbot 存在封号风险。</b>
 */
@Component
public class MessageEventHandler implements OnMessageListener {

    private static final Logger log = LoggerFactory.getLogger(MessageEventHandler.class);

    private final CloudbotProperties properties;
    private final VoiceProperties voiceProperties;
    private final DashScopeSpeechClient speechClient;
    private final VoiceCloneService voiceCloneService;
    private final InboundMessageCoalescer messageCoalescer;
    private final ILinkClient client;

    public MessageEventHandler(
            CloudbotProperties properties,
            VoiceProperties voiceProperties,
            DashScopeSpeechClient speechClient,
            VoiceCloneService voiceCloneService,
            InboundMessageCoalescer messageCoalescer,
            @Lazy ILinkClient client) {
        this.properties = properties;
        this.voiceProperties = voiceProperties;
        this.speechClient = speechClient;
        this.voiceCloneService = voiceCloneService;
        this.messageCoalescer = messageCoalescer;
        this.client = client;
    }

    @Override
    public void onMessages(List<WeixinMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (WeixinMessage message : messages) {
            handleOne(message);
        }
    }

    private void handleOne(WeixinMessage message) {
        String fromUserId = message.getFrom_user_id();
        if (fromUserId == null || fromUserId.isBlank()) {
            return;
        }
        if (fromUserId.endsWith("@im.bot")) {
            return;
        }

        List<MessageItem> items = message.getItem_list();
        if (items == null || items.isEmpty()) {
            return;
        }

        for (MessageItem item : items) {
            if (item.getVoice_item() != null) {
                handleVoice(item, fromUserId);
                continue;
            }

            if (item.getFile_item() != null) {
                if (voiceCloneService.isPending(fromUserId)) {
                    handleFileForVoiceClone(item, fromUserId);
                } else {
                    handleInboundFile(item, fromUserId);
                }
                continue;
            }

            String text = extractTextContent(item);
            if (text != null) {
                log.info("收到消息 from {}: {}", fromUserId, text);
                messageCoalescer.offerText(client, fromUserId, text);
                continue;
            }

            if (item.getImage_item() != null) {
                handleImageForVision(item, fromUserId);
                continue;
            }

            if (item.getVideo_item() != null) {
                handleVideoForVision(item, fromUserId);
            }
        }
    }

    private String extractTextContent(MessageItem item) {
        TextItem textItem = item.getText_item();
        if (textItem != null && textItem.getText() != null && !textItem.getText().isBlank()) {
            return textItem.getText();
        }

        if (!properties.isTreatVoiceAsText()) {
            return null;
        }

        // 语音已在 handleVoice 单独处理，这里不再把 voice 当文字抽取，避免双路径
        return null;
    }

    /**
     * 下载语音 →（必要时）ASR → 按语音渠道进入对话（可 TTS 回复）。
     */
    private void handleVoice(MessageItem item, String fromUserId) {
        VoiceItem voice = item.getVoice_item();
        String platformText =
                voice.getText() == null || voice.getText().isBlank() ? null : voice.getText().trim();

        byte[] audio = null;
        try {
            audio = client.downloadVoiceFromMessageItem(item);
            log.info(
                    "收到语音 from {}，size={} bytes，平台转写={}",
                    fromUserId,
                    audio == null ? 0 : audio.length,
                    platformText == null ? "(空)" : platformText);
        } catch (IOException e) {
            log.error("{}语音下载失败 from {}: {}", ErrorCode.CHANNEL_DOWNLOAD_ERROR.prefix(), fromUserId, e.getMessage(), e);
        }

        // 已发「自定义音色」口令：本条语音作为复刻样本，不走普通对话
        if (voiceCloneService.isPending(fromUserId)) {
            if (audio == null || audio.length == 0) {
                log.warn("语音复刻失败：语音数据为空 from {}", fromUserId);
                messageCoalescer.offerText(client, fromUserId, "语音下载失败，请重新发送语音或 wav/mp3 文件。");
                return;
            }
            final byte[] sample = audio;
            final int playMs = normalizePlaytimeMs(voice.getPlaytime());
            final int sampleRate =
                    voice.getSample_rate() == null || voice.getSample_rate() <= 0
                            ? -1
                            : voice.getSample_rate();
            messageCoalescer.offerVoiceClone(client, fromUserId, sample, playMs, sampleRate);
            return;
        }

        String transcript = platformText;
        if ((transcript == null || transcript.isBlank())
                && voiceProperties.isAsrEnabled()
                && audio != null
                && audio.length > 0) {
            try {
                transcript = speechClient.transcribe(audio);
                log.info("ASR 完成 from {}: {}", fromUserId, transcript);
            } catch (Exception e) {
                log.error("{}ASR 失败 from {}: {}", ErrorCode.VOICE_API_ERROR.prefix(), fromUserId, e.getMessage(), e);
            }
        }

        if (transcript == null || transcript.isBlank()) {
            transcript = "[用户发送了一条语音，但未能识别内容]";
            log.warn("语音无可用转写 from {}", fromUserId);
        }

        messageCoalescer.offerVoice(client, fromUserId, transcript);
    }

    /** 自定义音色 pending 时：接受用户发来的 wav/mp3 文件作为样本。 */
    private void handleFileForVoiceClone(MessageItem item, String fromUserId) {
        String name =
                item.getFile_item().getFile_name() == null
                        ? ""
                        : item.getFile_item().getFile_name().toLowerCase();
        try {
            byte[] data = client.downloadFileFromMessageItem(item);
            log.info(
                    "收到复刻样本文件 from {}，name={}, size={}",
                    fromUserId,
                    name,
                    data == null ? 0 : data.length);
            if (data == null || data.length == 0) {
                messageCoalescer.offerText(client, fromUserId, "文件是空的，请重新发送 wav/mp3。");
                return;
            }
            boolean okExt =
                    name.endsWith(".wav")
                            || name.endsWith(".mp3")
                            || name.endsWith(".m4a")
                            || AudioDataUrls.looksLikeSilk(data)
                            || (data.length >= 4
                                    && data[0] == 'R'
                                    && data[1] == 'I'
                                    && data[2] == 'F'
                                    && data[3] == 'F');
            if (!okExt) {
                messageCoalescer.offerText(
                        client,
                        fromUserId,
                        "请发送 wav / mp3 / m4a 音频文件，或按提示朗读后发语音。");
                return;
            }
            messageCoalescer.offerVoiceClone(client, fromUserId, data, -1, -1);
        } catch (IOException e) {
            log.error("{}复刻样本文件下载失败 from {}: {}", ErrorCode.CHANNEL_DOWNLOAD_ERROR.prefix(), fromUserId, e.getMessage(), e);
            messageCoalescer.offerText(client, fromUserId, "文件下载失败，请稍后重试");
        }
    }

    /** 普通入站文件：下载后交给编排层解析（txt/md/docx/xlsx/zip 等）。 */
    private void handleInboundFile(MessageItem item, String fromUserId) {
        String name =
                item.getFile_item().getFile_name() == null
                        ? "file"
                        : item.getFile_item().getFile_name().trim();
        try {
            byte[] data = client.downloadFileFromMessageItem(item);
            log.info(
                    "收到文件 from {}，name={}, size={}",
                    fromUserId,
                    name,
                    data == null ? 0 : data.length);
            messageCoalescer.offerFile(client, fromUserId, name, data);
        } catch (IOException e) {
            log.error(
                    "{}文件下载失败 from {}: {}",
                    ErrorCode.CHANNEL_DOWNLOAD_ERROR.prefix(),
                    fromUserId,
                    e.getMessage(),
                    e);
            messageCoalescer.offerText(client, fromUserId, "文件下载失败，请稍后重试");
        }
    }

    /**
     * 微信 VoiceItem.playtime：通常为秒（1～60）；若已是毫秒（&gt;120）则原样使用。
     */
    private static int normalizePlaytimeMs(Integer playtime) {
        if (playtime == null || playtime <= 0) {
            return -1;
        }
        if (playtime <= 120) {
            return playtime * 1000;
        }
        return playtime;
    }

    private void handleImageForVision(MessageItem item, String fromUserId) {
        ImageItem imageItem = item.getImage_item();
        log.info(
                "收到图片消息 from {}（aeskey 是否存在: {}）",
                fromUserId,
                imageItem.getAeskey() != null
                        || (imageItem.getMedia() != null
                                && imageItem.getMedia().getAes_key() != null));

        try {
            byte[] plain = client.downloadImageFromMessageItem(item);
            if (plain == null || plain.length == 0) {
                messageCoalescer.offerText(client, fromUserId, "[用户发送了一张图片，但内容为空]");
                return;
            }
            log.info("图片已解密 from {}，size={} bytes，送入 千问VL→DeepSeek", fromUserId, plain.length);

            if (properties.isDownloadImage()) {
                Path dir = Path.of(properties.getMediaSaveDir()).toAbsolutePath().normalize();
                Files.createDirectories(dir);
                Path file = dir.resolve("img_" + System.currentTimeMillis() + ".bin");
                Files.write(file, plain);
                log.info("图片已保存: {}", file);
            }

            messageCoalescer.offerImage(client, fromUserId, plain);
        } catch (IOException e) {
            log.error("{}图片下载/解密失败 from {}: {}", ErrorCode.CHANNEL_DOWNLOAD_ERROR.prefix(), fromUserId, e.getMessage(), e);
            messageCoalescer.offerText(
                    client, fromUserId, "[用户发送了一张图片，但下载解密失败]");
        }
    }

    private void handleVideoForVision(MessageItem item, String fromUserId) {
        VideoItem videoItem = item.getVideo_item();
        log.info(
                "收到视频消息 from {}，declaredSize={}, playLength={}, hasMedia={}",
                fromUserId,
                videoItem.getVideo_size(),
                videoItem.getPlay_length(),
                videoItem.getMedia() != null);

        try {
            byte[] plain = client.downloadVideoFromMessageItem(item);
            if (plain == null || plain.length == 0) {
                messageCoalescer.offerText(client, fromUserId, "[用户发送了一段视频，但内容为空]");
                return;
            }
            log.info("视频已解密 from {}，size={} bytes，送入 千问VL→DeepSeek", fromUserId, plain.length);

            if (properties.isDownloadImage()) {
                Path dir = Path.of(properties.getMediaSaveDir()).toAbsolutePath().normalize();
                Files.createDirectories(dir);
                Path file = dir.resolve("vid_" + System.currentTimeMillis() + ".mp4");
                Files.write(file, plain);
                log.info("视频已保存: {}", file);
            }

            messageCoalescer.offerVideo(client, fromUserId, plain);
        } catch (IOException e) {
            log.error("{}视频下载/解密失败 from {}: {}", ErrorCode.CHANNEL_DOWNLOAD_ERROR.prefix(), fromUserId, e.getMessage(), e);
            messageCoalescer.offerText(
                    client, fromUserId, "[用户发送了一段视频，但下载解密失败]");
        }
    }
}
