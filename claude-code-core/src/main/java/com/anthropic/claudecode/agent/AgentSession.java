/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code agent session
 */
package com.anthropic.claudecode.agent;

import java.util.*;

/**
 * AgentSession - Manages agent conversation state.
 */
public class AgentSession {

    private final String agentId;
    private final String initialPrompt;
    private final AgentDefinition definition;
    private final String model;
    private final List<String> allowedTools;
    private final String workingDirectory;

    // Conversation state
    private final List<Map<String, Object>> messages;
    private final Map<String, Object> metadata;
    private int turnCount;

    // Tool tracking
    private final Set<String> toolsUsed;
    private final Map<String, Integer> toolCallCounts;

    public AgentSession(
            String agentId,
            String initialPrompt,
            AgentDefinition definition,
            String model,
            List<String> allowedTools,
            String workingDirectory) {

        this.agentId = agentId;
        this.initialPrompt = initialPrompt;
        this.definition = definition;
        this.model = model != null ? model : definition.defaultModel();
        this.allowedTools = allowedTools != null ? allowedTools : definition.defaultTools();
        this.workingDirectory = workingDirectory;

        this.messages = new ArrayList<>();
        this.metadata = new HashMap<>();
        this.turnCount = 0;
        this.toolsUsed = new HashSet<>();
        this.toolCallCounts = new HashMap<>();

        // Initialize with system prompt if defined
        initializeSession();
    }

    /**
     * Initialize session with first user message.
     */
    private void initializeSession() {
        // Add initial user prompt
        Map<String, Object> userMessage = new LinkedHashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", buildInitialContent());
        messages.add(userMessage);

        // Track metadata
        metadata.put("createdAt", System.currentTimeMillis());
        metadata.put("agentType", definition.type());
    }

    /**
     * Build initial content with context.
     */
    private String buildInitialContent() {
        StringBuilder sb = new StringBuilder();

        // Add working directory context
        if (workingDirectory != null) {
            sb.append("Working directory: ").append(workingDirectory).append("\n\n");
        }

        // Add agent-specific instructions
        if (definition.contextInstructions() != null) {
            sb.append(definition.contextInstructions()).append("\n\n");
        }

        // Add the actual prompt
        sb.append(initialPrompt);

        return sb.toString();
    }

    /**
     * Get all messages for API request.
     */
    public List<Map<String, Object>> getMessages() {
        return new ArrayList<>(messages);
    }

    /**
     * Add assistant message.
     */
    public void addAssistantMessage(String content) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "assistant");
        msg.put("content", content);
        messages.add(msg);
        turnCount++;
    }

    /**
     * Add assistant message with tool calls.
     */
    public void addAssistantMessageWithTools(String content, List<Map<String, Object>> toolCalls) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "assistant");

        // Build content blocks
        List<Map<String, Object>> contentBlocks = new ArrayList<>();
        if (content != null && !content.isEmpty()) {
            Map<String, Object> textBlock = new LinkedHashMap<>();
            textBlock.put("type", "text");
            textBlock.put("text", content);
            contentBlocks.add(textBlock);
        }

        // Add tool use blocks
        for (Map<String, Object> toolCall : toolCalls) {
            Map<String, Object> toolBlock = new LinkedHashMap<>();
            toolBlock.put("type", "tool_use");
            toolBlock.put("id", toolCall.get("id"));
            toolBlock.put("name", toolCall.get("name"));
            toolBlock.put("input", toolCall.get("input"));
            contentBlocks.add(toolBlock);

            // Track tool usage
            String toolName = (String) toolCall.get("name");
            toolsUsed.add(toolName);
            toolCallCounts.merge(toolName, 1, Integer::sum);
        }

        msg.put("content", contentBlocks);
        messages.add(msg);
        turnCount++;
    }

    /**
     * Add tool results as user message.
     */
    public void addToolResults(List<AgentToolResult> results) {
        List<Map<String, Object>> contentBlocks = new ArrayList<>();

        for (AgentToolResult result : results) {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "tool_result");
            block.put("tool_use_id", result.toolId());
            block.put("content", result.content());
            if (result.isError()) {
                block.put("is_error", true);
            }
            contentBlocks.add(block);
        }

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "user");
        msg.put("content", contentBlocks);
        messages.add(msg);
    }

    /**
     * Get agent ID.
     */
    public String agentId() {
        return agentId;
    }

    /**
     * Get model.
     */
    public String model() {
        return model;
    }

    /**
     * Get definition.
     */
    public AgentDefinition definition() {
        return definition;
    }

    /**
     * Get turn count.
     */
    public int turnCount() {
        return turnCount;
    }

    /**
     * Get tools used.
     */
    public Set<String> toolsUsed() {
        return new HashSet<>(toolsUsed);
    }

    /**
     * Get tool call counts.
     */
    public Map<String, Integer> toolCallCounts() {
        return new HashMap<>(toolCallCounts);
    }

    /**
     * Get metadata.
     */
    public Map<String, Object> metadata() {
        return new HashMap<>(metadata);
    }

    /**
     * Get total token usage (approximate).
     */
    public int estimateTokenCount() {
        int total = 0;
        for (Map<String, Object> msg : messages) {
            Object content = msg.get("content");
            if (content instanceof String s) {
                total += estimateTokens(s);
            } else if (content instanceof List<?> list) {
                for (Object block : list) {
                    if (block instanceof Map<?, ?> map) {
                        Object text = map.get("text");
                        if (text != null) {
                            total += estimateTokens(text.toString());
                        }
                    }
                }
            }
        }
        return total;
    }

    /**
     * Rough token estimation (4 chars per token).
     */
    private int estimateTokens(String text) {
        return text.length() / 4;
    }
}