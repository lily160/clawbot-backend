package com.github.wechat.cloudbot.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 从 application.yml 的 {@code cloudbot.*} 绑定业务配置。
 *
 * <p>警告：请使用微信小号测试，Cloudbot 存在封号风险。
 */
@ConfigurationProperties(prefix = "cloudbot")
public class CloudbotProperties {

    /** 固定自动回复文案。 */
    private String autoReplyText = "[自动回复] 您好，我正在忙，请稍后联系。";

    /** 是否将语音消息中的转写文本按文字消息处理。 */
    private boolean treatVoiceAsText = true;

    /** 是否下载并解密图片（Day1 默认关闭）。 */
    private boolean downloadImage = false;

    /** 媒体文件保存目录。 */
    private String mediaSaveDir = "./data/media";

    /**
     * 同一用户连发文字合并窗口（毫秒）。窗口内多条合并成一轮再回复；0 表示关闭合并。
     */
    private long messageCoalesceMs = 1500L;

    /** 是否识别新话题并清空旧历史（避免串台）。 */
    private boolean topicShiftDetectionEnabled = true;

    private Qrcode qrcode = new Qrcode();

    /** 纯趣味开关：登录 ASCII 祝福、人格切换等。 */
    private Fun fun = new Fun();

    /** 本地文件持久化（对话 / 人格 / 登录凭证）。 */
    private Persistence persistence = new Persistence();

    /** 文件发送与导出功能。 */
    private File file = new File();

    /** Agent Tool 注册中心：启动禁用列表等。 */
    private Tools tools = new Tools();

    public String getAutoReplyText() {
        return autoReplyText;
    }

    public void setAutoReplyText(String autoReplyText) {
        this.autoReplyText = autoReplyText;
    }

    public boolean isTreatVoiceAsText() {
        return treatVoiceAsText;
    }

    public void setTreatVoiceAsText(boolean treatVoiceAsText) {
        this.treatVoiceAsText = treatVoiceAsText;
    }

    public boolean isDownloadImage() {
        return downloadImage;
    }

    public void setDownloadImage(boolean downloadImage) {
        this.downloadImage = downloadImage;
    }

    public String getMediaSaveDir() {
        return mediaSaveDir;
    }

    public void setMediaSaveDir(String mediaSaveDir) {
        this.mediaSaveDir = mediaSaveDir;
    }

    public long getMessageCoalesceMs() {
        return messageCoalesceMs;
    }

    public void setMessageCoalesceMs(long messageCoalesceMs) {
        this.messageCoalesceMs = messageCoalesceMs;
    }

    public boolean isTopicShiftDetectionEnabled() {
        return topicShiftDetectionEnabled;
    }

    public void setTopicShiftDetectionEnabled(boolean topicShiftDetectionEnabled) {
        this.topicShiftDetectionEnabled = topicShiftDetectionEnabled;
    }

    public Qrcode getQrcode() {
        return qrcode;
    }

    public void setQrcode(Qrcode qrcode) {
        this.qrcode = qrcode;
    }

    public Fun getFun() {
        return fun;
    }

    public void setFun(Fun fun) {
        this.fun = fun;
    }

    public Persistence getPersistence() {
        return persistence;
    }

    public void setPersistence(Persistence persistence) {
        this.persistence = persistence;
    }

    public File getFile() {
        return file;
    }

    public void setFile(File file) {
        this.file = file;
    }

    public Tools getTools() {
        return tools;
    }

    public void setTools(Tools tools) {
        this.tools = tools;
    }

    /**
     * Agent 工具注册中心配置。默认全部启用；{@code disabled} 中的工具名启动时关闭，
     * 运行期仍可通过 {@code ToolRegistry#enable} 打开。
     */
    public static class Tools {
        /** 启动时禁用的工具名（如 fun_joke、generate_video）；大小写不敏感。 */
        private List<String> disabled = new ArrayList<>();

        public List<String> getDisabled() {
            return disabled;
        }

        public void setDisabled(List<String> disabled) {
            this.disabled = disabled == null ? new ArrayList<>() : disabled;
        }
    }

    public static class Persistence {
        /** 是否落盘；false 时对话/人格/登录均仅内存。 */
        private boolean enabled = true;

        /**
         * 对话记忆存储后端：{@code file}（JSON）或 {@code jdbc}（MySQL/H2）。
         */
        private String type = "file";

        /**
         * 登录凭证存储：{@code file} 或 {@code redis}（{@code enabled=false} 时用内存实现）。
         */
        private String auth = "file";

        /** 数据根目录（其下 chat-memory/、personas.json、auth/）。 */
        private String dir = "./data";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getAuth() {
            return auth;
        }

        public void setAuth(String auth) {
            this.auth = auth;
        }

        public String getDir() {
            return dir;
        }

        public void setDir(String dir) {
            this.dir = dir;
        }
    }

    public static class Fun {
        /** 登录二维码旁打印随机欢迎语 / 像素猫。 */
        private boolean qrcodeBlessingEnabled = false;

        /** 「变身：人格名」切换 system prompt。 */
        private boolean personaSwitchEnabled = true;

        public boolean isQrcodeBlessingEnabled() {
            return qrcodeBlessingEnabled;
        }

        public void setQrcodeBlessingEnabled(boolean qrcodeBlessingEnabled) {
            this.qrcodeBlessingEnabled = qrcodeBlessingEnabled;
        }

        public boolean isPersonaSwitchEnabled() {
            return personaSwitchEnabled;
        }

        public void setPersonaSwitchEnabled(boolean personaSwitchEnabled) {
            this.personaSwitchEnabled = personaSwitchEnabled;
        }
    }

    public static class Qrcode {
        private boolean printAscii = false;
        private boolean saveImage = true;
        private String imagePath = "./data/login-qrcode.png";

        public boolean isPrintAscii() {
            return printAscii;
        }

        public void setPrintAscii(boolean printAscii) {
            this.printAscii = printAscii;
        }

        public boolean isSaveImage() {
            return saveImage;
        }

        public void setSaveImage(boolean saveImage) {
            this.saveImage = saveImage;
        }

        public String getImagePath() {
            return imagePath;
        }

        public void setImagePath(String imagePath) {
            this.imagePath = imagePath;
        }
    }

    /** 文件发送与导出（send_file / export_document）。 */
    public static class File {
        /** 是否允许发送文件。 */
        private boolean enabled = true;

        /** 入站可解析文件体积上限（字节）。 */
        private int maxInboundBytes = 2_097_152;

        /** 注入 prompt 的正文最大字符数。 */
        private int maxPromptChars = 80_000;

        /** 出站文件体积上限（字节）。 */
        private int maxOutboundBytes = 1_048_576;

        /** xlsx 最大行数。 */
        private int maxXlsxRows = 500;

        /** xlsx 最大列数。 */
        private int maxXlsxCols = 30;

        /** zip 最大条目数。 */
        private int maxZipEntries = 40;

        /** zip 单条目体积上限（字节）。 */
        private int maxZipEntryBytes = 262_144;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxInboundBytes() {
            return maxInboundBytes;
        }

        public void setMaxInboundBytes(int maxInboundBytes) {
            this.maxInboundBytes = maxInboundBytes;
        }

        public int getMaxPromptChars() {
            return maxPromptChars;
        }

        public void setMaxPromptChars(int maxPromptChars) {
            this.maxPromptChars = maxPromptChars;
        }

        public int getMaxOutboundBytes() {
            return maxOutboundBytes;
        }

        public void setMaxOutboundBytes(int maxOutboundBytes) {
            this.maxOutboundBytes = maxOutboundBytes;
        }

        public int getMaxXlsxRows() {
            return maxXlsxRows;
        }

        public void setMaxXlsxRows(int maxXlsxRows) {
            this.maxXlsxRows = maxXlsxRows;
        }

        public int getMaxXlsxCols() {
            return maxXlsxCols;
        }

        public void setMaxXlsxCols(int maxXlsxCols) {
            this.maxXlsxCols = maxXlsxCols;
        }

        public int getMaxZipEntries() {
            return maxZipEntries;
        }

        public void setMaxZipEntries(int maxZipEntries) {
            this.maxZipEntries = maxZipEntries;
        }

        public int getMaxZipEntryBytes() {
            return maxZipEntryBytes;
        }

        public void setMaxZipEntryBytes(int maxZipEntryBytes) {
            this.maxZipEntryBytes = maxZipEntryBytes;
        }
    }
}
