/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code MCP client manager
 */
package com.anthropic.claudecode.services.mcp;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

import com.anthropic.claudecode.services.mcp.McpTypes.*;
import com.anthropic.claudecode.services.mcp.transports.*;

/**
 * McpClientManager - Manages MCP server connections and tool discovery.
 *
 * <p>This is the main entry point for MCP functionality.
 * It handles:
 * <ul>
 *   <li>Server connection lifecycle</li>
 *   <li>Tool discovery from connected servers</li>
 *   <li>Resource management</li>
 *   <li>Tool invocation</li>
 * </ul>
 */
public class McpClientManager {

    private final Map<String, MCPServerConnection> connections;
    private final Map<String, McpTransport> transports;
    private final Map<String, List<McpDiscoveredTool>> discoveredTools;
    private final Map<String, List<ServerResource>> serverResources;
    private final ExecutorService executor;

    private Consumer<McpConnectionEvent> connectionListener;
    private volatile boolean shuttingDown = false;

    public McpClientManager() {
        this.connections = new ConcurrentHashMap<>();
        this.transports = new ConcurrentHashMap<>();
        this.discoveredTools = new ConcurrentHashMap<>();
        this.serverResources = new ConcurrentHashMap<>();
        this.executor = Executors.newCachedThreadPool();
    }

    /**
     * Connect to an MCP server.
     */
    public CompletableFuture<MCPServerConnection> connect(String name, ScopedMcpServerConfig config) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Create transport based on config type
                McpTransport transport = createTransport(config.config());
                transports.put(name, transport);

                // Add message handler
                transport.addHandler(new McpMessageHandler(name));

                // Connect
                transport.connect();

                // Wait for initialization
                Thread.sleep(500); // Allow server to initialize

                // Discover capabilities
                ServerCapabilities capabilities = discoverCapabilities(transport);

                // Discover tools
                List<McpDiscoveredTool> tools = discoverTools(transport);
                discoveredTools.put(name, tools);

                // Create connected server record
                ConnectedMCPServer connection = new ConnectedMCPServer(
                    name,
                    config,
                    capabilities,
                    new ServerInfo(name, "1.0.0"),
                    "",
                    () -> disconnect(name)
                );

                connections.put(name, connection);

                // Notify listener
                if (connectionListener != null) {
                    connectionListener.accept(new McpConnectionEvent(name, "connected", null));
                }

                return connection;

            } catch (Exception e) {
                FailedMCPServer failed = new FailedMCPServer(name, config, e.getMessage());
                connections.put(name, failed);

                if (connectionListener != null) {
                    connectionListener.accept(new McpConnectionEvent(name, "failed", e.getMessage()));
                }

                return failed;
            }
        }, executor);
    }

    /**
     * Disconnect from an MCP server.
     */
    public void disconnect(String name) {
        McpTransport transport = transports.remove(name);
        if (transport != null) {
            transport.disconnect();
        }

        connections.remove(name);
        discoveredTools.remove(name);
        serverResources.remove(name);

        if (connectionListener != null) {
            connectionListener.accept(new McpConnectionEvent(name, "disconnected", null));
        }
    }

    /**
     * Get all discovered tools.
     */
    public List<McpDiscoveredTool> getAllTools() {
        List<McpDiscoveredTool> all = new ArrayList<>();
        for (List<McpDiscoveredTool> tools : discoveredTools.values()) {
            all.addAll(tools);
        }
        return all;
    }

    /**
     * Get tools from a specific server.
     */
    public List<McpDiscoveredTool> getTools(String serverName) {
        return discoveredTools.getOrDefault(serverName, Collections.emptyList());
    }

    /**
     * Invoke a tool on an MCP server.
     */
    public CompletableFuture<McpToolResult> invokeTool(String serverName, String toolName, Map<String, Object> args) {
        return CompletableFuture.supplyAsync(() -> {
            McpTransport transport = transports.get(serverName);
            if (transport == null || !transport.isConnected()) {
                return new McpToolResult(null, "Server not connected: " + serverName, true);
            }

            try {
                // Build JSON-RPC request
                String request = buildToolCallRequest(toolName, args);

                // Send request
                transport.send(request);

                // Wait for response (simplified - real impl would correlate request/response)
                Thread.sleep(100);

                return new McpToolResult("Tool invoked: " + toolName, null, false);

            } catch (Exception e) {
                return new McpToolResult(null, "Tool invocation failed: " + e.getMessage(), true);
            }
        }, executor);
    }

    /**
     * Get all connections.
     */
    public Map<String, MCPServerConnection> getConnections() {
        return new HashMap<>(connections);
    }

    /**
     * Get a specific connection.
     */
    public MCPServerConnection getConnection(String name) {
        return connections.get(name);
    }

    /**
     * Set connection event listener.
     */
    public void setConnectionListener(Consumer<McpConnectionEvent> listener) {
        this.connectionListener = listener;
    }

    /**
     * Shutdown the manager.
     */
    public void shutdown() {
        shuttingDown = true;

        // Disconnect all servers
        for (String name : new ArrayList<>(transports.keySet())) {
            disconnect(name);
        }

        executor.shutdown();
    }

    // ==================== Private Methods ====================

    private McpTransport createTransport(McpServerConfig config) {
        if (config instanceof McpStdioServerConfig stdio) {
            return new StdioTransport(stdio);
        } else if (config instanceof McpSSEServerConfig sse) {
            return new SseTransport(sse);
        } else if (config instanceof McpHTTPServerConfig http) {
            return new HttpTransport(http);
        } else if (config instanceof McpWebSocketServerConfig ws) {
            return new WebSocketTransport(ws);
        } else if (config instanceof McpSdkServerConfig sdk) {
            return new SdkControlTransport(sdk.name());
        } else {
            return new NullTransport();
        }
    }

    private ServerCapabilities discoverCapabilities(McpTransport transport) {
        // Send initialize request and parse response
        // Simplified: return default capabilities
        return new ServerCapabilities(true, true, false, false);
    }

    private List<McpDiscoveredTool> discoverTools(McpTransport transport) {
        // Send tools/list request and parse response
        // Simplified: return empty list for now
        return new ArrayList<>();
    }

    private String buildToolCallRequest(String toolName, Map<String, Object> args) {
        // Build JSON-RPC 2.0 request
        StringBuilder sb = new StringBuilder();
        sb.append("{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"id\":1,");
        sb.append("\"params\":{\"name\":\"").append(toolName).append("\",");
        sb.append("\"arguments\":");
        sb.append(toJson(args));
        sb.append("}}");
        return sb.toString();
    }

    private String toJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof String s) {
                sb.append("\"").append(escapeJson(s)).append("\"");
            } else if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append("\"").append(v).append("\"");
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ==================== Inner Classes ====================

    /**
     * Message handler for MCP messages.
     */
    private class McpMessageHandler implements McpTransport.MessageHandler {
        private final String serverName;

        McpMessageHandler(String serverName) {
            this.serverName = serverName;
        }

        @Override
        public void handleMessage(String message) {
            // Parse and handle MCP message
            // Simplified: just log
            System.out.println("[MCP " + serverName + "] " + message);
        }
    }

    /**
     * Connection event record.
     */
    public record McpConnectionEvent(
        String serverName,
        String event,
        String error
    ) {}

    /**
     * Discovered tool record.
     */
    public record McpDiscoveredTool(
        String serverName,
        String name,
        String description,
        Map<String, Object> inputSchema
    ) {}

    /**
     * Tool result record.
     */
    public record McpToolResult(
        Object content,
        String error,
        boolean isError
    ) {}
}