/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code session management
 */
package com.anthropic.claudecode.session;

import com.anthropic.claudecode.Tool;
import com.anthropic.claudecode.message.*;
import com.anthropic.claudecode.services.api.*;
import com.anthropic.claudecode.services.api.SseEvent;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Claude session for managing conversation state with actual API calls.
 */
public class ClaudeSession {

    private final String sessionId;
    private final List<Message> messages = new ArrayList<>();
    private final AtomicInteger turnCount = new AtomicInteger(0);
    private SessionState state = SessionState.ACTIVE;
    private final ApiClient apiClient;
    private final String model;
    private final List<Tool<?, ?, ?>> tools;
    private final String systemPrompt;

    public ClaudeSession() {
        this.sessionId = UUID.randomUUID().toString();
        this.model = "glm-5";
        this.tools = new ArrayList<>();
        this.systemPrompt = null;
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey != null && !apiKey.isEmpty()) {
            this.apiClient = ApiClient.create(apiKey);
        } else {
            this.apiClient = null;
        }
    }

    public ClaudeSession(String sessionId) {
        this.sessionId = sessionId;
        this.model = "glm-5";
        this.tools = new ArrayList<>();
        this.systemPrompt = null;
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey != null && !apiKey.isEmpty()) {
            this.apiClient = ApiClient.create(apiKey);
        } else {
            this.apiClient = null;
        }
    }

    public ClaudeSession(String sessionId, String apiKey, String model) {
        this.sessionId = sessionId;
        this.model = model != null ? model : "glm-5";
        this.tools = new ArrayList<>();
        this.systemPrompt = null;
        if (apiKey != null && !apiKey.isEmpty()) {
            this.apiClient = ApiClient.create(apiKey);
        } else {
            this.apiClient = null;
        }
    }

    public ClaudeSession(String sessionId, String apiKey, String model, List<Tool<?, ?, ?>> tools) {
        this.sessionId = sessionId;
        this.model = model != null ? model : "glm-5";
        this.tools = tools != null ? tools : new ArrayList<>();
        this.systemPrompt = null;
        if (apiKey != null && !apiKey.isEmpty()) {
            this.apiClient = ApiClient.create(apiKey);
        } else {
            this.apiClient = null;
        }
    }

    public ClaudeSession(String sessionId, String apiKey, String model, List<Tool<?, ?, ?>> tools, String systemPrompt) {
        this.sessionId = sessionId;
        this.model = model != null ? model : "glm-5";
        this.tools = tools != null ? tools : new ArrayList<>();
        this.systemPrompt = systemPrompt;
        if (apiKey != null && !apiKey.isEmpty()) {
            this.apiClient = ApiClient.create(apiKey);
        } else {
            this.apiClient = null;
        }
    }

    /**
     * Get session ID.
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * Get all messages.
     */
    public List<Message> getMessages() {
        return new ArrayList<>(messages);
    }

    /**
     * Get turn count.
     */
    public int getTurnCount() {
        return turnCount.get();
    }

    /**
     * Get session state.
     */
    public SessionState getState() {
        return state;
    }

    /**
     * Send tool results and get response.
     */
    public Flux<Message> sendToolResults(List<ContentBlock.ToolResult> toolResults) {
        // Add user message with tool results
        List<ContentBlock> contentBlocks = new ArrayList<>(toolResults);
        messages.add(new Message.User(contentBlocks));
        turnCount.incrementAndGet();

        if (apiClient == null) {
            return Flux.just(new Message.Assistant(
                "No API key configured. Set ANTHROPIC_API_KEY environment variable or pass --api-key option."
            ));
        }

        // Build API request (with empty prompt since we're sending tool results)
        ApiRequest request = buildRequest("");

        // Make API call and convert response to Flux
        return Mono.fromFuture(apiClient.sendMessage(request))
            .map(this::convertResponse)
            .flux()
            .onErrorResume(e -> Flux.just(new Message.Assistant(
                "API Error: " + e.getMessage()
            )));
    }

    /**
     * Send a message and get response stream.
     */
    public Flux<Message> sendMessage(String prompt) {
        // Add user message
        messages.add(new Message.User(prompt));
        turnCount.incrementAndGet();

        if (apiClient == null) {
            // No API key - return informative message
            return Flux.just(new Message.Assistant(
                "No API key configured. Set ANTHROPIC_API_KEY environment variable or pass --api-key option."
            ));
        }

        // Build API request
        ApiRequest request = buildRequest(prompt);

        // Make API call and convert response to Flux
        return Mono.fromFuture(apiClient.sendMessage(request))
            .map(this::convertResponse)
            .flux()
            .onErrorResume(e -> Flux.just(new Message.Assistant(
                "API Error: " + e.getMessage()
            )));
    }

    /**
     * Send a message with TRUE streaming response.
     *
     * Each SSE event is emitted as soon as it arrives from the server.
     * Tool_use blocks are emitted immediately when content_block_stop is received.
     */
    public Flux<Message> sendMessageStreaming(String prompt) {
        messages.add(new Message.User(prompt));
        turnCount.incrementAndGet();

        if (apiClient == null) {
            return Flux.just(new Message.Assistant(
                "No API key configured. Set ANTHROPIC_API_KEY environment variable."
            ));
        }

        ApiRequest request = buildStreamingRequest(prompt);

        // TRUE streaming: use streamMessageFlux() which returns Flux<SseEvent>
        return apiClient.streamMessageFlux(request)
            .flatMap(event -> {
                // Convert SSE events to Messages
                Message msg = convertSseEventToMessage(event);
                if (msg != null) {
                    return Flux.just(msg);
                }
                return Flux.empty();
            })
            .onErrorResume(e -> Flux.just(new Message.Assistant(
                "API Error: " + e.getMessage()
            )));
    }

    /**
     * Convert SSE event to Message.
     *
     * Key events:
     * - text_complete: emits Assistant message with accumulated text
     * - tool_use_complete: emits Assistant message with ToolUse block
     * - message_stop: emits final Assistant message with all content
     */
    private Message convertSseEventToMessage(SseEvent event) {
        if (event == null) return null;

        // Skip raw delta events - we only emit on completion
        if (event.isContentBlockDelta()) return null;
        if (event.isContentBlockStart()) return null;
        if (event.isPing()) return null;

        // On text_complete, emit partial text
        if ("text_complete".equals(event.type())) {
            String text = event.text();
            if (text != null && !text.isEmpty()) {
                return new Message.Assistant(
                    java.util.List.of(new ContentBlock.Text(text))
                );
            }
        }

        // On tool_use_complete, emit the tool_use block
        if ("tool_use_complete".equals(event.type())) {
            String toolId = event.toolUseId();
            String toolName = event.toolName();
            Map<String, Object> input = event.input();
            if (toolId != null && toolName != null) {
                return new Message.Assistant(
                    java.util.List.of(new ContentBlock.ToolUse(toolId, toolName, input != null ? input : Map.of()))
                );
            }
        }

        // On message_stop, emit final message (empty placeholder for now)
        if (event.isMessageStop()) {
            // Final message will be accumulated in the caller
            return null;
        }

        return null;
    }

    /**
     * Build a non-streaming API request.
     */
    private ApiRequest buildRequest(String prompt) {
        List<Map<String, Object>> apiMessages = new ArrayList<>();
        for (Message msg : messages) {
            apiMessages.add(convertMessageToApiFormat(msg));
        }

        ApiRequest.Builder builder = ApiRequest.builder()
            .model(model)
            .maxTokens(4096)
            .messages(apiMessages);

        // Add tools if available
        if (tools != null && !tools.isEmpty()) {
            List<Map<String, Object>> toolsList = new ArrayList<>();
            for (Tool<?, ?, ?> tool : tools) {
                Map<String, Object> toolDef = new LinkedHashMap<>();
                toolDef.put("name", tool.name());
                toolDef.put("description", tool.description());
                toolDef.put("input_schema", tool.inputSchema());
                toolsList.add(toolDef);
            }
            builder.tools(toolsList);
        }

        // Add system prompt if available
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            builder.system(systemPrompt);
        }

        return builder.build();
    }

    /**
     * Build a streaming API request.
     */
    private ApiRequest buildStreamingRequest(String prompt) {
        List<Map<String, Object>> apiMessages = new ArrayList<>();
        for (Message msg : messages) {
            apiMessages.add(convertMessageToApiFormat(msg));
        }

        ApiRequest.Builder builder = ApiRequest.builder()
            .model(model)
            .maxTokens(4096)
            .messages(apiMessages)
            .stream(true);

        // Add tools if available
        if (tools != null && !tools.isEmpty()) {
            List<Map<String, Object>> toolsList = new ArrayList<>();
            for (Tool<?, ?, ?> tool : tools) {
                Map<String, Object> toolDef = new LinkedHashMap<>();
                toolDef.put("name", tool.name());
                toolDef.put("description", tool.description());
                toolDef.put("input_schema", tool.inputSchema());
                toolsList.add(toolDef);
            }
            builder.tools(toolsList);
        }

        // Add system prompt if available
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            builder.system(systemPrompt);
        }

        return builder.build();
    }

    /**
     * Convert internal message to API format.
     */
    private Map<String, Object> convertMessageToApiFormat(Message msg) {
        Map<String, Object> apiMsg = new LinkedHashMap<>();
        apiMsg.put("role", msg.role());

        if (msg instanceof Message.User userMsg) {
            // User message has List<ContentBlock> content
            List<ContentBlock> contentBlocks = userMsg.content();
            if (contentBlocks.size() == 1 && contentBlocks.get(0) instanceof ContentBlock.Text tb) {
                apiMsg.put("content", tb.text());
            } else {
                List<Map<String, Object>> contentList = new ArrayList<>();
                for (ContentBlock block : contentBlocks) {
                    contentList.add(convertContentBlock(block));
                }
                apiMsg.put("content", contentList);
            }
        } else if (msg instanceof Message.Assistant assistantMsg) {
            // Assistant message has List<ContentBlock> content
            List<ContentBlock> contentBlocks = assistantMsg.content();
            if (contentBlocks.size() == 1 && contentBlocks.get(0) instanceof ContentBlock.Text tb) {
                apiMsg.put("content", tb.text());
            } else {
                List<Map<String, Object>> contentList = new ArrayList<>();
                for (ContentBlock block : contentBlocks) {
                    contentList.add(convertContentBlock(block));
                }
                apiMsg.put("content", contentList);
            }
        }

        return apiMsg;
    }

    /**
     * Convert content block to API format.
     */
    private Map<String, Object> convertContentBlock(ContentBlock block) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (block instanceof ContentBlock.Text tb) {
            result.put("type", "text");
            result.put("text", tb.text());
        } else if (block instanceof ContentBlock.ToolUse tu) {
            result.put("type", "tool_use");
            result.put("id", tu.id());
            result.put("name", tu.name());
            result.put("input", tu.input());
        } else if (block instanceof ContentBlock.ToolResult tr) {
            result.put("type", "tool_result");
            result.put("tool_use_id", tr.toolUseId());
            result.put("content", tr.content());
            result.put("is_error", tr.isError());
        }
        return result;
    }

    /**
     * Convert API response to internal message.
     */
    private Message convertResponse(ApiResponse response) {
        List<ContentBlock> blocks = new ArrayList<>();
        for (ApiResponse.ContentBlock block : response.content()) {
            if (block instanceof ApiResponse.ContentBlock.TextBlock tb) {
                blocks.add(new ContentBlock.Text(tb.text()));
            } else if (block instanceof ApiResponse.ContentBlock.ToolUseBlock tb) {
                blocks.add(new ContentBlock.ToolUse(tb.id(), tb.name(), tb.input()));
            }
        }
        Message.Assistant assistantMsg = new Message.Assistant(blocks);
        messages.add(assistantMsg);
        return assistantMsg;
    }

    /**
     * Convert streaming response to messages.
     */
    private List<Message> convertStreamingResponse(ApiStreamingResponse response) {
        List<Message> result = new ArrayList<>();
        // Accumulate content from streaming events
        StringBuilder contentBuilder = new StringBuilder();

        // Get events from the streaming response
        try {
            List<Object> events = response.eventsFuture().get(30, java.util.concurrent.TimeUnit.SECONDS);
            for (Object event : events) {
                if (event instanceof Map eventMap) {
                    String type = (String) eventMap.get("type");
                    if ("content_block_delta".equals(type)) {
                        Object delta = eventMap.get("delta");
                        if (delta instanceof Map deltaMap) {
                            String text = (String) deltaMap.get("text");
                            if (text != null) contentBuilder.append(text);
                        }
                    }
                }
            }
        } catch (Exception e) {
            contentBuilder.append("Streaming error: ").append(e.getMessage());
        }

        Message.Assistant assistantMsg = new Message.Assistant(contentBuilder.toString());
        messages.add(assistantMsg);
        result.add(assistantMsg);
        return result;
    }

    /**
     * Reset the session.
     */
    public void reset() {
        messages.clear();
        turnCount.set(0);
        state = SessionState.ACTIVE;
    }

    /**
     * End the session.
     */
    public void end() {
        state = SessionState.ENDED;
        if (apiClient != null) {
            apiClient.close();
        }
    }

    /**
     * Session state enum.
     */
    public enum SessionState {
        ACTIVE,
        PAUSED,
        ENDED,
        ERROR
    }
}