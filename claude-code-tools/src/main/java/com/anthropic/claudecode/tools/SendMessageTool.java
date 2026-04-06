/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 * Java port of Claude Code tools/SendMessageTool/SendMessageTool.ts
 */
package com.anthropic.claudecode.tools;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import com.anthropic.claudecode.*;

/**
 * SendMessage Tool - send messages to agent teammates.
 * Implements swarm protocol for team communication.
 */
public final class SendMessageTool extends AbstractTool<SendMessageTool.Input, SendMessageTool.Output, ToolProgressData> {
    public static final String TOOL_NAME = "SendMessage";

    public SendMessageTool() {
        super(TOOL_NAME, "Send messages to agent teammates");
    }

    @Override
    public Input parseInput(Map<String, Object> input) {
        String to = (String) input.get("to");
        String summary = (String) input.get("summary");
        Object message = input.get("message");
        return new Input(to, summary, message);
    }

    /**
     * Input schema.
     */
    public record Input(
        String to,
        String summary,
        Object message
    ) {}

    /**
     * Output schema.
     */
    public sealed interface Output permits
        MessageOutput, BroadcastOutput, RequestOutput, ResponseOutput {}

    public record MessageOutput(
        boolean success,
        String message,
        MessageRouting routing
    ) implements Output {}

    public record BroadcastOutput(
        boolean success,
        String message,
        List<String> recipients,
        MessageRouting routing
    ) implements Output {}

    public record RequestOutput(
        boolean success,
        String message,
        String request_id,
        String target
    ) implements Output {}

    public record ResponseOutput(
        boolean success,
        String message,
        String request_id
    ) implements Output {}

    /**
     * Message routing.
     */
    public record MessageRouting(
        String sender,
        String senderColor,
        String target,
        String targetColor,
        String summary,
        String content
    ) {}

    @Override
    public String description() {
        return """
            Send messages to agent teammates (swarm protocol).

            Use this to:
            - Send text messages to specific teammates
            - Broadcast to all teammates (to: "*")
            - Request shutdown of a teammate
            - Approve/reject shutdown requests
            - Respond to plan approvals""";
    }

    @Override
    public String searchHint() {
        return "send messages to agent teammates (swarm protocol)";
    }

    @Override
    public boolean shouldDefer() {
        return true;
    }

    @Override
    public boolean isReadOnly(Input input) {
        return input.message() instanceof String;
    }

    @Override
    public boolean isEnabled() {
        String env = System.getenv("CLAUDE_CODE_SWARM_ENABLED");
        return "true".equalsIgnoreCase(env) || "1".equals(env);
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public Class<Output> outputType() {
        return Output.class;
    }

    @Override
    public ValidationResult validateInput(Input input) {
        if (input.to() == null || input.to().trim().isEmpty()) {
            return ValidationResult.failure("to must not be empty", 9);
        }

        if (input.message() instanceof String) {
            if (input.summary() == null || input.summary().trim().isEmpty()) {
                return ValidationResult.failure("summary is required when message is a string", 9);
            }
        }

        return ValidationResult.success();
    }

    @Override
    public CompletableFuture<ToolResult<Output>> call(
        Input input,
        ToolUseContext context,
        CanUseToolFn canUseTool,
        AssistantMessage parent,
        Consumer<ToolProgress<ToolProgressData>> onProgress
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Handle broadcast
                if ("*".equals(input.to())) {
                    return handleBroadcast(input, context);
                }

                // Handle string message
                if (input.message() instanceof String) {
                    return handleMessage(input, context);
                }

                // Handle structured messages
                if (input.message() instanceof Map) {
                    return handleStructuredMessage(input, context);
                }

                return ToolResult.of(new MessageOutput(
                    false,
                    "Invalid message type",
                    null
                ));
            } catch (Exception e) {
                return ToolResult.of(new MessageOutput(
                    false,
                    "Error: " + e.getMessage(),
                    null
                ));
            }
        });
    }

    private ToolResult<Output> handleMessage(Input input, ToolUseContext context) {
        String senderName = getAgentName();
        String senderColor = getTeammateColor();

        // Write to mailbox
        writeToMailbox(input.to(), (String) input.message(), senderName, senderColor);

        MessageRouting routing = new MessageRouting(
            senderName,
            senderColor,
            "@" + input.to(),
            null,
            input.summary(),
            (String) input.message()
        );

        return ToolResult.of(new MessageOutput(
            true,
            "Message sent to " + input.to() + "'s inbox",
            routing
        ));
    }

    private ToolResult<Output> handleBroadcast(Input input, ToolUseContext context) {
        String senderName = getAgentName();
        String senderColor = getTeammateColor();

        // Get team members
        List<String> recipients = getTeammates(context);

        if (recipients.isEmpty()) {
            return ToolResult.of(new BroadcastOutput(
                true,
                "No teammates to broadcast to (you are the only team member)",
                Collections.emptyList(),
                null
            ));
        }

        // Write to each mailbox
        for (String recipient : recipients) {
            writeToMailbox(recipient, (String) input.message(), senderName, senderColor);
        }

        MessageRouting routing = new MessageRouting(
            senderName,
            senderColor,
            "@team",
            null,
            input.summary(),
            (String) input.message()
        );

        return ToolResult.of(new BroadcastOutput(
            true,
            "Message broadcast to " + recipients.size() + " teammate(s): " + String.join(", ", recipients),
            recipients,
            routing
        ));
    }

    @SuppressWarnings("unchecked")
    private ToolResult<Output> handleStructuredMessage(Input input, ToolUseContext context) {
        Map<String, Object> msg = (Map<String, Object>) input.message();
        String type = (String) msg.get("type");

        if ("shutdown_request".equals(type)) {
            String requestId = generateRequestId("shutdown", input.to());
            return ToolResult.of(new RequestOutput(
                true,
                "Shutdown request sent to " + input.to() + ". Request ID: " + requestId,
                requestId,
                input.to()
            ));
        }

        if ("shutdown_response".equals(type)) {
            String requestId = (String) msg.get("request_id");
            Boolean approve = (Boolean) msg.get("approve");

            if (Boolean.TRUE.equals(approve)) {
                return ToolResult.of(new ResponseOutput(
                    true,
                    "Shutdown approved. Agent is exiting.",
                    requestId
                ));
            } else {
                String reason = (String) msg.get("reason");
                return ToolResult.of(new ResponseOutput(
                    true,
                    "Shutdown rejected. Reason: \"" + reason + "\". Continuing to work.",
                    requestId
                ));
            }
        }

        return ToolResult.of(new MessageOutput(
            false,
            "Unknown message type: " + type,
            null
        ));
    }

    private String getAgentName() {
        String name = System.getenv("CLAUDE_CODE_AGENT_NAME");
        return name != null ? name : "teammate";
    }

    private String getTeammateColor() {
        String color = System.getenv("CLAUDE_CODE_TEAMMATE_COLOR");
        return color != null ? color : "blue";
    }

    private List<String> getTeammates(ToolUseContext context) {
        // Get teammates from team context
        List<String> teammates = new ArrayList<>();

        try {
            // Check team context file
            String home = System.getProperty("user.home");
            java.nio.file.Path teamPath = java.nio.file.Paths.get(home, ".claude", "team.json");

            if (java.nio.file.Files.exists(teamPath)) {
                String content = java.nio.file.Files.readString(teamPath);

                // Find teammates array
                int teammatesIdx = content.indexOf("\"teammates\"");
                if (teammatesIdx >= 0) {
                    int arrStart = content.indexOf("[", teammatesIdx);
                    if (arrStart >= 0) {
                        int arrEnd = content.indexOf("]", arrStart);
                        String arr = content.substring(arrStart + 1, arrEnd);

                        // Parse teammate names
                        int i = 0;
                        while (i < arr.length()) {
                            int nameStart = arr.indexOf("\"name\"", i);
                            if (nameStart < 0) break;

                            int valStart = arr.indexOf("\"", nameStart + 6) + 1;
                            int valEnd = arr.indexOf("\"", valStart);
                            if (valStart > 0 && valEnd > valStart) {
                                teammates.add(arr.substring(valStart, valEnd));
                            }
                            i = valEnd + 1;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Return empty list on error
        }

        return teammates;
    }

    private void writeToMailbox(String recipient, String message, String from, String color) {
        // Write message to mailbox file
        try {
            String home = System.getProperty("user.home");
            java.nio.file.Path mailboxDir = java.nio.file.Paths.get(home, ".claude", "mailbox");
            java.nio.file.Files.createDirectories(mailboxDir);

            String timestamp = java.time.Instant.now().toString().replace(":", "-").replace(".", "-");
            String filename = recipient + "-" + timestamp + ".json";
            java.nio.file.Path mailboxFile = mailboxDir.resolve(filename);

            // Build JSON message
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"recipient\": \"").append(escapeJson(recipient)).append("\",\n");
            json.append("  \"from\": \"").append(escapeJson(from)).append("\",\n");
            json.append("  \"message\": \"").append(escapeJson(message)).append("\",\n");
            json.append("  \"color\": \"").append(escapeJson(color)).append("\",\n");
            json.append("  \"timestamp\": \"").append(java.time.Instant.now().toString()).append("\",\n");
            json.append("  \"read\": false\n");
            json.append("}");

            java.nio.file.Files.writeString(mailboxFile, json.toString());
        } catch (Exception e) {
            // Ignore errors writing to mailbox
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String generateRequestId(String type, String target) {
        return type + "-" + target + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Override
    public String formatResult(Output output) {
        if (output instanceof MessageOutput mo) {
            return mo.message();
        }
        if (output instanceof BroadcastOutput bo) {
            return bo.message();
        }
        if (output instanceof RequestOutput ro) {
            return ro.message();
        }
        if (output instanceof ResponseOutput ro) {
            return ro.message();
        }
        return "Unknown output type";
    }
}