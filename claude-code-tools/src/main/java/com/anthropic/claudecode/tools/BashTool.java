/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code BashTool
 */
package com.anthropic.claudecode.tools;

import com.anthropic.claudecode.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * BashTool - Execute shell commands.
 *
 * <p>Corresponds to BashTool in tools/BashTool/BashTool.ts.
 *
 * <p>Usage notes from system prompt:
 * - ALWAYS avoid using Bash to run find, grep, cat, head, tail, sed, awk
 * - Use dedicated tools (Glob, Grep, Read) instead
 * - Only use Bash for system commands that require shell execution
 * - Keep descriptions brief (5-10 words)
 * - Quote paths with spaces
 * - Don't use 'cd', use absolute paths
 */
public class BashTool extends AbstractTool<BashTool.Input, BashTool.Output, BashTool.Progress> {

    public static final String NAME = "Bash";

    public BashTool() {
        super(NAME, List.of("bash", "shell"), createSchema());
    }

    private static Map<String, Object> createSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> commandProp = new LinkedHashMap<>();
        commandProp.put("type", "string");
        commandProp.put("description", "The command to execute");
        properties.put("command", commandProp);

        Map<String, Object> timeoutProp = new LinkedHashMap<>();
        timeoutProp.put("type", "number");
        timeoutProp.put("description", "Optional timeout in milliseconds (max 600000, default 120000)");
        properties.put("timeout", timeoutProp);

        Map<String, Object> descriptionProp = new LinkedHashMap<>();
        descriptionProp.put("type", "string");
        descriptionProp.put("description", "Clear, concise description of what this command does");
        properties.put("description", descriptionProp);

        Map<String, Object> backgroundProp = new LinkedHashMap<>();
        backgroundProp.put("type", "boolean");
        backgroundProp.put("description", "Set to true to run in background and be notified when complete");
        properties.put("run_in_background", backgroundProp);

        schema.put("properties", properties);
        schema.put("required", List.of("command"));
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
                long timeoutMs = input.timeout() != null ? input.timeout() : 120000;

                ProcessBuilder pb = new ProcessBuilder();
                if (isWindows()) {
                    pb.command("cmd.exe", "/c", input.command());
                } else {
                    pb.command("sh", "-c", input.command());
                }

                if (input.cwd() != null) {
                    pb.directory(new java.io.File(input.cwd()));
                }

                pb.redirectErrorStream(true);

                Process process = pb.start();

                // Handle background execution
                if (input.runInBackground()) {
                    String processId = com.anthropic.claudecode.state.BackgroundProcessRegistry.register(
                        process, input.command(), input.cwd() != null ? input.cwd() : System.getProperty("user.dir")
                    );
                    return ToolResult.of(new Output(
                        "Background process started with ID: " + processId,
                        "",
                        0,
                        false
                    ));
                }

                StringBuilder output = new StringBuilder();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    if (onProgress != null) {
                        onProgress.accept(new ToolProgress<>(context.toolUseId(), new Progress(output.toString())));
                    }
                }

                boolean completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
                if (!completed) {
                    process.destroyForcibly();
                    return ToolResult.of(new Output(
                            "",
                            "Command timed out after " + timeoutMs + "ms",
                            -1,
                            true
                    ));
                }

                int exitCode = process.exitValue();
                String stdout = output.toString();

                return ToolResult.of(new Output(
                        stdout,
                        "",
                        exitCode,
                        exitCode != 0
                ));

            } catch (Exception e) {
                return ToolResult.of(new Output(
                        "",
                        e.getMessage(),
                        -1,
                        true
                ));
            }
        });
    }

    @Override
    public CompletableFuture<String> describe(Input input, ToolDescribeOptions options) {
        String desc = input.description() != null ? input.description() : input.command();
        return CompletableFuture.completedFuture(desc);
    }

    @Override
    public String userFacingName(Input input) {
        return "bash";
    }

    @Override
    public boolean isReadOnly(Input input) {
        // Bash commands can be destructive, but we let the permission system handle this
        return false;
    }

    @Override
    public boolean isDestructive(Input input) {
        String cmd = input.command().toLowerCase();
        // Detect potentially destructive commands
        return cmd.contains("rm") || cmd.contains("delete") ||
               cmd.contains("format") || cmd.contains("drop") ||
               cmd.contains("truncate") || cmd.contains("wipe");
    }

    @Override
    public boolean isConcurrencySafe(Input input) {
        return false; // Shell commands should not run concurrently by default
    }

    @Override
    public SearchOrReadCommand isSearchOrReadCommand(Input input) {
        String cmd = input.command();
        boolean isSearch = cmd.contains("grep") || cmd.contains("find") || cmd.contains("search");
        boolean isList = cmd.contains("ls") || cmd.contains("dir") || cmd.contains("list");
        return new SearchOrReadCommand(isSearch, false, isList);
    }

    @Override
    public String getActivityDescription(Input input) {
        String desc = input.description();
        if (desc != null && !desc.isEmpty()) {
            return desc.length() > 50 ? desc.substring(0, 50) + "..." : desc;
        }
        return "Running shell command";
    }

    @Override
    public Input parseInput(Map<String, Object> input) {
        String command = (String) input.get("command");
        Long timeout = input.get("timeout") != null ? ((Number) input.get("timeout")).longValue() : null;
        String description = (String) input.get("description");
        String cwd = (String) input.get("cwd");
        boolean runInBackground = input.get("run_in_background") != null &&
            Boolean.TRUE.equals(input.get("run_in_background"));
        return new Input(command, timeout, description, cwd, runInBackground);
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    // ==================== Input/Output/Progress ====================

    public record Input(
            String command,
            Long timeout,
            String description,
            String cwd,
            boolean runInBackground
    ) {}

    public record Output(
            String stdout,
            String stderr,
            int exitCode,
            boolean isError
    ) {
        public String toResultString() {
            if (isError) {
                return "Error: " + stderr + "\nExit code: " + exitCode;
            }
            return stdout;
        }
    }

    public record Progress(String partialOutput) implements ToolProgressData {}
}