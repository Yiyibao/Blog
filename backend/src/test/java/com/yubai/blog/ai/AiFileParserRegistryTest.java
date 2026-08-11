package com.yubai.blog.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yubai.blog.admin.ai.AiServiceException;
import com.yubai.blog.config.AiPlatformProperties;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AiFileParserRegistryTest {
    private AiPlatformProperties properties;
    private AiFileParserRegistry registry;

    @BeforeEach
    void setup() {
        properties = new AiPlatformProperties();
        registry = new AiFileParserRegistry(properties);
    }

    @Test
    void parsesSupportedImagePdfDocxAndTextFormats() throws Exception {
        assertThat(registry.parse("pixel.png", "image/png", png()).image()).isTrue();
        assertThat(registry.parse("note.md", "text/plain", bytes("# 标题")).extractedText())
                .contains("标题");
        assertThat(registry.parse("table.csv", "text/csv", bytes("name,value\n甲,1")))
                .extracting(AiParsedFile::mediaType)
                .isEqualTo("text/csv");
        assertThat(registry.parse("data.json", "application/json", bytes("{\"ok\":true}")))
                .extracting(AiParsedFile::mediaType)
                .isEqualTo("application/json");
        assertThat(registry.parse("paper.pdf", "application/pdf", pdf("hello pdf")))
                .extracting(AiParsedFile::extractedText)
                .asString()
                .contains("hello pdf");
        assertThat(registry.parse("paper.docx", null, docx("hello docx")))
                .extracting(AiParsedFile::extractedText)
                .asString()
                .contains("hello docx");
        assertThat(registry.parse("table.xlsx", null, xlsx("hello sheet")))
                .extracting(AiParsedFile::extractedText)
                .asString()
                .contains("hello sheet");
    }

    @Test
    void rejectsForgedMimeDamageAndStructuralLimits() {
        assertThatThrownBy(() -> registry.parse("forged.png", "image/png", bytes("not png")))
                .isInstanceOf(AiServiceException.class);
        assertThatThrownBy(() -> registry.parse("file.pdf", "text/plain", bytes("%PDF-bad")))
                .isInstanceOf(AiServiceException.class);
        assertThatThrownBy(() -> registry.parse("file.docm", null, bytes("PK\u0003\u0004")))
                .isInstanceOf(AiServiceException.class);

        properties.setMaxCsvColumns(1);
        assertThatThrownBy(() -> registry.parse("wide.csv", "text/csv", bytes("a,b")))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("column");
    }

    private static byte[] png() throws Exception {
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static byte[] pdf(String value) throws Exception {
        try (var document = new PDDocument()) {
            var page = new PDPage();
            document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText(value);
                content.endText();
            }
            var output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] docx(String value) throws Exception {
        try (var document = new XWPFDocument()) {
            document.createParagraph().createRun().setText(value);
            var output = new ByteArrayOutputStream();
            document.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] xlsx(String value) throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Data");
            sheet.createRow(0).createCell(0).setCellValue(value);
            var output = new ByteArrayOutputStream();
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
