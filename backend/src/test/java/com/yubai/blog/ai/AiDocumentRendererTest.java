package com.yubai.blog.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class AiDocumentRendererTest {
    private final AiDocumentRenderer renderer = new AiDocumentRenderer(new ObjectMapper());

    @Test
    void rendersChinesePdfWithEmbeddedFontAndStructuredContent() throws Exception {
        var rendered =
                renderer.render(
                        AiArtifactFormat.PDF,
                        "中文报告",
                        "# 摘要\n\n这是一个**可提取**的中文段落。\n\n| 指标 | 数值 |\n| --- | --- |\n| 完成度 | 100% |");

        assertThat(rendered.mediaType()).isEqualTo("application/pdf");
        assertThat(rendered.bytes()).startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));
        try (var document = Loader.loadPDF(rendered.bytes())) {
            assertThat(document.getNumberOfPages()).isGreaterThanOrEqualTo(1);
            assertThat(new PDFTextStripper().getText(document)).contains("中文报告", "可提取", "完成度");
        }
    }

    @Test
    void rendersDocxAndXlsxWithoutMacroOrFormulaInjection() throws Exception {
        var docx = renderer.render(AiArtifactFormat.DOCX, "Word 报告", "## 结论\n\n正文内容");
        try (var document = new XWPFDocument(new ByteArrayInputStream(docx.bytes()))) {
            assertThat(document.getParagraphs().stream().map(p -> p.getText()).toList())
                    .anyMatch(value -> value.contains("Word 报告"));
        }

        var xlsx =
                renderer.render(
                        AiArtifactFormat.XLSX,
                        "数据表",
                        "{\"sheets\":[{\"name\":\"汇总\",\"rows\":[[\"名称\",\"值\"],[\"安全文本\",\"=HYPERLINK(\\\"http://bad\\\")\"]]}]}");
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx.bytes()))) {
            assertThat(workbook.getSheetName(0)).isEqualTo("汇总");
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(1).getStringCellValue())
                    .startsWith("'=");
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(1).getCellType().name())
                    .isEqualTo("STRING");
        }
    }
}
