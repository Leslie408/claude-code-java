/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code agent tool executor
 */
package com.anthropic.claudecode.agent;

import java.util.*;
import java.util.concurrent.*;

import com.anthropic.claudecode.*;

/**
 * AgentToolExecutor - Interface for executing tools on behalf of agents.
 */
public interface AgentToolExecutor {

    /**
     * Execute a tool.
     */
    AgentToolResult execute(String toolName, Map<String, Object> input, AgentContext context);

    /**
     * Get available tool names.
     */
    Set<String> getAvailableTools();

    /**
     * Check if a tool is available.
     */
    boolean hasTool(String name);

    /**
     * Default implementation using Tool interface.
     */
    static AgentToolExecutor fromTools(List<Tool<?, ?, ?>> tools) {
        return new DefaultAgentToolExecutor(tools);
    }
}

/**
 * Default implementation of AgentToolExecutor.
 */
class DefaultAgentToolExecutor implements AgentToolExecutor {

    private final Map<String, Tool<?, ?, ?>> tools = new HashMap<>();

    DefaultAgentToolExecutor(List<Tool<?, ?, ?>> toolList) {
        if (toolList != null) {
            for (Tool<?, ?, ?> tool : toolList) {
                registerTool(tool);
            }
        }
    }

    private void registerTool(Tool<?, ?, ?> tool) {
        tools.put(tool.name(), tool);
        for (String alias : tool.aliases()) {
            tools.putIfAbsent(alias, tool);
        }
    }

    @Override
    public AgentToolResult execute(String toolName, Map<String, Object> input, AgentContext context) {
        Tool<?, ?, ?> tool = tools.get(toolName);
        if (tool == null) {
            return AgentToolResult.error("unknown", "Unknown tool: " + toolName);
        }

        try {
            // Execute tool using reflection to call parseInput and executeWithMapInput
            return executeTool(tool, input, context);
        } catch (Exception e) {
            return AgentToolResult.error("unknown", "Tool execution error: " + e.getMessage());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private AgentToolResult executeTool(Tool tool, Map<String, Object> input, AgentContext context) throws Exception {
        // Create a simple tool context
        ToolUseContext toolContext = ToolUseContext.empty();

        // Try to call via reflection for tools that support parseInput
        try {
            java.lang.reflect.Method parseMethod = tool.getClass().getMethod("parseInput", Map.class);
            Object parsedInput = parseMethod.invoke(tool, input);

            // Call executeWithMapInput if available
            java.lang.reflect.Method execMethod = tool.getClass().getMethod(
                "executeWithMapInput",
                Map.class,
                ToolUseContext.class,
                com.anthropic.claudecode.hooks.CanUseToolFn.class,
                AssistantMessage.class,
                java.util.function.Consumer.class
            );

            CompletableFuture<ToolResult> future = (CompletableFuture<ToolResult>) execMethod.invoke(
                tool, input, toolContext,
                (CanUseToolFn) (t, i, ctx, msg, id) -> CompletableFuture.completedFuture(
                    com.anthropic.claudecode.permission.PermissionResult.allow(i)
                ),
                null,
                (java.util.function.Consumer) progress -> {}
            );

            ToolResult result = future.get(60, TimeUnit.SECONDS);
            Object data = result.data();

            // Check if result indicates an error
            boolean isError = false;
            String content = "";

            if (data != null) {
                // Try to check for error indicator
                try {
                    java.lang.reflect.Method isErrorMethod = data.getClass().getMethod("isError");
                    isError = Boolean.TRUE.equals(isErrorMethod.invoke(data));
                } catch (NoSuchMethodException e) {
                    // No isError method
                }

                // Get content
                content = data.toString();
            }

            return new AgentToolResult(
                "tool_result",
                content,
                isError
            );
        } catch (NoSuchMethodException e) {
            // Tool doesn't support the extended interface
            return AgentToolResult.error("unknown", "Tool does not support agent execution");
        } catch (Exception e) {
            return AgentToolResult.error("unknown", "Tool execution error: " + e.getMessage());
        }
    }

    @Override
    public Set<String> getAvailableTools() {
        return new HashSet<>(tools.keySet());
    }

    @Override
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }
}