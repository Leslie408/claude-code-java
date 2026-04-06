/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 * Java port of Claude Code tools/TaskUpdateTool/TaskUpdateTool.ts
 */
package com.anthropic.claudecode.tools;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import com.anthropic.claudecode.*;
import com.anthropic.claudecode.tools.TaskManager;
import com.anthropic.claudecode.tools.TaskManager.Task;

/**
 * TaskUpdate Tool - Update a task.
 */
public final class TaskUpdateTool extends AbstractTool<TaskUpdateTool.Input, TaskUpdateTool.Output, ToolProgressData> {
    public static final String TOOL_NAME = "TaskUpdate";

    public TaskUpdateTool() {
        super(TOOL_NAME, "Update a task");
    }

    @Override
    @SuppressWarnings("unchecked")
    public Input parseInput(Map<String, Object> input) {
        String taskId = (String) input.get("taskId");
        String subject = (String) input.get("subject");
        String description = (String) input.get("description");
        String activeForm = (String) input.get("activeForm");
        String status = (String) input.get("status");
        List<String> addBlocks = (List<String>) input.get("addBlocks");
        List<String> addBlockedBy = (List<String>) input.get("addBlockedBy");
        String owner = (String) input.get("owner");
        Map<String, Object> metadata = (Map<String, Object>) input.get("metadata");
        return new Input(taskId, subject, description, activeForm, status, addBlocks, addBlockedBy, owner, metadata);
    }

    /**
     * Input schema.
     */
    public record Input(
        String taskId,
        String subject,
        String description,
        String activeForm,
        String status,
        List<String> addBlocks,
        List<String> addBlockedBy,
        String owner,
        Map<String, Object> metadata
    ) {}

    /**
     * Output schema.
     */
    public record Output(
        boolean success,
        String taskId,
        List<String> updatedFields,
        String error,
        StatusChange statusChange,
        Boolean verificationNudgeNeeded
    ) {}

    /**
     * Status change.
     */
    public record StatusChange(String from, String to) {}

    @Override
    public String description() {
        return """
            Update a task in the task list.

            Can update:
            - subject: New title
            - description: New description
            - status: pending, in_progress, completed, or deleted
            - owner: Assign the task
            - addBlocks: Task IDs this task blocks
            - addBlockedBy: Task IDs that block this task
            - metadata: Arbitrary metadata to merge""";
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
            String taskListId = TaskManager.getTaskListId();

            // Auto-expand task list
            if (context != null && context.setAppState() != null) {
                context.setAppState().apply(Map.of("expandedView", "tasks"));
            }

            // Check if task exists
            Task existingTask = TaskManager.getTask(taskListId, input.taskId());
            if (existingTask == null) {
                return ToolResult.of(new Output(
                    false,
                    input.taskId(),
                    Collections.emptyList(),
                    "Task not found",
                    null,
                    null
                ));
            }

            List<String> updatedFields = new ArrayList<>();

            // Handle deletion
            if ("deleted".equals(input.status())) {
                boolean deleted = TaskManager.deleteTask(taskListId, input.taskId());
                return ToolResult.of(new Output(
                    deleted,
                    input.taskId(),
                    deleted ? List.of("deleted") : Collections.emptyList(),
                    deleted ? null : "Failed to delete task",
                    deleted ? new StatusChange(existingTask.status(), "deleted") : null,
                    null
                ));
            }

            // Update fields
            if (input.subject() != null && !input.subject().equals(existingTask.subject())) {
                updatedFields.add("subject");
            }
            if (input.description() != null && !input.description().equals(existingTask.description())) {
                updatedFields.add("description");
            }
            if (input.activeForm() != null && !input.activeForm().equals(existingTask.activeForm())) {
                updatedFields.add("activeForm");
            }
            if (input.owner() != null && !input.owner().equals(existingTask.owner())) {
                updatedFields.add("owner");
            }
            if (input.status() != null && !input.status().equals(existingTask.status())) {
                updatedFields.add("status");

                // Update status
                TaskManager.updateTaskStatus(taskListId, input.taskId(), input.status());
            }

            // Add blocks
            if (input.addBlocks() != null && !input.addBlocks().isEmpty()) {
                for (String blockId : input.addBlocks()) {
                    if (!existingTask.blocks().contains(blockId)) {
                        TaskManager.addBlock(taskListId, input.taskId(), blockId);
                    }
                }
                updatedFields.add("blocks");
            }

            // Add blockedBy
            if (input.addBlockedBy() != null && !input.addBlockedBy().isEmpty()) {
                for (String blockerId : input.addBlockedBy()) {
                    if (!existingTask.blockedBy().contains(blockerId)) {
                        TaskManager.addBlock(taskListId, blockerId, input.taskId());
                    }
                }
                updatedFields.add("blockedBy");
            }

            StatusChange statusChange = null;
            if (input.status() != null && !input.status().equals(existingTask.status())) {
                statusChange = new StatusChange(existingTask.status(), input.status());
            }

            return ToolResult.of(new Output(
                true,
                input.taskId(),
                updatedFields,
                null,
                statusChange,
                false
            ));
        });
    }

    @Override
    public String formatResult(Output output) {
        if (!output.success()) {
            return output.error() != null ? output.error() : "Task #" + output.taskId() + " not found";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Updated task #").append(output.taskId());
        sb.append(" ").append(String.join(", ", output.updatedFields()));

        // Add reminder for completed tasks
        if (output.statusChange() != null && "completed".equals(output.statusChange().to())) {
            sb.append("\n\nTask completed. Call TaskList now to find your next available task.");
        }

        return sb.toString();
    }
}