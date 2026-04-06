/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 * Java port of Claude Code tools/TodoWriteTool/TodoWriteTool.ts
 */
package com.anthropic.claudecode.tools;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import com.anthropic.claudecode.*;
import com.anthropic.claudecode.permission.PermissionResult;

/**
 * TodoWrite Tool - manage the session task checklist.
 */
public final class TodoWriteTool extends AbstractTool<TodoWriteTool.Input, TodoWriteTool.Output, ToolProgressData> {
    public static final String TOOL_NAME = "TodoWrite";

    public TodoWriteTool() {
        super(TOOL_NAME, "Manage the session task checklist");
    }

    /**
     * Todo item.
     */
    public record TodoItem(
        String content,
        String status
    ) {}

    @Override
    @SuppressWarnings("unchecked")
    public Input parseInput(Map<String, Object> input) {
        List<Map<String, Object>> todosRaw = (List<Map<String, Object>>) input.get("todos");
        List<TodoItem> todos = new ArrayList<>();
        if (todosRaw != null) {
            for (Map<String, Object> item : todosRaw) {
                String content = (String) item.get("content");
                String status = (String) item.get("status");
                todos.add(new TodoItem(content, status));
            }
        }
        return new Input(todos);
    }

    /**
     * Input schema.
     */
    public record Input(
        List<TodoItem> todos
    ) {}

    /**
     * Output schema.
     */
    public record Output(
        List<TodoItem> oldTodos,
        List<TodoItem> newTodos,
        Boolean verificationNudgeNeeded
    ) {}

    @Override
    public String description() {
        return "Manage the session task checklist. Use this to track progress and organize work for complex tasks.";
    }

    @Override
    public String searchHint() {
        return "manage the session task checklist";
    }

    @Override
    public boolean shouldDefer() {
        return true;
    }

    @Override
    public boolean strict() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        // Disabled when TodoV2 is enabled
        return !isTodoV2Enabled();
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
    public CompletableFuture<PermissionResult> checkPermissions(Input input) {
        // No permission checks required for todo operations
        return CompletableFuture.completedFuture(PermissionResult.allow(input));
    }

    @Override
    public CompletableFuture<ValidationResult> validateInput(Input input, ToolUseContext context) {
        if (input.todos() == null) {
            return CompletableFuture.completedFuture(ValidationResult.failure("todos is required", 1));
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
            String todoKey = getSessionId(); // Use session ID as key

            // Get app state if available
            Map<String, Object> appState = context != null && context.getAppState() != null
                ? context.getAppState().apply(null)
                : Map.of();

            // Get old todos from app state
            @SuppressWarnings("unchecked")
            List<TodoItem> oldTodos = (List<TodoItem>) appState.getOrDefault("todos_" + todoKey, Collections.emptyList());

            boolean allDone = input.todos().stream()
                .allMatch(t -> "completed".equals(t.status()));

            List<TodoItem> newTodos = allDone ? Collections.emptyList() : input.todos();

            // Structural nudge: if the main-thread agent is closing out a 3+ item
            // list and none of those items was a verification step, flag it
            boolean verificationNudgeNeeded = false;
            if (allDone && input.todos().size() >= 3) {
                boolean hasVerification = input.todos().stream()
                    .anyMatch(t -> t.content() != null && t.content().toLowerCase().contains("verif"));
                if (!hasVerification) {
                    verificationNudgeNeeded = true;
                }
            }

            // Update app state with new todos
            if (context != null && context.setAppState() != null) {
                Map<String, Object> newState = new HashMap<>(appState);
                newState.put("todos_" + todoKey, newTodos);
                context.setAppState().apply(newState);
            }

            return ToolResult.of(new Output(
                oldTodos,
                input.todos(),
                verificationNudgeNeeded
            ));
        });
    }

    @Override
    public String formatResult(Output output) {
        StringBuilder sb = new StringBuilder();
        sb.append("Todos have been modified successfully. ");
        sb.append("Ensure that you continue to use the todo list to track your progress. ");
        sb.append("Please proceed with the current tasks as applicable");

        if (Boolean.TRUE.equals(output.verificationNudgeNeeded())) {
            sb.append("\n\nNOTE: You just closed out 3+ tasks and none of them was a verification step. ");
            sb.append("Before writing your final summary, spawn the verification agent. ");
            sb.append("You cannot self-assign PARTIAL by listing caveats in your summary — only the verifier issues a verdict.");
        }

        return sb.toString();
    }

    // Helpers

    private boolean isTodoV2Enabled() {
        String env = System.getenv("CLAUDE_CODE_TODO_V2");
        return "true".equalsIgnoreCase(env) || "1".equals(env);
    }

    private String getSessionId() {
        return UUID.randomUUID().toString();
    }
}