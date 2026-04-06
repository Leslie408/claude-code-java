/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 * Java port of Claude Code tools/TaskGetTool/TaskGetTool.ts
 */
package com.anthropic.claudecode.tools;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import com.anthropic.claudecode.*;
import com.anthropic.claudecode.tools.TaskManager;

/**
 * TaskGet Tool - retrieve a task by ID.
 */
public final class TaskGetTool extends AbstractTool<TaskGetTool.Input, TaskGetTool.Output, ToolProgressData> {
    public static final String TOOL_NAME = "TaskGet";

    public TaskGetTool() {
        super(TOOL_NAME, "Retrieve a task by ID");
    }

    @Override
    public Input parseInput(Map<String, Object> input) {
        String taskId = (String) input.get("taskId");
        return new Input(taskId);
    }

    /**
     * Input schema.
     */
    public record Input(
        String taskId
    ) {}

    /**
     * Task info.
     */
    public record TaskInfo(
        String id,
        String subject,
        String description,
        String status,
        List<String> blocks,
        List<String> blockedBy
    ) {}

    /**
     * Output schema.
     */
    public record Output(
        TaskInfo task
    ) {}

    @Override
    public String description() {
        return "Retrieve a task by ID from the task list. Use this when you need the full description and context before starting work on a task.";
    }

    @Override
    public String searchHint() {
        return "retrieve a task by ID";
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
    public boolean isReadOnly(Input input) {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return TaskManager.isTodoV2Enabled();
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
    public ValidationResult validateInput(Input input) {
        if (input.taskId() == null || input.taskId().isEmpty()) {
            return ValidationResult.failure("Task ID is required", 1);
        }
        return ValidationResult.success();
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
            String taskListId = TaskManager.getTaskListId();
            TaskManager.Task task = TaskManager.getTask(taskListId, input.taskId());

            if (task == null) {
                return ToolResult.of(new Output(null));
            }

            TaskInfo taskInfo = new TaskInfo(
                task.id(),
                task.subject(),
                task.description(),
                task.status(),
                task.blocks(),
                task.blockedBy()
            );

            return ToolResult.of(new Output(taskInfo));
        });
    }

    @Override
    public String formatResult(Output output) {
        if (output.task() == null) {
            return "Task not found";
        }

        TaskInfo task = output.task();
        StringBuilder sb = new StringBuilder();
        sb.append("Task #").append(task.id()).append(": ").append(task.subject()).append("\n");
        sb.append("Status: ").append(task.status()).append("\n");
        sb.append("Description: ").append(task.description());

        if (!task.blockedBy().isEmpty()) {
            sb.append("\nBlocked by: ");
            for (int i = 0; i < task.blockedBy().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append("#").append(task.blockedBy().get(i));
            }
        }

        if (!task.blocks().isEmpty()) {
            sb.append("\nBlocks: ");
            for (int i = 0; i < task.blocks().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append("#").append(task.blocks().get(i));
            }
        }

        return sb.toString();
    }
}