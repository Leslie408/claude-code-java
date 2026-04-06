/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code diff tool
 */
package com.anthropic.claudecode.tools;

import com.anthropic.claudecode.*;
import com.anthropic.claudecode.components.diff.*;
import com.anthropic.claudecode.permission.PermissionResult;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Diff tool for comparing files.
 *
 * <p>Provides file comparison functionality.
 */
public class DiffTool extends AbstractTool<DiffTool.Input, DiffTool.Output, ToolProgressData> {

    public DiffTool() {
        super("diff", List.of("compare"), createSchema());
    }

    private static Map<String, Object> createSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> file1Prop = new LinkedHashMap<>();
        file1Prop.put("type", "string");
        file1Prop.put("description", "First file path");
        properties.put("file1", file1Prop);

        Map<String, Object> file2Prop = new LinkedHashMap<>();
        file2Prop.put("type", "string");
        file2Prop.put("description", "Second file path");
        properties.put("file2", file2Prop);

        schema.put("properties", properties);
        schema.put("required", List.of("file1", "file2"));

        return schema;
    }

    @Override
    public boolean isReadOnly(Input input) {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(Input input) {
        return true;
    }

    @Override
    public CompletableFuture<ToolResult<Output>> call(
        Input input,
        ToolUseContext context,
        CanUseToolFn canUseTool,
        AssistantMessage parent,
        Consumer<ToolProgress<ToolProgressData>> onProgress
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path path1 = Paths.get(input.file1());
                Path path2 = Paths.get(input.file2());

                // Read files
                String content1 = Files.readString(path1);
                String content2 = Files.readString(path2);

                // Compute diff
                TextDiff differ = new TextDiff();
                List<TextDiff.DiffItem> diffItems = differ.diff(content1, content2);

                // Format output
                String diffOutput = differ.formatUnified(diffItems);
                TextDiff.DiffStats stats = differ.getStats(diffItems);

                Output output = new Output(
                    diffOutput,
                    stats.added(),
                    stats.removed(),
                    stats.unchanged(),
                    stats.hasChanges()
                );

                return ToolResult.of(output);

            } catch (IOException e) {
                Output errorOutput = new Output(e.getMessage(), 0, 0, 0, false);
                return ToolResult.of(errorOutput);
            }
        });
    }

    @Override
    public CompletableFuture<PermissionResult> checkPermissions(Input input, ToolUseContext context) {
        // Read-only operation - allowed by default
        return CompletableFuture.completedFuture(PermissionResult.allow(input));
    }

    @Override
    public Input parseInput(Map<String, Object> input) {
        String file1 = (String) input.get("file1");
        String file2 = (String) input.get("file2");
        String mode = (String) input.get("mode");
        return new Input(file1, file2, mode);
    }

    /**
     * Input for diff tool.
     */
    public record Input(
        String file1,
        String file2,
        String mode
    ) {}

    /**
     * Output from diff tool.
     */
    public record Output(
        String diff,
        int addedLines,
        int removedLines,
        int unchangedLines,
        boolean hasChanges
    ) {}
}