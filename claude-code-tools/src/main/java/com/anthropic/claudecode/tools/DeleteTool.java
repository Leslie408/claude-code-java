/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code delete tool
 */
package com.anthropic.claudecode.tools;

import com.anthropic.claudecode.*;
import com.anthropic.claudecode.permission.PermissionResult;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Delete tool for removing files and directories.
 *
 * <p>Provides file deletion functionality.
 */
public class DeleteTool extends AbstractTool<DeleteTool.Input, DeleteTool.Output, ToolProgressData> {

    public DeleteTool() {
        super("delete", List.of("rm", "remove"), createSchema());
    }

    private static Map<String, Object> createSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> pathProp = new LinkedHashMap<>();
        pathProp.put("type", "string");
        pathProp.put("description", "Path to delete");
        properties.put("path", pathProp);

        Map<String, Object> recursiveProp = new LinkedHashMap<>();
        recursiveProp.put("type", "boolean");
        recursiveProp.put("description", "Delete directories recursively");
        properties.put("recursive", recursiveProp);

        Map<String, Object> forceProp = new LinkedHashMap<>();
        forceProp.put("type", "boolean");
        forceProp.put("description", "Force deletion");
        properties.put("force", forceProp);

        schema.put("properties", properties);
        schema.put("required", List.of("path"));

        return schema;
    }

    @Override
    public boolean isReadOnly(Input input) {
        return false;
    }

    @Override
    public boolean isDestructive(Input input) {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(Input input) {
        return false;
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
                Path path = Paths.get(input.path());

                if (!Files.exists(path)) {
                    if (input.force()) {
                        Output output = new Output(input.path(), 0, 0, true, null);
                        return ToolResult.of(output);
                    }
                    Output errorOutput = new Output(input.path(), 0, 0, false, "Path not found");
                    return ToolResult.of(errorOutput);
                }

                int filesDeleted;
                int dirsDeleted;

                if (Files.isDirectory(path)) {
                    if (!input.recursive()) {
                        // Try to delete empty directory
                        try {
                            Files.delete(path);
                            dirsDeleted = 1;
                            filesDeleted = 0;
                        } catch (DirectoryNotEmptyException e) {
                            Output errorOutput = new Output(input.path(), 0, 0, false, "Directory not empty, use recursive=true");
                            return ToolResult.of(errorOutput);
                        }
                    } else {
                        // Delete recursively
                        int[] counts = new int[2]; // files, dirs

                        try (Stream<Path> walk = Files.walk(path)) {
                            walk.sorted(Comparator.reverseOrder())
                                .forEach(p -> {
                                    try {
                                        if (Files.isDirectory(p)) {
                                            Files.delete(p);
                                            counts[1]++;
                                        } else {
                                            Files.delete(p);
                                            counts[0]++;
                                        }
                                    } catch (IOException e) {
                                        // Skip files we can't delete
                                    }
                                });
                        }

                        filesDeleted = counts[0];
                        dirsDeleted = counts[1];
                    }
                } else {
                    Files.delete(path);
                    filesDeleted = 1;
                    dirsDeleted = 0;
                }

                Output output = new Output(input.path(), filesDeleted, dirsDeleted, true, null);
                return ToolResult.of(output);

            } catch (IOException e) {
                Output errorOutput = new Output(input.path(), 0, 0, false, e.getMessage());
                return ToolResult.of(errorOutput);
            }
        });
    }

    @Override
    public CompletableFuture<PermissionResult> checkPermissions(Input input, ToolUseContext context) {
        return CompletableFuture.completedFuture(
            PermissionResult.ask("Delete: " + input.path(), input)
        );
    }

    @Override
    public Input parseInput(Map<String, Object> input) {
        String path = (String) input.get("path");
        boolean recursive = input.get("recursive") != null &&
            Boolean.TRUE.equals(input.get("recursive"));
        boolean force = input.get("force") != null &&
            Boolean.TRUE.equals(input.get("force"));
        return new Input(path, recursive, force);
    }

    /**
     * Input for delete tool.
     */
    public record Input(
        String path,
        boolean recursive,
        boolean force
    ) {}

    /**
     * Output from delete tool.
     */
    public record Output(
        String path,
        int filesDeleted,
        int dirsDeleted,
        boolean success,
        String error
    ) {
        public int totalDeleted() {
            return filesDeleted + dirsDeleted;
        }
    }
}