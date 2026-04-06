/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code ExitPlanModeTool
 */
package com.anthropic.claudecode.tools;

import com.anthropic.claudecode.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * ExitPlanModeTool - Exit plan mode with approval.
 *
 * <p>This tool is used to exit plan mode after getting user approval
 * for a generated plan.
 */
public class ExitPlanModeTool extends AbstractTool<ExitPlanModeTool.Input, ExitPlanModeTool.Output, ExitPlanModeTool.Progress> {

    public static final String NAME = "ExitPlanMode";

    private PlanApprovalHandler approvalHandler;

    public ExitPlanModeTool() {
        super(NAME, List.of("plan", "exit", "approve"), createSchema());
    }

    public void setApprovalHandler(PlanApprovalHandler handler) {
        this.approvalHandler = handler;
    }

    private static Map<String, Object> createSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> planFileProp = new LinkedHashMap<>();
        planFileProp.put("type", "string");
        planFileProp.put("description", "Path to the plan file");
        properties.put("planFile", planFileProp);

        Map<String, Object> planContentProp = new LinkedHashMap<>();
        planContentProp.put("type", "string");
        planContentProp.put("description", "Content of the plan");
        properties.put("planContent", planContentProp);

        schema.put("properties", properties);
        schema.put("required", List.of("planFile", "planContent"));
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
            if (approvalHandler != null) {
                try {
                    boolean approved = approvalHandler
                        .requestApproval(input.planFile(), input.planContent())
                        .get();

                    return ToolResult.of(new Output(
                        approved,
                        approved ? "Plan approved" : "Plan rejected",
                        false
                    ));
                } catch (Exception e) {
                    return ToolResult.of(new Output(
                        false,
                        "Approval failed: " + e.getMessage(),
                        true
                    ));
                }
            }

            // Default: auto-approve if no handler set
            return ToolResult.of(new Output(
                true,
                "Plan auto-approved (no handler set)",
                false
            ));
        });
    }

    @Override
    public CompletableFuture<String> describe(Input input, ToolDescribeOptions options) {
        return CompletableFuture.completedFuture("Exit plan mode for: " + input.planFile());
    }

    @Override
    public boolean isReadOnly(Input input) {
        return true;
    }

    @Override
    public boolean requiresUserInteraction() {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(Input input) {
        return false;
    }

    @Override
    public String interruptBehavior() {
        return "cancel";
    }

    @Override
    public String getActivityDescription(Input input) {
        return "Requesting plan approval";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Input parseInput(Map<String, Object> input) {
        String planFileStr = (String) input.get("planFile");
        Path planFile = planFileStr != null ? Paths.get(planFileStr) : null;
        String planContent = (String) input.get("planContent");
        Map<String, Object> metadata = (Map<String, Object>) input.get("metadata");
        return new Input(planFile, planContent, metadata);
    }

    // ==================== Input/Output/Progress ====================

    public record Input(
        Path planFile,
        String planContent,
        Map<String, Object> metadata
    ) {}

    public record Output(
        boolean approved,
        String message,
        boolean isError
    ) {
        public String toResultString() {
            return message;
        }
    }

    public record Progress(String status) implements ToolProgressData {}

    // ==================== Handler Interface ====================

    /**
     * Interface for handling plan approval requests.
     */
    public interface PlanApprovalHandler {
        /**
         * Request approval for a plan.
         *
         * @param planFile Path to the plan file
         * @param planContent Content of the plan
         * @return CompletableFuture with approval result (true = approved)
         */
        CompletableFuture<Boolean> requestApproval(Path planFile, String planContent);
    }
}