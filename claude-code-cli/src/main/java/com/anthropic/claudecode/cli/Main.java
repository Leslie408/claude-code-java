/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code CLI
 */
package com.anthropic.claudecode.cli;

import com.anthropic.claudecode.*;
import com.anthropic.claudecode.engine.*;
import com.anthropic.claudecode.message.*;
import com.anthropic.claudecode.permission.*;
import com.anthropic.claudecode.session.*;
import com.anthropic.claudecode.types.*;
import com.anthropic.claudecode.tools.*;
import com.anthropic.claudecode.commands.*;
import com.anthropic.claudecode.services.sessions.SessionService;
import com.anthropic.claudecode.ToolResult;
import com.anthropic.claudecode.ToolUseContext;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

/**
 * Claude Code Java CLI entry point.
 *
 * <p>Corresponds to the CLI entry in Claude Code TypeScript.
 */
@Command(
        name = "claude-code",
        mixinStandardHelpOptions = true,
        version = "Claude Code Java 1.0.0",
        description = "Claude Code - Anthropic's official CLI for Claude"
)
public class Main implements Callable<Integer> {

    @Option(names = {"-m", "--model"}, description = "Model to use (sonnet, opus, haiku)")
    private String model = "sonnet";

    @Option(names = {"--max-turns"}, description = "Maximum turns per conversation")
    private Integer maxTurns = 10;

    @Option(names = {"--permission-mode"}, description = "Permission mode (default, accept-edits, bypass)")
    private String permissionMode = "default";

    @Option(names = {"--cwd"}, description = "Working directory")
    private String cwd = System.getProperty("user.dir");

    @Option(names = {"--verbose"}, description = "Verbose output")
    private boolean verbose = false;

    @Option(names = {"--no-tools"}, description = "Disable all tools")
    private boolean noTools = false;

    @Option(names = {"--read-only"}, description = "Only enable read-only tools")
    private boolean readOnly = false;

    @Option(names = {"--api-key"}, description = "Anthropic API key (or set ANTHROPIC_API_KEY env var)")
    private String apiKey;

    @Option(names = {"--resume"}, description = "Resume session by ID (use 'list' to see sessions)")
    private String resumeSessionId;

    @Option(names = {"--resume-latest"}, description = "Resume most recent session")
    private boolean resumeLatest;

    @Parameters(paramLabel = "PROMPT", arity = "0..1", description = "Initial prompt to send")
    private String initialPrompt;

    /**
     * Main entry point.
     */
    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        // Handle resume list
        if (resumeSessionId != null && resumeSessionId.equalsIgnoreCase("list")) {
            listSessions();
            return 0;
        }

        // Get API key
        String key = apiKey != null ? apiKey : System.getenv("ANTHROPIC_API_KEY");
        if (key == null) {
            key = System.getenv("CLAUDE_API_KEY");
        }

        if (key == null) {
            System.err.println("Error: No API key provided. Set ANTHROPIC_API_KEY environment variable or use --api-key");
            System.err.println("Get your API key from: https://console.anthropic.com/");
            return 1;
        }

        if (verbose) {
            System.err.println("Claude Code Java starting...");
            System.err.println("Working directory: " + cwd);
            System.err.println("Model: " + model);
        }

        // Build tools list
        List<Tool<?, ?, ?>> toolList = List.of();
        if (!noTools) {
            toolList = readOnly ? ToolFactory.createReadOnlyTools() : ToolFactory.createAllTools();
        }

        // Build service config
        String modelName = getModelName(model);
        String systemPrompt = buildSystemPrompt(toolList);

        ClaudeCodeService.ClaudeCodeConfig config = ClaudeCodeService.ClaudeCodeConfig.builder()
                .apiKey(key)
                .model(modelName)
                .systemPrompt(systemPrompt)
                .tools(toolList)
                .maxTurns(maxTurns)
                .maxTokens(4096)
                .permissionMode(parsePermissionMode(permissionMode))
                .build();

        // Create service
        ClaudeCodeService service = new ClaudeCodeService(config);

        // Print welcome message
        printWelcome();

        // Create or resume session
        String sessionId;
        if (resumeLatest) {
            sessionId = resumeLatestSession(service);
            if (sessionId == null) {
                System.err.println("No previous session found. Starting new session.");
                sessionId = service.createSession();
            }
        } else if (resumeSessionId != null) {
            sessionId = resumeSessionById(service, resumeSessionId);
            if (sessionId == null) {
                System.err.println("Session not found: " + resumeSessionId);
                System.err.println("Use --resume list to see available sessions.");
                return 1;
            }
        } else {
            sessionId = service.createSession();
        }

        // Handle initial prompt if provided
        if (initialPrompt != null && !initialPrompt.isEmpty()) {
            handlePrompt(service, sessionId, initialPrompt, toolList);
        }

        // Interactive loop
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("\n> ");
            System.out.flush();

            if (!scanner.hasNextLine()) {
                break;
            }

            String input = scanner.nextLine();

            if (input == null || input.trim().isEmpty()) {
                continue;
            }

            String trimmed = input.trim();
            if (trimmed.equalsIgnoreCase("exit") || trimmed.equalsIgnoreCase("quit")) {
                System.out.println("Goodbye!");
                service.endSession(sessionId);
                return 0;
            }

            if (trimmed.equalsIgnoreCase("help") || trimmed.equals("?")) {
                printHelp();
                continue;
            }

            if (trimmed.equalsIgnoreCase("clear")) {
                clearScreen();
                continue;
            }

            handlePrompt(service, sessionId, input, toolList);
        }

        service.endSession(sessionId);
        return 0;
    }

    /**
     * Handle a single prompt with tool execution loop.
     */
    private void handlePrompt(ClaudeCodeService service, String sessionId, String prompt, List<Tool<?, ?, ?>> toolList) {
        System.out.println();

        // Execute agentic loop
        int maxIterations = 20;
        int iteration = 0;

        // First iteration: send user prompt
        Flux<SDKMessage> messages = service.sendMessage(sessionId, prompt);

        while (iteration < maxIterations) {
            iteration++;

            // Collect response
            List<SDKMessage> responseMessages = new ArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);

            messages.subscribe(
                responseMessages::add,
                error -> {
                    System.err.println("Error: " + error.getMessage());
                    if (verbose) {
                        error.printStackTrace();
                    }
                    latch.countDown();
                },
                latch::countDown
            );

            try {
                latch.await(120, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            // Find assistant message
            SDKMessage.Assistant assistantMsg = null;
            for (SDKMessage msg : responseMessages) {
                if (msg instanceof SDKMessage.Assistant a) {
                    assistantMsg = a;
                    break;
                }
            }

            if (assistantMsg == null) {
                break;
            }

            // Print text content
            for (ContentBlock block : assistantMsg.content()) {
                if (block instanceof ContentBlock.Text t) {
                    System.out.println(t.text());
                }
            }

            // Check for tool calls
            List<ContentBlock.ToolUse> toolCalls = new ArrayList<>();
            for (ContentBlock block : assistantMsg.content()) {
                if (block instanceof ContentBlock.ToolUse tu) {
                    toolCalls.add(tu);
                }
            }

            if (toolCalls.isEmpty()) {
                // No tool calls, we're done
                break;
            }

            // Execute tools and collect results as ContentBlock.ToolResult
            List<ContentBlock.ToolResult> toolResults = new ArrayList<>();
            for (ContentBlock.ToolUse toolUse : toolCalls) {
                System.out.println("\n[Executing tool: " + toolUse.name() + "]");
                if (verbose) {
                    System.err.println("[Input: " + toolUse.input() + "]");
                }

                String result = executeTool(toolUse.name(), toolUse.id(), toolUse.input(), toolList);
                System.out.println("[Result: " + truncate(result, 200) + "]");

                // Add tool result as ContentBlock
                toolResults.add(new ContentBlock.ToolResult(toolUse.id(), result, false));
            }

            // Send tool results and continue loop
            messages = service.sendToolResults(sessionId, toolResults);
        }

        if (verbose) {
            System.err.println("\n[Turn completed]");
        }
    }

    /**
     * Execute a tool and return the result.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private String executeTool(String toolName, String toolUseId, Map<String, Object> input, List<Tool<?, ?, ?>> toolList) {
        try {
            // Find the tool
            AbstractTool tool = null;
            if (toolList != null) {
                for (Tool t : toolList) {
                    if (t.name().equals(toolName) || t.aliases().contains(toolName)) {
                        if (t instanceof AbstractTool) {
                            tool = (AbstractTool) t;
                        }
                        break;
                    }
                }
            }

            if (tool == null) {
                return "Error: Unknown tool: " + toolName;
            }

            // Parse input first
            Object parsedInput = tool.parseInput(input);

            // Check if we need to ask for permission
            if (!permissionMode.equals("bypass") && !permissionMode.equals("bypass-permissions")) {
                boolean needConfirm = false;
                String confirmMessage = null;

                // Check if tool is destructive
                if (tool.isDestructiveUntyped(parsedInput)) {
                    needConfirm = true;
                    confirmMessage = "Tool '" + toolName + "' may make destructive changes. Proceed?";
                } else if (!tool.isReadOnlyUntyped(parsedInput) && !permissionMode.equals("accept-edits")) {
                    needConfirm = true;
                    confirmMessage = "Tool '" + toolName + "' will modify files. Proceed?";
                }

                if (needConfirm) {
                    System.out.print("\n[Permission] " + confirmMessage + " (y/n): ");
                    System.out.flush();

                    Scanner confirmScanner = new Scanner(System.in);
                    String response = confirmScanner.nextLine().trim().toLowerCase();

                    if (!response.equals("y") && !response.equals("yes")) {
                        return "Permission denied by user";
                    }
                }
            }

            // Execute the tool
            java.util.concurrent.CompletableFuture future =
                tool.executeWithMapInput(input, createToolUseContext(), (t, i, ctx, msg, id) ->
                    java.util.concurrent.CompletableFuture.completedFuture(
                        com.anthropic.claudecode.permission.PermissionResult.allow(i)
                    ), null, progress -> {});

            ToolResult result = (ToolResult) future.get(60, java.util.concurrent.TimeUnit.SECONDS);

            if (result.data() != null) {
                Object data = result.data();
                // Handle different output types
                if (data instanceof String str) {
                    return str;
                } else if (data instanceof Map<?, ?> map) {
                    return formatMapResult(map);
                } else if (data instanceof List<?> list) {
                    return formatListResult(list);
                }
                // Try to find toResultString method
                try {
                    java.lang.reflect.Method method = data.getClass().getMethod("toResultString");
                    Object methodResult = method.invoke(data);
                    if (methodResult instanceof String str) {
                        return str;
                    }
                } catch (NoSuchMethodException e) {
                    // No toResultString method, use toString
                }
                return data.toString();
            }
            return "Tool completed with no output";
        } catch (Exception e) {
            return "Error executing tool: " + e.getMessage();
        }
    }

    /**
     * Create tool use context.
     */
    private ToolUseContext createToolUseContext() {
        return ToolUseContext.empty();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private String formatMapResult(Map<?, ?> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (sb.length() > 0) sb.append("\n");
            Object value = entry.getValue();
            String valueStr;
            if (value instanceof String str) {
                valueStr = str;
            } else if (value instanceof List<?> list) {
                valueStr = formatListResult(list);
            } else if (value instanceof Map<?, ?> m) {
                valueStr = formatMapResult(m);
            } else {
                valueStr = String.valueOf(value);
            }
            sb.append(entry.getKey()).append(": ").append(valueStr);
        }
        return sb.toString();
    }

    private String formatListResult(List<?> list) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(list.size()).append(" items]");
        for (Object item : list) {
            sb.append("\n  ");
            if (item instanceof String str) {
                sb.append(str);
            } else if (item instanceof Map<?, ?> m) {
                sb.append(formatMapResult(m).replace("\n", "\n  "));
            } else {
                sb.append(String.valueOf(item));
            }
        }
        return sb.toString();
    }

    /**
     * Print SDK message to console.
     */
    private void printMessage(SDKMessage message) {
        if (message instanceof SDKMessage.Assistant a) {
            System.out.println();
            for (ContentBlock block : a.content()) {
                printContentBlock(block);
            }
        } else if (message instanceof SDKMessage.Result r) {
            if (r.isError()) {
                System.err.println("\nError: " + r.result());
            }
        } else if (message instanceof SDKMessage.User u) {
            if (verbose) {
                System.err.println("[User message received]");
            }
        }
    }

    /**
     * Print content block.
     */
    private void printContentBlock(ContentBlock block) {
        if (block instanceof ContentBlock.Text t) {
            System.out.println(t.text());
        } else if (block instanceof ContentBlock.ToolUse tu) {
            System.out.println("\n[Tool: " + tu.name() + "]");
            if (verbose) {
                System.err.println("[Input: " + tu.input() + "]");
            }
        } else if (block instanceof ContentBlock.ToolResult tr) {
            Object content = tr.content();
            if (content != null) {
                String str = content.toString();
                if (str.length() > 500) {
                    System.out.println("\n[Tool result: " + str.substring(0, 500) + "...]");
                } else {
                    System.out.println("\n[Tool result: " + str + "]");
                }
            }
        } else if (block instanceof ContentBlock.Image i) {
            System.out.println("[Image content]");
        } else if (block instanceof ContentBlock.Thinking th) {
            if (verbose) {
                System.err.println("[Thinking: " + th.thinking() + "]");
            }
        }
    }

    private String getModelName(String shortName) {
        // 阿里云 dashscope 支持的模型
        return switch (shortName.toLowerCase()) {
            // 千问系列
            case "qwen", "qwen-plus" -> "qwen3.5-plus";
            case "qwen-max" -> "qwen3-max-2026-01-23";
            case "qwen-coder" -> "qwen3-coder-plus";
            case "qwen-coder-next" -> "qwen3-coder-next";
            // 智谱
            case "glm", "glm-5" -> "glm-5";
            case "glm-4" -> "glm-4.7";
            // Kimi
            case "kimi" -> "kimi-k2.5";
            // MiniMax
            case "minimax" -> "MiniMax-M2.5";
            default -> "glm-5";  // 默认使用 glm-5
        };
    }

    private String buildSystemPrompt(List<Tool<?, ?, ?>> tools) {
        return new com.anthropic.claudecode.prompt.SystemPromptBuilder()
            .cwd(cwd)
            .tools(tools)
            .permissionMode(parsePermissionMode(permissionMode))
            .build();
    }

    private PermissionMode parsePermissionMode(String mode) {
        String lower = mode.toLowerCase();
        if ("accept-edits".equals(lower) || "acceptedits".equals(lower)) {
            return PermissionMode.ACCEPT_EDITS;
        } else if ("bypass".equals(lower) || "bypass-permissions".equals(lower) || "bypasspermissions".equals(lower)) {
            return PermissionMode.BYPASS_PERMISSIONS;
        } else if ("plan".equals(lower)) {
            return PermissionMode.PLAN;
        } else if ("auto".equals(lower)) {
            return PermissionMode.AUTO;
        } else {
            return PermissionMode.DEFAULT;
        }
    }

    private void printWelcome() {
        System.out.println();
        System.out.println("╔════════════════════════════════════════════╗");
        System.out.println("║        Claude Code Java - Interactive      ║");
        System.out.println("║                                            ║");
        System.out.println("║  Anthropic's official CLI port for Claude  ║");
        System.out.println("║                                            ║");
        System.out.println("║  Commands:                                 ║");
        System.out.println("║    exit/quit  - Exit session               ║");
        System.out.println("║    help       - Show help                  ║");
        System.out.println("║    clear      - Clear screen               ║");
        System.out.println("╚════════════════════════════════════════════╝");
        System.out.println();
    }

    private void printHelp() {
        System.out.println();
        System.out.println("Claude Code Java - Help");
        System.out.println("========================");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  claude-code [options] [prompt]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -m, --model <model>      Model: sonnet (default), opus, haiku");
        System.out.println("  --max-turns <n>          Maximum conversation turns (default: 10)");
        System.out.println("  --permission-mode <mode> Permission mode (default, accept-edits, bypass)");
        System.out.println("  --api-key <key>          Anthropic API key");
        System.out.println("  --resume <id>            Resume session by ID (use 'list' to see sessions)");
        System.out.println("  --resume-latest          Resume most recent session");
        System.out.println("  --verbose                Enable verbose output");
        System.out.println("  --no-tools               Disable all tools");
        System.out.println("  --read-only              Only enable read-only tools");
        System.out.println("  --cwd <dir>              Working directory");
        System.out.println();
        System.out.println("Available Tools:");
        System.out.println("  Bash      - Execute shell commands");
        System.out.println("  Read      - Read files");
        System.out.println("  Write     - Write files");
        System.out.println("  Edit      - Edit files");
        System.out.println("  Glob      - Find files by pattern");
        System.out.println("  Grep      - Search file contents");
        System.out.println("  Agent     - Launch specialized agents");
        System.out.println("  WebFetch  - Fetch web content");
        System.out.println("  WebSearch - Search the web");
        System.out.println();
        System.out.println("For more information: https://github.com/anthropics/claude-code");
        System.out.println();
    }

    private void clearScreen() {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("windows")) {
                new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
            } else {
                System.out.print("\033[H\033[2J");
                System.out.flush();
            }
        } catch (Exception e) {
            for (int i = 0; i < 50; i++) {
                System.out.println();
            }
        }
    }

    // Session management methods
    private static SessionService sessionManager = new SessionService();

    private void listSessions() {
        System.out.println();
        System.out.println("Recent Sessions");
        System.out.println("===============");
        System.out.println();

        java.util.List<SessionService.SessionMeta> sessions = sessionManager.listSavedSessions();

        if (sessions.isEmpty()) {
            System.out.println("No previous sessions found.");
            System.out.println();
            System.out.println("Start a new session and it will be saved automatically.");
        } else {
            java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter
                .ofPattern("MM/dd HH:mm")
                .withZone(java.time.ZoneId.systemDefault());

            for (int i = 0; i < Math.min(sessions.size(), 20); i++) {
                SessionService.SessionMeta meta = sessions.get(i);
                String time = formatter.format(meta.updatedAt());
                String title = truncate(meta.title(), 40);

                System.out.printf("  [%s] %s (%d messages) - %s%n",
                    meta.id().substring(0, 8),
                    title,
                    meta.messageCount(),
                    time
                );
            }

            if (sessions.size() > 20) {
                System.out.println();
                System.out.println("  ... and " + (sessions.size() - 20) + " more sessions");
            }

            System.out.println();
            System.out.println("Usage:");
            System.out.println("  --resume <id>     Resume specific session");
            System.out.println("  --resume-latest   Resume most recent session");
        }
    }

    private String resumeLatestSession(ClaudeCodeService service) {
        java.util.List<SessionService.SessionMeta> sessions = sessionManager.listSavedSessions();

        if (sessions.isEmpty()) {
            return null;
        }

        SessionService.SessionMeta latest = sessions.get(0);
        return doResumeSession(service, latest.id(), latest.title());
    }

    private String resumeSessionById(ClaudeCodeService service, String sessionId) {
        java.util.List<SessionService.SessionMeta> sessions = sessionManager.listSavedSessions();

        for (SessionService.SessionMeta meta : sessions) {
            if (meta.id().equals(sessionId) || meta.id().startsWith(sessionId)) {
                return doResumeSession(service, meta.id(), meta.title());
            }
        }

        return null;
    }

    private String doResumeSession(ClaudeCodeService service, String sessionId, String title) {
        SessionService.Session session = sessionManager.loadSession(sessionId);

        if (session == null) {
            return null;
        }

        System.out.println();
        System.out.println("Resumed session: " + truncate(title, 50));
        System.out.println("Session ID: " + sessionId);
        System.out.println("Messages: " + session.getMessageCount());

        if (session.projectPath() != null && !session.projectPath().isEmpty()) {
            System.out.println("Project: " + session.projectPath());
        }

        System.out.println();
        System.out.println("--- Last messages ---");

        java.util.List<SessionService.MessageEntry> messages = session.messages();
        int start = Math.max(0, messages.size() - 3);
        for (int i = start; i < messages.size(); i++) {
            SessionService.MessageEntry msg = messages.get(i);
            System.out.println();
            System.out.println("[" + msg.role() + "]");
            System.out.println(truncate(msg.content(), 200));
        }

        System.out.println();
        return sessionId;
    }
}