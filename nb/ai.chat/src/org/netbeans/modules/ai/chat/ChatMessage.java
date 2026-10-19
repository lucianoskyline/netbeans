/*
 * Copyright 2026 Luciano Lopes.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 */
package org.netbeans.modules.ai.chat;

/**
 * One turn of the conversation. {@code display} is what the transcript shows;
 * {@code content} is what is sent to the API (it may carry the editor context).
 */
final class ChatMessage {

    /** ERROR entries are shown in the transcript only and never sent to the API. */
    enum Role { USER, ASSISTANT, ERROR }

    final Role role;
    final String display;
    final StringBuilder content;
    /** Set on questions that got no answer, so they are not replayed in later requests. */
    boolean excluded;
    /** For answers: the editor region the question was about, where "Apply" writes to. */
    CodeApplier.Target target;

    ChatMessage(Role role, String display, String content) {
        this.role = role;
        this.display = display;
        this.content = new StringBuilder(content);
    }
}
