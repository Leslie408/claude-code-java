/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code AgentTool
 */
package com.anthropic.claudecode.tools;

import com.anthropic.claudecode.*;
import com.anthropic.claudecode.agent.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * AgentTool - Launch a specialized agent for complex tasks.
 *
 * <p>This tool spawns specialized agents that can work autonomously on tasks.
 *
 * <p>Available agent types:
 * <ul>
 *   <li>general-purpose - Complex multi-step tasks</li>
 *   <li>Explore - Fast codebase exploration</li>
 *   <li>Plan - Implementation planning</li>
 *   <li>claude-code-guide - Claude Code help</li>
 * </ul>
 */
public class AgentTool extends AbstractTool<AgentTool.Input, AgentTool.Output, AgentTool.Progress> {

    public static final String NAME = "Agent";

    private AgentExecutor executor;

    public AgentTool() {
        super(NAME, List.of("agent", "spawn"), createSchema());
    }

    /**
     * Set a custom agent executor.
     */
    public void setExecutor(AgentExecutor executor) {
        this.executor = executor;
    }

    private static Map<String, Object> createSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> subagentTypeProp = new LinkedHashMap<>();
        subagentTypeProp.put("type", "string");
        subagentTypeProp.put("enum", List.of("general-purpose", "Explore", "Plan", "claude-code-guide"));
        subagentTypeProp.put("description", "The type of specialized agent to use");
        subagentTypeProp.put("default", "general-purpose");
        properties.put("subagent_type", subagentTypeProp);

        Map<String, Object> promptProp = new LinkedHashMap<>();
        promptProp.put("type", "string");
        promptProp.put("description", "The task for the agent to perform");
        properties.put("prompt", promptProp);

        Map<String, Object> descriptionProp = new LinkedHashMap<>();
        descriptionProp.put("type", "string");
        descriptionProp.put("description", "A short (3-5 word) description of the task");
        properties.put("description", descriptionProp);

        Map<String, Object> modelProp = new LinkedHashMap<>();
        modelProp.put("type", "string");
        modelProp.put("enum", List.of("sonnet", "opus", "haiku"));
        modelProp.put("description", "Optional model override");
        properties.put("model", modelProp);

        Map<String, Object> backgroundProp = new LinkedHashMap<>();
        backgroundProp.put("type", "boolean");
        backgroundProp.put("description", "Run in background and be notified when complete");
        properties.put("run_in_background", backgroundProp);

        Map<String, Object> isolationProp = new LinkedHashMap<>();
        isolationProp.put("type", "string");
        isolationProp.put("enum", List.of("worktree"));
        isolationProp.put("description", "Run in isolated git worktree");
        properties.put("isolation", isolationProp);

        schema.put("properties", properties);
        schema.put("required", List.of("prompt"));
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
            String agentId = "agent_" + UUID.randomUUID().toString().substring(0, 8);
            String agentType = input.subagentType() != null ? input.subagentType() : "general-purpose";

            // Validate agent type
            if (!AgentRegistry.hasAgent(agentType)) {
                return ToolResult.of(new Output("", "Unknown agent type: " + agentType, agentType, agentId, true));
            }

            // Report progress
            if (onProgress != null) {
                onProgress.accept(ToolProgress.of(agentId, new Progress("starting", "Initializing " + agentType + " agent", 0)));
            }

            try {
                // Get or create executor
                AgentExecutor exec = getOrCreateExecutor(context);

                // Build request
                AgentRequest request = AgentRequest.builder()
                    .agentType(agentType)
                    .prompt(input.prompt())
                    .description(input.description())
                    .model(mapModel(input.model()))
                    .workingDirectory(System.getProperty("user.dir"))
                    .build();

                // Execute agent
                AgentResult result;
                if (input.runInBackground()) {
                    result = exec.executeAsync(request, progress -> {
                        if (onProgress != null) {
                            onProgress.accept(ToolProgress.of(agentId,
                                new Progress(progress.status(), progress.message(), progress.progress())));
                        }
                    }).get(5, java.util.concurrent.TimeUnit.MINUTES);
                } else {
                    result = exec.execute(request, progress -> {
                        if (onProgress != null) {
                            onProgress.accept(ToolProgress.of(agentId,
                                new Progress(progress.status(), progress.message(), progress.progress())));
                        }
                    });
                }

                return ToolResult.of(new Output(
                    result.content(),
                    result.error(),
                    agentType,
                    result.agentId(),
                    !result.success()
                ));

            } catch (Exception e) {
                return ToolResult.of(new Output(
                    "",
                    "Agent execution failed: " + e.getMessage(),
                    agentType,
                    agentId,
                    true
                ));
            }
        });
    }

    /**
     * Get or create an agent executor.
     */
    private AgentExecutor getOrCreateExecutor(ToolUseContext context) {
        if (executor != null) {
            return executor;
        }

        // Create default executor
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null) {
            apiKey = System.getenv("CLAUDE_API_KEY");
        }

        com.anthropic.claudecode.services.api.ApiClient apiClient =
            com.anthropic.claudecode.services.api.ApiClient.create(apiKey != null ? apiKey : "");

        AgentApiClient agentApi = AgentApiClient.fromExisting(apiClient, "glm-5");
        AgentToolExecutor toolExecutor = AgentToolExecutor.fromTools(ToolFactory.createAllTools());

        AgentConfig config = AgentConfig.builder()
            .apiClient(agentApi)
            .toolExecutor(toolExecutor)
            .maxTurns(20)
            .maxTokens(4096)
            .build();

        executor = new AgentExecutor(config);
        return executor;
    }

    /**
     * Map model name to API model ID.
     */
    private String mapModel(String model) {
        if (model == null) return null;
        return switch (model.toLowerCase()) {
            case "sonnet" -> "glm-5";
            case "opus" -> "qwen3-max-2026-01-23";
            case "haiku" -> "qwen3.5-plus";
            default -> model;
        };
    }

    @Override
    public CompletableFuture<String> describe(Input input, ToolDescribeOptions options) {
        String desc = input.description() != null ? input.description() : "Agent task";
        String type = input.subagentType() != null ? input.subagentType() : "general-purpose";
        return CompletableFuture.completedFuture("Launch " + type + " agent: " + desc);
    }

    @Override
    public boolean isReadOnly(Input input) {
        // Agents can do both read and write operations
        return false;
    }

    @Override
    public boolean isDestructive(Input input) {
        // Agents might do destructive operations
        return true;
    }

    @Override
    public boolean isConcurrencySafe(Input input) {
        // Agents can run concurrently if background mode
        return input.runInBackground();
    }

    @Override
    public String getActivityDescription(Input input) {
        String desc = input.description();
        if (desc != null) return desc;
        return "Running agent task";
    }

    @Override
    public String getToolUseSummary(Input input) {
        String type = input.subagentType() != null ? input.subagentType() : "agent";
        String desc = input.description();
        return type + (desc != null ? ": " + desc : "");
    }

    @Override
    public Input parseInput(Map<String, Object> input) {
        String subagentType = (String) input.get("subagent_type");
        String prompt = (String) input.get("prompt");
        String description = (String) input.get("description");
        String model = (String) input.get("model");
        boolean runInBackground = Boolean.TRUE.equals(input.get("run_in_background"));
        String isolation = (String) input.get("isolation");
        return new Input(subagentType, prompt, description, model, runInBackground, isolation);
    }

    // ==================== Input/Output/Progress ====================

    public record Input(
        String subagentType,
        String prompt,
        String description,
        String model,
        boolean runInBackground,
        String isolation
    ) {
        public Input(String prompt, String description) {
            this(null, prompt, description, null, false, null);
        }
    }

    public record Output(
        String result,
        String error,
        String agentType,
        String agentId,
        boolean isError
    ) {
        public String toResultString() {
            if (isError) return error;
            return result;
        }
    }

    public record Progress(
        String status,
        String message,
        int percent
    ) implements ToolProgressData {}
}