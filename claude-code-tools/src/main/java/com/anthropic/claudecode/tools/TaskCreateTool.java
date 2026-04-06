/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 * Java port of Claude Code tools/TaskCreateTool/TaskCreateTool.ts
 */
package com.anthropic.claudecode.tools;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import com.anthropic.claudecode.*;
import com.anthropic.claudecode.tools.TaskManager;

/**
 * TaskCreate Tool - Create tasks in the task list.
 */
public final class TaskCreateTool extends AbstractTool<TaskCreateTool.Input, TaskCreateTool.Output, ToolProgressData> {
    public static final String TOOL_NAME = "TaskCreate";

    public TaskCreateTool() {
        super(TOOL_NAME, "Create a task in the task list");
    }

    @Override
    @SuppressWarnings("unchecked")
    public Input parseInput(Map<String, Object> input) {
        String subject = (String) input.get("subject");
        String description = (String) input.get("description");
        String activeForm = (String) input.get("activeForm");
        Map<String, Object> metadata = (Map<String, Object>) input.get("metadata");
        return new Input(subject, description, activeForm, metadata);
    }

    /**
     * Input schema.
     */
    public record Input(
        String subject,
        String description,
        String activeForm,
        Map<String, Object> metadata
    ) {}

    /**
     * Output schema.
     */
    public record Output(
        TaskInfo task,
        String error
    ) {
        public static Output success(TaskInfo task) {
            return new Output(task, null);
        }
        public static Output error(String error) {
            return new Output(null, error);
        }
    }

    /**
     * Task info record.
     */
    public record TaskInfo(
        String id,
        String subject
    ) {}

    @Override
    public String description() {
        return """
            Use this tool to create a structured task list for your current coding session.
            This helps you track progress, organize complex tasks, and demonstrate thoroughness to the user.

            Create tasks with clear, specific subjects that describe the outcome.""";
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
            // Create the task
            String taskId = TaskManager.createTask(
                TaskManager.getTaskListId(),
                new TaskManager.Task(
                    null,
                    input.subject(),
                    input.description(),
                    input.activeForm(),
                    "pending",
                    null,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    input.metadata()
                )
            );

            // Execute task created hooks
            List<String> blockingErrors = new ArrayList<>();
            for (TaskManager.HookResult result : TaskManager.executeTaskCreatedHooks(
                taskId, input.subject(), input.description()
            )) {
                if (result.blockingError() != null) {
                    blockingErrors.add(result.blockingError());
                }
            }

            if (!blockingErrors.isEmpty()) {
                TaskManager.deleteTask(TaskManager.getTaskListId(), taskId);
                return ToolResult.of(Output.error(String.join("\n", blockingErrors)));
            }

            // Auto-expand task list
            if (context != null && context.setAppState() != null) {
                context.setAppState().apply(Map.of("expandedView", "tasks"));
            }

            return ToolResult.of(Output.success(
                new TaskInfo(taskId, input.subject())
            ));
        });
    }

    @Override
    public String formatResult(Output output) {
        if (output.error() != null) {
            return "Error: " + output.error();
        }
        return "Task #" + output.task().id() + " created successfully: " + output.task().subject();
    }
}