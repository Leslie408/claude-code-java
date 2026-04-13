/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 * Java port of Claude Code - True SSE Streaming HTTP Client
 */
package com.anthropic.claudecode.services.api;

import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.net.URI;
import java.net.http.*;
import java.net.http.HttpRequest.BodyPublishers;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * HTTP Client with true SSE streaming support.
 *
 * Uses Java's Flow.Subscriber to receive SSE events line by line,
 * instead of waiting for the entire response.
 */
public class StreamingHttpClient {
    private final ApiClientConfig config;
    private final java.net.http.HttpClient httpClient;
    private final ExecutorService executor;
    private final String baseUrl;

    public StreamingHttpClient(ApiClientConfig config) {
        this.config = config;
        this.baseUrl = config.baseUrl() != null ? config.baseUrl() : "https://coding.dashscope.aliyuncs.com/v1";
        this.executor = Executors.newCachedThreadPool();
        this.httpClient = java.net.http.HttpClient.newBuilder()
            .version(java.net.http.HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(30))
            .executor(executor)
            .build();
    }

    /**
     * Get the appropriate API endpoint.
     */
    private String getMessagesEndpoint() {
        if (baseUrl.contains("/apps/anthropic")) {
            return "/v1/messages";
        }
        if (baseUrl.contains("dashscope") || baseUrl.contains("openai") || baseUrl.contains("localhost")) {
            return "/chat/completions";
        }
        return "/v1/messages";
    }

    /**
     * Check if using OpenAI-compatible format.
     */
    private boolean isOpenAIFormat() {
        return baseUrl.contains("dashscope") && !baseUrl.contains("/apps/anthropic")
            || baseUrl.contains("openai")
            || baseUrl.contains("localhost");
    }

    /**
     * True streaming POST - returns Flux of SSE events.
     * Each event is emitted as soon as it arrives from the server.
     */
    public Flux<SseEvent> streamPost(String path, ApiRequest request) {
        return Flux.create(sink -> {
            String json = serializeRequest(request);
            String endpoint = path.equals("/v1/messages") ? getMessagesEndpoint() : path;

            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + endpoint))
                .header("Content-Type", "application/json")
                .header("x-api-key", config.apiKey())
                .header("anthropic-version", "2023-06-01")
                .header("Accept", "text/event-stream")  // SSE
                .POST(BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(120))
                .build();

            // Create a subscriber that receives the raw byte stream
            SseByteSubscriber subscriber = new SseByteSubscriber(sink);

            // Use BodyHandlers.ofInputStream() for true streaming
            httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
                .thenAccept(response -> {
                    if (response.statusCode() >= 400) {
                        sink.error(new ApiException("API error: " + response.statusCode()));
                    } else {
                        // Read the stream in background thread
                        executor.submit(() -> {
                            try (java.io.InputStream is = response.body();
                                 java.io.BufferedReader reader = new java.io.BufferedReader(
                                     new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    subscriber.processLine(line);
                                }
                                sink.complete();
                            } catch (Exception e) {
                                sink.error(e);
                            }
                        });
                    }
                })
                .exceptionally(error -> {
                    sink.error(error);
                    return null;
                });
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    /**
     * SSE Byte Subscriber - processes each line as it arrives from InputStream.
     */
    private class SseByteSubscriber {
        private final FluxSink<SseEvent> sink;
        private StringBuilder eventBuilder = new StringBuilder();

        // Accumulators for reconstructing complete content
        private final Map<Integer, StringBuilder> textAccumulators = new ConcurrentHashMap<>();
        private final Map<Integer, StringBuilder> jsonAccumulators = new ConcurrentHashMap<>();
        private final Map<Integer, ToolUseBuilder> toolUseBuilders = new ConcurrentHashMap<>();
        private String currentMessageId;
        private String currentModel;

        SseByteSubscriber(FluxSink<SseEvent> sink) {
            this.sink = sink;
        }

        /**
         * Process a line from the SSE stream.
         */
        public void processLine(String line) {
            if (line == null || line.isEmpty()) {
                return;
            }

            // SSE format:
            // event: xxx
            // data: {...}
            // (empty line separates events)

            if (line.startsWith("data: ")) {
                String data = line.substring(6);

                // Check for [DONE] marker (OpenAI format)
                if ("[DONE]".equals(data)) {
                    sink.complete();
                    return;
                }

                // Parse the JSON data
                SseEvent event = parseSseData(data);
                if (event != null) {
                    // Emit raw event
                    sink.next(event);

                    // Also emit accumulated content events for convenience
                    emitAccumulatedContent(event);
                }
            } else if (line.startsWith("event: ")) {
                // Event type line - can be used for routing
            } else if (line.isEmpty()) {
                // Event boundary - flush if needed
            }
        }

        private SseEvent parseSseData(String data) {
            try {
                // Parse JSON without full parser (simplified)
                String type = extractField(data, "type");

                if ("message_start".equals(type)) {
                    // Extract message.id, message.model, message.usage
                    int msgStart = data.indexOf("\"message\":");
                    if (msgStart >= 0) {
                        int objStart = data.indexOf("{", msgStart);
                        int objEnd = findMatchingBracket(data, objStart);
                        if (objStart >= 0 && objEnd >= 0) {
                            String msgObj = data.substring(objStart, objEnd + 1);
                            currentMessageId = extractField(msgObj, "id");
                            currentModel = extractField(msgObj, "model");
                        }
                    }
                    return SseEvent.messageStart(currentMessageId, currentModel, null);
                }

                if ("content_block_start".equals(type)) {
                    String index = extractField(data, "index");

                    int contentBlockStart = data.indexOf("\"content_block\":");
                    if (contentBlockStart >= 0) {
                        int objStart = data.indexOf("{", contentBlockStart);
                        int objEnd = findMatchingBracket(data, objStart);
                        if (objStart >= 0 && objEnd >= 0) {
                            String cbObj = data.substring(objStart, objEnd + 1);
                            String cbType = extractField(cbObj, "type");

                            if ("text".equals(cbType)) {
                                textAccumulators.put(Integer.parseInt(index), new StringBuilder());
                                return SseEvent.textBlockStart(index, "text");
                            } else if ("tool_use".equals(cbType)) {
                                String toolId = extractField(cbObj, "id");
                                String toolName = extractField(cbObj, "name");
                                toolUseBuilders.put(Integer.parseInt(index), new ToolUseBuilder(toolId, toolName));
                                jsonAccumulators.put(Integer.parseInt(index), new StringBuilder());
                                return SseEvent.toolUseBlockStart(index, toolId, toolName);
                            }
                        }
                    }
                }

                if ("content_block_delta".equals(type)) {
                    String index = extractField(data, "index");
                    int deltaStart = data.indexOf("\"delta\":");
                    if (deltaStart >= 0) {
                        int objStart = data.indexOf("{", deltaStart);
                        int objEnd = findMatchingBracket(data, objStart);
                        if (objStart >= 0 && objEnd >= 0) {
                            String deltaObj = data.substring(objStart, objEnd + 1);
                            String deltaType = extractField(deltaObj, "type");

                            if ("text_delta".equals(deltaType)) {
                                String text = extractField(deltaObj, "text");
                                // Accumulate text
                                int idx = Integer.parseInt(index);
                                StringBuilder acc = textAccumulators.get(idx);
                                if (acc != null) {
                                    acc.append(text);
                                }
                                return SseEvent.textDelta(index, text);
                            } else if ("input_json_delta".equals(deltaType)) {
                                String partialJson = extractField(deltaObj, "partial_json");
                                // Accumulate JSON
                                int idx = Integer.parseInt(index);
                                StringBuilder acc = jsonAccumulators.get(idx);
                                if (acc != null) {
                                    acc.append(partialJson);
                                }
                                return SseEvent.inputJsonDelta(index, partialJson);
                            }
                        }
                    }
                }

                if ("content_block_stop".equals(type)) {
                    String index = extractField(data, "index");
                    int idx = Integer.parseInt(index);

                    // Finalize tool_use if complete
                    ToolUseBuilder builder = toolUseBuilders.get(idx);
                    StringBuilder jsonAcc = jsonAccumulators.get(idx);
                    if (builder != null && jsonAcc != null && jsonAcc.length() > 0) {
                        builder.setInput(parseJsonToMap(jsonAcc.toString()));
                    }

                    return new SseEvent("content_block_stop", index, null, null, null, null, null, null, null, null, null, null, null);
                }

                if ("message_delta".equals(type)) {
                    String stopReason = null;
                    Map<String, Object> usage = null;

                    int deltaStart = data.indexOf("\"delta\":");
                    if (deltaStart >= 0) {
                        int objStart = data.indexOf("{", deltaStart);
                        int objEnd = findMatchingBracket(data, objStart);
                        if (objStart >= 0 && objEnd >= 0) {
                            String deltaObj = data.substring(objStart, objEnd + 1);
                            stopReason = extractField(deltaObj, "stop_reason");
                        }
                    }

                    int usageStart = data.indexOf("\"usage\":");
                    if (usageStart >= 0) {
                        int objStart = data.indexOf("{", usageStart);
                        int objEnd = findMatchingBracket(data, objStart);
                        if (objStart >= 0 && objEnd >= 0) {
                            usage = parseJsonToMap(data.substring(objStart, objEnd + 1));
                        }
                    }

                    return SseEvent.messageDelta(stopReason, usage);
                }

                if ("message_stop".equals(type)) {
                    return SseEvent.messageStop();
                }

                if ("ping".equals(type)) {
                    return new SseEvent("ping", null, null, null, null, null, null, null, null, null, null, null, null);
                }

                if ("error".equals(type)) {
                    int errorStart = data.indexOf("\"error\":");
                    if (errorStart >= 0) {
                        int objStart = data.indexOf("{", errorStart);
                        int objEnd = findMatchingBracket(data, objStart);
                        if (objStart >= 0 && objEnd >= 0) {
                            String errorObj = data.substring(objStart, objEnd + 1);
                            String message = extractField(errorObj, "message");
                            return SseEvent.error(message);
                        }
                    }
                }

                // OpenAI format parsing
                if (data.contains("\"choices\"")) {
                    return parseOpenAISseData(data);
                }

                return SseEvent.empty();

            } catch (Exception e) {
                return SseEvent.error(e.getMessage());
            }
        }

        /**
         * Parse OpenAI SSE format (dashscope, etc).
         */
        private SseEvent parseOpenAISseData(String data) {
            // OpenAI format: {"choices":[{"delta":{"content":"..."}}]}
            int choicesStart = data.indexOf("\"choices\":");
            if (choicesStart < 0) return SseEvent.empty();

            int arrayStart = data.indexOf("[", choicesStart);
            int arrayEnd = findMatchingBracket(data, arrayStart);
            if (arrayStart < 0 || arrayEnd < 0) return SseEvent.empty();

            String choicesArray = data.substring(arrayStart, arrayEnd + 1);

            // Find first choice
            int choiceStart = choicesArray.indexOf("{");
            int choiceEnd = findMatchingBracket(choicesArray, choiceStart);
            if (choiceStart < 0 || choiceEnd < 0) return SseEvent.empty();

            String choiceObj = choicesArray.substring(choiceStart, choiceEnd + 1);

            // Extract delta
            int deltaStart = choiceObj.indexOf("\"delta\":");
            if (deltaStart >= 0) {
                int objStart = choiceObj.indexOf("{", deltaStart);
                int objEnd = findMatchingBracket(choiceObj, objStart);
                if (objStart >= 0 && objEnd >= 0) {
                    String deltaObj = choiceObj.substring(objStart, objEnd + 1);

                    // Content
                    String content = extractField(deltaObj, "content");
                    if (content != null && !content.isEmpty()) {
                        // Use index 0 for OpenAI format
                        StringBuilder acc = textAccumulators.get(0);
                        if (acc == null) {
                            acc = new StringBuilder();
                            textAccumulators.put(0, acc);
                        }
                        acc.append(content);
                        return SseEvent.textDelta("0", content);
                    }

                    // Tool calls
                    int toolCallsStart = deltaObj.indexOf("\"tool_calls\":");
                    if (toolCallsStart >= 0) {
                        // Parse tool calls delta
                        return new SseEvent("content_block_delta", "0", "tool_use", "input_json_delta", null, null, null, null, null, null, null, null, null);
                    }
                }
            }

            // Finish reason
            String finishReason = extractField(choiceObj, "finish_reason");
            if (finishReason != null && !finishReason.isEmpty()) {
                return SseEvent.messageDelta(finishReason, null);
            }

            return SseEvent.empty();
        }

        /**
         * Emit accumulated content events for convenience.
         */
        private void emitAccumulatedContent(SseEvent event) {
            // On content_block_stop, emit accumulated text if available
            if ("content_block_stop".equals(event.type()) && event.index() != null) {
                int idx = Integer.parseInt(event.index());
                StringBuilder textAcc = textAccumulators.get(idx);
                if (textAcc != null && textAcc.length() > 0) {
                    // Emit a complete text event
                    sink.next(new SseEvent("text_complete", event.index(), "text", null, textAcc.toString(), null, null, null, null, null, null, null, null));
                }

                ToolUseBuilder builder = toolUseBuilders.get(idx);
                if (builder != null) {
                    // Emit a complete tool_use event
                    sink.next(new SseEvent("tool_use_complete", event.index(), "tool_use", null, null, null, builder.id, builder.name, builder.input, null, null, null, null));
                }
            }
        }

        // JSON parsing helpers
        private String extractField(String json, String field) {
            String pattern = "\"" + field + "\"";
            int idx = json.indexOf(pattern);
            if (idx < 0) return null;

            int colon = json.indexOf(":", idx);
            if (colon < 0) return null;

            int start = colon + 1;
            while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\n')) start++;

            if (start >= json.length()) return null;

            if (json.charAt(start) == '"') {
                start++;
                int end = start;
                while (end < json.length()) {
                    if (json.charAt(end) == '"' && json.charAt(end - 1) != '\\') break;
                    end++;
                }
                return json.substring(start, end);
            } else {
                int end = start;
                while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}' && json.charAt(end) != ']') end++;
                return json.substring(start, end).trim();
            }
        }

        private int findMatchingBracket(String s, int start) {
            int depth = 0;
            for (int i = start; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '{' || c == '[') depth++;
                else if (c == '}' || c == ']') {
                    depth--;
                    if (depth == 0) return i;
                }
            }
            return -1;
        }

        private Map<String, Object> parseJsonToMap(String json) {
            Map<String, Object> result = new LinkedHashMap<>();
            if (json == null || json.isEmpty() || !json.startsWith("{")) return result;

            json = json.substring(1, json.length() - 1).trim();
            if (json.isEmpty()) return result;

            int depth = 0;
            StringBuilder current = new StringBuilder();
            String currentKey = null;
            boolean inString = false;

            for (int i = 0; i < json.length(); i++) {
                char c = json.charAt(i);

                if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                    inString = !inString;
                    current.append(c);
                } else if (!inString) {
                    if (c == '{' || c == '[') {
                        depth++;
                        current.append(c);
                    } else if (c == '}' || c == ']') {
                        depth--;
                        current.append(c);
                    } else if (depth == 0 && c == ':') {
                        currentKey = current.toString().trim();
                        if (currentKey.startsWith("\"") && currentKey.endsWith("\"")) {
                            currentKey = currentKey.substring(1, currentKey.length() - 1);
                        }
                        current = new StringBuilder();
                    } else if (depth == 0 && c == ',') {
                        if (currentKey != null) {
                            result.put(currentKey, parseValue(current.toString().trim()));
                        }
                        currentKey = null;
                        current = new StringBuilder();
                    } else {
                        current.append(c);
                    }
                } else {
                    current.append(c);
                }
            }

            if (currentKey != null) {
                result.put(currentKey, parseValue(current.toString().trim()));
            }

            return result;
        }

        private Object parseValue(String value) {
            if (value == null || value.isEmpty()) return null;
            value = value.trim();
            if (value.startsWith("\"") && value.endsWith("\"")) {
                return value.substring(1, value.length() - 1)
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")
                    .replace("\\t", "\t")
                    .replace("\\\\", "\\");
            }
            if (value.startsWith("{")) return parseJsonToMap(value);
            if ("true".equals(value)) return true;
            if ("false".equals(value)) return false;
            if ("null".equals(value)) return null;
            try {
                if (value.contains(".")) return Double.parseDouble(value);
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                return value;
            }
        }
    }

    /**
     * Helper class to build ToolUse blocks.
     */
    private static class ToolUseBuilder {
        final String id;
        final String name;
        Map<String, Object> input;

        ToolUseBuilder(String id, String name) {
            this.id = id;
            this.name = name;
        }

        void setInput(Map<String, Object> input) {
            this.input = input;
        }
    }

    // ==================== Request Serialization ====================

    private String serializeRequest(ApiRequest req) {
        if (isOpenAIFormat()) {
            return serializeOpenAIRequest(req);
        }
        return serializeAnthropicRequest(req);
    }

    private String serializeAnthropicRequest(ApiRequest req) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"model\":\"").append(escapeJson(req.model())).append("\"");
        sb.append(",\"max_tokens\":").append(req.maxTokens());
        sb.append(",\"messages\":");
        if (req.messages() == null || req.messages().isEmpty()) {
            sb.append("[]");
        } else {
            sb.append("[");
            for (int i = 0; i < req.messages().size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(toJson(req.messages().get(i)));
            }
            sb.append("]");
        }
        if (req.system() != null) {
            sb.append(",\"system\":\"").append(escapeJson(req.system())).append("\"");
        }
        if (req.tools() != null && !req.tools().isEmpty()) {
            sb.append(",\"tools\":");
            sb.append("[");
            for (int i = 0; i < req.tools().size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(toJson(req.tools().get(i)));
            }
            sb.append("]");
        }
        sb.append(",\"stream\":true");
        if (req.temperature() != null) {
            sb.append(",\"temperature\":").append(req.temperature());
        }
        sb.append("}");
        return sb.toString();
    }

    private String serializeOpenAIRequest(ApiRequest req) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"model\":\"").append(escapeJson(req.model())).append("\"");
        sb.append(",\"messages\":[");

        if (req.system() != null && !req.system().isEmpty()) {
            sb.append("{\"role\":\"system\",\"content\":\"").append(escapeJson(req.system())).append("\"}");
            if (req.messages() != null && !req.messages().isEmpty()) {
                sb.append(",");
            }
        }

        if (req.messages() != null && !req.messages().isEmpty()) {
            for (int i = 0; i < req.messages().size(); i++) {
                if (i > 0) sb.append(",");
                Map<String, Object> msg = req.messages().get(i);
                sb.append(toJson(msg));
            }
        }
        sb.append("]");

        if (req.tools() != null && !req.tools().isEmpty()) {
            sb.append(",\"tools\":");
            sb.append("[");
            for (int i = 0; i < req.tools().size(); i++) {
                if (i > 0) sb.append(",");
                Map<String, Object> tool = req.tools().get(i);
                sb.append("{\"type\":\"function\",\"function\":");
                sb.append("{\"name\":\"").append(escapeJson((String) tool.get("name"))).append("\"");
                if (tool.get("description") != null) {
                    sb.append(",\"description\":\"").append(escapeJson((String) tool.get("description"))).append("\"");
                }
                if (tool.get("input_schema") != null) {
                    sb.append(",\"parameters\":").append(toJson(tool.get("input_schema")));
                }
                sb.append("}}");
            }
            sb.append("]");
        }

        sb.append(",\"stream\":true");
        if (req.maxTokens() > 0) {
            sb.append(",\"max_tokens\":").append(req.maxTokens());
        }
        if (req.temperature() != null) {
            sb.append(",\"temperature\":").append(req.temperature());
        }
        sb.append("}");
        return sb.toString();
    }

    private String toJson(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof String s) return "\"" + escapeJson(s) + "\"";
        if (obj instanceof Number n) return n.toString();
        if (obj instanceof Boolean b) return b.toString();
        if (obj instanceof Map m) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Object key : m.keySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(key).append("\":").append(toJson(m.get(key)));
                first = false;
            }
            sb.append("}");
            return sb.toString();
        }
        if (obj instanceof List l) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(toJson(l.get(i)));
            }
            sb.append("]");
            return sb.toString();
        }
        return "\"" + escapeJson(obj.toString()) + "\"";
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Close the client.
     */
    public void close() {
        executor.shutdown();
    }
}