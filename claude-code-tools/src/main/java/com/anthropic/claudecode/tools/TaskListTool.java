/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 * Java port of Claude Code tools/TaskListTool/TaskListTool.ts
 */
package com.anthropic.claudecode.tools;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import com.anthropic.claudecode.*;
import com.anthropic.claudecode.tools.TaskManager;
import com.anthropic.claudecode.tools.TaskManager.Task;

/**
 * TaskList Tool - List all tasks.
 */
public final class TaskListTool extends AbstractTool<TaskListTool.Input, TaskListTool.Output, ToolProgressData> {
    public static final String TOOL_NAME = "TaskList";

    public TaskListTool() {
        super(TOOL_NAME, "List all tasks");
    }

    @Override
    public Input parseInput(Map<String, Object> input) {
        return new Input();
    }

    /**
     * Input schema (empty).
     */
    public record Input() {}

    /**
     * Output schema.
     */
    public record Output(
        List<TaskInfo> tasks
    ) {}

    /**
     * Task info.
     */
    public record TaskInfo(
        String id,
        String subject,
        String status,
        String owner,
        List<String> blockedBy
    ) {}

    @Override
    public String description() {
        return "List all tasks in the current task list.";
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
    public CompletableFuture<ToolResult<Output>> call(
        Input input,
        ToolUseContext context,
        CanUseToolFn canUseTool,
        AssistantMessage parent,
        Consumer<ToolProgress<ToolProgressData>> onProgress
    ) {
        return CompletableFuture.supplyAsync(() -> {
            String taskListId = TaskManager.getTaskListId();
            List<Task> allTasks = TaskManager.getTasks(taskListId);

            // Build a set of resolved task IDs
            Set<String> resolvedTaskIds = new HashSet<>();
            for (Task task : allTasks) {
                if ("completed".equals(task.status())) {
                    resolvedTaskIds.add(task.id());
                }
            }

            // Map to output format
            List<TaskInfo> tasks = new ArrayList<>();
            for (Task task : allTasks) {
                // Skip internal tasks
                if (task.metadata() != null && task.metadata().containsKey("_internal")) {
                    continue;
                }

                // Filter blockedBy to exclude resolved tasks
                List<String> activeBlockedBy = new ArrayList<>();
                for (String blockerId : task.blockedBy()) {
                    if (!resolvedTaskIds.contains(blockerId)) {
                        activeBlockedBy.add(blockerId);
                    }
                }

                tasks.add(new TaskInfo(
                    task.id(),
                    task.subject(),
                    task.status(),
                    task.owner(),
                    activeBlockedBy
                ));
            }

            return ToolResult.of(new Output(tasks));
        });
    }

    @Override
    public String formatResult(Output output) {
        if (output.tasks().isEmpty()) {
            return "No tasks found";
        }

        List<String> lines = new ArrayList<>();
        for (TaskInfo task : output.tasks()) {
            StringBuilder sb = new StringBuilder();
            sb.append("#").append(task.id());
            sb.append(" [").append(task.status()).append("]");
            sb.append(" ").append(task.subject());

            if (task.owner() != null) {
                sb.append(" (").append(task.owner()).append(")");
            }

            if (!task.blockedBy().isEmpty()) {
                List<String> blockedByIds = new ArrayList<>();
                for (String id : task.blockedBy()) {
                    blockedByIds.add("#" + id);
                }
                sb.append(" [blocked by ").append(String.join(", ", blockedByIds)).append("]");
            }

            lines.add(sb.toString());
        }

        return String.join("\n", lines);
    }
}