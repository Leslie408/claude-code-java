/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 * Java port of Claude Code tools/TaskOutputTool/TaskOutputTool.tsx
 */
package com.anthropic.claudecode.tools;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import com.anthropic.claudecode.*;

/**
 * TaskOutput Tool - read output/logs from a background task.
 * DEPRECATED: Prefer using the Read tool on the task's output file path.
 */
public final class TaskOutputTool extends AbstractTool<TaskOutputTool.Input, TaskOutputTool.Output, ToolProgressData> {
    public static final String TOOL_NAME = "TaskOutput";
    public static final List<String> ALIASES = List.of("AgentOutputTool", "BashOutputTool");

    public TaskOutputTool() {
        super(TOOL_NAME, "Read output/logs from a background task");
    }

    @Override
    public List<String> aliases() {
        return ALIASES;
    }

    @Override
    public Input parseInput(Map<String, Object> input) {
        String taskId = (String) input.get("task_id");
        boolean block = input.get("block") != null &&
            Boolean.TRUE.equals(input.get("block"));
        int timeout = input.get("timeout") != null
            ? ((Number) input.get("timeout")).intValue()
            : 30000;
        return new Input(taskId, block, timeout);
    }

    /**
     * Input schema.
     */
    public record Input(
        String task_id,
        boolean block,
        int timeout
    ) {
        public Input {
            // Defaults
            if (timeout <= 0) timeout = 30000;
        }
    }

    /**
     * Task output data.
     */
    public record TaskOutput(
        String task_id,
        String task_type,
        String status,
        String description,
        String output,
        Integer exitCode,
        String error,
        String prompt,
        String result
    ) {}

    /**
     * Output schema.
     */
    public record Output(
        String retrieval_status,
        TaskOutput task
    ) {}

    @Override
    public String description() {
        return "[Deprecated] — prefer Read on the task output file path";
    }

    @Override
    public String searchHint() {
        return "read output/logs from a background task";
    }

    @Override
    public boolean shouldDefer() {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(Input input) {
        return isReadOnly(input);
    }

    @Override
    public boolean isReadOnly(Input input) {
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
        if (input.task_id() == null || input.task_id().isEmpty()) {
            return CompletableFuture.completedFuture(ValidationResult.failure("Task ID is required", 1));
        }

        Map<String, Object> appState = context != null && context.getAppState() != null
            ? context.getAppState().apply(null)
            : Map.of();

        @SuppressWarnings("unchecked")
        Map<String, Object> task = (Map<String, Object>) appState.get("task_" + input.task_id());
        if (task == null) {
            return CompletableFuture.completedFuture(ValidationResult.failure("No task found with ID: " + input.task_id(), 2));
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
            Map<String, Object> appState = context != null && context.getAppState() != null
                ? context.getAppState().apply(null)
                : Map.of();

            @SuppressWarnings("unchecked")
            Map<String, Object> taskMap = (Map<String, Object>) appState.get("task_" + input.task_id());
            if (taskMap == null) {
                throw new RuntimeException("No task found with ID: " + input.task_id());
            }

            if (!input.block()) {
                // Non-blocking: return current state
                String status = (String) taskMap.get("status");
                if (!"running".equals(status) && !"pending".equals(status)) {
                    markAsNotified(input.task_id(), context);
                    return ToolResult.of(new Output("success", getTaskOutputData(taskMap)));
                }
                return ToolResult.of(new Output("not_ready", getTaskOutputData(taskMap)));
            }

            // Blocking: wait for completion
            if (onProgress != null) {
                onProgress.accept(ToolProgress.of(
                    "task-output-" + input.task_id(),
                    new ToolProgressData.TaskOutputProgress(
                        input.task_id(),
                        "waiting",
                        null
                    )
                ));
            }

            Map<String, Object> completedTask = waitForTaskCompletion(
                input.task_id(),
                context,
                input.timeout()
            );

            if (completedTask == null) {
                return ToolResult.of(new Output("timeout", null));
            }

            String status = (String) completedTask.get("status");
            if ("running".equals(status) || "pending".equals(status)) {
                return ToolResult.of(new Output("timeout", getTaskOutputData(completedTask)));
            }

            markAsNotified(input.task_id(), context);
            return ToolResult.of(new Output("success", getTaskOutputData(completedTask)));
        });
    }

    @Override
    public String formatResult(Output output) {
        StringBuilder sb = new StringBuilder();
        sb.append("<retrieval_status>").append(output.retrieval_status()).append("</retrieval_status>");

        if (output.task() != null) {
            TaskOutput task = output.task();
            sb.append("\n<task_id>").append(task.task_id()).append("</task_id>");
            sb.append("\n<task_type>").append(task.task_type()).append("</task_type>");
            sb.append("\n<status>").append(task.status()).append("</status>");

            if (task.exitCode() != null) {
                sb.append("\n<exit_code>").append(task.exitCode()).append("</exit_code>");
            }

            if (task.output() != null && !task.output().isBlank()) {
                sb.append("\n<output>\n").append(task.output().trim()).append("\n</output>");
            }

            if (task.error() != null) {
                sb.append("\n<error>").append(task.error()).append("</error>");
            }
        }

        return sb.toString();
    }

    // Helpers

    private TaskOutput getTaskOutputData(Map<String, Object> taskMap) {
        String taskId = (String) taskMap.get("id");
        String type = (String) taskMap.getOrDefault("type", "unknown");
        String status = (String) taskMap.getOrDefault("status", "unknown");
        String description = (String) taskMap.getOrDefault("description", "");
        String output = (String) taskMap.getOrDefault("output", "");

        Integer exitCode = null;
        String error = null;
        String prompt = null;
        String result = null;

        if ("local_bash".equals(type)) {
            exitCode = (Integer) taskMap.get("exitCode");
            error = (String) taskMap.get("error");
        } else if ("local_agent".equals(type)) {
            prompt = (String) taskMap.get("prompt");
            result = (String) taskMap.get("result");
            error = (String) taskMap.get("error");
            output = result != null ? result : output;
        } else if ("remote_agent".equals(type)) {
            prompt = (String) taskMap.get("command");
        }

        return new TaskOutput(taskId, type, status, description, output, exitCode, error, prompt, result);
    }

    private void markAsNotified(String taskId, ToolUseContext context) {
        if (context == null || context.getAppState() == null || context.setAppState() == null) return;

        Map<String, Object> appState = context.getAppState().apply(null);
        @SuppressWarnings("unchecked")
        Map<String, Object> task = (Map<String, Object>) appState.get("task_" + taskId);
        if (task != null) {
            Map<String, Object> updated = new HashMap<>(task);
            updated.put("notified", true);
            Map<String, Object> newState = new HashMap<>(appState);
            newState.put("task_" + taskId, updated);
            context.setAppState().apply(newState);
        }
    }

    private Map<String, Object> waitForTaskCompletion(String taskId, ToolUseContext context, int timeoutMs) {
        long startTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (context == null || context.getAppState() == null) return null;

            Map<String, Object> appState = context.getAppState().apply(null);
            @SuppressWarnings("unchecked")
            Map<String, Object> task = (Map<String, Object>) appState.get("task_" + taskId);
            if (task == null) {
                return null;
            }

            String status = (String) task.get("status");
            if (!"running".equals(status) && !"pending".equals(status)) {
                return task;
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        // Timeout - return current state
        if (context == null || context.getAppState() == null) return null;
        Map<String, Object> appState = context.getAppState().apply(null);
        @SuppressWarnings("unchecked")
        Map<String, Object> task = (Map<String, Object>) appState.get("task_" + taskId);
        return task;
    }
}