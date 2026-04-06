/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code move tool
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
 * Move tool for moving files and directories.
 *
 * <p>Provides file moving functionality.
 */
public class MoveTool extends AbstractTool<MoveTool.Input, MoveTool.Output, ToolProgressData> {

    public MoveTool() {
        super("move", List.of("mv"), createSchema());
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
                Path source = Paths.get(input.source());
                Path destination = Paths.get(input.destination());

                // Check source exists
                if (!Files.exists(source)) {
                    Output errorOutput = new Output(source.toString(), destination.toString(), false, false, "Source not found");
                    return ToolResult.of(errorOutput);
                }

                // Check destination
                if (Files.exists(destination) && !input.overwrite()) {
                    Output errorOutput = new Output(source.toString(), destination.toString(), false, false, "Destination exists");
                    return ToolResult.of(errorOutput);
                }

                // Create parent directories
                Path parentDir = destination.getParent();
                if (parentDir != null && !Files.exists(parentDir)) {
                    Files.createDirectories(parentDir);
                }

                // Move file/directory
                if (input.overwrite() && Files.exists(destination)) {
                    Files.delete(destination);
                }

                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);

                Output output = new Output(
                    source.toString(),
                    destination.toString(),
                    true,
                    Files.isDirectory(destination),
                    null
                );

                return ToolResult.of(output);

            } catch (IOException e) {
                Output errorOutput = new Output(input.source(), input.destination(), false, false, e.getMessage());
                return ToolResult.of(errorOutput);
            }
        });
    }

    @Override
    public CompletableFuture<PermissionResult> checkPermissions(Input input, ToolUseContext context) {
        return CompletableFuture.completedFuture(
            PermissionResult.ask("Move file: " + input.source() + " -> " + input.destination(), input)
        );
    }

    @Override
    public Input parseInput(Map<String, Object> input) {
        String source = (String) input.get("source");
        String destination = (String) input.get("destination");
        boolean overwrite = input.get("overwrite") != null &&
            Boolean.TRUE.equals(input.get("overwrite"));
        return new Input(source, destination, overwrite);
    }

    /**
     * Input for move tool.
     */
    public record Input(
        String source,
        String destination,
        boolean overwrite
    ) {}

    /**
     * Output from move tool.
     */
    public record Output(
        String source,
        String destination,
        boolean success,
        boolean isDirectory,
        String error
    ) {}
}