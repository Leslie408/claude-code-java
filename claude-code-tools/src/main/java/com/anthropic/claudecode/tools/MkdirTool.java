/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code mkdir tool
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
 * Mkdir tool for creating directories.
 *
 * <p>Provides directory creation functionality.
 */
public class MkdirTool extends AbstractTool<MkdirTool.Input, MkdirTool.Output, ToolProgressData> {

    public MkdirTool() {
        super("mkdir", List.of("make_directory"), createSchema());
    }

    private static Map<String, Object> createSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> pathProp = new LinkedHashMap<>();
        pathProp.put("type", "string");
        pathProp.put("description", "Directory path to create");
        properties.put("path", pathProp);

        Map<String, Object> parentsProp = new LinkedHashMap<>();
        parentsProp.put("type", "boolean");
        parentsProp.put("description", "Create parent directories as needed");
        properties.put("parents", parentsProp);

        schema.put("properties", properties);
        schema.put("required", List.of("path"));

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
                Path path = Paths.get(input.path());

                if (Files.exists(path)) {
                    if (Files.isDirectory(path)) {
                        Output output = new Output(input.path(), true, false, "Directory already exists");
                        return ToolResult.of(output);
                    } else {
                        Output errorOutput = new Output(input.path(), false, false, "Path exists and is a file");
                        return ToolResult.of(errorOutput);
                    }
                }

                if (input.createParents()) {
                    Files.createDirectories(path);
                } else {
                    Files.createDirectory(path);
                }

                Output output = new Output(input.path(), true, true, null);
                return ToolResult.of(output);

            } catch (NoSuchFileException e) {
                Output errorOutput = new Output(input.path(), false, false, "Parent directory does not exist");
                return ToolResult.of(errorOutput);
            } catch (IOException e) {
                Output errorOutput = new Output(input.path(), false, false, e.getMessage());
                return ToolResult.of(errorOutput);
            }
        });
    }

    @Override
    public CompletableFuture<PermissionResult> checkPermissions(Input input, ToolUseContext context) {
        return CompletableFuture.completedFuture(
            PermissionResult.ask("Create directory: " + input.path(), input)
        );
    }

    @Override
    public Input parseInput(Map<String, Object> input) {
        String path = (String) input.get("path");
        boolean createParents = input.get("parents") != null &&
            Boolean.TRUE.equals(input.get("parents"));
        return new Input(path, createParents);
    }

    /**
     * Input for mkdir tool.
     */
    public record Input(
        String path,
        boolean createParents
    ) {}

    /**
     * Output from mkdir tool.
     */
    public record Output(
        String path,
        boolean success,
        boolean created,
        String error
    ) {}
}