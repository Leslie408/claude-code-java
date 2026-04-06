/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code BashOutput tool
 */
package com.anthropic.claudecode.tools;

import com.anthropic.claudecode.*;
import com.anthropic.claudecode.message.*;
import com.anthropic.claudecode.utils.schema.SchemaUtils;
import com.anthropic.claudecode.state.BackgroundProcessRegistry;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Bash output tool for reading background command output.
 */
public class BashOutputTool extends AbstractTool<BashOutputTool.Input, Object, ToolProgressData> {

    public BashOutputTool() {
        super("BashOutput", List.of("bash_output"), createSchema());
    }

    private static Map<String, Object> createSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("bashId", SchemaUtils.stringSchema());
        return SchemaUtils.objectSchema(properties, List.of("bashId"));
    }

    @Override
    public String description() {
        return "Get output from a background bash command";
    }

    @Override
    public CompletableFuture<ToolResult<Object>> call(
            Input input,
            ToolUseContext context,
            CanUseToolFn canUseTool,
            AssistantMessage parentMessage,
            Consumer<ToolProgress<ToolProgressData>> onProgress) {

        return CompletableFuture.supplyAsync(() -> {
            // Look up the background process from registry
            BackgroundProcessRegistry.ProcessInfo processInfo = BackgroundProcessRegistry.get(input.bashId());

            if (processInfo == null) {
                return ToolResult.of(Map.of(
                        "output", "",
                        "status", "not_found",
                        "message", "Background command not found: " + input.bashId()
                ));
            }

            // Read output from the process
            StringBuilder output = new StringBuilder();
            Process process = processInfo.process();

            if (process != null && process.isAlive()) {
                // Process still running - read available output
                try {
                    java.io.InputStream inputStream = process.getInputStream();
                    if (inputStream.available() > 0) {
                        byte[] buffer = new byte[Math.min(inputStream.available(), 8192)];
                        int read = inputStream.read(buffer);
                        if (read > 0) {
                            output.append(new String(buffer, 0, read));
                        }
                    }
                } catch (Exception e) {
                    output.append("Error reading output: ").append(e.getMessage());
                }

                return ToolResult.of(Map.of(
                        "output", output.toString(),
                        "status", "running",
                        "pid", process.pid(),
                        "message", "Process is still running"
                ));
            } else if (process != null) {
                // Process completed
                try {
                    // Read remaining output
                    java.io.InputStream inputStream = process.getInputStream();
                    byte[] buffer = inputStream.readAllBytes();
                    output.append(new String(buffer));

                    java.io.InputStream errorStream = process.getErrorStream();
                    byte[] errorBuffer = errorStream.readAllBytes();
                    String errorOutput = new String(errorBuffer);

                    int exitCode = process.exitValue();

                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("output", output.toString());
                    result.put("error", errorOutput);
                    result.put("status", "completed");
                    result.put("exitCode", exitCode);
                    result.put("message", "Process completed with exit code " + exitCode);

                    // Remove from registry after completion
                    BackgroundProcessRegistry.remove(input.bashId());

                    return ToolResult.of(result);
                } catch (Exception e) {
                    return ToolResult.of(Map.of(
                            "output", "",
                            "status", "error",
                            "message", "Error reading final output: " + e.getMessage()
                    ));
                }
            } else {
                // Process info exists but process is null (stored output)
                return ToolResult.of(Map.of(
                        "output", processInfo.storedOutput() != null ? processInfo.storedOutput() : "",
                        "status", "completed",
                        "message", "Process output retrieved from storage"
                ));
            }
        });
    }

    @Override
    public CompletableFuture<String> describe(Input input, ToolDescribeOptions options) {
        return CompletableFuture.completedFuture("BashOutput: " + input.bashId());
    }

    @Override
    public boolean isReadOnly(Input input) {
        return true;
    }

    @Override
    public Input parseInput(Map<String, Object> input) {
        String bashId = (String) input.get("bashId");
        return new Input(bashId);
    }

    public record Input(String bashId) {}
}