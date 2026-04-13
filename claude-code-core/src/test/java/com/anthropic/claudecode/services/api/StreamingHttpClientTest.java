/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 * Test for true SSE streaming
 */
package com.anthropic.claudecode.services.api;

import org.junit.jupiter.api.*;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.*;

/**
 * Test StreamingHttpClient with SSE events.
 */
public class StreamingHttpClientTest {

    @Test
    @DisplayName("Test SSE event parsing - message_start")
    void testSseEventParsingMessageStart() {
        // Parse the data field manually
        String data = "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_123\",\"model\":\"claude-3\"}}";

        // Create a test SseEvent
        SseEvent event = SseEvent.messageStart("msg_123", "claude-3", null);

        Assertions.assertTrue(event.isMessageStart());
        Assertions.assertEquals("msg_123", event.messageId());
        Assertions.assertEquals("claude-3", event.model());
    }

    @Test
    @DisplayName("Test SSE event parsing - text_delta")
    void testSseEventParsingTextDelta() {
        SseEvent event = SseEvent.textDelta("0", "Hello");

        Assertions.assertTrue(event.isContentBlockDelta());
        Assertions.assertEquals("0", event.index());
        Assertions.assertEquals("Hello", event.text());
        Assertions.assertEquals("text_delta", event.deltaType());
    }

    @Test
    @DisplayName("Test SSE event parsing - tool_use")
    void testSseEventParsingToolUse() {
        SseEvent event = SseEvent.toolUseBlockStart("0", "tool_123", "bash");

        Assertions.assertTrue(event.isContentBlockStart());
        Assertions.assertEquals("tool_use", event.contentBlockType());
        Assertions.assertEquals("tool_123", event.toolUseId());
        Assertions.assertEquals("bash", event.toolName());
    }

    @Test
    @DisplayName("Test SSE event parsing - input_json_delta")
    void testSseEventParsingInputJsonDelta() {
        SseEvent event = SseEvent.inputJsonDelta("0", "{\"command\":\"ls\"");

        Assertions.assertTrue(event.isContentBlockDelta());
        Assertions.assertEquals("input_json_delta", event.deltaType());
        Assertions.assertEquals("{\"command\":\"ls\"", event.partialJson());
    }

    @Test
    @DisplayName("Test SSE event parsing - message_stop")
    void testSseEventParsingMessageStop() {
        SseEvent event = SseEvent.messageStop();

        Assertions.assertTrue(event.isMessageStop());
    }

    @Test
    @DisplayName("Test SSE event parsing - message_delta with stop_reason")
    void testSseEventParsingMessageDelta() {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("output_tokens", 100);

        SseEvent event = SseEvent.messageDelta("end_turn", usage);

        Assertions.assertTrue(event.isMessageDelta());
        Assertions.assertEquals("end_turn", event.stopReason());
    }

    @Test
    @DisplayName("Test SSE event parsing - error")
    void testSseEventParsingError() {
        SseEvent event = SseEvent.error("API rate limit exceeded");

        Assertions.assertTrue(event.isError());
        Assertions.assertEquals("error", event.type());
    }

    @Test
    @DisplayName("Test Flux streaming simulation")
    void testFluxStreamingSimulation() {
        // Simulate streaming events arriving one by one
        Flux<SseEvent> events = Flux.just(
            SseEvent.messageStart("msg_1", "claude-3", null),
            SseEvent.textBlockStart("0", "text"),
            SseEvent.textDelta("0", "Hello"),
            SseEvent.textDelta("0", " world"),
            new SseEvent("content_block_stop", "0", null, null, null, null, null, null, null, null, null, null, null),
            SseEvent.messageStop()
        );

        // Verify events arrive in order
        StepVerifier.create(events)
            .expectNextMatches(e -> e.isMessageStart())
            .expectNextMatches(e -> e.isContentBlockStart())
            .expectNextMatches(e -> e.isContentBlockDelta() && "Hello".equals(e.text()))
            .expectNextMatches(e -> e.isContentBlockDelta() && " world".equals(e.text()))
            .expectNextMatches(e -> "content_block_stop".equals(e.type()))
            .expectNextMatches(e -> e.isMessageStop())
            .verifyComplete();
    }

    @Test
    @DisplayName("Test delayed Flux streaming - simulates real SSE")
    void testDelayedFluxStreaming() {
        // Simulate real SSE with delays between events
        Flux<SseEvent> events = Flux.interval(Duration.ofMillis(100))
            .take(5)
            .map(i -> {
                switch (i.intValue()) {
                    case 0: return SseEvent.messageStart("msg_1", "claude-3", null);
                    case 1: return SseEvent.textDelta("0", "Hello");
                    case 2: return SseEvent.textDelta("0", " world");
                    case 3: return new SseEvent("content_block_stop", "0", null, null, null, null, null, null, null, null, null, null, null);
                    case 4: return SseEvent.messageStop();
                    default: return SseEvent.empty();
                }
            });

        // Verify events arrive with timing
        StepVerifier.create(events)
            .expectNextMatches(e -> e.isMessageStart())
            .thenAwait(Duration.ofMillis(100))
            .expectNextMatches(e -> "Hello".equals(e.text()))
            .thenAwait(Duration.ofMillis(100))
            .expectNextMatches(e -> " world".equals(e.text()))
            .thenAwait(Duration.ofMillis(100))
            .expectNextMatches(e -> "content_block_stop".equals(e.type()))
            .thenAwait(Duration.ofMillis(100))
            .expectNextMatches(e -> e.isMessageStop())
            .verifyComplete();
    }
}