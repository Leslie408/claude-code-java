/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code MCP null transport
 */
package com.anthropic.claudecode.services.mcp.transports;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

/**
 * NullTransport - Placeholder transport that does nothing.
 */
public class NullTransport implements McpTransport {

    private final List<MessageHandler> handlers = new CopyOnWriteArrayList<>();
    private volatile boolean connected = false;

    @Override
    public void connect() {
        connected = true;
    }

    @Override
    public void disconnect() {
        connected = false;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void send(String message) {
        // Do nothing
    }

    @Override
    public void addHandler(MessageHandler handler) {
        handlers.add(handler);
    }

    @Override
    public void removeHandler(MessageHandler handler) {
        handlers.remove(handler);
    }
}