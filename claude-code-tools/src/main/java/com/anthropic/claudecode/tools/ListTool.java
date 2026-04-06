/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code list tool
 */
package com.anthropic.claudecode.tools;

import com.anthropic.claudecode.*;
import com.anthropic.claudecode.permission.PermissionResult;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * List tool for listing directory contents.
 *
 * <p>Provides directory listing functionality.
 */
public class ListTool extends AbstractTool<ListTool.Input, ListTool.Output, ToolProgressData> {

    public ListTool() {
        super("list", List.of("ls", "dir"), createSchema());
    }

    private static Map<String, Object> createSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> pathProp = new LinkedHashMap<>();
        pathProp.put("type", "string");
        pathProp.put("description", "Directory path to list");
        properties.put("path", pathProp);

        Map<String, Object> allProp = new LinkedHashMap<>();
        allProp.put("type", "boolean");
        allProp.put("description", "Show hidden files");
        properties.put("all", allProp);

        Map<String, Object> longProp = new LinkedHashMap<>();
        longProp.put("type", "boolean");
        longProp.put("description", "Show detailed information");
        properties.put("long", longProp);

        schema.put("properties", properties);
        schema.put("required", List.of("path"));

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
                Path path = Paths.get(input.path());

                if (!Files.exists(path)) {
                    Output errorOutput = new Output(input.path(), Collections.emptyList(), false, "Path not found");
                    return ToolResult.of(errorOutput);
                }

                if (!Files.isDirectory(path)) {
                    Output errorOutput = new Output(input.path(), Collections.emptyList(), false, "Not a directory");
                    return ToolResult.of(errorOutput);
                }

                List<FileEntry> entries = new ArrayList<>();

                try (Stream<Path> stream = Files.list(path)) {
                    stream.forEach(p -> {
                        try {
                            boolean isHidden = p.getFileName().toString().startsWith(".");
                            if (!input.showAll() && isHidden) {
                                return;
                            }

                            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);

                            entries.add(new FileEntry(
                                p.getFileName().toString(),
                                p.toString(),
                                attrs.isDirectory(),
                                attrs.size(),
                                attrs.lastModifiedTime().toInstant(),
                                isHidden,
                                input.showLong()
                            ));
                        } catch (IOException e) {
                            // Skip files we can't read
                        }
                    });
                }

                // Sort: directories first, then by name
                entries.sort((a, b) -> {
                    if (a.isDirectory() && !b.isDirectory()) return -1;
                    if (!a.isDirectory() && b.isDirectory()) return 1;
                    return a.name().compareToIgnoreCase(b.name());
                });

                Output output = new Output(path.toString(), entries, true, null);

                return ToolResult.of(output);

            } catch (IOException e) {
                Output errorOutput = new Output(input.path(), Collections.emptyList(), false, e.getMessage());
                return ToolResult.of(errorOutput);
            }
        });
    }

    @Override
    public CompletableFuture<PermissionResult> checkPermissions(Input input, ToolUseContext context) {
        return CompletableFuture.completedFuture(PermissionResult.allow(input));
    }

    @Override
    public Input parseInput(Map<String, Object> input) {
        String path = (String) input.get("path");
        boolean showAll = input.get("all") != null &&
            Boolean.TRUE.equals(input.get("all"));
        boolean showLong = input.get("long") != null &&
            Boolean.TRUE.equals(input.get("long"));
        return new Input(path, showAll, showLong);
    }

    /**
     * Input for list tool.
     */
    public record Input(
        String path,
        boolean showAll,
        boolean showLong
    ) {}

    /**
     * Output from list tool.
     */
    public record Output(
        String path,
        List<FileEntry> entries,
        boolean success,
        String error
    ) {
        public int getCount() {
            return entries.size();
        }

        public List<FileEntry> getDirectories() {
            return entries.stream().filter(FileEntry::isDirectory).toList();
        }

        public List<FileEntry> getFiles() {
            return entries.stream().filter(e -> !e.isDirectory()).toList();
        }
    }

    /**
     * File entry.
     */
    public record FileEntry(
        String name,
        String path,
        boolean isDirectory,
        long size,
        Instant lastModified,
        boolean isHidden,
        boolean showDetails
    ) {
        public String getExtension() {
            if (isDirectory) return "";
            int dot = name.lastIndexOf('.');
            return dot > 0 ? name.substring(dot + 1) : "";
        }
    }
}