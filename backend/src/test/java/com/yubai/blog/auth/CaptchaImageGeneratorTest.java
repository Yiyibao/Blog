package com.yubai.blog.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.util.Base64;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class CaptchaImageGeneratorTest {

    @Test
    void generatesReadablePngWithSafeAlphabet() throws Exception {
        var generator = new CaptchaImageGenerator();
        var captcha = generator.generate();

        assertThat(captcha.text()).hasSize(5)
            .as("字符集不含易混淆字符")
            .matches("[23456789ABCDEFGHJKMNPQRSTUVWXYZ]+");
        assertThat(captcha.imageDataUri()).startsWith("data:image/png;base64,");

        var pngBytes = Base64.getDecoder().decode(captcha.imageDataUri().substring("data:image/png;base64,".length()));
        var image = ImageIO.read(new ByteArrayInputStream(pngBytes));
        assertThat(image).as("base64 内容必须是可解码的 PNG").isNotNull();
        assertThat(image.getWidth()).isEqualTo(160);
        assertThat(image.getHeight()).isEqualTo(56);
    }

    @Test
    void textsAreRandomAcrossGenerations() {
        var generator = new CaptchaImageGenerator();
        var distinct = new java.util.HashSet<String>();
        for (int i = 0; i < 10; i++) {
            distinct.add(generator.generate().text());
        }
        assertThat(distinct.size()).as("10 次生成不应全部相同").isGreaterThan(1);
    }
}
