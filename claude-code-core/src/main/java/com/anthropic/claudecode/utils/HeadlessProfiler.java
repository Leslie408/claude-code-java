/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 * Java port of Claude Code headless profiler utilities
 */
package com.anthropic.claudecode.utils;

import java.util.*;

/**
 * Headless mode profiling utility for measuring per-turn latency.
 */
public final class HeadlessProfiler {
    private HeadlessProfiler() {}

    private static final String MARK_PREFIX = "headless_";
    private static final double STATSIG_SAMPLE_RATE = 0.05;

    // Track current turn number
    private static int currentTurnNumber = -1;

    // Check if sampled for logging
    private static final boolean STATSIG_LOGGING_SAMPLED = isSampled();

    // Detailed profiling mode
    private static final boolean DETAILED_PROFILING = EnvUtils.isEnvTruthy("CLAUDE_CODE_PROFILE_STARTUP");

    // Enable profiling if either detailed mode OR sampled
    private static final boolean SHOULD_PROFILE = DETAILED_PROFILING || STATSIG_LOGGING_SAMPLED;

    // Performance marks storage
    private static final Map<String, Long> marks = new LinkedHashMap<>();

    private static boolean isSampled() {
        String userType = System.getenv("USER_TYPE");
        return userType != null && userType.equals("ant") || Math.random() < STATSIG_SAMPLE_RATE;
    }

    /**
     * Clear all headless profiler marks.
     */
    private static void clearHeadlessMarks() {
        marks.keySet().removeIf(key -> key.startsWith(MARK_PREFIX));
    }

    /**
     * Start a new turn for profiling.
     */
    public static void headlessProfilerStartTurn() {
        if (!shouldProfile()) return;

        currentTurnNumber++;
        clearHeadlessMarks();

        long now = System.nanoTime() / 1_000_000;
        marks.put(MARK_PREFIX + "turn_start", now);

        if (DETAILED_PROFILING) {
            Debug.log("[headlessProfiler] Started turn " + currentTurnNumber);
        }
    }

    /**
     * Record a checkpoint with the given name.
     */
    public static void headlessProfilerCheckpoint(String name) {
        if (!shouldProfile()) return;

        long now = System.nanoTime() / 1_000_000;
        marks.put(MARK_PREFIX + name, now);

        if (DETAILED_PROFILING) {
            Debug.log("[headlessProfiler] Checkpoint: " + name + " at " + now + "ms");
        }
    }

    /**
     * Log headless latency metrics for the current turn.
     */
    public static void logHeadlessProfilerTurn() {
        if (!shouldProfile()) return;

        Long turnStart = marks.get(MARK_PREFIX + "turn_start");
        if (turnStart == null) return;

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("turn_number", currentTurnNumber);

        // Time to system message from process start (only meaningful for turn 0)
        Long systemMessageTime = marks.get(MARK_PREFIX + "system_message_yielded");
        if (systemMessageTime != null && currentTurnNumber == 0) {
            metadata.put("time_to_system_message_ms", Math.round(systemMessageTime));
        }

        // Time to query start
        Long queryStartTime = marks.get(MARK_PREFIX + "query_started");
        if (queryStartTime != null) {
            metadata.put("time_to_query_start_ms", Math.round(queryStartTime - turnStart));
        }

        // Time to first response
        Long firstChunkTime = marks.get(MARK_PREFIX + "first_chunk");
        if (firstChunkTime != null) {
            metadata.put("time_to_first_response_ms", Math.round(firstChunkTime - turnStart));
        }

        // Query overhead
        Long apiRequestTime = marks.get(MARK_PREFIX + "api_request_sent");
        if (queryStartTime != null && apiRequestTime != null) {
            metadata.put("query_overhead_ms", Math.round(apiRequestTime - queryStartTime));
        }

        // Checkpoint count
        metadata.put("checkpoint_count", marks.keySet().stream()
                .filter(k -> k.startsWith(MARK_PREFIX))
                .count());

        // Entrypoint
        String entrypoint = System.getenv("CLAUDE_CODE_ENTRYPOINT");
        if (entrypoint != null) {
            metadata.put("entrypoint", entrypoint);
        }

        // Log to analytics if sampled
        if (STATSIG_LOGGING_SAMPLED) {
            Analytics.logEvent("tengu_headless_latency", metadata);
        }

        // Log detailed output
        if (DETAILED_PROFILING) {
            Debug.log("[headlessProfiler] Turn " + currentTurnNumber + " metrics: " + metadata);
        }
    }

    /**
     * Check if profiling should be active.
     */
    private static boolean shouldProfile() {
        // Only profile in headless/non-interactive mode
        String nonInteractive = System.getenv("CLAUDE_CODE_NON_INTERACTIVE");
        return SHOULD_PROFILE && EnvUtils.isEnvTruthy(nonInteractive);
    }

    /**
     * Get current turn number.
     */
    public static int getCurrentTurnNumber() {
        return currentTurnNumber;
    }

    /**
     * Reset profiler state.
     */
    public static void reset() {
        currentTurnNumber = -1;
        clearHeadlessMarks();
    }
}