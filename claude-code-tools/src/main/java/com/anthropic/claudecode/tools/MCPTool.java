/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 * Java port of Claude Code tools/MCPTool/MCPTool.ts
 */
package com.anthropic.claudecode.tools;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

import com.anthropic.claudecode.*;
import com.anthropic.claudecode.permission.PermissionResult;
import com.anthropic.claudecode.services.mcp.McpClientManager;

/**
 * MCP Tool - execute MCP server tools.
 *
 * <p>This tool acts as a bridge to tools discovered from MCP servers.
 * It delegates the actual execution to the McpClientManager.
 *
 * <p>The input schema is dynamic and determined by the specific MCP tool being invoked.
 */
public final class MCPTool extends AbstractTool<MCPTool.Input, MCPTool.Output, ToolProgressData> {

    public static final String TOOL_NAME = "MCP";

    private final String serverName;
    private final String toolName;
    private final String toolDescription;
    private final Map<String, Object> inputSchema;
    private McpClientManager clientManager;

    /**
     * Create a generic MCP tool placeholder.
     */
    public MCPTool() {
        this(null, null, "Execute MCP server tools", null);
    }

    /**
     * Create a specific MCP tool.
     */
    public MCPTool(String serverName, String toolName, String description, Map<String, Object> inputSchema) {
        super(TOOL_NAME, "Execute MCP server tools");
        this.serverName = serverName;
        this.toolName = toolName;
        this.toolDescription = description != null ? description : "MCP tool: " + toolName;
        this.inputSchema = inputSchema;
    }

    /**
     * Set the MCP client manager.
     */
    public void setClientManager(McpClientManager manager) {
        this.clientManager = manager;
    }

    /**
     * Get the server name for this tool.
     */
    public String serverName() {
        return serverName;
    }

    /**
     * Get the tool name on the MCP server.
     */
    public String mcpToolName() {
        return toolName;
    }

    @Override
    public String description() {
        return toolDescription;
    }

    @Override
    public Map<String, Object> inputSchema() {
        return inputSchema != null ? inputSchema : super.inputSchema();
    }

    @Override
    public String searchHint() {
        return "execute MCP server tools";
    }

    @Override
    public boolean isOpenWorld(Input input) {
        return true; // MCP tools can have any schema
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
    public Input parseInput(Map<String, Object> input) {
        return new Input(input);
    }

    @Override
    public CompletableFuture<PermissionResult> checkPermissions(Input input, ToolUseContext context) {
        // MCP tools should ask for permission
        String message = "Execute MCP tool: " + (toolName != null ? toolName : "unknown");
        if (serverName != null) {
            message += " (server: " + serverName + ")";
        }
        return CompletableFuture.completedFuture(PermissionResult.ask(message, input));
    }

    @Override
    public CompletableFuture<ToolResult<Output>> call(
            Input input,
            ToolUseContext context,
            CanUseToolFn canUseTool,
            AssistantMessage parent,
            Consumer<ToolProgress<ToolProgressData>> onProgress) {

        return CompletableFuture.supplyAsync(() -> {
            // Check if we have a client manager
            if (clientManager == null) {
                return ToolResult.of(new Output("MCP client not initialized"));
            }

            // Check if we have tool info
            if (serverName == null || toolName == null) {
                return ToolResult.of(new Output("MCP tool not properly configured"));
            }

            try {
                // Invoke the tool via MCP client
                McpClientManager.McpToolResult result = clientManager
                    .invokeTool(serverName, toolName, input.fields())
                    .get(60, TimeUnit.SECONDS);

                if (result.isError()) {
                    return ToolResult.of(new Output("Error: " + result.error()));
                }

                Object content = result.content();
                String output = content != null ? content.toString() : "Tool completed";

                return ToolResult.of(new Output(output));

            } catch (TimeoutException e) {
                return ToolResult.of(new Output("Error: MCP tool timed out"));
            } catch (Exception e) {
                return ToolResult.of(new Output("Error: " + e.getMessage()));
            }
        });
    }

    @Override
    public String formatResult(Output output) {
        return output.result() != null ? output.result() : "";
    }

    // ==================== Input/Output ====================

    /**
     * Input schema - allows any input object since MCP tools define their own schemas.
     */
    public record Input(
        Map<String, Object> fields
    ) {
        public Object get(String key) {
            return fields != null ? fields.get(key) : null;
        }

        public String getString(String key) {
            Object value = get(key);
            return value != null ? value.toString() : null;
        }

        public Integer getInteger(String key) {
            Object value = get(key);
            if (value instanceof Number n) {
                return n.intValue();
            }
            return null;
        }

        public Boolean getBoolean(String key) {
            Object value = get(key);
            return Boolean.TRUE.equals(value);
        }
    }

    /**
     * Output schema.
     */
    public record Output(
        String result
    ) {
        public String toResultString() {
            return result != null ? result : "";
        }
    }
}