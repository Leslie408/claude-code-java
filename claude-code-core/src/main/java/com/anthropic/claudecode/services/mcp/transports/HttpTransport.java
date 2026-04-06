/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code MCP HTTP transport
 */
package com.anthropic.claudecode.services.mcp.transports;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import com.anthropic.claudecode.services.mcp.McpTypes.*;

/**
 * HttpTransport - Transport for MCP servers using HTTP.
 */
public class HttpTransport implements McpTransport {

    private final McpHTTPServerConfig config;
    private final List<MessageHandler> handlers = new CopyOnWriteArrayList<>();
    private volatile boolean connected = false;
    private HttpClient httpClient;

    public HttpTransport(McpHTTPServerConfig config) {
        this.config = config;
    }

    @Override
    public void connect() {
        httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        connected = true;
    }

    @Override
    public void disconnect() {
        connected = false;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void send(String message) {
        if (!isConnected()) {
            throw new IllegalStateException("Transport not connected");
        }

        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(config.url()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(message));

            if (config.headers() != null) {
                for (Map.Entry<String, String> header : config.headers().entrySet()) {
                    requestBuilder.header(header.getKey(), header.getValue());
                }
            }

            HttpRequest request = requestBuilder.build();
            HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
            );

            // Dispatch response to handlers
            if (response.body() != null) {
                for (MessageHandler handler : handlers) {
                    handler.handleMessage(response.body());
                }
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to send HTTP message: " + e.getMessage(), e);
        }
    }

    @Override
    public void addHandler(MessageHandler handler) {
        handlers.add(handler);
    }

    @Override
    public void removeHandler(MessageHandler handler) {
        handlers.remove(handler);
    }
}