package com.github.wechat.cloudbot.modules.voice.infra;

import io.github.kasukusakura.silkcodec.SilkCoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * TTS 输出多为 WAV/MP3，微信 iLink 语音条需要 SILK。
 */
public final class SilkAudioConverter {

    private static final Logger log = LoggerFactory.getLogger(SilkAudioConverter.class);

    private SilkAudioConverter() {}

    public record SilkVoice(byte[] silkBytes, int playLengthMs, int sampleRate) {}

    public static SilkVoice toSilk(byte[] audioBytes, int fallbackSampleRate, int fallbackPlayMs)
            throws Exception {
        if (audioBytes == null || audioBytes.length == 0) {
            throw new IllegalArgumentException("audio empty");
        }

        log.info(
                "准备转 SILK：bytes={}, magic={}",
                audioBytes.length,
                magicHex(audioBytes));

        PcmAudio pcm = decodeToPcm(audioBytes, fallbackSampleRate);
        int playMs = pcm.playLengthMs() > 0 ? pcm.playLengthMs() : fallbackPlayMs;
        playMs = clampPlayMs(playMs);

        ByteArrayOutputStream silkOut = new ByteArrayOutputStream(pcm.pcm().length / 2 + 64);
        SilkCoder.encode(new ByteArrayInputStream(pcm.pcm()), silkOut, pcm.sampleRate());
        byte[] silk = ensureTencentSilkPrefix(silkOut.toByteArray());
        if (silk.length == 0) {
            throw new IllegalStateException("silk encode produced empty output");
        }
        log.info(
                "SILK 转码完成：pcmBytes={}, silkBytes={}, playMs={}, sampleRate={}",
                pcm.pcm().length,
                silk.length,
                playMs,
                pcm.sampleRate());
        return new SilkVoice(silk, playMs, pcm.sampleRate());
    }

    /**
     * 微信入站 SILK → 标准 WAV（供声音复刻 enrollment）。
     * 若已是 WAV/MP3 则原样返回。
     *
     * <p>注意：当前 silk-codec 的 {@code SilkCoder.decode} 在部分环境下会
     * {@code NoSuchMethodError}，因此优先走 Python {@code pilk}（tools/silk_to_wav.py）。
     */
    public static byte[] toWavForEnrollment(byte[] audioBytes, int sampleRate) throws Exception {
        if (audioBytes == null || audioBytes.length == 0) {
            throw new IllegalArgumentException("audio empty");
        }
        if (isRiff(audioBytes) || looksLikeMp3(audioBytes)) {
            return audioBytes;
        }
        if (AudioDataUrls.looksLikeSilk(audioBytes)) {
            int rate = sampleRate > 0 ? sampleRate : 24000;
            Exception last = null;
            try {
                return silkToWavViaPython(stripTencentSilkPrefix(audioBytes), rate);
            } catch (Exception e) {
                last = e;
                log.warn("Python pilk SILK→WAV 失败，尝试 SilkCoder.decode: {}", e.getMessage());
            }
            try {
                byte[] silk = stripTencentSilkPrefix(audioBytes);
                ByteArrayOutputStream pcmOut = new ByteArrayOutputStream(audioBytes.length * 4);
                SilkCoder.decode(new ByteArrayInputStream(silk), pcmOut, true, rate, 0);
                byte[] pcm = pcmOut.toByteArray();
                if (pcm.length == 0) {
                    throw new IllegalStateException("SILK 解码得到空 PCM");
                }
                log.info(
                        "SILK→WAV(SilkCoder) 完成：pcmBytes={}, sampleRate={}",
                        pcm.length,
                        rate);
                return wrapPcmAsWav(pcm, rate);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "无法解码微信语音 SILK（请确认已 pip install pilk，且 python 在 PATH 中）。"
                                + " 也可直接发送 wav/mp3 文件作为样本。原因: "
                                + (last != null ? last.getMessage() + "; " : "")
                                + e.getMessage(),
                        e);
            }
        }
        PcmAudio pcm = decodeToPcm(audioBytes, sampleRate > 0 ? sampleRate : 24000);
        return wrapPcmAsWav(pcm.pcm(), pcm.sampleRate());
    }

    private static boolean looksLikeMp3(byte[] bytes) {
        if (bytes.length >= 3 && bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3') {
            return true;
        }
        return bytes.length >= 2
                && (bytes[0] & 0xFF) == 0xFF
                && ((bytes[1] & 0xE0) == 0xE0);
    }

    private static byte[] silkToWavViaPython(byte[] silkBytes, int sampleRate) throws Exception {
        Path work =
                Files.createTempDirectory("wx-silk-");
        Path silkFile = work.resolve("in.silk");
        Path wavFile = work.resolve("out.wav");
        try {
            Files.write(silkFile, silkBytes);
            Path script = resolveSilkToWavScript();
            String python = resolvePythonCommand();
            ProcessBuilder pb =
                    new ProcessBuilder(
                            python,
                            script.toAbsolutePath().toString(),
                            silkFile.toAbsolutePath().toString(),
                            wavFile.toAbsolutePath().toString(),
                            String.valueOf(sampleRate));
            pb.redirectErrorStream(true);
            String out;
            int code;
            Process p = pb.start();
            try {
                try (var in = p.getInputStream()) {
                    out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                code = p.waitFor();
            } finally {
                p.destroy();
            }
            if (code != 0 || !Files.isRegularFile(wavFile)) {
                throw new IllegalStateException(
                        "pilk exit=" + code + " out=" + out.trim());
            }
            byte[] wav = Files.readAllBytes(wavFile);
            log.info(
                    "SILK→WAV(pilk) 完成：wavBytes={}, sampleRate={}, out={}",
                    wav.length,
                    sampleRate,
                    out.trim());
            return wav;
        } finally {
            try {
                Files.deleteIfExists(silkFile);
                Files.deleteIfExists(wavFile);
                Files.deleteIfExists(work);
            } catch (Exception ignored) {
                // temp cleanup best-effort
            }
        }
    }

    private static String resolvePythonCommand() {
        String override = System.getenv("PYTHON_CMD");
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        for (String cmd : new String[] {"python", "py", "python3"}) {
            if (commandExists(cmd)) {
                return cmd;
            }
        }
        return "python";
    }

    private static boolean commandExists(String cmd) {
        try {
            ProcessBuilder pb =
                    System.getProperty("os.name", "").toLowerCase().contains("win")
                            ? new ProcessBuilder("where", cmd)
                            : new ProcessBuilder("which", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try {
                p.getInputStream().readAllBytes();
                return p.waitFor() == 0;
            } finally {
                p.destroy();
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static Path resolveSilkToWavScript() {
        Path[] candidates =
                new Path[] {
                    Path.of("tools", "silk_to_wav.py"),
                    Path.of("bot", "tools", "silk_to_wav.py"),
                    Path.of("cloudbot", "tools", "silk_to_wav.py"),
                    Path.of("echo-bot", "tools", "silk_to_wav.py"),
                    Path.of("..", "tools", "silk_to_wav.py")
                };
        for (Path p : candidates) {
            Path abs = p.toAbsolutePath().normalize();
            if (Files.isRegularFile(abs)) {
                return abs;
            }
        }
        throw new IllegalStateException(
                "找不到 tools/silk_to_wav.py（请在 bot 或仓库根目录启动，或设置工作目录）");
    }

    /**
     * 估算音频时长（毫秒）；未知返回 -1。
     *
     * <p>注意：SILK 压缩率波动大，字节数无法可靠估时长，一律返回 -1，避免误拒。
     */
    public static int estimateDurationMs(byte[] wavOrPcmLike, int sampleRate) {
        if (wavOrPcmLike == null || wavOrPcmLike.length == 0) {
            return -1;
        }
        if (AudioDataUrls.looksLikeSilk(wavOrPcmLike)) {
            return -1;
        }
        if (isRiff(wavOrPcmLike) && wavOrPcmLike.length > 44) {
            int rate = readWavSampleRate(wavOrPcmLike);
            if (rate <= 0) {
                rate = sampleRate > 0 ? sampleRate : 24000;
            }
            int dataBytes = Math.max(0, wavOrPcmLike.length - 44);
            return Math.max(1, (int) (dataBytes * 1000L / (rate * 2L)));
        }
        int rate = sampleRate > 0 ? sampleRate : 24000;
        return Math.max(1, wavOrPcmLike.length * 1000 / (rate * 2));
    }

    /** 从标准 WAV 头读取采样率；失败返回 -1。 */
    static int readWavSampleRate(byte[] wav) {
        if (wav == null || wav.length < 28 || !isRiff(wav)) {
            return -1;
        }
        return (wav[24] & 0xFF)
                | ((wav[25] & 0xFF) << 8)
                | ((wav[26] & 0xFF) << 16)
                | ((wav[27] & 0xFF) << 24);
    }

    static byte[] stripTencentSilkPrefix(byte[] silk) {
        if (silk == null || silk.length < 2) {
            return silk;
        }
        int start = 0;
        // 微信可能叠多层 0x02
        while (start < silk.length
                && (silk[start] & 0xFF) == 0x02
                && start + 1 < silk.length
                && silk[start + 1] == '#') {
            start++;
        }
        if (start == 0) {
            // 非前缀场景：在前 8 字节内找 #!SILK
            for (int i = 0; i < Math.min(8, silk.length - 6); i++) {
                if (silk[i] == '#' && silk[i + 1] == '!' && silk[i + 2] == 'S') {
                    start = i;
                    break;
                }
            }
        }
        if (start == 0) {
            return silk;
        }
        byte[] stripped = new byte[silk.length - start];
        System.arraycopy(silk, start, stripped, 0, stripped.length);
        return stripped;
    }

    static byte[] wrapPcmAsWav(byte[] pcm, int sampleRate) {
        int channels = 1;
        int bitsPerSample = 16;
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        byte[] header = new byte[44];
        // RIFF
        header[0] = 'R';
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';
        writeIntLe(header, 4, 36 + pcm.length);
        header[8] = 'W';
        header[9] = 'A';
        header[10] = 'V';
        header[11] = 'E';
        header[12] = 'f';
        header[13] = 'm';
        header[14] = 't';
        header[15] = ' ';
        writeIntLe(header, 16, 16);
        writeShortLe(header, 20, (short) 1);
        writeShortLe(header, 22, (short) channels);
        writeIntLe(header, 24, sampleRate);
        writeIntLe(header, 28, byteRate);
        writeShortLe(header, 32, (short) blockAlign);
        writeShortLe(header, 34, (short) bitsPerSample);
        header[36] = 'd';
        header[37] = 'a';
        header[38] = 't';
        header[39] = 'a';
        writeIntLe(header, 40, pcm.length);
        byte[] wav = new byte[44 + pcm.length];
        System.arraycopy(header, 0, wav, 0, 44);
        System.arraycopy(pcm, 0, wav, 44, pcm.length);
        return wav;
    }

    private static void writeIntLe(byte[] buf, int off, int v) {
        buf[off] = (byte) (v & 0xFF);
        buf[off + 1] = (byte) ((v >> 8) & 0xFF);
        buf[off + 2] = (byte) ((v >> 16) & 0xFF);
        buf[off + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private static void writeShortLe(byte[] buf, int off, short v) {
        buf[off] = (byte) (v & 0xFF);
        buf[off + 1] = (byte) ((v >> 8) & 0xFF);
    }

    static byte[] ensureTencentSilkPrefix(byte[] silk) {
        if (silk == null || silk.length == 0) {
            return silk;
        }
        if (silk.length >= 10
                && (silk[0] & 0xFF) == 0x02
                && silk[1] == '#'
                && silk[2] == '!') {
            return silk;
        }
        if (silk.length >= 9 && silk[0] == '#' && silk[1] == '!') {
            byte[] withPrefix = new byte[silk.length + 1];
            withPrefix[0] = 0x02;
            System.arraycopy(silk, 0, withPrefix, 1, silk.length);
            return withPrefix;
        }
        return silk;
    }

    private static int clampPlayMs(int playMs) {
        if (playMs < 1000) {
            return 1000;
        }
        return Math.min(playMs, 60_000);
    }

    private record PcmAudio(byte[] pcm, int sampleRate, int playLengthMs) {}

    private static PcmAudio decodeToPcm(byte[] data, int fallbackSampleRate) throws Exception {
        // 1) Java Sound：标准 WAV 最稳
        try {
            return decodeWithAudioSystem(data, fallbackSampleRate);
        } catch (Exception e) {
            log.warn("AudioSystem 解码失败，尝试手工解析 WAV: {}", e.getMessage());
        }

        // 2) 手工扫 data chunk（兼容 data size=0xFFFFFFFF / 写错长度）
        if (isRiff(data)) {
            return parseWavLenient(data, fallbackSampleRate);
        }

        throw new IllegalArgumentException(
                "无法解码 TTS 音频（非标准 WAV/不支持的格式），magic=" + magicHex(data));
    }

    private static boolean isRiff(byte[] data) {
        return data.length >= 12
                && data[0] == 'R'
                && data[1] == 'I'
                && data[2] == 'F'
                && data[3] == 'F';
    }

    private static PcmAudio decodeWithAudioSystem(byte[] data, int fallbackSampleRate)
            throws Exception {
        try (AudioInputStream source =
                AudioSystem.getAudioInputStream(new ByteArrayInputStream(data))) {
            AudioFormat src = source.getFormat();
            int sampleRate =
                    src.getSampleRate() > 0
                            ? Math.round(src.getSampleRate())
                            : (fallbackSampleRate > 0 ? fallbackSampleRate : 24000);

            AudioFormat target =
                    new AudioFormat(
                            AudioFormat.Encoding.PCM_SIGNED,
                            sampleRate,
                            16,
                            1,
                            2,
                            sampleRate,
                            false);

            try (AudioInputStream converted = AudioSystem.getAudioInputStream(target, source);
                    ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                converted.transferTo(out);
                byte[] pcm = out.toByteArray();
                if (pcm.length == 0) {
                    throw new IllegalStateException("AudioSystem produced empty PCM");
                }
                int playMs = (int) (pcm.length * 1000L / (sampleRate * 2L));
                return new PcmAudio(pcm, sampleRate, playMs);
            }
        }
    }

    private static PcmAudio parseWavLenient(byte[] wav, int fallbackSampleRate) {
        int sampleRate = fallbackSampleRate > 0 ? fallbackSampleRate : 24000;
        int channels = 1;
        int bitsPerSample = 16;
        int byteRate = sampleRate * 2;
        byte[] pcm = null;

        int offset = 12;
        while (offset + 8 <= wav.length) {
            String id = new String(wav, offset, 4, StandardCharsets.US_ASCII);
            long sizeLong =
                    (wav[offset + 4] & 0xFFL)
                            | ((wav[offset + 5] & 0xFFL) << 8)
                            | ((wav[offset + 6] & 0xFFL) << 16)
                            | ((wav[offset + 7] & 0xFFL) << 24);
            int dataStart = offset + 8;
            int remain = wav.length - dataStart;
            int size =
                    sizeLong == 0xFFFFFFFFL || sizeLong > remain
                            ? remain
                            : (int) sizeLong;

            if ("fmt ".equals(id) && size >= 16) {
                channels = (wav[dataStart + 2] & 0xFF) | ((wav[dataStart + 3] & 0xFF) << 8);
                sampleRate =
                        (wav[dataStart + 4] & 0xFF)
                                | ((wav[dataStart + 5] & 0xFF) << 8)
                                | ((wav[dataStart + 6] & 0xFF) << 16)
                                | ((wav[dataStart + 7] & 0xFF) << 24);
                byteRate =
                        (wav[dataStart + 8] & 0xFF)
                                | ((wav[dataStart + 9] & 0xFF) << 8)
                                | ((wav[dataStart + 10] & 0xFF) << 16)
                                | ((wav[dataStart + 11] & 0xFF) << 24);
                bitsPerSample =
                        (wav[dataStart + 14] & 0xFF) | ((wav[dataStart + 15] & 0xFF) << 8);
            } else if ("data".equalsIgnoreCase(id.trim()) || "data".equals(id)) {
                pcm = new byte[size];
                System.arraycopy(wav, dataStart, pcm, 0, size);
                break;
            }

            // 跳过本 chunk；size 异常时避免死循环
            int step = 8 + size + (size & 1);
            if (step <= 0) {
                break;
            }
            offset += step;
        }

        // 兜底：全文扫描 "data"
        if (pcm == null) {
            for (int i = 12; i + 8 < wav.length; i++) {
                if (wav[i] == 'd'
                        && wav[i + 1] == 'a'
                        && wav[i + 2] == 't'
                        && wav[i + 3] == 'a') {
                    long sizeLong =
                            (wav[i + 4] & 0xFFL)
                                    | ((wav[i + 5] & 0xFFL) << 8)
                                    | ((wav[i + 6] & 0xFFL) << 16)
                                    | ((wav[i + 7] & 0xFFL) << 24);
                    int dataStart = i + 8;
                    int remain = wav.length - dataStart;
                    int size =
                            sizeLong == 0xFFFFFFFFL || sizeLong > remain
                                    ? remain
                                    : (int) sizeLong;
                    if (size > 0) {
                        pcm = new byte[size];
                        System.arraycopy(wav, dataStart, pcm, 0, size);
                        break;
                    }
                }
            }
        }

        if (pcm == null || pcm.length == 0) {
            throw new IllegalArgumentException("WAV missing data chunk");
        }
        if (channels <= 0) {
            channels = 1;
        }
        if (sampleRate <= 0) {
            sampleRate = fallbackSampleRate > 0 ? fallbackSampleRate : 24000;
        }
        if (byteRate <= 0) {
            byteRate = sampleRate * Math.max(channels, 1) * Math.max(bitsPerSample, 16) / 8;
        }
        if (channels > 1 && bitsPerSample == 16) {
            pcm = downmixToMono16(pcm, channels);
            byteRate = sampleRate * 2;
        }
        int playMs = (int) Math.min(Integer.MAX_VALUE, Math.max(1, pcm.length * 1000L / byteRate));
        return new PcmAudio(pcm, sampleRate, playMs);
    }

    private static byte[] downmixToMono16(byte[] pcm, int channels) {
        int frame = channels * 2;
        int frames = pcm.length / frame;
        byte[] mono = new byte[frames * 2];
        for (int i = 0; i < frames; i++) {
            mono[i * 2] = pcm[i * frame];
            mono[i * 2 + 1] = pcm[i * frame + 1];
        }
        return mono;
    }

    private static String magicHex(byte[] data) {
        int n = Math.min(16, data.length);
        StringBuilder sb = new StringBuilder(n * 3);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02X", data[i] & 0xFF));
        }
        return sb.toString();
    }
}
