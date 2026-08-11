package com.yubai.blog.ai;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yubai.blog.admin.ai.AiServiceException;
import com.yubai.blog.config.AiPlatformProperties;
import com.yubai.blog.note.InvalidNoteFileException;
import com.yubai.blog.note.NoteAttachmentService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class AiFileParserRegistry {
    private static final String DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String XLS = "application/vnd.ms-excel";
    private static final Map<String, String> MEDIA_TYPES =
            Map.ofEntries(
                    Map.entry("png", "image/png"),
                    Map.entry("jpg", "image/jpeg"),
                    Map.entry("jpeg", "image/jpeg"),
                    Map.entry("webp", "image/webp"),
                    Map.entry("pdf", "application/pdf"),
                    Map.entry("docx", DOCX),
                    Map.entry("xlsx", XLSX),
                    Map.entry("xls", XLS),
                    Map.entry("txt", "text/plain"),
                    Map.entry("md", "text/markdown"),
                    Map.entry("markdown", "text/markdown"),
                    Map.entry("csv", "text/csv"),
                    Map.entry("json", "application/json"));
    private static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/webp");

    private final AiPlatformProperties properties;
    private final ObjectMapper constrainedJsonMapper;

    public AiFileParserRegistry(AiPlatformProperties properties) {
        this.properties = properties;
        constrainedJsonMapper = new ObjectMapper();
        constrainedJsonMapper
                .getFactory()
                .setStreamReadConstraints(
                        StreamReadConstraints.builder()
                                .maxNestingDepth(100)
                                .maxStringLength(Math.max(1, properties.getMaxExtractedChars()))
                                .maxNumberLength(1_000)
                                .build());
    }

    public AiParsedFile parse(String filename, String declaredMediaType, byte[] bytes) {
        var extension = extension(filename);
        var mediaType = MEDIA_TYPES.get(extension);
        if (mediaType == null) {
            throw badRequest("Unsupported AI file type");
        }
        validateDeclaration(mediaType, declaredMediaType);
        if (IMAGE_TYPES.contains(mediaType)) {
            parseImage(bytes, mediaType);
            return new AiParsedFile(mediaType, null, true);
        }
        return switch (mediaType) {
            case "application/pdf" -> new AiParsedFile(mediaType, parsePdf(bytes), false);
            case DOCX -> new AiParsedFile(mediaType, parseDocx(bytes), false);
            case XLSX, XLS -> new AiParsedFile(mediaType, parseSpreadsheet(bytes), false);
            case "text/csv" -> new AiParsedFile(mediaType, parseCsv(bytes), false);
            case "application/json" -> new AiParsedFile(mediaType, parseJson(bytes), false);
            default -> new AiParsedFile(mediaType, parseText(bytes), false);
        };
    }

    public static String safeFilename(String original) {
        if (original == null || original.isBlank()) return "upload.bin";
        var base = original.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        if (base.isBlank()) return "upload.bin";
        return base.length() <= 255 ? base : base.substring(base.length() - 255);
    }

    private void parseImage(byte[] bytes, String mediaType) {
        if (!NoteAttachmentService.matchesMagicBytes(bytes, mediaType)) {
            throw badRequest("Image content does not match its type");
        }
        try {
            NoteAttachmentService.assertDimensionsWithinLimit(bytes);
        } catch (InvalidNoteFileException exception) {
            throw badRequest("Image dimensions are invalid");
        }
    }

    private String parsePdf(byte[] bytes) {
        if (bytes.length < 5
                || bytes[0] != '%'
                || bytes[1] != 'P'
                || bytes[2] != 'D'
                || bytes[3] != 'F'
                || bytes[4] != '-') {
            throw badRequest("PDF magic bytes are invalid");
        }
        try (var document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()) throw badRequest("Encrypted PDF files are not supported");
            if (document.getNumberOfPages() > Math.max(1, properties.getMaxPdfPages())) {
                throw badRequest("PDF page limit exceeded");
            }
            return limit(new PDFTextStripper().getText(document));
        } catch (AiServiceException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw badRequest("PDF is damaged or unsupported");
        }
    }

    private String parseDocx(byte[] bytes) {
        validateDocxContainer(bytes);
        try (var document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            var text = new StringBuilder();
            document.getParagraphs().forEach(paragraph -> appendLine(text, paragraph.getText()));
            document.getTables()
                    .forEach(
                            table ->
                                    table.getRows()
                                            .forEach(
                                                    row ->
                                                            row.getTableCells()
                                                                    .forEach(
                                                                            cell ->
                                                                                    appendLine(
                                                                                            text,
                                                                                            cell
                                                                                                    .getText()))));
            return limit(text.toString());
        } catch (IOException | RuntimeException exception) {
            throw badRequest("DOCX is damaged or unsupported");
        }
    }

    private String parseSpreadsheet(byte[] bytes) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var formatter = new DataFormatter();
            var output = new StringBuilder();
            var rowLimit = Math.max(1, properties.getMaxCsvRows());
            var columnLimit = Math.max(1, properties.getMaxCsvColumns());
            var rows = 0;
            for (var sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                var sheet = workbook.getSheetAt(sheetIndex);
                appendLine(output, "Sheet: " + sheet.getSheetName());
                var lastRow = sheet.getLastRowNum();
                if (lastRow >= rowLimit) throw badRequest("Spreadsheet row limit exceeded");
                for (var rowIndex = sheet.getFirstRowNum(); rowIndex <= lastRow; rowIndex++) {
                    var row = sheet.getRow(rowIndex);
                    if (row == null) continue;
                    var lastCell = row.getLastCellNum();
                    if (lastCell > columnLimit) {
                        throw badRequest("Spreadsheet column limit exceeded");
                    }
                    var line = new StringBuilder();
                    for (var columnIndex = 0; columnIndex < Math.max(0, lastCell); columnIndex++) {
                        if (!line.isEmpty()) line.append('\t');
                        var cell = row.getCell(columnIndex);
                        line.append(cell == null ? "" : formatter.formatCellValue(cell));
                    }
                    appendLine(output, line.toString());
                    rows++;
                    if (rows > rowLimit) throw badRequest("Spreadsheet row limit exceeded");
                }
            }
            return limit(output.toString());
        } catch (AiServiceException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw badRequest("Spreadsheet is damaged or unsupported");
        }
    }

    private void validateDocxContainer(byte[] bytes) {
        if (bytes.length < 4
                || bytes[0] != 'P'
                || bytes[1] != 'K'
                || bytes[2] != 3
                || bytes[3] != 4) {
            throw badRequest("DOCX container is invalid");
        }
        var entries = 0;
        long total = 0;
        var buffer = new byte[8192];
        var hasContentTypes = false;
        var hasDocument = false;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                if (entries > Math.max(1, properties.getMaxDocxEntries())) {
                    throw badRequest("DOCX entry limit exceeded");
                }
                var name = entry.getName().replace('\\', '/').toLowerCase(Locale.ROOT);
                if (name.startsWith("/")
                        || name.contains("../")
                        || name.endsWith("vbaproject.bin")) {
                    throw badRequest("DOCX contains an unsafe entry");
                }
                hasContentTypes |= name.equals("[content_types].xml");
                hasDocument |= name.equals("word/document.xml");
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    total += read;
                    if (total > Math.max(1, properties.getMaxDocxUncompressedBytes())) {
                        throw badRequest("DOCX uncompressed size limit exceeded");
                    }
                }
            }
        } catch (AiServiceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw badRequest("DOCX container is damaged");
        }
        if (!hasContentTypes || !hasDocument) throw badRequest("DOCX structure is invalid");
    }

    private String parseCsv(byte[] bytes) {
        var text = parseText(bytes);
        var rows = parseCsvRows(text);
        if (rows.size() > Math.max(1, properties.getMaxCsvRows())) {
            throw badRequest("CSV row limit exceeded");
        }
        for (var row : rows) {
            if (row.size() > Math.max(1, properties.getMaxCsvColumns())) {
                throw badRequest("CSV column limit exceeded");
            }
        }
        return text;
    }

    private String parseJson(byte[] bytes) {
        var text = parseText(bytes);
        try {
            constrainedJsonMapper.readTree(text);
            return text;
        } catch (IOException exception) {
            throw badRequest("JSON is invalid or exceeds structural limits");
        }
    }

    private String parseText(byte[] bytes) {
        try {
            var decoder =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT);
            var text = decoder.decode(ByteBuffer.wrap(bytes)).toString();
            if (text.indexOf('\0') >= 0) throw badRequest("Text file contains NUL bytes");
            return limit(text);
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw badRequest("Text file must be valid UTF-8");
        }
    }

    private String limit(String text) {
        var max = Math.max(1, properties.getMaxExtractedChars());
        if (text.length() > max) throw badRequest("Extracted text limit exceeded");
        return text;
    }

    private static List<List<String>> parseCsvRows(String text) {
        var rows = new ArrayList<List<String>>();
        var row = new ArrayList<String>();
        var cell = new StringBuilder();
        var quoted = false;
        for (int i = 0; i < text.length(); i++) {
            var value = text.charAt(i);
            if (value == '"') {
                if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (value == ',' && !quoted) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if ((value == '\n' || value == '\r') && !quoted) {
                if (value == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(List.copyOf(row));
                row.clear();
            } else {
                cell.append(value);
            }
        }
        if (quoted) throw badRequest("CSV contains an unterminated quoted field");
        if (!cell.isEmpty() || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(List.copyOf(row));
        }
        return rows;
    }

    private static void appendLine(StringBuilder output, String value) {
        if (value == null || value.isBlank()) return;
        if (!output.isEmpty()) output.append('\n');
        output.append(value);
    }

    private static void validateDeclaration(String expected, String declared) {
        if (declared == null || declared.isBlank() || declared.equals("application/octet-stream"))
            return;
        var normalized = declared.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (expected.equals("text/markdown") && normalized.equals("text/plain")) return;
        if (expected.equals("text/csv") && normalized.equals("text/plain")) return;
        if (!expected.equals(normalized)) {
            throw badRequest("Declared MIME type does not match the filename");
        }
    }

    private static String extension(String filename) {
        var safe = safeFilename(filename).toLowerCase(Locale.ROOT);
        var index = safe.lastIndexOf('.');
        return index < 0 ? "" : safe.substring(index + 1);
    }

    private static AiServiceException badRequest(String message) {
        return new AiServiceException(HttpStatus.BAD_REQUEST, message);
    }
}
