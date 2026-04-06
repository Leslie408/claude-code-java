/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code WebFetchTool
 */
package com.anthropic.claudecode.tools;

import com.anthropic.claudecode.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * WebFetchTool - Fetch content from URLs.
 *
 * <p>Corresponds to WebFetchTool in tools/WebFetchTool/.
 *
 * <p>IMPORTANT usage notes from system prompt:
 * - IMPORTANT: WebFetch WILL FAIL for authenticated or private URLs
 * - Before using, check if URL points to authenticated service
 *   (e.g. Google Docs, Confluence, Jira, GitHub)
 * - If so, look for specialized MCP tool for authenticated access
 * - Fetches content and converts HTML to markdown
 * - Uses a small, fast model for processing
 * - Results are cached for faster repeated access
 */
public class WebFetchTool extends AbstractTool<WebFetchTool.Input, WebFetchTool.Output, WebFetchTool.Progress> {

    public static final String NAME = "WebFetch";

    public WebFetchTool() {
        super(NAME, List.of("fetch", "web", "curl"), createSchema());
    }

    private static Map<String, Object> createSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> urlProp = new LinkedHashMap<>();
        urlProp.put("type", "string");
        urlProp.put("format", "uri");
        urlProp.put("description", "The URL to fetch content from");
        properties.put("url", urlProp);

        Map<String, Object> promptProp = new LinkedHashMap<>();
        promptProp.put("type", "string");
        promptProp.put("description", "The prompt to run on the fetched content");
        properties.put("prompt", promptProp);

        schema.put("properties", properties);
        schema.put("required", List.of("url", "prompt"));
        return schema;
    }

    @Override
    public CompletableFuture<ToolResult<Output>> call(
            Input input,
            ToolUseContext context,
            CanUseToolFn canUseTool,
            AssistantMessage parentMessage,
            Consumer<ToolProgress<Progress>> onProgress) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                URL url = new URL(input.url());

                // Check for authenticated services
                String host = url.getHost();
                if (isAuthenticatedService(host)) {
                    return ToolResult.of(new Output(
                            "",
                            "URL appears to require authentication. Use specialized MCP tool instead.",
                            input.url(),
                            0,
                            true
                    ));
                }

                // Fetch content
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "Claude-Code/1.0");

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    return ToolResult.of(new Output(
                            "",
                            "HTTP error: " + responseCode,
                            input.url(),
                            0,
                            true
                    ));
                }

                // Read content
                InputStream in = conn.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
                in.close();

                String content = out.toString(StandardCharsets.UTF_8);
                conn.disconnect();

                // Simple HTML to markdown conversion (basic)
                String markdown = convertHtmlToMarkdown(content);

                // In real implementation, would process with model using prompt
                String processedResult = "Fetched content from " + input.url() + "\n" +
                        "Prompt: " + input.prompt() + "\n\n" +
                        truncate(markdown, 5000);

                return ToolResult.of(new Output(
                        processedResult,
                        "",
                        input.url(),
                        content.length(),
                        false
                ));

            } catch (MalformedURLException e) {
                return ToolResult.of(new Output(
                        "",
                        "Invalid URL: " + e.getMessage(),
                        input.url(),
                        0,
                        true
                ));
            } catch (IOException e) {
                return ToolResult.of(new Output(
                        "",
                        "Error fetching URL: " + e.getMessage(),
                        input.url(),
                        0,
                        true
                ));
            }
        });
    }

    private boolean isAuthenticatedService(String host) {
        Set<String> authServices = Set.of(
                "docs.google.com", "drive.google.com",
                "confluence", "jira",
                "github.com", "gitlab.com"
        );
        return authServices.stream().anyMatch(host::contains);
    }

    private String convertHtmlToMarkdown(String html) {
        // Very basic HTML to markdown conversion
        String md = html;

        // Remove script and style tags
        md = md.replaceAll("<script[^>]*>.*?</script>", "");
        md = md.replaceAll("<style[^>]*>.*?</style>", "");

        // Convert common tags
        md = md.replaceAll("<h1[^>]*>", "# ");
        md = md.replaceAll("<h2[^>]*>", "## ");
        md = md.replaceAll("<h3[^>]*>", "### ");
        md = md.replaceAll("</h[1-6]>", "\n");

        md = md.replaceAll("<p[^>]*>", "");
        md = md.replaceAll("</p>", "\n");

        md = md.replaceAll("<br[^>]*>", "\n");
        md = md.replaceAll("<li[^>]*>", "- ");
        md = md.replaceAll("</li>", "\n");

        md = md.replaceAll("<a[^>]*href=\"([^\"]+)\"[^>]*>", "[$1](");
        md = md.replaceAll("</a>", ")");

        md = md.replaceAll("<[^>]+>", ""); // Remove remaining tags

        return md.trim();
    }

    private String truncate(String s, int maxLength) {
        if (s.length() <= maxLength) {
            return s;
        }
        return s.substring(0, maxLength) + "\n[Truncated - " + (s.length() - maxLength) + " more chars]";
    }

    @Override
    public CompletableFuture<String> describe(Input input, ToolDescribeOptions options) {
        return CompletableFuture.completedFuture("Fetch: " + input.url());
    }

    @Override
    public boolean isReadOnly(Input input) {
        return true;
    }

    @Override
    public boolean isOpenWorld(Input input) {
        return true; // Fetching from external URLs
    }

    @Override
    public boolean isConcurrencySafe(Input input) {
        return true; // Fetches can run concurrently
    }

    @Override
    public SearchOrReadCommand isSearchOrReadCommand(Input input) {
        return new SearchOrReadCommand(false, true, false);
    }

    @Override
    public String getActivityDescription(Input input) {
        return "Fetching " + input.url();
    }

    @Override
    public Input parseInput(Map<String, Object> input) {
        String url = (String) input.get("url");
        String prompt = (String) input.get("prompt");
        return new Input(url, prompt);
    }

    // ==================== Input/Output/Progress ====================

    public record Input(
            String url,
            String prompt
    ) {}

    public record Output(
            String content,
            String error,
            String url,
            int contentLength,
            boolean isError
    ) {
        public String toResultString() {
            if (isError) {
                return error;
            }
            return content;
        }
    }

    public record Progress(int bytesFetched) implements ToolProgressData {}
}