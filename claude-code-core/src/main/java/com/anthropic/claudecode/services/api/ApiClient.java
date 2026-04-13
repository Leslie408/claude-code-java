/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 * Java port of Claude Code services/api/client.ts
 */
package com.anthropic.claudecode.services.api;

import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * API Client for Claude API interactions.
 */
public class ApiClient {
    private final ApiClientConfig config;
    private final HttpClient httpClient;
    private final StreamingHttpClient streamingHttpClient;
    private volatile boolean closed = false;

    public ApiClient(ApiClientConfig config) {
        this.config = config;
        this.httpClient = new HttpClient(config);
        this.streamingHttpClient = new StreamingHttpClient(config);
    }

    /**
     * Send a message to the API (non-streaming).
     */
    public CompletableFuture<ApiResponse> sendMessage(ApiRequest request) {
        if (closed) {
            return CompletableFuture.failedFuture(new ApiException("Client is closed"));
        }
        return httpClient.post("/v1/messages", request);
    }

    /**
     * Stream a message from the API - returns Flux of SSE events.
     * TRUE streaming: events arrive as they are received from the server.
     */
    public Flux<SseEvent> streamMessageFlux(ApiRequest request) {
        if (closed) {
            return Flux.error(new ApiException("Client is closed"));
        }
        return streamingHttpClient.streamPost("/v1/messages", request);
    }

    /**
     * Stream a message from the API - legacy method (fake streaming).
     * @deprecated Use streamMessageFlux() for true streaming.
     */
    @Deprecated
    public CompletableFuture<ApiStreamingResponse> streamMessage(ApiRequest request) {
        if (closed) {
            return CompletableFuture.failedFuture(new ApiException("Client is closed"));
        }
        return httpClient.streamPost("/v1/messages", request);
    }

    /**
     * Close the client.
     */
    public void close() {
        closed = true;
        httpClient.close();
        streamingHttpClient.close();
    }

    /**
     * Check if the client is closed.
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Get the configuration.
     */
    public ApiClientConfig getConfig() {
        return config;
    }

    /**
     * Create a default API client.
     */
    public static ApiClient create(String apiKey) {
        return new ApiClient(ApiClientConfig.builder()
            .apiKey(apiKey)
            .build());
    }
}