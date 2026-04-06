/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code copy tool
 */
package com.anthropic.claudecode.tools;

import com.anthropic.claudecode.*;
import com.anthropic.claudecode.permission.PermissionResult;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Copy tool for copying files and directories.
 *
 * <p>Provides file copying functionality.
 */
public class CopyTool extends AbstractTool<CopyTool.Input, CopyTool.Output, ToolProgressData> {

    public CopyTool() {
        super("copy", List.of("cp"), createSchema());
    }

    private static Map<String, Object> createSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> sourceProp = new LinkedHashMap<>();
        sourceProp.put("type", "string");
        sourceProp.put("description", "Source path");
        properties.put("source", sourceProp);

        Map<String, Object> destProp = new LinkedHashMap<>();
        destProp.put("type", "string");
        destProp.put("description", "Destination path");
        properties.put("destination", destProp);

        schema.put("properties", properties);
        schema.put("required", List.of("source", "destination"));

        return schema;
    }

    @Override
    public boolean isReadOnly(Input input) {
        return false;
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
                Path source = Paths.get(input.source());
                Path destination = Paths.get(input.destination());

                // Check source exists
                if (!Files.exists(source)) {
                    Output errorOutput = new Output(source.toString(), destination.toString(), false, 0, 0, false, "Source not found");
                    return ToolResult.of(errorOutput);
                }

                // Check destination
                if (Files.exists(destination) && !input.overwrite()) {
                    Output errorOutput = new Output(source.toString(), destination.toString(), false, 0, 0, false, "Destination exists");
                    return ToolResult.of(errorOutput);
                }

                // Create parent directories
                Path parentDir = destination.getParent();
                if (parentDir != null && !Files.exists(parentDir)) {
                    Files.createDirectories(parentDir);
                }

                long bytesCopied;
                int filesCopied;

                if (Files.isDirectory(source)) {
                    // Copy directory
                    if (!input.recursive()) {
                        Output errorOutput = new Output(source.toString(), destination.toString(), false, 0, 0, false, "Source is directory, need recursive=true");
                        return ToolResult.of(errorOutput);
                    }

                    final int[] count = {0};
                    final long[] bytes = {0};

                    Files.walk(source).forEach(path -> {
                        try {
                            Path relative = source.relativize(path);
                            Path target = destination.resolve(relative);

                            if (Files.isDirectory(path)) {
                                Files.createDirectories(target);
                            } else {
                                Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                                count[0]++;
                                bytes[0] += Files.size(path);
                            }
                        } catch (IOException e) {
                            // Ignore errors
                        }
                    });

                    filesCopied = count[0];
                    bytesCopied = bytes[0];
                } else {
                    // Copy file
                    Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                    bytesCopied = Files.size(destination);
                    filesCopied = 1;
                }

                Output output = new Output(
                    source.toString(),
                    destination.toString(),
                    true,
                    filesCopied,
                    bytesCopied,
                    Files.isDirectory(source),
                    null
                );

                return ToolResult.of(output);

            } catch (IOException e) {
                Output errorOutput = new Output(input.source(), input.destination(), false, 0, 0, false, e.getMessage());
                return ToolResult.of(errorOutput);
            }
        });
    }

    @Override
    public CompletableFuture<PermissionResult> checkPermissions(Input input, ToolUseContext context) {
        return CompletableFuture.completedFuture(
            PermissionResult.ask("Copy file: " + input.source() + " -> " + input.destination(), input)
        );
    }

    @Override
    public Input parseInput(Map<String, Object> input) {
        String source = (String) input.get("source");
        String destination = (String) input.get("destination");
        boolean overwrite = input.get("overwrite") != null &&
            Boolean.TRUE.equals(input.get("overwrite"));
        boolean recursive = input.get("recursive") != null &&
            Boolean.TRUE.equals(input.get("recursive"));
        return new Input(source, destination, overwrite, recursive);
    }

    /**
     * Input for copy tool.
     */
    public record Input(
        String source,
        String destination,
        boolean overwrite,
        boolean recursive
    ) {}

    /**
     * Output from copy tool.
     */
    public record Output(
        String source,
        String destination,
        boolean success,
        int filesCopied,
        long bytesCopied,
        boolean isDirectory,
        String error
    ) {}
}