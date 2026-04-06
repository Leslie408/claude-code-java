/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code GlobTool
 */
package com.anthropic.claudecode.tools;

import com.anthropic.claudecode.*;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * GlobTool - Fast file pattern matching.
 *
 * <p>Corresponds to GlobTool in tools/GlobTool/.
 *
 * <p>IMPORTANT usage notes from system prompt:
 * - Use Glob for file pattern matching (NOT find or ls)
 * - Supports glob patterns like "**\/*.js" or "src/**\/*.ts"
 * - Returns matching file paths sorted by modification time
 * - Use Agent tool for open-ended searches requiring multiple rounds
 * - Use Glob/Grep directly for simple, directed searches
 */
public class GlobTool extends AbstractTool<GlobTool.Input, GlobTool.Output, GlobTool.Progress> {

    public static final String NAME = "Glob";

    public GlobTool() {
        super(NAME, List.of("glob", "find", "ls"), createSchema());
    }

    private static Map<String, Object> createSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> patternProp = new LinkedHashMap<>();
        patternProp.put("type", "string");
        patternProp.put("description", "The glob pattern to match files (e.g. '**/*.js', 'src/**/*.ts')");
        properties.put("pattern", patternProp);

        Map<String, Object> pathProp = new LinkedHashMap<>();
        pathProp.put("type", "string");
        pathProp.put("description", "The directory to search in (defaults to current working directory)");
        properties.put("path", pathProp);

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
                            "Directory not found: " + basePath,
                            true
                    ));
                }

                // Convert glob pattern to PathMatcher
                String globPattern = input.pattern();
                PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + globPattern);

                // Find all matching files
                List<Path> matches = new ArrayList<>();
                Files.walkFileTree(basePath, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        Path relativePath = basePath.relativize(file);
                        if (matcher.matches(relativePath) || matcher.matches(file)) {
                            matches.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        // Check if directory matches (for patterns like "**/src")
                        Path relativeDir = basePath.relativize(dir);
                        if (matcher.matches(relativeDir)) {
                            matches.add(dir);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });

                // Sort by modification time (most recent first)
                matches.sort((a, b) -> {
                    try {
                        long timeA = Files.getLastModifiedTime(a).toMillis();
                        long timeB = Files.getLastModifiedTime(b).toMillis();
                        return Long.compare(timeB, timeA); // Descending order
                    } catch (IOException e) {
                        return 0;
                    }
                });

                // Convert to string paths
                List<String> resultPaths = matches.stream()
                        .map(Path::toString)
                        .collect(Collectors.toList());

                return ToolResult.of(new Output(
                        resultPaths,
                        "",
                        false
                ));

            } catch (IOException e) {
                return ToolResult.of(new Output(
                        List.of(),
                        "Error searching: " + e.getMessage(),
                        true
                ));
            }
        });
    }

    @Override
    public CompletableFuture<String> describe(Input input, ToolDescribeOptions options) {
        return CompletableFuture.completedFuture("Glob: " + input.pattern());
    }

    @Override
    public boolean isReadOnly(Input input) {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(Input input) {
        return true; // Glob is safe to run concurrently
    }

    @Override
    public SearchOrReadCommand isSearchOrReadCommand(Input input) {
        return new SearchOrReadCommand(false, false, true); // It's a list/search operation
    }

    @Override
    public String getActivityDescription(Input input) {
        return "Glob: " + input.pattern();
    }

    @Override
    public String getToolUseSummary(Input input) {
        return input.pattern();
    }

    @Override
    public Input parseInput(Map<String, Object> input) {
        String pattern = (String) input.get("pattern");
        String path = (String) input.get("path");
        return new Input(pattern, path);
    }

    // ==================== Input/Output/Progress ====================

    public record Input(
            String pattern,
            String path
    ) {}

    public record Output(
            List<String> files,
            String error,
            boolean isError
    ) {
        public int count() {
            return files.size();
        }

        public String toResultString() {
            if (isError) {
                return error;
            }
            if (files.isEmpty()) {
                return "No files found matching pattern";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Found ").append(files.size()).append(" files:\n");
            for (String file : files) {
                sb.append(file).append("\n");
            }
            return sb.toString();
        }
    }

    public record Progress(int filesFound) implements ToolProgressData {}
}