/*
 * Copyright 2026 Luciano Lopes.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 */
package org.netbeans.modules.ai.chat;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

/**
 * Streams a chat completion from one of the providers. Each provider speaks
 * server-sent events; only the text deltas are forwarded to the caller.
 */
@SuppressWarnings("unchecked")
final class ChatClient {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();
    static final int CHAT_MAX_TOKENS = 8192;

    private final AtomicReference<Stream<String>> body = new AtomicReference<>();
    private volatile boolean cancelled;

    /** Closing the response stream is the only reliable way to abort a blocked SSE read. */
    void cancel() {
        cancelled = true;
        Stream<String> s = body.getAndSet(null);
        if (s != null) {
            s.close();
        }
    }

    void stream(AiProvider provider, String model, String apiKey, String system, List<ChatMessage> history,
            int maxTokens, Consumer<String> onText) throws IOException, InterruptedException {
        HttpRequest request = switch (provider) {
            case CLAUDE -> claude(model, apiKey, system, history, maxTokens);
            case CHATGPT -> openAi(model, apiKey, system, history, maxTokens);
            case GEMINI -> gemini(model, apiKey, system, history, maxTokens);
        };
        HttpResponse<Stream<String>> response = HTTP.send(request, HttpResponse.BodyHandlers.ofLines());
        try (Stream<String> lines = response.body()) {
            body.set(lines);
            if (cancelled) {
                return;
            }
            if (response.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + response.statusCode() + ": " // NOI18N
                        + errorMessage(lines.collect(Collectors.joining("\n")))); // NOI18N
            }
            Iterator<String> it = lines.iterator();
            while (!cancelled && it.hasNext()) {
                String line = it.next();
                if (!line.startsWith("data:")) { // NOI18N
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty() || data.equals("[DONE]")) { // NOI18N
                    continue;
                }
                String text = extractText(provider, data);
                if (!text.isEmpty()) {
                    onText.accept(text);
                }
            }
        } catch (RuntimeException | IOException ex) {
            // the stream throws (UncheckedIOException/IllegalStateException) once cancel() closed it underneath us
            if (!cancelled) {
                throw ex instanceof IOException ? (IOException) ex : new IOException(ex);
            }
        } finally {
            body.set(null);
        }
    }

    private static HttpRequest.Builder post(String url, JSONObject json) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .header("content-type", "application/json") // NOI18N
                .POST(HttpRequest.BodyPublishers.ofString(json.toJSONString(), StandardCharsets.UTF_8));
    }

    private static HttpRequest claude(String model, String key, String system, List<ChatMessage> history, int maxTokens) {
        JSONArray messages = new JSONArray();
        for (ChatMessage m : history) {
            messages.add(message(m.role == ChatMessage.Role.USER ? "user" : "assistant", m.content.toString())); // NOI18N
        }
        JSONObject json = new JSONObject();
        json.put("model", model); // NOI18N
        json.put("max_tokens", maxTokens); // NOI18N
        json.put("stream", true); // NOI18N
        json.put("system", system); // NOI18N
        json.put("messages", messages); // NOI18N
        return post("https://api.anthropic.com/v1/messages", json) // NOI18N
                .header("x-api-key", key) // NOI18N
                .header("anthropic-version", "2023-06-01") // NOI18N
                .build();
    }

    private static HttpRequest openAi(String model, String key, String system, List<ChatMessage> history, int maxTokens) {
        JSONArray messages = new JSONArray();
        messages.add(message("system", system)); // NOI18N
        for (ChatMessage m : history) {
            messages.add(message(m.role == ChatMessage.Role.USER ? "user" : "assistant", m.content.toString())); // NOI18N
        }
        JSONObject json = new JSONObject();
        json.put("model", model); // NOI18N
        json.put("stream", true); // NOI18N
        json.put("max_completion_tokens", maxTokens); // NOI18N
        json.put("messages", messages); // NOI18N
        return post("https://api.openai.com/v1/chat/completions", json) // NOI18N
                .header("authorization", "Bearer " + key) // NOI18N
                .build();
    }

    private static HttpRequest gemini(String model, String key, String system, List<ChatMessage> history, int maxTokens) {
        JSONArray contents = new JSONArray();
        for (ChatMessage m : history) {
            JSONObject c = new JSONObject();
            c.put("role", m.role == ChatMessage.Role.USER ? "user" : "model"); // NOI18N
            c.put("parts", textParts(m.content.toString())); // NOI18N
            contents.add(c);
        }
        JSONObject instruction = new JSONObject();
        instruction.put("parts", textParts(system)); // NOI18N
        JSONObject json = new JSONObject();
        json.put("systemInstruction", instruction); // NOI18N
        json.put("contents", contents); // NOI18N
        JSONObject generation = new JSONObject();
        generation.put("maxOutputTokens", maxTokens); // NOI18N
        json.put("generationConfig", generation); // NOI18N
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" // NOI18N
                + URLEncoder.encode(model, StandardCharsets.UTF_8) + ":streamGenerateContent?alt=sse"; // NOI18N
        return post(url, json)
                .header("x-goog-api-key", key) // NOI18N
                .build();
    }

    private static JSONObject message(String role, String content) {
        JSONObject m = new JSONObject();
        m.put("role", role); // NOI18N
        m.put("content", content); // NOI18N
        return m;
    }

    private static JSONArray textParts(String text) {
        JSONObject part = new JSONObject();
        part.put("text", text); // NOI18N
        JSONArray parts = new JSONArray();
        parts.add(part);
        return parts;
    }

    private static String extractText(AiProvider provider, String data) throws IOException {
        Object parsed = JSONValue.parse(data);
        if (!(parsed instanceof JSONObject)) {
            return "";
        }
        JSONObject event = (JSONObject) parsed;
        if (event.get("error") != null) { // NOI18N
            throw new IOException(errorMessage(data));
        }
        StringBuilder sb = new StringBuilder();
        switch (provider) {
            case CLAUDE -> {
                if ("content_block_delta".equals(event.get("type"))) { // NOI18N
                    JSONObject delta = (JSONObject) event.get("delta"); // NOI18N
                    if (delta != null && "text_delta".equals(delta.get("type"))) { // NOI18N
                        sb.append((String) delta.get("text")); // NOI18N
                    }
                }
            }
            case CHATGPT -> {
                JSONArray choices = (JSONArray) event.get("choices"); // NOI18N
                if (choices != null && !choices.isEmpty()) {
                    JSONObject delta = (JSONObject) ((JSONObject) choices.get(0)).get("delta"); // NOI18N
                    Object content = delta == null ? null : delta.get("content"); // NOI18N
                    if (content instanceof String) {
                        sb.append((String) content);
                    }
                }
            }
            case GEMINI -> {
                JSONArray candidates = (JSONArray) event.get("candidates"); // NOI18N
                if (candidates != null && !candidates.isEmpty()) {
                    JSONObject content = (JSONObject) ((JSONObject) candidates.get(0)).get("content"); // NOI18N
                    JSONArray parts = content == null ? null : (JSONArray) content.get("parts"); // NOI18N
                    if (parts != null) {
                        for (Object p : parts) {
                            JSONObject part = (JSONObject) p;
                            if (!Boolean.TRUE.equals(part.get("thought")) && part.get("text") instanceof String) { // NOI18N
                                sb.append((String) part.get("text")); // NOI18N
                            }
                        }
                    }
                }
            }
        }
        return sb.toString();
    }

    /** All three providers report errors as {"error": {"message": ...}}, Gemini sometimes wrapped in an array. */
    static String errorMessage(String body) {
        Object parsed = JSONValue.parse(body);
        if (parsed instanceof JSONArray && !((JSONArray) parsed).isEmpty()) {
            parsed = ((JSONArray) parsed).get(0);
        }
        if (parsed instanceof JSONObject) {
            Object error = ((JSONObject) parsed).get("error"); // NOI18N
            if (error instanceof JSONObject && ((JSONObject) error).get("message") != null) { // NOI18N
                return String.valueOf(((JSONObject) error).get("message")); // NOI18N
            }
        }
        return body.length() > 500 ? body.substring(0, 500) + "..." : body;
    }
}
