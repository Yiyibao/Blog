package com.yubai.blog.auth;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.imageio.ImageIO;

import org.springframework.stereotype.Component;

/**
 * L-7：Java2D 图形验证码，零第三方依赖。
 * 字符集剔除 0/O/1/I/l 等易混淆字符；答案校验大小写不敏感。
 */
@Component
public class CaptchaImageGenerator {
    private static final char[] ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();
    private static final int LENGTH = 5;
    private static final int WIDTH = 160;
    private static final int HEIGHT = 56;

    private final SecureRandom random = new SecureRandom();

    public record Captcha(String text, String imageDataUri) {
    }

    public Captcha generate() {
        var text = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            text.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return new Captcha(text.toString(), render(text.toString()));
    }

    private String render(String text) {
        var image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(247, 246, 242));
            graphics.fillRect(0, 0, WIDTH, HEIGHT);

            // 干扰线在字符下层，避免完全遮挡
            for (int i = 0; i < 6; i++) {
                graphics.setColor(new Color(120 + random.nextInt(100), 120 + random.nextInt(100), 120 + random.nextInt(100)));
                graphics.setStroke(new BasicStroke(1.2f));
                graphics.drawLine(random.nextInt(WIDTH), random.nextInt(HEIGHT), random.nextInt(WIDTH), random.nextInt(HEIGHT));
            }

            int charWidth = WIDTH / (LENGTH + 1);
            for (int i = 0; i < text.length(); i++) {
                graphics.setColor(new Color(30 + random.nextInt(90), 30 + random.nextInt(90), 30 + random.nextInt(90)));
                graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30 + random.nextInt(8)));
                var original = graphics.getTransform();
                double angle = (random.nextDouble() - 0.5) * 0.7;
                int x = charWidth / 2 + charWidth * i + random.nextInt(8);
                int y = HEIGHT / 2 + 12 + random.nextInt(6) - 3;
                graphics.setTransform(AffineTransform.getRotateInstance(angle, x, y));
                graphics.drawString(String.valueOf(text.charAt(i)), x, y);
                graphics.setTransform(original);
            }

            // 噪点
            for (int i = 0; i < 60; i++) {
                graphics.setColor(new Color(random.nextInt(256), random.nextInt(256), random.nextInt(256)));
                graphics.fillRect(random.nextInt(WIDTH), random.nextInt(HEIGHT), 1, 1);
            }
        } finally {
            graphics.dispose();
        }
        try (var output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException("生成验证码图片失败", e);
        }
    }
}
