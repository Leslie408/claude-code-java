/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code FileEditTool
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
 * FileEditTool - Perform exact string replacements in files.
 *
 * <p>Corresponds to FileEditTool in tools/FileEditTool/.
 *
 * <p>IMPORTANT usage notes from system prompt:
 * - You MUST use Read tool at least once before editing
 * - Preserve exact indentation (tabs/spaces) as it appears AFTER line number prefix
 * - The line number prefix format is: line number + tab
 * - NEVER include line number prefix in old_string or new_string
 * - The edit will FAIL if old_string is not unique in the file
 * - Use replace_all to change every instance of old_string
 * - ONLY prefer editing existing files, NEVER write new files unless required
 * - Avoid emojis unless user explicitly requests
 */
public class FileEditTool extends AbstractTool<FileEditTool.Input, FileEditTool.Output, FileEditTool.Progress> {

    public static final String NAME = "Edit";

    public FileEditTool() {
        super(NAME, List.of("edit", "file_edit", "sed"), createSchema());
    }

    private static Map<String, Object> createSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> fileProp = new LinkedHashMap<>();
        fileProp.put("type", "string");
        fileProp.put("description", "The absolute path to the file to modify");
        properties.put("file_path", fileProp);

        Map<String, Object> oldProp = new LinkedHashMap<>();
        oldProp.put("type", "string");
        oldProp.put("description", "The text to replace (must match exactly)");
        properties.put("old_string", oldProp);

        Map<String, Object> newProp = new LinkedHashMap<>();
        newProp.put("type", "string");
        newProp.put("description", "The text to replace with");
        properties.put("new_string", newProp);

        Map<String, Object> replaceAllProp = new LinkedHashMap<>();
        replaceAllProp.put("type", "boolean");
        replaceAllProp.put("description", "Replace all occurrences of old_string (default false)");
        properties.put("replace_all", replaceAllProp);

        schema.put("properties", properties);
        schema.put("required", List.of("file_path", "old_string", "new_string"));
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
                            0,
                            0,
                            true
                    ));
                }

                // Read current content
                String content = Files.readString(path, StandardCharsets.UTF_8);
                String oldString = input.oldString();
                String newString = input.newString();

                // Check if old_string exists in content
                if (!content.contains(oldString)) {
                    return ToolResult.of(new Output(
                            "",
                            "old_string not found in file. Make sure it matches exactly, including whitespace.",
                            0,
                            0,
                            true
                    ));
                }

                // Check uniqueness if not replace_all
                if (!input.replaceAll()) {
                    int firstIndex = content.indexOf(oldString);
                    int lastIndex = content.lastIndexOf(oldString);
                    if (firstIndex != lastIndex) {
                        return ToolResult.of(new Output(
                                "",
                                "old_string appears " + countOccurrences(content, oldString) +
                                " times in file. Use replace_all=true to replace all instances, " +
                                "or provide a larger string with more context to make it unique.",
                                0,
                                0,
                                true
                        ));
                    }
                }

                // Perform replacement
                String newContent;
                int replacements;
                if (input.replaceAll()) {
                    replacements = countOccurrences(content, oldString);
                    newContent = content.replace(oldString, newString);
                } else {
                    replacements = 1;
                    newContent = content.replaceFirst(escapeRegex(oldString), newString);
                }

                // Write back
                Files.writeString(path, newContent, StandardCharsets.UTF_8);

                return ToolResult.of(new Output(
                        "Successfully replaced " + replacements + " occurrence(s) in " + input.filePath(),
                        "",
                        replacements,
                        Files.size(path),
                        false
                ));

            } catch (IOException e) {
                return ToolResult.of(new Output(
                        "",
                        "Error editing file: " + e.getMessage(),
                        0,
                        0,
                        true
                ));
            }
        });
    }

    private int countOccurrences(String content, String search) {
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(search, index)) != -1) {
            count++;
            index += search.length();
        }
        return count;
    }

    private String escapeRegex(String str) {
        // Escape special regex characters for literal replacement
        return str.replace("\\", "\\\\")
                  .replace("*", "\\*")
                  .replace("+", "\\+")
                  .replace("?", "\\?")
                  .replace(".", "\\.")
                  .replace("{", "\\{")
                  .replace("}", "\\}")
                  .replace("[", "\\[")
                  .replace("]", "\\]")
                  .replace("|", "\\|")
                  .replace("^", "\\^")
                  .replace("$", "\\$")
                  .replace("(", "\\(")
                  .replace(")", "\\)");
    }

    @Override
    public CompletableFuture<String> describe(Input input, ToolDescribeOptions options) {
        String filename = Paths.get(input.filePath()).getFileName().toString();
        return CompletableFuture.completedFuture("Edit " + filename);
    }

    @Override
    public boolean isReadOnly(Input input) {
        return false;
    }

    @Override
    public boolean isDestructive(Input input) {
        return true; // Editing modifies files
    }

    @Override
    public boolean isConcurrencySafe(Input input) {
        return false; // Editing should not run concurrently
    }

    @Override
    public String getActivityDescription(Input input) {
        String filename = Paths.get(input.filePath()).getFileName().toString();
        return "Editing " + filename;
    }

    @Override
    public String getToolUseSummary(Input input) {
        int oldLen = input.oldString().length();
        int newLen = input.newString().length();
        String suffix = input.replaceAll() ? " (all)" : "";
        return "Replace " + oldLen + " chars with " + newLen + " chars" + suffix;
    }

    @Override
    public Input parseInput(Map<String, Object> input) {
        String filePath = (String) input.get("file_path");
        String oldString = (String) input.get("old_string");
        String newString = (String) input.get("new_string");
        boolean replaceAll = input.get("replace_all") != null &&
            Boolean.TRUE.equals(input.get("replace_all"));
        return new Input(filePath, oldString, newString, replaceAll);
    }

    // ==================== Input/Output/Progress ====================

    public record Input(
            String filePath,
            String oldString,
            String newString,
            boolean replaceAll
    ) {}

    public record Output(
            String message,
            String error,
            int replacements,
            long fileSize,
            boolean isError
    ) {
        public String toResultString() {
            if (isError) {
                return error;
            }
            return message;
        }
    }

    public record Progress(int replacementsDone) implements ToolProgressData {}
}