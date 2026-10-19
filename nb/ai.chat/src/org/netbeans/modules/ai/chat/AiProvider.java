/*
 * Copyright 2026 Luciano Lopes.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 */
package org.netbeans.modules.ai.chat;

enum AiProvider {

    CLAUDE("Claude", "claude-opus-5-5", "ANTHROPIC_API_KEY"),
    CHATGPT("ChatGPT", "gpt-6-sol", "OPENAI_API_KEY"),
    GEMINI("Gemini", "gemini-4-argon", "GEMINI_API_KEY");

    final String displayName;
    final String defaultModel;
    final String envVariable;

    AiProvider(String displayName, String defaultModel, String envVariable) {
        this.displayName = displayName;
        this.defaultModel = defaultModel;
        this.envVariable = envVariable;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
