package com.yubai.blog.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yubai.blog.admin.ai.AiServiceException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Component;

/**
 * Deterministic, bounded renderers for assistant-produced artifacts.
 *
 * <p>The renderer deliberately accepts data, not executable document templates. It does not load
 * external URLs, macros, formulas from arbitrary strings, or server-side paths.
 */
@Component
public class AiDocumentRenderer {
    private static final int MAX_CONTENT_CHARS = 120_000;
    private static final int MAX_ROWS = 10_000;
    private static final int MAX_COLUMNS = 200;
    private static final int MAX_PDF_PAGES = 100;
    private static final float PAGE_MARGIN = 54F;
    private static final float BODY_SIZE = 10.5F;
    private static final float LINE_HEIGHT = 15F;
    private static final DateTimeFormatter GENERATED_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private final ObjectMapper objectMapper;

    public AiDocumentRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public RenderedDocument render(AiArtifactFormat format, String title, String content) {
        if (format == null || !format.isDocument()) {
            throw new AiServiceException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Unsupported document artifact format");
        }
        var normalizedTitle = normalizeTitle(title);
        var normalizedContent = boundedContent(content);
        return switch (format) {
            case PDF -> renderPdf(normalizedTitle, normalizedContent);
            case DOCX -> renderDocx(normalizedTitle, normalizedContent);
            case XLSX -> renderXlsx(normalizedTitle, normalizedContent);
            default -> throw new IllegalStateException("Unhandled document format: " + format);
        };
    }

    public boolean supports(AiArtifactFormat format) {
        return format != null && format.isDocument();
    }

    private RenderedDocument renderPdf(String title, String content) {
        try (var document = new PDDocument();
                var output = new ByteArrayOutputStream()) {
            var regular = loadFont(document, "/fonts/NotoSansSC-Static.ttf");
            var bold = regular;
            document.getDocumentInformation().setTitle(title);
            document.getDocumentInformation().setAuthor("BlogDemo AI workspace");
            document.getDocumentInformation().setCreator("BlogDemo controlled artifact renderer");
            document.getDocumentInformation().setCreationDate(java.util.Calendar.getInstance());

            var blocks = markdownBlocks(content);
            var page = new PdfPage(document, regular, bold, title);
            page.writeTitle();
            for (var block : blocks) page.write(block);
            page.close();
            if (document.getNumberOfPages() > MAX_PDF_PAGES) {
                throw new AiServiceException(
                        org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,
                        "PDF exceeds the maximum page limit");
            }
            document.save(output);
            var bytes = output.toByteArray();
            validatePdf(bytes, title);
            return new RenderedDocument(AiArtifactFormat.PDF.mediaType(), bytes);
        } catch (AiServiceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new AiServiceException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "PDF artifact could not be rendered",
                    exception);
        }
    }

    private RenderedDocument renderDocx(String title, String content) {
        try (var document = new XWPFDocument();
                var output = new ByteArrayOutputStream()) {
            var headerFooter = new XWPFHeaderFooterPolicy(document);
            var header = headerFooter.createHeader(XWPFHeaderFooterPolicy.DEFAULT);
            header.createParagraph().createRun().setText(title);
            var footer = headerFooter.createFooter(XWPFHeaderFooterPolicy.DEFAULT);
            footer.createParagraph().createRun().setText("BlogDemo AI · " + generatedAt());

            var heading = document.createParagraph();
            heading.setStyle("Title");
            heading.setAlignment(ParagraphAlignment.CENTER);
            heading.createRun().setText(title);
            for (var block : markdownBlocks(content)) addDocxBlock(document, block);
            document.write(output);
            var bytes = output.toByteArray();
            validateDocx(bytes, title);
            return new RenderedDocument(AiArtifactFormat.DOCX.mediaType(), bytes);
        } catch (AiServiceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new AiServiceException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "DOCX artifact could not be rendered");
        }
    }

    private RenderedDocument renderXlsx(String title, String content) {
        try (var workbook = new XSSFWorkbook();
                var output = new ByteArrayOutputStream()) {
            var spec = readWorkbookSpec(content);
            var headerStyle = headerStyle(workbook);
            var bodyStyle = bodyStyle(workbook);
            for (var sheetSpec : spec) {
                var sheetName = safeSheetName(sheetSpec.name());
                var sheet = workbook.createSheet(sheetName);
                var rows = boundedRows(sheetSpec.rows());
                for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                    var row = sheet.createRow(rowIndex);
                    var values = rows.get(rowIndex);
                    for (int columnIndex = 0; columnIndex < values.size(); columnIndex++) {
                        var cell = row.createCell(columnIndex);
                        putSafeCell(cell, values.get(columnIndex), rowIndex == 0);
                        cell.setCellStyle(rowIndex == 0 ? headerStyle : bodyStyle);
                    }
                }
                if (!rows.isEmpty()) sheet.createFreezePane(0, 1);
                for (int column = 0; column < maxColumns(rows); column++) {
                    sheet.setColumnWidth(column, 18 * 256);
                }
                sheet.setAutobreaks(true);
            }
            workbook.write(output);
            var bytes = output.toByteArray();
            validateXlsx(bytes, title);
            return new RenderedDocument(AiArtifactFormat.XLSX.mediaType(), bytes);
        } catch (AiServiceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new AiServiceException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "XLSX artifact could not be rendered");
        }
    }

    private static void addDocxBlock(XWPFDocument document, MarkdownBlock block) {
        if (block.kind() == BlockKind.TABLE) {
            var table =
                    document.createTable(
                            block.rows().size(), Math.max(1, maxColumns(block.rows())));
            for (int rowIndex = 0; rowIndex < block.rows().size(); rowIndex++) {
                var values = block.rows().get(rowIndex);
                for (int columnIndex = 0; columnIndex < values.size(); columnIndex++) {
                    var cell = table.getRow(rowIndex).getCell(columnIndex);
                    cell.removeParagraph(0);
                    var paragraph = cell.addParagraph();
                    var run = paragraph.createRun();
                    run.setText(values.get(columnIndex));
                    run.setBold(rowIndex == 0);
                }
            }
            return;
        }
        var paragraph = document.createParagraph();
        if (block.kind() == BlockKind.HEADING) {
            paragraph.setStyle("Heading" + Math.min(6, Math.max(1, block.level())));
        } else if (block.kind() == BlockKind.LIST) {
            paragraph.setStyle("List Bullet");
        }
        addRuns(paragraph, block.text());
    }

    private static void addRuns(XWPFParagraph paragraph, String text) {
        var remaining = text == null ? "" : text;
        while (!remaining.isEmpty()) {
            var boldStart = remaining.indexOf("**");
            var italicStart = remaining.indexOf('*');
            if (boldStart < 0 && italicStart < 0) {
                paragraph.createRun().setText(remaining);
                break;
            }
            var start =
                    boldStart >= 0 && (italicStart < 0 || boldStart <= italicStart)
                            ? boldStart
                            : italicStart;
            if (start > 0) {
                paragraph.createRun().setText(remaining.substring(0, start));
                remaining = remaining.substring(start);
            }
            var marker = remaining.startsWith("**") ? "**" : "*";
            var end = remaining.indexOf(marker, marker.length());
            if (end < 0) {
                paragraph.createRun().setText(remaining);
                break;
            }
            var run = paragraph.createRun();
            run.setText(remaining.substring(marker.length(), end));
            if (marker.equals("**")) run.setBold(true);
            else {
                run.setItalic(true);
                run.setUnderline(UnderlinePatterns.NONE);
            }
            remaining = remaining.substring(end + marker.length());
        }
    }

    private static XSSFCellStyle headerStyle(XSSFWorkbook workbook) {
        var style = workbook.createCellStyle();
        style.setFillForegroundColor(
                new XSSFColor(new byte[] {(byte) 0xE8, (byte) 0xF0, (byte) 0xFE}, null));
        style.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
        style.setWrapText(true);
        var font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private static XSSFCellStyle bodyStyle(XSSFWorkbook workbook) {
        var style = workbook.createCellStyle();
        style.setWrapText(true);
        return style;
    }

    private static void putSafeCell(XSSFCell cell, String raw, boolean header) {
        var value = raw == null ? "" : raw.trim();
        if (value.length() > 32_000) value = value.substring(0, 32_000);
        if (!header
                && value.startsWith("=")
                && value.matches("=\s*(SUM|AVERAGE|COUNT|MIN|MAX)\\([^;]+\\)")) {
            cell.setCellFormula(value.substring(1));
        } else {
            if (!value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0) value = "'" + value;
            cell.setCellValue(value);
        }
    }

    private List<SheetSpec> readWorkbookSpec(String content) {
        try {
            var node = objectMapper.readTree(content);
            if (node != null && node.isObject() && node.has("sheets")) {
                var result = new ArrayList<SheetSpec>();
                for (var sheet : node.withArray("sheets")) {
                    result.add(
                            new SheetSpec(
                                    text(sheet, "name", "Sheet" + (result.size() + 1)),
                                    jsonRows(sheet.get("rows"))));
                }
                if (!result.isEmpty()) return result;
            }
        } catch (Exception ignored) {
            // Plain text/CSV is intentionally supported below.
        }
        return List.of(new SheetSpec("Sheet1", parseDelimitedRows(content)));
    }

    private static List<List<String>> jsonRows(JsonNode node) {
        var rows = new ArrayList<List<String>>();
        if (node == null || !node.isArray()) return rows;
        for (var row : node) {
            var values = new ArrayList<String>();
            if (row.isArray()) {
                for (var value : row)
                    values.add(value.isValueNode() ? value.asText() : value.toString());
            } else values.add(row.asText());
            rows.add(List.copyOf(values));
        }
        return rows;
    }

    private static List<List<String>> parseDelimitedRows(String content) {
        var rows = new ArrayList<List<String>>();
        for (var line : content.split("\\R", -1)) {
            if (line.isBlank()) continue;
            var delimiter = line.indexOf(',') >= 0 ? ',' : '\t';
            var values = new ArrayList<String>();
            for (var cell :
                    line.split(java.util.regex.Pattern.quote(String.valueOf(delimiter)), -1)) {
                values.add(cell.trim());
            }
            rows.add(List.copyOf(values));
        }
        return rows;
    }

    private static List<List<String>> boundedRows(List<List<String>> rows) {
        return rows.stream()
                .limit(MAX_ROWS)
                .map(row -> row.stream().limit(MAX_COLUMNS).toList())
                .toList();
    }

    private static int maxColumns(List<List<String>> rows) {
        return rows.stream().mapToInt(List::size).max().orElse(0);
    }

    private static String safeSheetName(String raw) {
        var value = raw == null ? "Sheet" : raw.replaceAll("[\\\\/*?:\\[\\]]", " ").trim();
        if (value.isBlank()) value = "Sheet";
        return value.substring(0, Math.min(31, value.length()));
    }

    private static String text(JsonNode node, String field, String fallback) {
        var value = node == null ? null : node.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank()
                ? value.asText()
                : fallback;
    }

    private static String normalizeTitle(String title) {
        var value = title == null ? "AI artifact" : title.replaceAll("[\\p{Cntrl}]", " ").trim();
        return value.isBlank() ? "AI artifact" : value.substring(0, Math.min(160, value.length()));
    }

    private static String boundedContent(String content) {
        var value = content == null ? "" : content.replace("\u0000", "").trim();
        if (value.length() > MAX_CONTENT_CHARS) {
            throw new AiServiceException(
                    org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,
                    "Document content exceeds the maximum length");
        }
        if (value.isBlank()) {
            throw new AiServiceException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Document content is empty");
        }
        return value;
    }

    private static String generatedAt() {
        return OffsetDateTime.now().format(GENERATED_AT);
    }

    private static PDType0Font loadFont(PDDocument document, String path) throws IOException {
        try (InputStream input = AiDocumentRenderer.class.getResourceAsStream(path)) {
            if (input == null) throw new IOException("Embedded font is missing: " + path);
            return PDType0Font.load(document, input, false);
        }
    }

    private static void validatePdf(byte[] bytes, String title) throws IOException {
        if (bytes.length < 5
                || bytes[0] != '%'
                || bytes[1] != 'P'
                || bytes[2] != 'D'
                || bytes[3] != 'F') {
            throw new IOException("PDF magic bytes are invalid");
        }
        try (var loaded = Loader.loadPDF(bytes)) {
            var extracted = new PDFTextStripper().getText(loaded);
            if (loaded.getNumberOfPages() < 1
                    || extracted.isBlank()
                    || !extracted.contains(title)) {
                throw new IOException("PDF validation did not find the title");
            }
        }
    }

    private static void validateDocx(byte[] bytes, String title) throws IOException {
        try {
            try (var packageRef = OPCPackage.open(new ByteArrayInputStream(bytes));
                    var document = new XWPFDocument(packageRef)) {
                var text =
                        document.getParagraphs().stream()
                                .map(XWPFParagraph::getText)
                                .reduce("", (a, b) -> a + b);
                if (!text.contains(title))
                    throw new IOException("DOCX validation did not find the title");
            }
        } catch (InvalidFormatException exception) {
            throw new IOException("DOCX package is invalid", exception);
        }
    }

    private static void validateXlsx(byte[] bytes, String title) throws IOException {
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() == 0 || workbook.getSheetAt(0).getLastRowNum() < 0) {
                throw new IOException("XLSX validation found no rows");
            }
        }
    }

    private static List<MarkdownBlock> markdownBlocks(String content) {
        var blocks = new ArrayList<MarkdownBlock>();
        var lines = content.split("\\R", -1);
        var paragraph = new StringBuilder();
        var table = new ArrayList<List<String>>();
        for (var raw : lines) {
            var line = raw.trim();
            if (line.startsWith("|") && line.endsWith("|") && line.length() >= 2) {
                flushParagraph(blocks, paragraph);
                if (!line.matches("\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)+\\|?")) {
                    table.add(splitTableRow(line));
                }
                continue;
            }
            if (!table.isEmpty()) {
                blocks.add(new MarkdownBlock(BlockKind.TABLE, null, 0, List.copyOf(table)));
                table.clear();
            }
            if (line.isBlank()) {
                flushParagraph(blocks, paragraph);
                continue;
            }
            var heading = java.util.regex.Pattern.compile("^(#{1,6})\\s+(.+)$").matcher(line);
            if (heading.matches()) {
                flushParagraph(blocks, paragraph);
                blocks.add(
                        new MarkdownBlock(
                                BlockKind.HEADING,
                                heading.group(2),
                                heading.group(1).length(),
                                List.of()));
            } else if (line.matches("^[-*+]\\s+.+$")) {
                flushParagraph(blocks, paragraph);
                blocks.add(
                        new MarkdownBlock(BlockKind.LIST, line.substring(2).trim(), 0, List.of()));
            } else if (line.startsWith("```") || line.startsWith(">")) {
                if (!paragraph.isEmpty()) paragraph.append(' ');
                paragraph.append(line.startsWith(">") ? line.substring(1).trim() : line);
            } else {
                if (!paragraph.isEmpty()) paragraph.append(' ');
                paragraph.append(line);
            }
        }
        if (!table.isEmpty())
            blocks.add(new MarkdownBlock(BlockKind.TABLE, null, 0, List.copyOf(table)));
        flushParagraph(blocks, paragraph);
        return blocks.isEmpty()
                ? List.of(new MarkdownBlock(BlockKind.PARAGRAPH, content, 0, List.of()))
                : blocks;
    }

    private static List<String> splitTableRow(String line) {
        var value = line.substring(1, line.length() - 1);
        return List.of(value.split("\\|", -1)).stream().map(String::trim).toList();
    }

    private static void flushParagraph(List<MarkdownBlock> blocks, StringBuilder paragraph) {
        if (!paragraph.isEmpty()) {
            blocks.add(new MarkdownBlock(BlockKind.PARAGRAPH, paragraph.toString(), 0, List.of()));
            paragraph.setLength(0);
        }
    }

    private enum BlockKind {
        HEADING,
        PARAGRAPH,
        LIST,
        TABLE
    }

    private record MarkdownBlock(BlockKind kind, String text, int level, List<List<String>> rows) {}

    private record SheetSpec(String name, List<List<String>> rows) {}

    public record RenderedDocument(String mediaType, byte[] bytes) {
        public RenderedDocument {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private static final class PdfPage {
        private final PDDocument document;
        private final PDType0Font regular;
        private final PDType0Font bold;
        private final String title;
        private PDPage page;
        private PDPageContentStream stream;
        private float y;
        private int pageNumber;

        PdfPage(PDDocument document, PDType0Font regular, PDType0Font bold, String title)
                throws IOException {
            this.document = document;
            this.regular = regular;
            this.bold = bold;
            this.title = title;
            newPage();
        }

        void writeTitle() throws IOException {
            writeLine(title, 18F, true, 24F);
            writeLine("生成时间：" + generatedAt(), 8.5F, false, 18F);
        }

        void write(MarkdownBlock block) throws IOException {
            if (block.kind() == BlockKind.TABLE) {
                writeTable(block.rows());
                return;
            }
            var size =
                    block.kind() == BlockKind.HEADING
                            ? Math.max(11F, 17F - block.level())
                            : BODY_SIZE;
            var prefix = block.kind() == BlockKind.LIST ? "• " : "";
            writeWrapped(
                    prefix + (block.text() == null ? "" : block.text()),
                    size,
                    block.kind() == BlockKind.HEADING,
                    size + 8F);
        }

        void close() throws IOException {
            footer();
            stream.close();
        }

        private void writeTable(List<List<String>> rows) throws IOException {
            if (rows.isEmpty()) return;
            var columns = Math.min(MAX_COLUMNS, maxColumns(rows));
            var widths = new float[columns];
            java.util.Arrays.fill(widths, 0F);
            for (var row : rows) {
                for (int column = 0; column < Math.min(columns, row.size()); column++) {
                    widths[column] =
                            Math.max(
                                    widths[column],
                                    Math.min(160F, measure(row.get(column), 8.5F) + 10F));
                }
            }
            var available = PDRectangle.A4.getWidth() - PAGE_MARGIN * 2;
            var total = 0F;
            for (var width : widths) total += width;
            if (total > available)
                for (int i = 0; i < widths.length; i++) widths[i] *= available / total;
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                var rowHeight = 20F;
                if (y - rowHeight < PAGE_MARGIN + 18F) newPage();
                float x = PAGE_MARGIN;
                for (int column = 0; column < columns; column++) {
                    var width = widths[column];
                    stream.setNonStrokingColor(
                            (rowIndex == 0 ? 232 : 250) / 255F,
                            (rowIndex == 0 ? 240 : 250) / 255F,
                            (rowIndex == 0 ? 254 : 250) / 255F);
                    stream.addRect(x, y - rowHeight, width, rowHeight);
                    stream.fill();
                    stream.setStrokingColor(190 / 255F, 198 / 255F, 210 / 255F);
                    stream.addRect(x, y - rowHeight, width, rowHeight);
                    stream.stroke();
                    var value =
                            column < rows.get(rowIndex).size()
                                    ? rows.get(rowIndex).get(column)
                                    : "";
                    writeAt(value, x + 4F, y - 14F, 8F, rowIndex == 0);
                    x += width;
                }
                y -= rowHeight;
            }
            y -= 10F;
        }

        private void writeWrapped(String text, float size, boolean boldText, float gap)
                throws IOException {
            var maxWidth = PDRectangle.A4.getWidth() - PAGE_MARGIN * 2;
            var current = new StringBuilder();
            for (var codePoint : text.codePoints().toArray()) {
                var candidate = current.toString() + new String(Character.toChars(codePoint));
                if (!current.isEmpty() && measure(candidate, size) > maxWidth) {
                    writeLine(current.toString(), size, boldText, LINE_HEIGHT);
                    current.setLength(0);
                }
                current.appendCodePoint(codePoint);
            }
            if (!current.isEmpty()) writeLine(current.toString(), size, boldText, gap);
            else y -= gap;
        }

        private void writeLine(String value, float size, boolean boldText, float gap)
                throws IOException {
            if (y - size < PAGE_MARGIN + 20F) newPage();
            writeAt(value, PAGE_MARGIN, y, size, boldText);
            y -= gap;
        }

        private void writeAt(String value, float x, float baseline, float size, boolean boldText)
                throws IOException {
            stream.beginText();
            stream.setFont(boldText ? bold : regular, size);
            stream.newLineAtOffset(x, baseline);
            stream.showText(value == null ? "" : value.replace("\t", "    "));
            stream.endText();
        }

        private float measure(String value, float size) {
            try {
                return (bold.getStringWidth(value == null ? "" : value) / 1000F) * size;
            } catch (IOException ignored) {
                return (value == null ? 0 : value.length()) * size;
            }
        }

        private void newPage() throws IOException {
            if (stream != null) {
                footer();
                stream.close();
            }
            page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            pageNumber = document.getNumberOfPages();
            stream = new PDPageContentStream(document, page);
            y = PDRectangle.A4.getHeight() - PAGE_MARGIN;
        }

        private void footer() throws IOException {
            writeAt(title, PAGE_MARGIN, 26F, 7.5F, false);
            var pageText = pageNumber + " / " + document.getNumberOfPages();
            writeAt(
                    pageText,
                    PDRectangle.A4.getWidth() - PAGE_MARGIN - measure(pageText, 7.5F),
                    26F,
                    7.5F,
                    false);
        }
    }
}
