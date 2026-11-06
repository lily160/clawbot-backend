package com.github.wechat.cloudbot.platform.channel.wechat;

import com.github.wechat.cloudbot.platform.config.CloudbotProperties;
import com.github.wechat.cloudbot.platform.config.ErrorCode;
import com.github.wechat.cloudbot.modules.persona.service.FunFlavorTexts;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Desktop;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * 登录二维码：默认只写本地 PNG（可自动打开）；可选控制台字符画（{@code print-ascii}）。
 */
@Service
public class QrCodeDisplayService {

    private static final Logger log = LoggerFactory.getLogger(QrCodeDisplayService.class);

    private final CloudbotProperties properties;

    public QrCodeDisplayService(CloudbotProperties properties) {
        this.properties = properties;
    }

    /**
     * @param qrContent SDK {@code executeLogin()} 返回的 {@code qrcode_img_content}（通常为待编码 URL/文本）
     */
    public void display(String qrContent) {
        if (qrContent == null || qrContent.isBlank()) {
            log.warn("二维码内容为空，无法展示");
            return;
        }

        log.info("请使用【微信小号】扫码登录（Cloudbot 存在封号风险，切勿使用主号）");
        // 完整二维码载荷即登录入口，降级为 DEBUG，避免日志泄出即泄露登录能力
        log.debug("二维码原始内容: {}", qrContent);

        CloudbotProperties.Qrcode qr = properties.getQrcode();
        try {
            if (qr.isPrintAscii()) {
                BitMatrix modules = encode(qrContent, 0);
                Charset stdout = stdoutCharset();
                String asciiQr = toConsoleQr(modules, stdout);
                System.out.println();
                if (properties.getFun().isQrcodeBlessingEnabled()) {
                    System.out.print(composeBlessingHeader());
                }
                System.out.print(asciiQr);
                System.out.println();
                log.info("控制台二维码已按 {} 编码渲染（无法扫码时请扫下方 PNG）", stdout.name());
            }
            if (qr.isSaveImage()) {
                Path path = Path.of(qr.getImagePath()).toAbsolutePath().normalize();
                Files.createDirectories(path.getParent() == null ? Path.of(".") : path.getParent());
                writePng(encode(qrContent, 320), path);
                log.info("登录二维码已保存（请优先扫此图）: {}", path);
                maybeOpenImage(path);
            }
        } catch (WriterException | IOException e) {
            log.error("{}渲染登录二维码失败: {}", ErrorCode.PLATFORM_CONFIG.prefix(), e.getMessage(), e);
        }
    }

    /** 欢迎语 + 像素猫放在二维码上方，不侧拼，防止控制台折行毁掉二维码。 */
    static String composeBlessingHeader() {
        String welcome = FunFlavorTexts.randomWelcome();
        String[] cat = FunFlavorTexts.randomPixelCat();
        StringBuilder sb = new StringBuilder();
        sb.append("* ").append(welcome).append('\n');
        sb.append("----------------------------------------\n");
        for (String line : cat) {
            sb.append(line).append('\n');
        }
        sb.append('\n');
        return sb.toString();
    }

    private static BitMatrix encode(String content, int size) throws WriterException {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 1);
        return new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints);
    }

    /**
     * 根据终端编码选择渲染方式。UTF-8 终端用半块字符 {@code █▀▄}（更美观），
     * 否则降级为纯文本方块 {@code ■}（兼容任何编码）。
     */
    static String toConsoleQr(BitMatrix matrix, Charset stdout) {
        if (stdout.newEncoder().canEncode("█▀▄")) {
            return toHalfBlockQr(matrix);
        }
        return toGbkSafeBlockQr(matrix);
    }

    /** 半块字符，两行合并，接近正方形。 */
    private static String toHalfBlockQr(BitMatrix matrix) {
        StringBuilder sb = new StringBuilder();
        int width = matrix.getWidth();
        int height = matrix.getHeight();
        for (int y = 0; y < height; y += 2) {
            for (int x = 0; x < width; x++) {
                boolean top = matrix.get(x, y);
                boolean bottom = y + 1 < height && matrix.get(x, y + 1);
                if (top && bottom) {
                    sb.append('█');
                } else if (top) {
                    sb.append('▀');
                } else if (bottom) {
                    sb.append('▄');
                } else {
                    sb.append(' ');
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * GBK 可显示的实心方块。过宽时单字符，否则双字符接近正方形。
     * {@code ■} 在 CP936/GBK 中有码位，不会变成 {@code ?}。
     */
    private static String toGbkSafeBlockQr(BitMatrix matrix) {
        int width = matrix.getWidth();
        int height = matrix.getHeight();
        // 留余量，避免 Cursor/窄终端折行把定位点拆开
        boolean doubleWidth = width * 2 <= 72;
        String on = doubleWidth ? "■■" : "■";
        String off = doubleWidth ? "  " : " ";

        StringBuilder sb = new StringBuilder(height * (width * on.length() + 1));
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                sb.append(matrix.get(x, y) ? on : off);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static Charset stdoutCharset() {
        for (String key : new String[] {"stdout.encoding", "sun.stdout.encoding", "file.encoding"}) {
            String name = System.getProperty(key);
            if (name != null && !name.isBlank()) {
                try {
                    return Charset.forName(name);
                } catch (Exception ignored) {
                    // try next
                }
            }
        }
        return Charset.defaultCharset();
    }

    private static void writePng(BitMatrix matrix, Path path) throws IOException {
        int w = matrix.getWidth();
        int h = matrix.getHeight();
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                image.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
            }
        }
        ImageIO.write(image, "PNG", path.toFile());
    }

    /** Windows 下尝试打开 PNG，方便直接扫码（失败则忽略）。 */
    private void maybeOpenImage(Path path) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(path.toFile());
            }
        } catch (Exception e) {
            log.debug("自动打开二维码图片失败（可手动打开）: {}", e.getMessage());
        }
    }
}
