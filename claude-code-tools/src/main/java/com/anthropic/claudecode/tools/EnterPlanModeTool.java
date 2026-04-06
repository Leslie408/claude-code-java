/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code EnterPlanModeTool
 */
package com.anthropic.claudecode.tools;

import com.anthropic.claudecode.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * EnterPlanModeTool - Enter planning mode for implementation tasks.
 *
 * <p>Corresponds to EnterPlanModeTool in services/tools/EnterPlanModeTool/.
 *
 * <p>Usage notes:
 * - Use proactively when about to start a non-trivial implementation task
 * - Getting user sign-off on approach before writing code prevents wasted effort
 * - Transitions into plan mode for exploring codebase and designing approach
 * - Use for: New features, Multiple valid approaches, Architectural decisions
 * - DON'T use for: Simple tasks, Single-line fixes, Pure research tasks
 */
public class EnterPlanModeTool extends AbstractTool<EnterPlanModeTool.Input, EnterPlanModeTool.Output, EnterPlanModeTool.Progress> {

    public static final String NAME = "EnterPlanMode";

    public EnterPlanModeTool() {
        super(NAME, List.of("plan", "enter_plan"), createSchema());
    }

    private static Map<String, Object> createSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        // Empty schema - this tool takes no parameters
        schema.put("properties", properties);
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
            // In real implementation, this would transition the conversation state
            // to plan mode

            return ToolResult.of(new Output(
                    true,
                    "Entered plan mode",
                    "",
                    false
            ));
        });
    }

    @Override
    public CompletableFuture<String> describe(Input input, ToolDescribeOptions options) {
        return CompletableFuture.completedFuture("Enter planning mode");
    }

    @Override
    public boolean isReadOnly(Input input) {
        return true;
    }

    @Override
    public boolean requiresUserInteraction() {
        return true; // Plan mode requires user approval
    }

    @Override
    public String getActivityDescription(Input input) {
        return "Entering plan mode";
    }

    @Override
    public Input parseInput(Map<String, Object> input) {
        return new Input();
    }

    // ==================== Input/Output/Progress ====================

    public record Input() {} // Empty input

    public record Output(
            boolean entered,
            String message,
            String error,
            boolean isError
    ) {
        public String toResultString() {
            if (isError) {
                return error;
            }
            return message;
        }
    }

    public record Progress(String status) implements ToolProgressData {}
}