/*
 * Copyright 2026 Luciano Lopes.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 */
package org.netbeans.modules.ai.chat;

import java.awt.Font;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.CaretEvent;
import javax.swing.event.CaretListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import org.netbeans.api.editor.EditorRegistry;
import org.netbeans.api.editor.settings.AttributesUtilities;
import org.netbeans.lib.editor.util.swing.DocumentUtilities;
import org.netbeans.spi.editor.highlighting.support.OffsetsBag;
import org.openide.modules.OnStart;
import org.openide.text.NbDocument;
import org.openide.util.RequestProcessor;

/**
 * Full-line AI completion: after a typing pause at the end of a line, asks a
 * fast model for the rest of the line and shows it as grey ghost text.
 * Tab accepts, Esc or any other edit/caret move dismisses it.
 */
@OnStart
public final class InlineCompletion implements Runnable {

    private static final Logger LOG = Logger.getLogger(InlineCompletion.class.getName());
    private static final RequestProcessor RP = new RequestProcessor(InlineCompletion.class);
    private static final int DELAY_MS = 600;
    private static final int MAX_PREFIX = 4000;
    private static final int MAX_SUFFIX = 1500;
    private static final int MAX_TOKENS = 128;
    private static final String CURSOR = "<CURSOR>"; // NOI18N
    private static final String SYSTEM_PROMPT =
            "You are a code completion engine inside an IDE. You receive a file with a " + CURSOR // NOI18N
            + " marker. Reply ONLY with the text that should be inserted at the marker to complete the " // NOI18N
            + "current line: no explanations, no Markdown, no code fences, a single line, and do not " // NOI18N
            + "repeat text that is already before the marker. Reply with nothing if no completion fits."; // NOI18N

    private final Timer timer = new Timer(DELAY_MS, e -> request());
    private final Listener listener = new Listener();
    private JTextComponent component;
    private Document document;
    /** Bumped on every edit so late answers for stale text are dropped. */
    private int editVersion;
    private String suggestion;
    private int suggestionOffset = -1;
    private ChatClient inflight;
    private boolean errorLogged;

    @Override
    public void run() {
        timer.setRepeats(false);
        SwingUtilities.invokeLater(() -> {
            EditorRegistry.addPropertyChangeListener(listener);
            attach(EditorRegistry.focusedComponent());
        });
    }

    private void attach(JTextComponent c) {
        if (c == component) {
            return;
        }
        detach();
        if (c == null) {
            return;
        }
        component = c;
        document = c.getDocument();
        document.addDocumentListener(listener);
        c.addCaretListener(listener);
        c.addKeyListener(listener);
    }

    private void detach() {
        dismiss();
        timer.stop();
        if (component != null) {
            component.removeCaretListener(listener);
            component.removeKeyListener(listener);
            document.removeDocumentListener(listener);
        }
        component = null;
        document = null;
    }

    private void request() {
        JTextComponent c = component;
        if (!AiSettings.inlineEnabled() || c == null || !c.isShowing() || !c.isEditable()
                || c.getSelectionStart() != c.getSelectionEnd()) {
            return;
        }
        Document doc = c.getDocument();
        int caret = c.getCaretPosition();
        String prefix;
        String suffix;
        String linePrefix;
        try {
            Element line = doc.getDefaultRootElement().getElement(doc.getDefaultRootElement().getElementIndex(caret));
            // only at the end of a line that already has some text, like IntelliJ's full line completion
            if (caret != line.getEndOffset() - 1 || caret >= doc.getLength()) {
                return;
            }
            linePrefix = doc.getText(line.getStartOffset(), caret - line.getStartOffset());
            if (linePrefix.isBlank()) {
                return;
            }
            int from = Math.max(0, caret - MAX_PREFIX);
            prefix = doc.getText(from, caret - from);
            suffix = doc.getText(caret, Math.min(doc.getLength() - caret, MAX_SUFFIX));
        } catch (BadLocationException ex) {
            return;
        }
        String prompt = "File: " + CodeApplier.nameOf(doc) + "\n\n" + prefix + CURSOR + suffix; // NOI18N
        AiProvider provider = AiSettings.inlineProvider();
        String model = AiSettings.inlineModel(provider);
        int version = editVersion;
        cancelInflight();
        ChatClient client = new ChatClient();
        inflight = client;

        RP.post(() -> {
            String key = AiSettings.apiKey(provider);
            if (key == null) {
                return;
            }
            StringBuilder answer = new StringBuilder();
            try {
                client.stream(provider, model, key, SYSTEM_PROMPT,
                        List.of(new ChatMessage(ChatMessage.Role.USER, null, prompt)), MAX_TOKENS, answer::append);
            } catch (IOException ex) {
                if (!errorLogged) {
                    errorLogged = true;
                    LOG.log(Level.INFO, "Inline completion failed: {0}", ex.getMessage()); // NOI18N
                }
                return;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            String text = clean(answer.toString(), linePrefix);
            SwingUtilities.invokeLater(() -> {
                if (inflight == client) {
                    inflight = null;
                }
                if (component == c && editVersion == version && c.getCaretPosition() == caret && !text.isEmpty()) {
                    show(text, caret);
                }
            });
        });
    }

    /** Keeps the first line of the answer and drops an echo of what is already typed. */
    static String clean(String answer, String linePrefix) {
        String text = answer.replaceAll("(?m)^[ \\t]*```[^\\n]*(\\n|$)", ""); // NOI18N
        if (text.startsWith("\n") || text.startsWith("\r")) { // NOI18N
            return "";
        }
        int nl = text.indexOf('\n');
        if (nl >= 0) {
            text = text.substring(0, nl);
        }
        String typed = linePrefix.strip();
        if (typed.length() > 2 && text.strip().startsWith(typed)) {
            text = text.strip().substring(typed.length());
        }
        return text.stripTrailing();
    }

    private void show(String text, int offset) {
        Font font = component.getFont();
        AttributeSet attrs = AttributesUtilities.createImmutable(
                "virtual-text-prepend", text, // NOI18N
                StyleConstants.FontFamily, font.getFamily(),
                StyleConstants.FontSize, font.getSize(),
                StyleConstants.Italic, Boolean.TRUE);
        suggestion = text;
        suggestionOffset = offset;
        OffsetsBag bag = GhostTextLayerFactory.bag(document);
        bag.clear();
        bag.addHighlight(offset, offset + 1, attrs);
    }

    private void dismiss() {
        if (suggestion != null) {
            suggestion = null;
            suggestionOffset = -1;
            GhostTextLayerFactory.bag(document).clear();
        }
    }

    private void cancelInflight() {
        if (inflight != null) {
            inflight.cancel();
            inflight = null;
        }
    }

    private void accept() {
        String text = suggestion;
        int offset = suggestionOffset;
        JTextComponent c = component;
        dismiss();
        if (!(c.getDocument() instanceof StyledDocument)) {
            return;
        }
        StyledDocument doc = (StyledDocument) c.getDocument();
        try {
            NbDocument.runAtomicAsUser(doc, () -> {
                try {
                    doc.insertString(offset, text, null);
                } catch (BadLocationException ex) {
                    LOG.log(Level.FINE, null, ex);
                }
            });
            c.setCaretPosition(offset + text.length());
        } catch (BadLocationException ex) {
            LOG.log(Level.FINE, null, ex);
        }
    }

    private final class Listener extends KeyAdapter implements PropertyChangeListener, DocumentListener, CaretListener {

        @Override
        public void propertyChange(PropertyChangeEvent evt) {
            if (EditorRegistry.FOCUS_GAINED_PROPERTY.equals(evt.getPropertyName())) {
                attach(EditorRegistry.focusedComponent());
            } else if (EditorRegistry.FOCUS_LOST_PROPERTY.equals(evt.getPropertyName())) {
                dismiss();
                timer.stop();
                cancelInflight();
            } else if (EditorRegistry.COMPONENT_REMOVED_PROPERTY.equals(evt.getPropertyName())
                    && evt.getOldValue() == component) {
                detach();
            }
        }

        @Override
        public void insertUpdate(DocumentEvent e) {
            edited(e);
        }

        @Override
        public void removeUpdate(DocumentEvent e) {
            edited(e);
        }

        @Override
        public void changedUpdate(DocumentEvent e) {
        }

        private void edited(DocumentEvent e) {
            boolean typing = DocumentUtilities.isTypingModification(e.getDocument());
            Runnable r = () -> {
                editVersion++;
                dismiss();
                cancelInflight();
                // only the user's own typing triggers a request, not reformat/refactoring/our accept()
                if (typing && AiSettings.inlineEnabled()) {
                    timer.restart();
                } else {
                    timer.stop();
                }
            };
            if (SwingUtilities.isEventDispatchThread()) {
                r.run();
            } else {
                SwingUtilities.invokeLater(r);
            }
        }

        @Override
        public void caretUpdate(CaretEvent e) {
            if (suggestion != null && e.getDot() != suggestionOffset) {
                dismiss();
            }
        }

        @Override
        public void keyPressed(KeyEvent e) {
            if (suggestion == null || e.getModifiersEx() != 0) {
                return;
            }
            if (e.getKeyCode() == KeyEvent.VK_TAB) {
                e.consume();
                accept();
            } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                e.consume();
                dismiss();
            }
        }
    }
}
