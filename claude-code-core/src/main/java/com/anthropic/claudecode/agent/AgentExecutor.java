/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code agent system
 */
package com.anthropic.claudecode.agent;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * AgentExecutor - Executes agent tasks with full tool access.
 *
 * <p>This is the core executor that runs specialized agents.
 */
public class AgentExecutor {

    private final AgentConfig config;
    private final AgentContext context;
    private volatile boolean cancelled = false;

    public AgentExecutor(AgentConfig config) {
        this.config = config;
        this.context = new AgentContext(config);
    }

    /**
     * Execute an agent task synchronously.
     */
    public AgentResult execute(AgentRequest request) {
        return execute(request, null);
    }

    /**
     * Execute an agent task with progress reporting.
     */
    public AgentResult execute(AgentRequest request, Consumer<AgentProgress> onProgress) {
        String agentId = "agent_" + UUID.randomUUID().toString().substring(0, 8);
        long startTime = System.currentTimeMillis();

        try {
            // Report start
            if (onProgress != null) {
                onProgress.accept(new AgentProgress(agentId, "starting", "Initializing agent", 0));
            }

            // Validate request
            if (request.prompt() == null || request.prompt().isEmpty()) {
                return AgentResult.failure(agentId, "No prompt provided");
            }

            // Get agent definition
            AgentDefinition definition = getDefinition(request.agentType());
            if (definition == null) {
                return AgentResult.failure(agentId, "Unknown agent type: " + request.agentType());
            }

            // Create agent session
            AgentSession session = createSession(agentId, request, definition);

            // Execute the agent loop
            AgentResult result = runAgentLoop(session, onProgress);

            long duration = System.currentTimeMillis() - startTime;
            return result.withDuration(duration);

        } catch (Exception e) {
            return AgentResult.failure(agentId, "Execution error: " + e.getMessage());
        }
    }

    /**
     * Execute an agent task asynchronously.
     */
    public CompletableFuture<AgentResult> executeAsync(AgentRequest request) {
        return executeAsync(request, null);
    }

    /**
     * Execute an agent task asynchronously with progress reporting.
     */
    public CompletableFuture<AgentResult> executeAsync(AgentRequest request, Consumer<AgentProgress> onProgress) {
        return CompletableFuture.supplyAsync(() -> execute(request, onProgress));
    }

    /**
     * Cancel the current execution.
     */
    public void cancel() {
        this.cancelled = true;
    }

    /**
     * Get the agent definition for a type.
     */
    private AgentDefinition getDefinition(String agentType) {
        return AgentRegistry.getDefinition(agentType);
    }

    /**
     * Create an agent session.
     */
    private AgentSession createSession(String agentId, AgentRequest request, AgentDefinition definition) {
        return new AgentSession(
            agentId,
            request.prompt(),
            definition,
            request.model(),
            request.tools(),
            request.workingDirectory()
        );
    }

    /**
     * Run the agent loop.
     */
    private AgentResult runAgentLoop(AgentSession session, Consumer<AgentProgress> onProgress) {
        int maxTurns = config.maxTurns();
        int turn = 0;

        while (turn < maxTurns && !cancelled) {
            turn++;

            if (onProgress != null) {
                onProgress.accept(new AgentProgress(
                    session.agentId(),
                    "processing",
                    "Turn " + turn,
                    (turn * 100) / maxTurns
                ));
            }

            // Send message to API
            AgentResponse response = sendToApi(session);

            if (response == null) {
                return AgentResult.failure(session.agentId(), "No response from API");
            }

            // Check for completion
            if (response.isComplete()) {
                return AgentResult.success(
                    session.agentId(),
                    response.content(),
                    turn,
                    response.tokenUsage()
                );
            }

            // Handle tool calls
            if (response.hasToolCalls()) {
                List<AgentToolResult> toolResults = executeTools(session, response.toolCalls(), onProgress);
                session.addToolResults(toolResults);
            } else {
                // No tool calls but not complete - add response and continue
                session.addAssistantMessage(response.content());
            }
        }

        if (cancelled) {
            return AgentResult.failure(session.agentId(), "Agent execution cancelled");
        }

        return AgentResult.failure(session.agentId(), "Max turns exceeded");
    }

    /**
     * Send message to API.
     */
    private AgentResponse sendToApi(AgentSession session) {
        try {
            // Build API request
            Map<String, Object> request = buildApiRequest(session);

            // Make API call
            return config.apiClient().sendAgentRequest(request);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Build API request from session.
     */
    private Map<String, Object> buildApiRequest(AgentSession session) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", session.model());
        request.put("max_tokens", config.maxTokens());
        request.put("messages", session.getMessages());

        // Add system prompt
        String systemPrompt = session.definition().systemPrompt();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            request.put("system", systemPrompt);
        }

        // Add tools
        List<Map<String, Object>> tools = session.definition().tools();
        if (tools != null && !tools.isEmpty()) {
            request.put("tools", tools);
        }

        return request;
    }

    /**
     * Execute tool calls.
     */
    private List<AgentToolResult> executeTools(AgentSession session, List<ToolCall> toolCalls, Consumer<AgentProgress> onProgress) {
        List<AgentToolResult> results = new ArrayList<>();

        for (ToolCall call : toolCalls) {
            if (cancelled) break;

            if (onProgress != null) {
                onProgress.accept(new AgentProgress(
                    session.agentId(),
                    "tool",
                    "Executing: " + call.name(),
                    -1
                ));
            }

            AgentToolResult result = executeTool(session, call);
            results.add(result);
        }

        return results;
    }

    /**
     * Execute a single tool.
     */
    private AgentToolResult executeTool(AgentSession session, ToolCall call) {
        try {
            return config.toolExecutor().execute(call.name(), call.input(), context);
        } catch (Exception e) {
            return AgentToolResult.error(call.id(), "Tool execution failed: " + e.getMessage());
        }
    }
}