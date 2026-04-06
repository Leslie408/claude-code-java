/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 * Java port of Claude Code services/api
 */
package com.anthropic.claudecode.services.api;

import java.net.URI;
import java.net.http.*;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * HTTP Client for API calls using Java 11+ HttpClient.
 */
public class HttpClient {
    private final ApiClientConfig config;
    private final java.net.http.HttpClient httpClient;
    private final ExecutorService executor;
    private final String baseUrl;

    public HttpClient(ApiClientConfig config) {
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
     * Get the appropriate API endpoint based on baseUrl.
     */
    private String getMessagesEndpoint() {
        // Anthropic-compatible endpoint (apps/anthropic)
        if (baseUrl.contains("/apps/anthropic")) {
            return "/v1/messages";
        }
        // OpenAI-compatible APIs (dashscope /v1, openai, localhost)
        if (baseUrl.contains("dashscope") || baseUrl.contains("openai") || baseUrl.contains("localhost")) {
            return "/chat/completions";
        }
        // Default to Anthropic format
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
     * POST request with JSON body.
     */
    public CompletableFuture<ApiResponse> post(String path, Object body) {
        String json = serializeBody(body);

        // Use appropriate endpoint for the API type
        String endpoint = path.equals("/v1/messages") ? getMessagesEndpoint() : path;

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + endpoint))
            .header("Content-Type", "application/json")
            .header("x-api-key", config.apiKey())
            .header("anthropic-version", "2023-06-01")
            .POST(BodyPublishers.ofString(json))
            .timeout(Duration.ofSeconds(60))
            .build();

        return httpClient.sendAsync(request, BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() >= 400) {
                    throw new ApiException("API error: " + response.statusCode() + " - " + response.body());
                }
                return parseResponse(response.body());
            });
    }

    /**
     * Streaming POST request.
     */
    public CompletableFuture<ApiStreamingResponse> streamPost(String path, Object body) {
        String json = serializeBody(body);

        // Use appropriate endpoint for the API type
        String endpoint = path.equals("/v1/messages") ? getMessagesEndpoint() : path;

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + endpoint))
            .header("Content-Type", "application/json")
            .header("x-api-key", config.apiKey())
            .header("anthropic-version", "2023-06-01")
            .header("Accept", "text/event-stream")
            .POST(BodyPublishers.ofString(json))
            .timeout(Duration.ofSeconds(120))
            .build();

        return httpClient.sendAsync(request, BodyHandlers.ofString())
            .thenApply(response -> {
                String messageId = extractMessageId(response.body());
                List<Object> events = parseStreamingEvents(response.body());
                return new ApiStreamingResponse(messageId, CompletableFuture.completedFuture(events));
            });
    }

    /**
     * Close the client.
     */
    public void close() {
        executor.shutdown();
    }

    // ==================== Private Helpers ====================

    private String serializeBody(Object body) {
        if (body == null) return "{}";
        if (body instanceof ApiRequest req) {
            // Check if using OpenAI-compatible format
            if (isOpenAIFormat()) {
                return serializeOpenAIRequest(req);
            }
            return serializeApiRequest(req);
        }
        return toJson(body);
    }

    /**
     * Serialize for OpenAI-compatible APIs (dashscope, etc).
     */
    private String serializeOpenAIRequest(ApiRequest req) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"model\":\"").append(escapeJson(req.model())).append("\"");

        // Messages - OpenAI format
        sb.append(",\"messages\":[");

        // Add system message first if present
        if (req.system() != null && !req.system().isEmpty()) {
            sb.append("{\"role\":\"system\",\"content\":\"").append(escapeJson(req.system())).append("\"}");
            if (req.messages() != null && !req.messages().isEmpty()) {
                sb.append(",");
            }
        }

        // Add user/assistant messages
        if (req.messages() != null && !req.messages().isEmpty()) {
            boolean first = true;
            for (int i = 0; i < req.messages().size(); i++) {
                Map<String, Object> msg = req.messages().get(i);
                String role = (String) msg.get("role");

                // Handle content - check if it contains tool_result blocks
                Object contentObj = msg.get("content");

                // Check for tool_result in content list (for OpenAI format, these need to be separate messages)
                if (contentObj instanceof List<?> parts) {
                    for (Object part : parts) {
                        if (part instanceof Map<?, ?> partMap) {
                            String partType = (String) partMap.get("type");
                            if ("tool_result".equals(partType)) {
                                // Convert to OpenAI tool message format
                                if (!first) sb.append(",");
                                first = false;

                                String toolUseId = (String) partMap.get("tool_use_id");
                                Object toolContent = partMap.get("content");

                                sb.append("{\"role\":\"tool\"");
                                sb.append(",\"tool_call_id\":\"").append(escapeJson(toolUseId != null ? toolUseId : "")).append("\"");
                                sb.append(",\"content\":");
                                if (toolContent instanceof String str) {
                                    sb.append("\"").append(escapeJson(str)).append("\"");
                                } else {
                                    sb.append(toJson(toolContent));
                                }
                                sb.append("}");
                            }
                        }
                    }
                    // Also handle non-tool_result parts as regular message
                    List<Object> nonToolParts = new ArrayList<>();
                    for (Object part : parts) {
                        if (part instanceof Map<?, ?> partMap) {
                            String partType = (String) partMap.get("type");
                            if (!"tool_result".equals(partType)) {
                                nonToolParts.add(part);
                            }
                        } else {
                            nonToolParts.add(part);
                        }
                    }
                    if (!nonToolParts.isEmpty()) {
                        if (!first) sb.append(",");
                        first = false;
                        sb.append("{\"role\":\"").append(escapeJson(role != null ? role : "user")).append("\"");
                        sb.append(",\"content\":").append(toJson(nonToolParts));
                        sb.append("}");
                    }
                } else {
                    // Regular message (not a list of content blocks with tool_result)
                    if (!first) sb.append(",");
                    first = false;

                    sb.append("{\"role\":\"").append(escapeJson(role != null ? role : "user")).append("\"");

                    if (contentObj instanceof String text) {
                        sb.append(",\"content\":\"").append(escapeJson(text)).append("\"");
                    } else if (contentObj != null) {
                        sb.append(",\"content\":\"").append(escapeJson(contentObj.toString())).append("\"");
                    }

                    // Handle tool calls in assistant message
                    Object toolCallsObj = msg.get("tool_calls");
                    if (toolCallsObj instanceof List<?> toolCalls) {
                        sb.append(",\"tool_calls\":[");
                        for (int j = 0; j < toolCalls.size(); j++) {
                            if (j > 0) sb.append(",");
                            if (toolCalls.get(j) instanceof Map<?, ?> tc) {
                                String tcId = (String) tc.get("id");
                                String tcType = (String) tc.get("type");
                                if (tcType == null) tcType = "function";

                                Object funcObj = tc.get("function");
                                if (funcObj instanceof Map<?, ?> func) {
                                    String funcName = (String) func.get("name");
                                    Object funcArgs = func.get("arguments");

                                    sb.append("{\"id\":\"").append(escapeJson(tcId != null ? tcId : "")).append("\"");
                                    sb.append(",\"type\":\"").append(tcType).append("\"");
                                    sb.append(",\"function\":{\"name\":\"").append(escapeJson(funcName != null ? funcName : "")).append("\"");
                                    sb.append(",\"arguments\":").append(funcArgs != null ? funcArgs.toString() : "{}");
                                    sb.append("}}");
                                }
                            }
                        }
                        sb.append("]");
                    }

                    sb.append("}");
                }
            }
        }
        sb.append("]");

        // Tools - OpenAI format (different from Anthropic)
        if (req.tools() != null && !req.tools().isEmpty()) {
            sb.append(",\"tools\":[");
            for (int i = 0; i < req.tools().size(); i++) {
                if (i > 0) sb.append(",");
                Map<String, Object> tool = req.tools().get(i);
                String toolName = (String) tool.get("name");
                String toolDesc = (String) tool.get("description");
                @SuppressWarnings("unchecked")
                Map<String, Object> inputSchema = (Map<String, Object>) tool.get("input_schema");

                sb.append("{\"type\":\"function\"");
                sb.append(",\"function\":{\"name\":\"").append(escapeJson(toolName != null ? toolName : "")).append("\"");
                if (toolDesc != null) {
                    sb.append(",\"description\":\"").append(escapeJson(toolDesc)).append("\"");
                }
                if (inputSchema != null) {
                    sb.append(",\"parameters\":").append(toJson(inputSchema));
                }
                sb.append("}}");
            }
            sb.append("]");
        }

        // Stream
        if (req.stream()) {
            sb.append(",\"stream\":true");
        }

        // Temperature
        if (req.temperature() != null) {
            sb.append(",\"temperature\":").append(req.temperature());
        }

        // Max tokens
        if (req.maxTokens() > 0) {
            sb.append(",\"max_tokens\":").append(req.maxTokens());
        }

        sb.append("}");
        return sb.toString();
    }

    private String serializeApiRequest(ApiRequest req) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"model\":\"").append(escapeJson(req.model())).append("\"");
        sb.append(",\"max_tokens\":").append(req.maxTokens());

        // Messages
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

        // System
        if (req.system() != null) {
            sb.append(",\"system\":\"").append(escapeJson(req.system())).append("\"");
        }

        // Tools
        if (req.tools() != null && !req.tools().isEmpty()) {
            sb.append(",\"tools\":");
            sb.append("[");
            for (int i = 0; i < req.tools().size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(toJson(req.tools().get(i)));
            }
            sb.append("]");
        }

        // Stream
        if (req.stream()) {
            sb.append(",\"stream\":true");
        }

        // Temperature
        if (req.temperature() != null) {
            sb.append(",\"temperature\":").append(req.temperature());
        }

        sb.append("}");
        return sb.toString();
    }

    private ApiResponse parseResponse(String body) {
        // Detect response format (OpenAI vs Anthropic)
        if (isOpenAIFormat() || body.contains("\"choices\"")) {
            return parseOpenAIResponse(body);
        }
        return parseAnthropicResponse(body);
    }

    /**
     * Parse OpenAI-compatible response (used by dashscope).
     */
    private ApiResponse parseOpenAIResponse(String body) {
        String id = extractField(body, "id");
        String model = extractField(body, "model");

        // Parse choices array
        List<ApiResponse.ContentBlock> content = new ArrayList<>();
        int choicesStart = body.indexOf("\"choices\":");
        if (choicesStart >= 0) {
            int arrayStart = body.indexOf("[", choicesStart);
            int arrayEnd = findMatchingBracket(body, arrayStart);
            if (arrayStart >= 0 && arrayEnd >= 0) {
                String choicesArray = body.substring(arrayStart, arrayEnd + 1);

                // Find message content in first choice
                int msgStart = choicesArray.indexOf("\"message\":");
                if (msgStart >= 0) {
                    int objStart = choicesArray.indexOf("{", msgStart);
                    int objEnd = findMatchingBracket(choicesArray, objStart);
                    if (objStart >= 0 && objEnd >= 0) {
                        String msgObj = choicesArray.substring(objStart, objEnd + 1);

                        // Parse text content
                        String msgContent = extractField(msgObj, "content");
                        if (msgContent != null && !msgContent.isEmpty()) {
                            content.add(new ApiResponse.ContentBlock.TextBlock(msgContent));
                        }

                        // Parse tool_calls (OpenAI format)
                        int toolCallsStart = msgObj.indexOf("\"tool_calls\":");
                        if (toolCallsStart >= 0) {
                            int tcArrayStart = msgObj.indexOf("[", toolCallsStart);
                            int tcArrayEnd = findMatchingBracket(msgObj, tcArrayStart);
                            if (tcArrayStart >= 0 && tcArrayEnd >= 0) {
                                String tcArray = msgObj.substring(tcArrayStart, tcArrayEnd + 1);

                                // Parse each tool call
                                int i = 1;
                                while (i < tcArray.length()) {
                                    int tcStart = tcArray.indexOf("{", i);
                                    if (tcStart < 0) break;
                                    int tcEnd = findMatchingBracket(tcArray, tcStart);
                                    if (tcEnd < 0) break;

                                    String tcObj = tcArray.substring(tcStart, tcEnd + 1);
                                    String tcId = extractField(tcObj, "id");

                                    // Parse function object
                                    int funcStart = tcObj.indexOf("\"function\":");
                                    if (funcStart >= 0) {
                                        int funcObjStart = tcObj.indexOf("{", funcStart);
                                        int funcObjEnd = findMatchingBracket(tcObj, funcObjStart);
                                        if (funcObjStart >= 0 && funcObjEnd >= 0) {
                                            String funcObj = tcObj.substring(funcObjStart, funcObjEnd + 1);
                                            String funcName = extractField(funcObj, "name");
                                            String funcArgs = extractField(funcObj, "arguments");

                                            // Parse arguments as Map
                                            Map<String, Object> argsMap = parseArgumentsMap(funcArgs);

                                            content.add(new ApiResponse.ContentBlock.ToolUseBlock(
                                                tcId, funcName, argsMap
                                            ));
                                        }
                                    }

                                    i = tcEnd + 1;
                                }
                            }
                        }
                    }
                }
            }
        }

        // Parse usage
        int usageStart = body.indexOf("\"usage\":");
        int inputTokens = 0, outputTokens = 0;
        if (usageStart >= 0) {
            int objStart = body.indexOf("{", usageStart);
            int objEnd = findMatchingBracket(body, objStart);
            if (objStart >= 0 && objEnd >= 0) {
                String usageObj = body.substring(objStart, objEnd + 1);
                inputTokens = extractIntField(usageObj, "prompt_tokens");
                outputTokens = extractIntField(usageObj, "completion_tokens");
            }
        }

        // Extract finish_reason as stop_reason
        String stopReason = "";
        int finishIdx = body.indexOf("\"finish_reason\":");
        if (finishIdx >= 0) {
            stopReason = extractField(body, "finish_reason");
        }

        return new ApiResponse(id, "message", "assistant", content, model, stopReason, 0, 0,
            new ApiResponse.Usage(inputTokens, outputTokens));
    }

    /**
     * Parse arguments JSON string to Map.
     */
    private Map<String, Object> parseArgumentsMap(String argsJson) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (argsJson == null || argsJson.isEmpty()) {
            return result;
        }

        // Remove quotes if present
        argsJson = argsJson.trim();
        if (argsJson.startsWith("\"") && argsJson.endsWith("\"")) {
            argsJson = argsJson.substring(1, argsJson.length() - 1);
            // Unescape
            argsJson = argsJson.replace("\\\"", "\"")
                               .replace("\\n", "\n")
                               .replace("\\r", "\r")
                               .replace("\\t", "\t")
                               .replace("\\\\", "\\");
        }

        if (!argsJson.startsWith("{")) {
            return result;
        }

        // Simple JSON object parsing
        return parseJsonObjectToMap(argsJson);
    }

    private Map<String, Object> parseJsonObjectToMap(String json) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (json == null || json.isEmpty()) return result;

        json = json.trim();
        if (!json.startsWith("{") || !json.endsWith("}")) return result;

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
        if (value.startsWith("{")) return parseJsonObjectToMap(value);
        if (value.startsWith("[")) {
            // Parse array - simplified
            return new java.util.ArrayList<>();
        }
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

    /**
     * Parse Anthropic response format.
     */
    private ApiResponse parseAnthropicResponse(String body) {
        String id = extractField(body, "id");
        String type = extractField(body, "type");
        String role = extractField(body, "role");
        String model = extractField(body, "model");
        String stopReason = extractField(body, "stop_reason");

        // Parse content blocks
        List<ApiResponse.ContentBlock> content = parseContentBlocks(body);

        // Parse usage
        ApiResponse.Usage usage = parseUsage(body);

        return new ApiResponse(id, type, role, content, model, stopReason, 0, 0, usage);
    }

    private List<ApiResponse.ContentBlock> parseContentBlocks(String body) {
        List<ApiResponse.ContentBlock> blocks = new ArrayList<>();
        int contentStart = body.indexOf("\"content\":");
        if (contentStart < 0) return blocks;

        int arrayStart = body.indexOf("[", contentStart);
        int arrayEnd = findMatchingBracket(body, arrayStart);
        if (arrayStart < 0 || arrayEnd < 0) return blocks;

        String contentArray = body.substring(arrayStart, arrayEnd + 1);

        // Parse each content block
        int i = 1;
        while (i < contentArray.length()) {
            int blockStart = contentArray.indexOf("{", i);
            if (blockStart < 0) break;
            int blockEnd = findMatchingBracket(contentArray, blockStart);
            if (blockEnd < 0) break;

            String blockStr = contentArray.substring(blockStart, blockEnd + 1);
            String blockType = extractField(blockStr, "type");

            if ("text".equals(blockType)) {
                String text = extractField(blockStr, "text");
                blocks.add(new ApiResponse.ContentBlock.TextBlock(text));
            } else if ("tool_use".equals(blockType)) {
                String toolId = extractField(blockStr, "id");
                String toolName = extractField(blockStr, "name");
                Map<String, Object> toolInput = extractInputMap(blockStr);
                blocks.add(new ApiResponse.ContentBlock.ToolUseBlock(toolId, toolName, toolInput));
            }

            i = blockEnd + 1;
        }

        return blocks;
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

    private ApiResponse.Usage parseUsage(String body) {
        int usageStart = body.indexOf("\"usage\":");
        if (usageStart < 0) return new ApiResponse.Usage(0, 0);

        int objStart = body.indexOf("{", usageStart);
        int objEnd = findMatchingBracket(body, objStart);
        if (objStart < 0 || objEnd < 0) return new ApiResponse.Usage(0, 0);

        String usageObj = body.substring(objStart, objEnd + 1);
        int inputTokens = extractIntField(usageObj, "input_tokens");
        int outputTokens = extractIntField(usageObj, "output_tokens");

        return new ApiResponse.Usage(inputTokens, outputTokens);
    }

    private String extractField(String json, String field) {
        String pattern = "\"" + field + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return "";

        int colon = json.indexOf(":", idx);
        if (colon < 0) return "";

        // Skip whitespace
        int start = colon + 1;
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\n')) start++;

        if (start >= json.length()) return "";

        if (json.charAt(start) == '"') {
            // String value
            start++;
            int end = start;
            while (end < json.length()) {
                if (json.charAt(end) == '"' && json.charAt(end - 1) != '\\') break;
                end++;
            }
            return json.substring(start, end);
        } else {
            // Non-string value
            int end = start;
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}' && json.charAt(end) != ']') end++;
            return json.substring(start, end).trim();
        }
    }

    private int extractIntField(String json, String field) {
        String value = extractField(json, field);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Map<String, Object> extractInputMap(String blockStr) {
        Map<String, Object> result = new LinkedHashMap<>();
        int inputStart = blockStr.indexOf("\"input\":");
        if (inputStart < 0) return result;

        int objStart = blockStr.indexOf("{", inputStart);
        int objEnd = findMatchingBracket(blockStr, objStart);
        if (objStart < 0 || objEnd < 0) return result;

        String inputObj = blockStr.substring(objStart, objEnd + 1);
        // Simple extraction - real implementation would use proper JSON parser
        return result;
    }

    private String extractMessageId(String body) {
        return extractField(body, "id");
    }

    private List<Object> parseStreamingEvents(String body) {
        List<Object> events = new ArrayList<>();
        // Parse SSE events from the response body
        String[] lines = body.split("\n");
        for (String line : lines) {
            if (line.startsWith("data: ")) {
                String data = line.substring(6);
                events.add(parseEvent(data));
            }
        }
        return events;
    }

    private Object parseEvent(String data) {
        String eventType = extractField(data, "type");
        // Return a map representing the event
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", eventType);
        return event;
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
        // Default: use toString
        return "\"" + escapeJson(obj.toString()) + "\"";
    }
}