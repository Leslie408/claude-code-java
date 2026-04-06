/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 * Java port of Claude Code tools/TaskStopTool/TaskStopTool.ts
 */
package com.anthropic.claudecode.tools;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import com.anthropic.claudecode.*;

/**
 * TaskStop Tool - stop a running background task.
 */
public final class TaskStopTool extends AbstractTool<TaskStopTool.Input, TaskStopTool.Output, ToolProgressData> {
    public static final String TOOL_NAME = "TaskStop";
    public static final String LEGACY_NAME = "KillShell";

    public TaskStopTool() {
        super(TOOL_NAME, "Stop a running background task by ID");
    }

    @Override
    public List<String> aliases() {
        return List.of(LEGACY_NAME);
    }

    @Override
    public Input parseInput(Map<String, Object> input) {
        String taskId = (String) input.get("task_id");
        String shellId = (String) input.get("shell_id");
        return new Input(taskId, shellId);
    }

    /**
     * Input schema.
     */
    public record Input(
        String task_id,
        String shell_id
    ) {}

    /**
     * Output schema.
     */
    public record Output(
        String message,
        String task_id,
        String task_type,
        String command
    ) {}

    @Override
    public String description() {
        return "Stop a running background task by ID";
    }

    @Override
    public String searchHint() {
        return "kill a running background task";
    }

    @Override
    public boolean shouldDefer() {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(Input input) {
        return true;
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public Class<Output> outputType() {
        return Output.class;
    }

    @Override
    public CompletableFuture<ValidationResult> validateInput(Input input, ToolUseContext context) {
        String id = input.task_id() != null ? input.task_id() : input.shell_id();

        if (id == null || id.isEmpty()) {
            return CompletableFuture.completedFuture(ValidationResult.failure("Missing required parameter: task_id", 1));
        }

        // Check if task exists and is running
        Map<String, Object> appState = context != null && context.getAppState() != null
            ? context.getAppState().apply(null)
            : Map.of();

        @SuppressWarnings("unchecked")
        Map<String, Object> task = (Map<String, Object>) appState.get("task_" + id);
        if (task == null) {
            return CompletableFuture.completedFuture(ValidationResult.failure("No task found with ID: " + id, 1));
        }

        String status = (String) task.get("status");
        if (!"running".equals(status)) {
            return CompletableFuture.completedFuture(ValidationResult.failure("Task " + id + " is not running (status: " + status + ")", 3));
        }

        return CompletableFuture.completedFuture(ValidationResult.success());
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
            String id = input.task_id() != null ? input.task_id() : input.shell_id();

            if (id == null) {
                throw new RuntimeException("Missing required parameter: task_id");
            }

            // Stop the task
            StopResult result = stopTask(id, context);

            return ToolResult.of(new Output(
                "Successfully stopped task: " + result.taskId + " (" + result.command + ")",
                result.taskId,
                result.taskType,
                result.command
            ));
        });
    }

    @Override
    public String formatResult(Output output) {
        return output.message();
    }

    // Helper

    private static class StopResult {
        final String taskId;
        final String taskType;
        final String command;

        StopResult(String taskId, String taskType, String command) {
            this.taskId = taskId;
            this.taskType = taskType;
            this.command = command;
        }
    }

    private StopResult stopTask(String id, ToolUseContext context) {
        Map<String, Object> appState = context != null && context.getAppState() != null
            ? context.getAppState().apply(null)
            : Map.of();

        @SuppressWarnings("unchecked")
        Map<String, Object> task = (Map<String, Object>) appState.get("task_" + id);
        if (task == null) {
            throw new RuntimeException("No task found with ID: " + id);
        }

        // Update task status to killed
        Map<String, Object> updated = new HashMap<>(task);
        updated.put("status", "killed");

        if (context != null && context.setAppState() != null) {
            Map<String, Object> newState = new HashMap<>(appState);
            newState.put("task_" + id, updated);
            context.setAppState().apply(newState);
        }

        String taskType = (String) task.getOrDefault("type", "unknown");
        String command = (String) task.getOrDefault("command", task.getOrDefault("description", ""));

        return new StopResult(id, taskType, command);
    }
}