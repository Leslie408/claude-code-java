/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code agent API client
 */
package com.anthropic.claudecode.agent;

import java.util.*;

/**
 * AgentApiClient - Interface for making API calls from agents.
 */
public interface AgentApiClient {

    /**
     * Send an agent request and get a response.
     */
    AgentResponse sendAgentRequest(Map<String, Object> request);

    /**
     * Send an agent request asynchronously.
     */
    java.util.concurrent.CompletableFuture<AgentResponse> sendAgentRequestAsync(Map<String, Object> request);

    /**
     * Default implementation using existing API client.
     */
    static AgentApiClient fromExisting(com.anthropic.claudecode.services.api.ApiClient client, String defaultModel) {
        return new DefaultAgentApiClient(client, defaultModel);
    }
}

/**
 * Default implementation of AgentApiClient.
 */
class DefaultAgentApiClient implements AgentApiClient {

    private final com.anthropic.claudecode.services.api.ApiClient client;
    private final String defaultModel;

    DefaultAgentApiClient(com.anthropic.claudecode.services.api.ApiClient client, String defaultModel) {
        this.client = client;
        this.defaultModel = defaultModel;
    }

    @Override
    public AgentResponse sendAgentRequest(Map<String, Object> request) {
        try {
            // Build ApiRequest from map
            com.anthropic.claudecode.services.api.ApiRequest apiRequest = buildApiRequest(request);

            // Send to API
            com.anthropic.claudecode.services.api.ApiResponse response = client.sendMessage(apiRequest).join();

            // Convert response
            return convertResponse(response);
        } catch (Exception e) {
            return AgentResponse.complete("API error: " + e.getMessage(), TokenUsage.empty());
        }
    }

    @Override
    public java.util.concurrent.CompletableFuture<AgentResponse> sendAgentRequestAsync(Map<String, Object> request) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> sendAgentRequest(request));
    }

    private com.anthropic.claudecode.services.api.ApiRequest buildApiRequest(Map<String, Object> request) {
        String model = (String) request.getOrDefault("model", defaultModel);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) request.get("messages");
        String system = (String) request.get("system");
        Integer maxTokens = (Integer) request.getOrDefault("max_tokens", 4096);

        com.anthropic.claudecode.services.api.ApiRequest.Builder builder = com.anthropic.claudecode.services.api.ApiRequest.builder()
            .model(model)
            .maxTokens(maxTokens)
            .messages(messages != null ? messages : List.of());

        if (system != null) {
            builder.system(system);
        }

        return builder.build();
    }

    private AgentResponse convertResponse(com.anthropic.claudecode.services.api.ApiResponse response) {
        String content = null;
        List<ToolCall> toolCalls = new ArrayList<>();

        for (com.anthropic.claudecode.services.api.ApiResponse.ContentBlock block : response.content()) {
            if (block instanceof com.anthropic.claudecode.services.api.ApiResponse.ContentBlock.TextBlock tb) {
                content = tb.text();
            } else if (block instanceof com.anthropic.claudecode.services.api.ApiResponse.ContentBlock.ToolUseBlock tb) {
                toolCalls.add(ToolCall.of(tb.id(), tb.name(), tb.input()));
            }
        }

        TokenUsage usage = TokenUsage.of(
            response.usage() != null ? response.usage().inputTokens() : 0,
            response.usage() != null ? response.usage().outputTokens() : 0
        );

        boolean isComplete = toolCalls.isEmpty();
        return new AgentResponse(response.id(), content, toolCalls, isComplete, usage);
    }
}