/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code FileWriteTool
 */
package com.anthropic.claudecode.tools;

import com.anthropic.claudecode.*;
import com.anthropic.claudecode.permission.PermissionResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * FileWriteTool - Write files to the filesystem.
 *
 * <p>Corresponds to FileWriteTool in tools/FileWriteTool/.
 *
 * <p>IMPORTANT usage notes:
 * - This tool will OVERWRITE existing files
 * - You MUST use Read tool first before editing existing files
 * - Prefer Edit tool for modifying existing files
 * - Only use Write for complete rewrites or new files
 * - NEVER write documentation files (*.md) unless explicitly requested
 * - File path must be absolute
 */
public class FileWriteTool extends AbstractTool<FileWriteTool.Input, FileWriteTool.Output, FileWriteTool.Progress> {

    public static final String NAME = "Write";

    public FileWriteTool() {
        super(NAME, List.of("write", "file_write"), createSchema());
    }

    private static Map<String, Object> createSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> fileProp = new LinkedHashMap<>();
        fileProp.put("type", "string");
        fileProp.put("description", "The absolute path to the file to write");
        properties.put("file_path", fileProp);

        Map<String, Object> contentProp = new LinkedHashMap<>();
        contentProp.put("type", "string");
        contentProp.put("description", "The content to write to the file");
        properties.put("content", contentProp);

        schema.put("properties", properties);
        schema.put("required", List.of("file_path", "content"));
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

                // Create parent directories if needed
                Path parent = path.getParent();
                if (parent != null && !Files.exists(parent)) {
                    Files.createDirectories(parent);
                }

                // Check if file already exists
                boolean existed = Files.exists(path);

                // Write the content
                Files.writeString(path, input.content(), StandardCharsets.UTF_8);

                long size = Files.size(path);

                return ToolResult.of(new Output(
                        "Successfully wrote " + size + " bytes to " + input.filePath(),
                        "",
                        size,
                        existed,
                        false
                ));

            } catch (IOException e) {
                return ToolResult.of(new Output(
                        "",
                        "Error writing file: " + e.getMessage(),
                        0,
                        false,
                        true
                ));
            }
        });
    }

    @Override
    public CompletableFuture<PermissionResult> checkPermissions(Input input, ToolUseContext context) {
        // Writing files requires permission
        Path path = Paths.get(input.filePath());

        // Check if file exists - overwriting needs extra permission
        if (Files.exists(path)) {
            return CompletableFuture.completedFuture(
                    PermissionResult.ask("File exists. Overwrite " + input.filePath() + "?", input)
            );
        }

        return CompletableFuture.completedFuture(PermissionResult.allow(input));
    }

    @Override
    public CompletableFuture<String> describe(Input input, ToolDescribeOptions options) {
        String filename = Paths.get(input.filePath()).getFileName().toString();
        return CompletableFuture.completedFuture("Write " + filename);
    }

    @Override
    public boolean isReadOnly(Input input) {
        return false;
    }

    @Override
    public boolean isDestructive(Input input) {
        return Files.exists(Paths.get(input.filePath()));
    }

    @Override
    public boolean isConcurrencySafe(Input input) {
        return false; // Writing files should not run concurrently
    }

    @Override
    public String getActivityDescription(Input input) {
        String filename = Paths.get(input.filePath()).getFileName().toString();
        return "Writing " + filename;
    }

    @Override
    public String getToolUseSummary(Input input) {
        String filename = Paths.get(input.filePath()).getFileName().toString();
        int lines = input.content().split("\n").length;
        return filename + " (" + lines + " lines)";
    }

    @Override
    public Input parseInput(Map<String, Object> input) {
        String filePath = (String) input.get("file_path");
        String content = (String) input.get("content");
        return new Input(filePath, content);
    }

    // ==================== Input/Output/Progress ====================

    public record Input(
            String filePath,
            String content
    ) {}

    public record Output(
            String message,
            String error,
            long bytesWritten,
            boolean overwritten,
            boolean isError
    ) {
        public String toResultString() {
            if (isError) {
                return error;
            }
            String suffix = overwritten ? " (overwritten)" : " (created)";
            return message + suffix;
        }
    }

    public record Progress(long bytesWritten) implements ToolProgressData {}
}