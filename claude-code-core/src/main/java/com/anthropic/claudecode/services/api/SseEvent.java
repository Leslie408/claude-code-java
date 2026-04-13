/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 * Java port of Claude Code services/api - SSE Streaming Event
 */
package com.anthropic.claudecode.services.api;

import java.util.Map;

/**
 * SSE Streaming Event from API.
 */
public record SseEvent(
    String type,
    String index,       // content_block_index
    String contentBlockType,  // type of content block (text, tool_use)
    String deltaType,   // type of delta (text_delta, input_json_delta)
    String text,        // text content for text_delta
    String partialJson, // partial JSON for input_json_delta
    String toolUseId,   // tool use id
    String toolName,    // tool name
    Map<String, Object> input,  // tool input (parsed when complete)
    String stopReason,  // end_turn, tool_use, etc.
    String messageId,   // message id
    String model,       // model name
    Map<String, Object> usage  // usage stats
) {
    /**
     * Check if this is a message_start event.
     */
    public boolean isMessageStart() {
        return "message_start".equals(type);
    }

    /**
     * Check if this is a content_block_start event.
     */
    public boolean isContentBlockStart() {
        return "content_block_start".equals(type);
    }

    /**
     * Check if this is a content_block_delta event.
     */
    public boolean isContentBlockDelta() {
        return "content_block_delta".equals(type);
    }

    /**
     * Check if this is a content_block_stop event.
     */
    public boolean isContentBlockStop() {
        return "content_block_stop".equals(type);
    }

    /**
     * Check if this is a message_delta event.
     */
    public boolean isMessageDelta() {
        return "message_delta".equals(type);
    }

    /**
     * Check if this is a message_stop event.
     */
    public boolean isMessageStop() {
        return "message_stop".equals(type);
    }

    /**
     * Check if this is a ping event.
     */
    public boolean isPing() {
        return "ping".equals(type);
    }

    /**
     * Check if this is an error event.
     */
    public boolean isError() {
        return "error".equals(type);
    }

    /**
     * Create an empty event.
     */
    public static SseEvent empty() {
        return new SseEvent(null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Create a message_start event.
     */
    public static SseEvent messageStart(String messageId, String model, Map<String, Object> usage) {
        return new SseEvent("message_start", null, null, null, null, null, null, null, null, null, messageId, model, usage);
    }

    /**
     * Create a content_block_start event (text).
     */
    public static SseEvent textBlockStart(String index, String contentBlockType) {
        return new SseEvent("content_block_start", index, contentBlockType, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Create a content_block_start event (tool_use).
     */
    public static SseEvent toolUseBlockStart(String index, String toolUseId, String toolName) {
        return new SseEvent("content_block_start", index, "tool_use", null, null, null, toolUseId, toolName, null, null, null, null, null);
    }

    /**
     * Create a content_block_delta event (text).
     */
    public static SseEvent textDelta(String index, String text) {
        return new SseEvent("content_block_delta", index, null, "text_delta", text, null, null, null, null, null, null, null, null);
    }

    /**
     * Create a content_block_delta event (input_json_delta).
     */
    public static SseEvent inputJsonDelta(String index, String partialJson) {
        return new SseEvent("content_block_delta", index, "tool_use", "input_json_delta", null, partialJson, null, null, null, null, null, null, null);
    }

    /**
     * Create a message_stop event.
     */
    public static SseEvent messageStop() {
        return new SseEvent("message_stop", null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Create a message_delta event with stop_reason.
     */
    public static SseEvent messageDelta(String stopReason, Map<String, Object> usage) {
        return new SseEvent("message_delta", null, null, null, null, null, null, null, null, stopReason, null, null, usage);
    }

    /**
     * Create an error event.
     */
    public static SseEvent error(String message) {
        return new SseEvent("error", null, null, null, message, null, null, null, null, null, null, null, null);
    }
}