/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code MCP SSE transport
 */
package com.anthropic.claudecode.services.mcp.transports;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import com.anthropic.claudecode.services.mcp.McpTypes.*;

/**
 * SseTransport - Transport for MCP servers using Server-Sent Events.
 */
public class SseTransport implements McpTransport {

    private final McpSSEServerConfig config;
    private final List<MessageHandler> handlers = new CopyOnWriteArrayList<>();
    private volatile boolean connected = false;
    private HttpClient httpClient;
    private CompletableFuture<Void> eventLoop;

    public SseTransport(McpSSEServerConfig config) {
        this.config = config;
    }

    @Override
    public void connect() {
        try {
            httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

            connected = true;

            // Start SSE event loop in background
            eventLoop = CompletableFuture.runAsync(this::sseLoop);

        } catch (Exception e) {
            connected = false;
            throw new RuntimeException("Failed to connect SSE transport: " + e.getMessage(), e);
        }
    }

    @Override
    public void disconnect() {
        connected = false;
        if (eventLoop != null) {
            eventLoop.cancel(true);
        }
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
            // Send via POST to the SSE endpoint
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(config.url()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(message));

            // Add headers
            if (config.headers() != null) {
                for (Map.Entry<String, String> header : config.headers().entrySet()) {
                    requestBuilder.header(header.getKey(), header.getValue());
                }
            }

            HttpRequest request = requestBuilder.build();
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());

        } catch (Exception e) {
            throw new RuntimeException("Failed to send SSE message: " + e.getMessage(), e);
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

    private void sseLoop() {
        while (connected) {
            try {
                // Build GET request for SSE stream
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(config.url()))
                    .header("Accept", "text/event-stream")
                    .timeout(Duration.ofMinutes(5))
                    .GET();

                // Add headers
                if (config.headers() != null) {
                    for (Map.Entry<String, String> header : config.headers().entrySet()) {
                        requestBuilder.header(header.getKey(), header.getValue());
                    }
                }

                HttpRequest request = requestBuilder.build();
                HttpResponse<InputStream> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofInputStream()
                );

                // Read SSE events
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body()))) {

                    String line;
                    StringBuilder eventData = new StringBuilder();

                    while (connected && (line = reader.readLine()) != null) {
                        if (line.isEmpty()) {
                            // Empty line = end of event
                            if (eventData.length() > 0) {
                                dispatchEvent(eventData.toString());
                                eventData.setLength(0);
                            }
                        } else if (line.startsWith("data: ")) {
                            eventData.append(line.substring(6));
                        }
                    }
                }

            } catch (Exception e) {
                if (connected) {
                    // Try to reconnect after delay
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    private void dispatchEvent(String data) {
        for (MessageHandler handler : handlers) {
            try {
                handler.handleMessage(data);
            } catch (Exception e) {
                // Ignore handler errors
            }
        }
    }
}