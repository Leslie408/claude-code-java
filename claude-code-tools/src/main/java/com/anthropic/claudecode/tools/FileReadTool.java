/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code FileReadTool
 */
package com.anthropic.claudecode.tools;

import com.anthropic.claudecode.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * FileReadTool - Read files from the filesystem.
 *
 * <p>Corresponds to FileReadTool in tools/FileReadTool/.
 *
 * <p>IMPORTANT usage notes from system prompt:
 * - Use Read tool instead of cat, head, tail
 * - Can read images (PNG, JPG) as visual content
 * - Can read PDF files (max 20 pages per request)
 * - Can read Jupyter notebooks (.ipynb)
 * - File path must be absolute, not relative
 * - By default reads up to 2000 lines from beginning
 * - Can specify offset and limit for large files
 */
public class FileReadTool extends AbstractTool<FileReadTool.Input, FileReadTool.Output, FileReadTool.Progress> {

    public static final String NAME = "Read";

    // Image file extensions
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "bmp", "webp", "svg"
    );

    public FileReadTool() {
        super(NAME, List.of("read", "file_read", "cat"), createSchema());
    }

    private static Map<String, Object> createSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> fileProp = new LinkedHashMap<>();
        fileProp.put("type", "string");
        fileProp.put("description", "The absolute path to the file to read");
        properties.put("file_path", fileProp);

        Map<String, Object> limitProp = new LinkedHashMap<>();
        limitProp.put("type", "number");
        limitProp.put("description", "Number of lines to read (default 2000)");
        properties.put("limit", limitProp);

        Map<String, Object> offsetProp = new LinkedHashMap<>();
        offsetProp.put("type", "number");
        offsetProp.put("description", "Line number to start reading from");
        properties.put("offset", offsetProp);

        Map<String, Object> pagesProp = new LinkedHashMap<>();
        pagesProp.put("type", "string");
        pagesProp.put("description", "Page range for PDF files (e.g. '1-5')");
        properties.put("pages", pagesProp);

        schema.put("properties", properties);
        schema.put("required", List.of("file_path"));
        return schema;
    }

    @Override
    public CompletableFuture<ToolResult<Output>> call(
            Input input,
            ToolUseContext context,
            CanUseToolFn canUseTool,
            AssistantMessage parentMessage,
            Consumer<ToolProgress<Progress>> onProgress) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                Path path = Paths.get(input.filePath());

                // Check if file exists
                if (!Files.exists(path)) {
                    return ToolResult.of(new Output(
                            "",
                            "File not found: " + input.filePath(),
                            null,
                            true,
                            FileType.TEXT
                    ));
                }

                // Check if it's an image
                String extension = getFileExtension(path);
                if (IMAGE_EXTENSIONS.contains(extension.toLowerCase())) {
                    return ToolResult.of(readImage(path));
                }

                // Check if it's a PDF
                if ("pdf".equalsIgnoreCase(extension)) {
                    return ToolResult.of(readPdf(path, input.pages()));
                }

                // Check if it's a Jupyter notebook
                if ("ipynb".equalsIgnoreCase(extension)) {
                    return ToolResult.of(readJupyterNotebook(path));
                }

                // Read as text file
                return ToolResult.of(readTextFile(path, input.limit(), input.offset()));

            } catch (Exception e) {
                return ToolResult.of(new Output(
                        "",
                        "Error reading file: " + e.getMessage(),
                        null,
                        true,
                        FileType.TEXT
                ));
            }
        });
    }

    private Output readTextFile(Path path, Integer limit, Integer offset) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);

        int startLine = offset != null ? offset : 0;
        int lineLimit = limit != null ? limit : 2000;
        int endLine = Math.min(startLine + lineLimit, lines.size());

        // Format with line numbers (cat -n format)
        StringBuilder content = new StringBuilder();
        content.append("Lines ").append(startLine + 1).append("-").append(endLine)
               .append(" of ").append(lines.size()).append("\n\n");

        for (int i = startLine; i < endLine; i++) {
            content.append(i + 1).append("\t").append(lines.get(i)).append("\n");
        }

        if (endLine < lines.size()) {
            content.append("\n[File truncated - ").append(lines.size() - endLine)
                   .append(" more lines]");
        }

        return new Output(
                content.toString(),
                "",
                lines.size(),
                false,
                FileType.TEXT
        );
    }

    private Output readImage(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        String base64 = Base64.getEncoder().encodeToString(bytes);

        // Return as visual content marker
        String content = "[Image: " + path.getFileName() + "]\n" +
                        "Size: " + bytes.length + " bytes\n" +
                        "Format: " + getFileExtension(path);

        return new Output(
                content,
                "",
                bytes.length,
                false,
                FileType.IMAGE
        );
    }

    private Output readPdf(Path path, String pages) {
        try {
            // Basic PDF reading without external library
            // Read PDF header and extract text content
            byte[] bytes = Files.readAllBytes(path);

            if (bytes.length < 5) {
                return new Output("[PDF file is empty or corrupted]", "", null, false, FileType.PDF);
            }

            // Check PDF header
            String header = new String(bytes, 0, Math.min(8, bytes.length));
            if (!header.startsWith("%PDF-")) {
                return new Output("[File is not a valid PDF]", "", null, true, FileType.PDF);
            }

            StringBuilder content = new StringBuilder();
            content.append("[PDF file: ").append(path.getFileName()).append("]\n");
            content.append("Size: ").append(bytes.length).append(" bytes\n");

            // Extract basic text content from PDF
            // This is a simplified extraction - full implementation would use PDFBox
            String pdfText = extractPdfText(bytes);

            if (pages != null && !pages.isEmpty()) {
                content.append("Pages requested: ").append(pages).append("\n");
            }

            content.append("\n").append(pdfText);

            return new Output(
                content.toString(),
                "",
                bytes.length,
                false,
                FileType.PDF
            );

        } catch (Exception e) {
            return new Output(
                "[Error reading PDF: " + e.getMessage() + "]",
                "",
                null,
                true,
                FileType.PDF
            );
        }
    }

    /**
     * Extract text content from PDF bytes (simplified implementation).
     */
    private String extractPdfText(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        boolean inTextObject = false;
        StringBuilder currentText = new StringBuilder();

        try {
            String content = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);

            // Look for text between BT and ET markers (Begin Text / End Text)
            int i = 0;
            while (i < content.length()) {
                // Check for BT (Begin Text)
                if (i + 1 < content.length() && content.charAt(i) == 'B' && content.charAt(i + 1) == 'T') {
                    inTextObject = true;
                    i += 2;
                    continue;
                }

                // Check for ET (End Text)
                if (i + 1 < content.length() && content.charAt(i) == 'E' && content.charAt(i + 1) == 'T') {
                    inTextObject = false;
                    if (currentText.length() > 0) {
                        text.append(currentText.toString().trim()).append("\n");
                        currentText.setLength(0);
                    }
                    i += 2;
                    continue;
                }

                // Extract text from Tj and TJ operators
                if (inTextObject) {
                    if (content.charAt(i) == '(') {
                        // Text string between parentheses
                        i++;
                        int start = i;
                        while (i < content.length() && content.charAt(i) != ')') {
                            if (content.charAt(i) == '\\') i++;
                            i++;
                        }
                        String str = content.substring(start, i);
                        // Unescape PDF string escapes
                        str = unescapePdfString(str);
                        currentText.append(str);
                    } else if (content.charAt(i) == '<') {
                        // Hex string
                        i++;
                        int start = i;
                        while (i < content.length() && content.charAt(i) != '>') i++;
                        String hex = content.substring(start, i);
                        currentText.append(decodeHexPdfString(hex));
                    }
                }

                i++;
            }

            // Add any remaining text
            if (currentText.length() > 0) {
                text.append(currentText.toString().trim()).append("\n");
            }

        } catch (Exception e) {
            text.append("[Text extraction error]");
        }

        String result = text.toString().trim();
        if (result.isEmpty()) {
            return "[PDF text extraction limited - install Apache PDFBox for full support]";
        }
        return result;
    }

    /**
     * Unescape PDF string escapes.
     */
    private String unescapePdfString(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case 'n' -> { sb.append('\n'); i++; }
                    case 'r' -> { sb.append('\r'); i++; }
                    case 't' -> { sb.append('\t'); i++; }
                    case '(' -> { sb.append('('); i++; }
                    case ')' -> { sb.append(')'); i++; }
                    case '\\' -> { sb.append('\\'); i++; }
                    default -> sb.append(c);
                }
            } else {
                // Filter out control characters
                if (c >= 32 || c == '\n' || c == '\r' || c == '\t') {
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    /**
     * Decode hex string from PDF.
     */
    private String decodeHexPdfString(String hex) {
        StringBuilder sb = new StringBuilder();
        try {
            for (int i = 0; i < hex.length(); i += 2) {
                if (i + 1 < hex.length()) {
                    int code = Integer.parseInt(hex.substring(i, i + 2), 16);
                    if (code >= 32 && code < 127) {
                        sb.append((char) code);
                    }
                }
            }
        } catch (Exception ignored) {}
        return sb.toString();
    }

    private Output readJupyterNotebook(Path path) throws IOException {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        // Jupyter notebooks are JSON - we could parse and format cells
        return new Output(
                content,
                "",
                null,
                false,
                FileType.JUPYTER
        );
    }

    private String getFileExtension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1) : "";
    }

    @Override
    public CompletableFuture<String> describe(Input input, ToolDescribeOptions options) {
        String path = input.filePath();
        String filename = Paths.get(path).getFileName().toString();
        return CompletableFuture.completedFuture("Read " + filename);
    }

    @Override
    public boolean isReadOnly(Input input) {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(Input input) {
        return true; // Reading files is safe to run concurrently
    }

    @Override
    public SearchOrReadCommand isSearchOrReadCommand(Input input) {
        return new SearchOrReadCommand(false, true, false);
    }

    @Override
    public String getActivityDescription(Input input) {
        String filename = Paths.get(input.filePath()).getFileName().toString();
        return "Reading " + filename;
    }

    @Override
    public Input parseInput(Map<String, Object> input) {
        String filePath = (String) input.get("file_path");
        Integer limit = input.get("limit") != null ? ((Number) input.get("limit")).intValue() : null;
        Integer offset = input.get("offset") != null ? ((Number) input.get("offset")).intValue() : null;
        String pages = (String) input.get("pages");
        return new Input(filePath, limit, offset, pages);
    }

    // ==================== Input/Output/Progress ====================

    public record Input(
            String filePath,
            Integer limit,
            Integer offset,
            String pages
    ) {}

    public record Output(
            String content,
            String error,
            Integer lineCount,
            boolean isError,
            FileType fileType
    ) {
        public String toResultString() {
            if (isError) {
                return error;
            }
            return content;
        }
    }

    public record Progress(String partialContent) implements ToolProgressData {}

    public enum FileType {
        TEXT, IMAGE, PDF, JUPYTER, DIRECTORY
    }
}