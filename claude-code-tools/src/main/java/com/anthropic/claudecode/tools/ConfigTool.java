/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 * Java port of Claude Code tools/ConfigTool/ConfigTool.ts
 */
package com.anthropic.claudecode.tools;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import com.anthropic.claudecode.*;
import com.anthropic.claudecode.utils.ConfigManager;
import com.anthropic.claudecode.permission.PermissionResult;
import com.anthropic.claudecode.services.analytics.AnalyticsMetadata;

/**
 * Config Tool - get or set Claude Code settings.
 */
public final class ConfigTool extends AbstractTool<ConfigTool.Input, ConfigTool.Output, ToolProgressData> {
    public static final String TOOL_NAME = "Config";

    // Supported settings
    private static final Map<String, SettingConfig> SETTINGS = new LinkedHashMap<>();

    static {
        SETTINGS.put("theme", new SettingConfig("global", new String[]{"dark", "light"}, "string"));
        SETTINGS.put("model", new SettingConfig("global", null, "string"));
        SETTINGS.put("permissions.defaultMode", new SettingConfig("settings", new String[]{"default", "acceptEdits", "bypassPermissions", "plan"}, "string"));
        SETTINGS.put("remoteControlAtStartup", new SettingConfig("global", new String[]{"true", "false", "default"}, "boolean"));
        SETTINGS.put("speculationEnabled", new SettingConfig("global", null, "boolean"));
        SETTINGS.put("voiceEnabled", new SettingConfig("settings", new String[]{"true", "false"}, "boolean"));
    }

    public ConfigTool() {
        super(TOOL_NAME, "Get or set Claude Code settings");
    }

    @Override
    public Input parseInput(Map<String, Object> input) {
        String setting = (String) input.get("setting");
        Object value = input.get("value");
        return new Input(setting, value);
    }

    /**
     * Input schema.
     */
    public record Input(
        String setting,
        Object value
    ) {}

    /**
     * Output schema.
     */
    public record Output(
        boolean success,
        String operation,
        String setting,
        Object value,
        Object previousValue,
        Object newValue,
        String error
    ) {}

    /**
     * Setting config.
     */
    public record SettingConfig(
        String source,
        String[] options,
        String type
    ) {}

    @Override
    public String description() {
        return """
            Get or set Claude Code settings.

            Supported settings:
            - theme: dark, light
            - model: Model ID (e.g., claude-opus-4-6, claude-sonnet-4-6)
            - permissions.defaultMode: default, acceptEdits, bypassPermissions, plan
            - remoteControlAtStartup: true, false, default
            - speculationEnabled: true, false
            - voiceEnabled: true, false

            Omit value to get current value. Include value to set.""";
    }

    @Override
    public String searchHint() {
        return "get or set Claude Code settings (theme, model)";
    }

    @Override
    public boolean shouldDefer() {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(Input input) {
        return true;
    }

    @Override
    public boolean isReadOnly(Input input) {
        return input.value() == null;
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
    public CompletableFuture<PermissionResult> checkPermissions(Input input, ToolUseContext context) {
        // Auto-allow reading configs
        if (input.value() == null) {
            return CompletableFuture.completedFuture(PermissionResult.allow(input));
        }
        return CompletableFuture.completedFuture(PermissionResult.ask("Set " + input.setting() + " to " + input.value(), input));
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
            // 1. Check if setting is supported
            SettingConfig config = SETTINGS.get(input.setting());
            if (config == null) {
                return ToolResult.of(new Output(
                    false, null, input.setting(), null, null, null,
                    "Unknown setting: \"" + input.setting() + "\""
                ));
            }

            // 2. GET operation
            if (input.value() == null) {
                Object currentValue = getValue(config.source(), input.setting());
                return ToolResult.of(new Output(
                    true, "get", input.setting(), currentValue, null, null, null
                ));
            }

            // 3. SET operation
            Object finalValue = input.value();

            // Coerce boolean values
            if ("boolean".equals(config.type())) {
                if (input.value() instanceof String) {
                    String lower = ((String) input.value()).toLowerCase().trim();
                    if ("true".equals(lower)) finalValue = true;
                    else if ("false".equals(lower)) finalValue = false;
                }
                if (!(finalValue instanceof Boolean)) {
                    return ToolResult.of(new Output(
                        false, "set", input.setting(), null, null, null,
                        input.setting() + " requires true or false."
                    ));
                }
            }

            // Check options
            if (config.options() != null) {
                boolean validOption = false;
                for (String option : config.options()) {
                    if (option.equalsIgnoreCase(String.valueOf(finalValue))) {
                        validOption = true;
                        break;
                    }
                }
                if (!validOption) {
                    return ToolResult.of(new Output(
                        false, "set", input.setting(), null, null, null,
                        "Invalid value \"" + input.value() + "\". Options: " + String.join(", ", config.options())
                    ));
                }
            }

            // Get previous value
            Object previousValue = getValue(config.source(), input.setting());

            // Write to storage
            try {
                setValue(config.source(), input.setting(), finalValue);

                // Log analytics
                AnalyticsMetadata.logEvent("tengu_config_tool_changed", Map.of(
                    "setting", input.setting(),
                    "value", String.valueOf(finalValue)
                ), true);

                return ToolResult.of(new Output(
                    true, "set", input.setting(), null, previousValue, finalValue, null
                ));
            } catch (Exception e) {
                return ToolResult.of(new Output(
                    false, "set", input.setting(), null, null, null,
                    e.getMessage()
                ));
            }
        });
    }

    @Override
    public String formatResult(Output output) {
        if (!output.success()) {
            return "Error: " + output.error();
        }
        if ("get".equals(output.operation())) {
            return output.setting() + " = " + formatValue(output.value());
        }
        return "Set " + output.setting() + " to " + formatValue(output.newValue());
    }

    private Object getValue(String source, String setting) {
        // Use ConfigManager to get value
        Map<String, Object> config = "global".equals(source)
            ? ConfigManager.loadGlobalConfig()
            : ConfigManager.loadUserSettings();

        // Handle nested paths
        String[] parts = setting.split("\\.");
        Object current = config;
        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<?, ?>) current).get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    private void setValue(String source, String setting, Object value) {
        if ("global".equals(source)) {
            ConfigManager.saveGlobalConfig(setting, value);
        } else {
            ConfigManager.saveUserSetting(setting, value);
        }
    }

    private String formatValue(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return "\"" + value + "\"";
        return String.valueOf(value);
    }
}