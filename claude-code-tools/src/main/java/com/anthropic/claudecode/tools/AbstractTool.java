/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code tools
 */
package com.anthropic.claudecode.tools;

import com.anthropic.claudecode.*;
import com.anthropic.claudecode.permission.PermissionResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Abstract base class for built-in tools.
 *
 * <p>Provides common implementations for tool interface methods.
 */
public abstract class AbstractTool<I, O, P extends ToolProgressData> implements Tool<I, O, P> {

    protected final String toolName;
    protected final List<String> toolAliases;
    protected final Map<String, Object> schema;

    protected AbstractTool(String name, List<String> aliases, Map<String, Object> schema) {
        this.toolName = name;
        this.toolAliases = aliases != null ? aliases : List.of();
        this.schema = schema;
    }

    /**
     * Convenience constructor with name and description.
     */
    protected AbstractTool(String name, String description) {
        this(name, List.of(), null);
    }

    /**
     * Convenience constructor with name, description, and schema.
     */
    protected AbstractTool(String name, String description, Map<String, Object> schema) {
        this(name, List.of(), schema);
    }

    @Override
    public String name() {
        return toolName;
    }

    @Override
    public List<String> aliases() {
        return toolAliases;
    }

    @Override
    public Map<String, Object> inputSchema() {
        return schema;
    }

    /**
     * Parse input from a Map (received from API) to the tool's input type.
     * Subclasses should override this to enable dynamic tool invocation.
     * Default implementation throws UnsupportedOperationException.
     */
    @SuppressWarnings("unchecked")
    public I parseInput(Map<String, Object> input) {
        throw new UnsupportedOperationException(
            "Tool " + toolName + " does not implement parseInput. " +
            "Please add parseInput method to enable dynamic invocation."
        );
    }

    /**
     * Execute the tool with Map input (received from API).
     * This parses the input first, then calls the typed execute method.
     */
    public CompletableFuture<ToolResult<O>> executeWithMapInput(
            Map<String, Object> input,
            ToolUseContext context,
            CanUseToolFn canUseTool,
            AssistantMessage parentMessage,
            Consumer<ToolProgress<P>> onProgress) {
        I parsedInput = parseInput(input);
        return call(parsedInput, context, canUseTool, parentMessage, onProgress);
    }

    @Override
    public CompletableFuture<ValidationResult> validateInput(I input, ToolUseContext context) {
        return CompletableFuture.completedFuture(ValidationResult.success());
    }

    @Override
    public CompletableFuture<String> describe(I input, ToolDescribeOptions options) {
        return CompletableFuture.completedFuture("Executing " + toolName);
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(I input) {
        return isReadOnly(input);
    }

    @Override
    public boolean isReadOnly(I input) {
        return false;
    }

    @Override
    public boolean isDestructive(I input) {
        return !isReadOnly(input);
    }

    @Override
    public boolean requiresUserInteraction() {
        return false;
    }

    @Override
    public String interruptBehavior() {
        return "block";
    }

    @Override
    public SearchOrReadCommand isSearchOrReadCommand(I input) {
        return new SearchOrReadCommand(false, false, false);
    }

    @Override
    public boolean isOpenWorld(I input) {
        return false;
    }

    @Override
    public long maxResultSizeChars() {
        return 100000; // Default limit
    }

    @Override
    public boolean strict() {
        return true;
    }

    @Override
    public boolean shouldDefer() {
        return false;
    }

    @Override
    public boolean alwaysLoad() {
        return true;
    }

    @Override
    public McpInfo mcpInfo() {
        return null;
    }

    @Override
    public boolean matchesName(String name) {
        return toolName.equals(name) || toolAliases.contains(name);
    }

    @Override
    public Object toAutoClassifierInput(I input) {
        return "";
    }

    @Override
    public String getToolUseSummary(I input) {
        return toolName;
    }

    @Override
    public String getActivityDescription(I input) {
        return "Running " + toolName;
    }

    // ==================== Optional Helper Methods ====================

    /**
     * Get the input type class. Optional helper for reflection.
     */
    public Class<?> inputType() {
        return null;
    }

    /**
     * Get the output type class. Optional helper for reflection.
     */
    public Class<?> outputType() {
        return null;
    }

    /**
     * Format the result for display. Optional helper.
     */
    public String formatResult(O output) {
        return output != null ? output.toString() : "null";
    }

    /**
     * Validate input without context. Convenience method.
     * Override validateInput(I, ToolUseContext) instead for full control.
     */
    public ValidationResult validateInput(I input) {
        return ValidationResult.success();
    }

    /**
     * Check permissions without context. Convenience method.
     * Override checkPermissions(I, ToolUseContext) in the Tool interface instead.
     */
    public CompletableFuture<PermissionResult> checkPermissions(I input) {
        return CompletableFuture.completedFuture(PermissionResult.allow(input));
    }
}