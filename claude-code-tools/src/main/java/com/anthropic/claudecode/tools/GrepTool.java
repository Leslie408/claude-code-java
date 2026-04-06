/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code GrepTool
 */
package com.anthropic.claudecode.tools;

import com.anthropic.claudecode.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * GrepTool - Search file contents using regex patterns.
 *
 * <p>Corresponds to GrepTool in tools/GrepTool/.
 *
 * <p>IMPORTANT usage notes from system prompt:
 * - ALWAYS use Grep for search tasks. NEVER invoke grep or rg via Bash
 * - The tool has been optimized for correct permissions and access
 * - Use Agent tool for open-ended searches requiring multiple rounds
 * - Use Glob/Grep directly for simple, directed searches
 * - Supports full regex syntax (e.g. "log.*Error", "function\\s+\\w+")
 * - Filter files with glob parameter (e.g. "*.js" or "**\/*.tsx")
 * - Output modes: "content" (shows lines), "files_with_matches", "count"
 * - Uses ripgrep pattern syntax - literal braces need escaping
 */
public class GrepTool extends AbstractTool<GrepTool.Input, GrepTool.Output, GrepTool.Progress> {

    public static final String NAME = "Grep";

    public GrepTool() {
        super(NAME, List.of("grep", "rg", "search"), createSchema());
    }

    private static Map<String, Object> createSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> patternProp = new LinkedHashMap<>();
        patternProp.put("type", "string");
        patternProp.put("description", "The regular expression pattern to search for");
        properties.put("pattern", patternProp);

        Map<String, Object> pathProp = new LinkedHashMap<>();
        pathProp.put("type", "string");
        pathProp.put("description", "The directory to search in (defaults to current working directory)");
        properties.put("path", pathProp);

        Map<String, Object> globProp = new LinkedHashMap<>();
        globProp.put("type", "string");
        globProp.put("description", "Glob pattern to filter files (e.g. '*.js', '**/*.tsx')");
        properties.put("glob", globProp);

        Map<String, Object> typeProp = new LinkedHashMap<>();
        typeProp.put("type", "string");
        typeProp.put("description", "File type to search (js, py, rust, go, java, etc.)");
        properties.put("type", typeProp);

        Map<String, Object> outputModeProp = new LinkedHashMap<>();
        outputModeProp.put("type", "string");
        outputModeProp.put("enum", List.of("content", "files_with_matches", "count"));
        outputModeProp.put("description", "Output mode: 'content' shows lines, 'files_with_matches' shows paths, 'count' shows counts");
        outputModeProp.put("default", "files_with_matches");
        properties.put("output_mode", outputModeProp);

        Map<String, Object> caseInsensitiveProp = new LinkedHashMap<>();
        caseInsensitiveProp.put("type", "boolean");
        caseInsensitiveProp.put("description", "Case insensitive search");
        properties.put("-i", caseInsensitiveProp);

        Map<String, Object> multilineProp = new LinkedHashMap<>();
        multilineProp.put("type", "boolean");
        multilineProp.put("description", "Enable multiline mode for patterns spanning lines");
        properties.put("multiline", multilineProp);

        Map<String, Object> headLimitProp = new LinkedHashMap<>();
        headLimitProp.put("type", "number");
        headLimitProp.put("description", "Limit output to first N results (default 250)");
        properties.put("head_limit", headLimitProp);

        Map<String, Object> contextProp = new LinkedHashMap<>();
        contextProp.put("type", "number");
        contextProp.put("description", "Number of lines to show before and after each match");
        properties.put("-C", contextProp);

        schema.put("properties", properties);
        schema.put("required", List.of("pattern"));
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
                Path basePath = input.path() != null
                        ? Paths.get(input.path())
                        : Paths.get(System.getProperty("user.dir"));

                // Validate directory exists
                if (!Files.exists(basePath) || !Files.isDirectory(basePath)) {
                    return ToolResult.of(new Output(
                            List.of(),
                            List.of(),
                            0,
                            "Directory not found: " + basePath,
                            true
                    ));
                }

                // Compile regex pattern
                int flags = 0;
                if (input.caseInsensitive()) {
                    flags |= Pattern.CASE_INSENSITIVE;
                }
                if (input.multiline()) {
                    flags |= Pattern.MULTILINE;
                }

                Pattern pattern;
                try {
                    pattern = Pattern.compile(input.pattern(), flags);
                } catch (PatternSyntaxException e) {
                    return ToolResult.of(new Output(
                            List.of(),
                            List.of(),
                            0,
                            "Invalid regex pattern: " + e.getMessage(),
                            true
                    ));
                }

                // Create glob matcher if specified
                final PathMatcher finalGlobMatcher;
                if (input.glob() != null) {
                    finalGlobMatcher = FileSystems.getDefault().getPathMatcher("glob:" + input.glob());
                } else if (input.type() != null) {
                    String typeGlob = getTypeGlob(input.type());
                    finalGlobMatcher = typeGlob != null
                            ? FileSystems.getDefault().getPathMatcher("glob:" + typeGlob)
                            : null;
                } else {
                    finalGlobMatcher = null;
                }

                // Search files
                final List<MatchResult> results = new ArrayList<>();
                final int finalHeadLimit = input.headLimit() != null ? input.headLimit() : 250;

                Files.walkFileTree(basePath, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        // Check glob filter
                        if (finalGlobMatcher != null && !finalGlobMatcher.matches(file.getFileName())) {
                            return FileVisitResult.CONTINUE;
                        }

                        // Search file content
                        try {
                            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                            for (int lineNum = 0; lineNum < lines.size(); lineNum++) {
                                String line = lines.get(lineNum);
                                Matcher matcher = pattern.matcher(line);
                                if (matcher.find()) {
                                    results.add(new MatchResult(
                                            file.toString(),
                                            lineNum + 1,
                                            line,
                                            matcher.group()
                                    ));

                                    if (results.size() >= finalHeadLimit) {
                                        return FileVisitResult.TERMINATE;
                                    }
                                }
                            }
                        } catch (IOException e) {
                            // Skip unreadable files
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });

                // Format output based on mode
                String outputMode = input.outputMode() != null ? input.outputMode() : "files_with_matches";

                return formatOutput(results, outputMode, finalHeadLimit);

            } catch (IOException e) {
                return ToolResult.of(new Output(
                        List.of(),
                        List.of(),
                        0,
                        "Error searching: " + e.getMessage(),
                        true
                ));
            }
        });
    }

    private ToolResult<Output> formatOutput(List<MatchResult> results, String mode, int limit) {
        if ("count".equals(mode)) {
            Map<String, Long> counts = results.stream()
                    .collect(Collectors.groupingBy(MatchResult::filePath, Collectors.counting()));
            return ToolResult.of(new Output(
                    List.of(),
                    counts.entrySet().stream()
                            .map(e -> e.getKey() + ": " + e.getValue())
                            .collect(Collectors.toList()),
                    results.size(),
                    "",
                    false
            ));
        }

        if ("files_with_matches".equals(mode)) {
            List<String> files = results.stream()
                    .map(MatchResult::filePath)
                    .distinct()
                    .collect(Collectors.toList());
            return ToolResult.of(new Output(
                    files,
                    List.of(),
                    results.size(),
                    "",
                    false
            ));
        }

        // "content" mode
        List<String> contentLines = results.stream()
                .limit(limit)
                .map(m -> m.filePath() + ":" + m.lineNumber() + ":" + m.line())
                .collect(Collectors.toList());

        return ToolResult.of(new Output(
                List.of(),
                contentLines,
                results.size(),
                "",
                false
        ));
    }

    private String getTypeGlob(String type) {
        return switch (type) {
            case "js" -> "*.js";
            case "py" -> "*.py";
            case "rust" -> "*.rs";
            case "go" -> "*.go";
            case "java" -> "*.java";
            case "ts" -> "*.ts";
            case "tsx" -> "*.tsx";
            case "c" -> "*.c";
            case "cpp" -> "*.cpp";
            case "h" -> "*.h";
            case "json" -> "*.json";
            case "yaml" -> "*.yaml";
            case "yml" -> "*.yml";
            case "md" -> "*.md";
            case "txt" -> "*.txt";
            default -> null;
        };
    }

    @Override
    public CompletableFuture<String> describe(Input input, ToolDescribeOptions options) {
        return CompletableFuture.completedFuture("Grep: " + input.pattern());
    }

    @Override
    public boolean isReadOnly(Input input) {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(Input input) {
        return true; // Grep is safe to run concurrently
    }

    @Override
    public SearchOrReadCommand isSearchOrReadCommand(Input input) {
        return new SearchOrReadCommand(true, false, false);
    }

    @Override
    public String getActivityDescription(Input input) {
        return "Searching for: " + input.pattern();
    }

    @Override
    public String getToolUseSummary(Input input) {
        return input.pattern();
    }

    @Override
    public Input parseInput(Map<String, Object> input) {
        String pattern = (String) input.get("pattern");
        String path = (String) input.get("path");
        String glob = (String) input.get("glob");
        String type = (String) input.get("type");
        String outputMode = (String) input.get("output_mode");
        boolean caseInsensitive = input.get("-i") != null || Boolean.TRUE.equals(input.get("case_insensitive"));
        boolean multiline = Boolean.TRUE.equals(input.get("multiline"));
        Integer headLimit = input.get("head_limit") != null ? ((Number) input.get("head_limit")).intValue() : null;
        Integer context = input.get("context") != null ? ((Number) input.get("context")).intValue() : null;
        return new Input(pattern, path, glob, type,
            outputMode != null ? outputMode : "files_with_matches",
            caseInsensitive, multiline,
            headLimit != null ? headLimit : 250,
            context);
    }

    // ==================== Input/Output/Progress ====================

    public record Input(
            String pattern,
            String path,
            String glob,
            String type,
            String outputMode,
            boolean caseInsensitive,
            boolean multiline,
            Integer headLimit,
            Integer context
    ) {
        // Convenience constructor for most common params
        public Input(String pattern, String path) {
            this(pattern, path, null, null, "files_with_matches", false, false, 250, null);
        }
    }

    public record Output(
            List<String> files,
            List<String> content,
            int totalMatches,
            String error,
            boolean isError
    ) {
        public String toResultString() {
            if (isError) {
                return error;
            }
            StringBuilder sb = new StringBuilder();
            if (!files.isEmpty()) {
                sb.append("Found matches in ").append(files.size()).append(" files:\n");
                for (String file : files) {
                    sb.append(file).append("\n");
                }
            }
            if (!content.isEmpty()) {
                sb.append("Matched lines:\n");
                for (String line : content) {
                    sb.append(line).append("\n");
                }
            }
            return sb.toString();
        }
    }

    public record Progress(int matchesFound) implements ToolProgressData {}

    // Internal match result
    private record MatchResult(String filePath, int lineNumber, String line, String match) {}
}