/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code MCP STDIO transport
 */
package com.anthropic.claudecode.services.mcp.transports;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

import com.anthropic.claudecode.services.mcp.McpTypes.*;

/**
 * StdioTransport - Transport for MCP servers using STDIO.
 *
 * <p>Spawns a subprocess and communicates via stdin/stdout.
 */
public class StdioTransport implements McpTransport {

    private final McpStdioServerConfig config;
    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;
    private Thread readerThread;
    private final List<MessageHandler> handlers = new CopyOnWriteArrayList<>();
    private volatile boolean connected = false;

    public StdioTransport(McpStdioServerConfig config) {
        this.config = config;
    }

    @Override
    public void connect() {
        try {
            // Build command
            List<String> command = new ArrayList<>();
            command.add(config.command());
            if (config.args() != null) {
                command.addAll(config.args());
            }

            // Build process
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);

            // Set environment
            if (config.env() != null) {
                Map<String, String> env = pb.environment();
                env.putAll(config.env());
            }

            // Start process
            process = pb.start();
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            connected = true;

            // Start reader thread
            readerThread = new Thread(this::readLoop, "MCP-STDIO-" + config.command());
            readerThread.setDaemon(true);
            readerThread.start();

        } catch (Exception e) {
            connected = false;
            throw new RuntimeException("Failed to connect STDIO transport: " + e.getMessage(), e);
        }
    }

    @Override
    public void disconnect() {
        connected = false;

        try {
            if (writer != null) {
                writer.close();
            }
        } catch (Exception e) {
            // Ignore
        }

        try {
            if (reader != null) {
                reader.close();
            }
        } catch (Exception e) {
            // Ignore
        }

        if (process != null) {
            process.destroyForcibly();
        }

        if (readerThread != null) {
            readerThread.interrupt();
        }
    }

    @Override
    public boolean isConnected() {
        return connected && process != null && process.isAlive();
    }

    @Override
    public void send(String message) {
        if (!isConnected()) {
            throw new IllegalStateException("Transport not connected");
        }

        try {
            writer.write(message);
            writer.newLine();
            writer.flush();
        } catch (Exception e) {
            throw new RuntimeException("Failed to send message: " + e.getMessage(), e);
        }
    }

    @Override
    public void addHandler(MessageHandler handler) {
        handlers.add(handler);
    }

    @Override
    public void removeHandler(MessageHandler handler) {
        handlers.remove(handler);
    }

    private void readLoop() {
        try {
            String line;
            while (connected && (line = reader.readLine()) != null) {
                final String message = line;
                for (MessageHandler handler : handlers) {
                    try {
                        handler.handleMessage(message);
                    } catch (Exception e) {
                        // Ignore handler errors
                    }
                }
            }
        } catch (Exception e) {
            if (connected) {
                // Unexpected error while connected
                System.err.println("STDIO transport error: " + e.getMessage());
            }
        }
    }
}