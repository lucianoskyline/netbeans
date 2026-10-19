/*
 * Copyright 2026 Luciano Lopes.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 */
package org.netbeans.modules.ai.chat;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import org.netbeans.api.editor.EditorRegistry;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionReferences;
import org.openide.loaders.DataObject;
import org.openide.util.NbBundle;
import org.openide.util.RequestProcessor;
import org.openide.windows.TopComponent;

@TopComponent.Description(
        preferredID = AiChatTopComponent.ID,
        iconBase = "org/netbeans/modules/ai/chat/resources/ai.svg",
        persistenceType = TopComponent.PERSISTENCE_ONLY_OPENED)
@TopComponent.Registration(mode = "properties", openAtStartup = false, position = 500)
@ActionID(category = "Window", id = "org.netbeans.modules.ai.chat.AiChatTopComponent")
@ActionReferences({
    @ActionReference(path = "Menu/Window", position = 215),
    @ActionReference(path = "Toolbars/File", position = 2000)
})
@TopComponent.OpenActionRegistration(displayName = "#CTL_AiChatAction", preferredID = AiChatTopComponent.ID)
@NbBundle.Messages({
    "CTL_AiChatAction=AI Chat",
    "CTL_AiChatTopComponent=AI Chat",
    "HINT_AiChatTopComponent=Chat with Claude, ChatGPT or Gemini",
    "LBL_NewChat=New Chat",
    "LBL_Send=Send",
    "LBL_Stop=Stop",
    "LBL_IncludeContext=Include current file / selection",
    "LBL_You=You",
    "# {0} - model name",
    "LBL_Model=Model: {0}",
    "LBL_Thinking=...",
    "# {0} - provider name",
    "# {1} - environment variable",
    "ERR_NoApiKey=No API key for {0}. Set it in Tools > Options > Miscellaneous > AI Chat, or in the {1} environment variable.",
    "# {0} - file name",
    "LBL_ContextFile=context: {0}",
    "# {0} - file name",
    "LBL_ContextSelection=context: selection from {0}",
    "LBL_Stopped=[stopped]",
    "LBL_Welcome=Ask anything about your code. Enter sends, Shift+Enter adds a new line."
})
public final class AiChatTopComponent extends TopComponent {

    static final String ID = "AiChatTopComponent"; // NOI18N

    private static final RequestProcessor RP = new RequestProcessor(AiChatTopComponent.class);
    private static final int MAX_CONTEXT_CHARS = 200_000;
    private static final String SYSTEM_PROMPT =
            "You are an AI coding assistant embedded in the Apache NetBeans IDE. " // NOI18N
            + "Be concise and precise. Use Markdown code fences with a language tag for code. " // NOI18N
            + "Answer in the same language the user writes in."; // NOI18N

    private final List<ChatMessage> history = new ArrayList<>();
    private final AtomicBoolean renderPending = new AtomicBoolean();
    private final JComboBox<AiProvider> providerCombo = new JComboBox<>(AiProvider.values());
    private final JLabel modelLabel = new JLabel();
    private final JTextPane transcript = new JTextPane();
    private final JTextArea input = new JTextArea(3, 20);
    private final JCheckBox includeContext = new JCheckBox(Bundle.LBL_IncludeContext(), true);
    private final JButton sendButton = new JButton(Bundle.LBL_Send());
    private volatile ChatClient current;

    public AiChatTopComponent() {
        setName(Bundle.CTL_AiChatTopComponent());
        setToolTipText(Bundle.HINT_AiChatTopComponent());
        setLayout(new BorderLayout());
        add(createHeader(), BorderLayout.NORTH);
        add(createTranscript(), BorderLayout.CENTER);
        add(createInput(), BorderLayout.SOUTH);
        render();
    }

    private JComponent createHeader() {
        providerCombo.setSelectedItem(AiSettings.lastProvider());
        providerCombo.addActionListener(e -> {
            AiSettings.setLastProvider(selectedProvider());
            updateModelLabel();
        });
        JButton newChat = new JButton(Bundle.LBL_NewChat());
        newChat.addActionListener(e -> newChat());
        modelLabel.setForeground(UIManager.getColor("Label.disabledForeground")); // NOI18N
        updateModelLabel();

        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
        header.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        header.add(providerCombo);
        header.add(Box.createHorizontalStrut(8));
        header.add(modelLabel);
        header.add(Box.createHorizontalGlue());
        header.add(newChat);
        providerCombo.setMaximumSize(providerCombo.getPreferredSize());
        return header;
    }

    private JComponent createTranscript() {
        transcript.setEditable(false);
        transcript.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        JScrollPane scroll = new JScrollPane(transcript);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, UIManager.getColor("Component.borderColor"))); // NOI18N
        return scroll;
    }

    private JComponent createInput() {
        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "ai-send"); // NOI18N
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK),
                DefaultEditorKit.insertBreakAction);
        input.getActionMap().put("ai-send", new AbstractAction() { // NOI18N
            @Override
            public void actionPerformed(ActionEvent e) {
                send();
            }
        });
        sendButton.addActionListener(e -> {
            if (current != null) {
                stop();
            } else {
                send();
            }
        });

        JPanel buttons = new JPanel(new BorderLayout());
        buttons.add(includeContext, BorderLayout.WEST);
        buttons.add(sendButton, BorderLayout.EAST);

        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));
        panel.add(new JScrollPane(input), BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private AiProvider selectedProvider() {
        return (AiProvider) providerCombo.getSelectedItem();
    }

    private void updateModelLabel() {
        modelLabel.setText(Bundle.LBL_Model(AiSettings.model(selectedProvider())));
    }

    @Override
    protected void componentActivated() {
        updateModelLabel();
        input.requestFocusInWindow();
    }

    private void newChat() {
        stop();
        history.clear();
        render();
        input.requestFocusInWindow();
    }

    private void send() {
        String text = input.getText().trim();
        if (text.isEmpty() || current != null) {
            return;
        }
        String display = text;
        String content = text;
        if (includeContext.isSelected()) {
            String[] ctx = captureEditorContext();
            if (ctx != null) {
                display = text + "\n[" + ctx[0] + "]";
                content = ctx[1] + "\n\n" + text;
            }
        }
        ChatMessage question = new ChatMessage(ChatMessage.Role.USER, display, content);
        history.add(question);
        List<ChatMessage> conversation = apiHistory();
        AiProvider provider = selectedProvider();
        String model = AiSettings.model(provider);
        ChatMessage reply = new ChatMessage(ChatMessage.Role.ASSISTANT, provider.displayName, "");
        history.add(reply);
        input.setText("");
        ChatClient client = new ChatClient();
        setBusy(client);
        render();

        RP.post(() -> {
            String error = null;
            try {
                String key = AiSettings.apiKey(provider);
                if (key == null) {
                    error = Bundle.ERR_NoApiKey(provider.displayName, provider.envVariable);
                } else {
                    client.stream(provider, model, key, SYSTEM_PROMPT, conversation, delta -> {
                        synchronized (reply) {
                            reply.content.append(delta);
                        }
                        scheduleRender();
                    });
                }
            } catch (IOException ex) {
                error = ex.getMessage() != null ? ex.getMessage() : ex.toString();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            String failure = error;
            SwingUtilities.invokeLater(() -> finish(question, reply, client, failure));
        });
    }

    private void finish(ChatMessage question, ChatMessage reply, ChatClient client, String error) {
        if (current == client) {
            setBusy(null);
        }
        boolean empty;
        synchronized (reply) {
            empty = reply.content.length() == 0;
        }
        if (error != null || empty) {
            // a turn without an answer must not be replayed to the API on the next question
            question.excluded = true;
            if (empty) {
                history.remove(reply);
            }
            if (error != null) {
                history.add(new ChatMessage(ChatMessage.Role.ERROR, null, error));
            }
        }
        render();
    }

    private void stop() {
        ChatClient client = current;
        if (client != null) {
            client.cancel();
            setBusy(null);
        }
    }

    private void setBusy(ChatClient client) {
        current = client;
        sendButton.setText(client != null ? Bundle.LBL_Stop() : Bundle.LBL_Send());
        providerCombo.setEnabled(client == null);
    }

    private List<ChatMessage> apiHistory() {
        List<ChatMessage> result = new ArrayList<>();
        for (ChatMessage m : history) {
            if (m.role != ChatMessage.Role.ERROR && !m.excluded) {
                result.add(m);
            }
        }
        return result;
    }

    /** @return {label, prompt text} or null when no editor is open */
    private static String[] captureEditorContext() {
        JTextComponent editor = EditorRegistry.lastFocusedComponent();
        if (editor == null) {
            return null;
        }
        Document doc = editor.getDocument();
        Object source = doc.getProperty(Document.StreamDescriptionProperty);
        String name = source instanceof DataObject
                ? ((DataObject) source).getPrimaryFile().getNameExt()
                : "untitled"; // NOI18N
        String selection = editor.getSelectedText();
        boolean isSelection = selection != null && !selection.isBlank();
        String code = isSelection ? selection : editor.getText();
        if (code.length() > MAX_CONTEXT_CHARS) {
            code = code.substring(0, MAX_CONTEXT_CHARS);
        }
        String label = isSelection ? Bundle.LBL_ContextSelection(name) : Bundle.LBL_ContextFile(name);
        String prompt = (isSelection ? "Selected code from " : "Contents of the file ") + name // NOI18N
                + " open in the editor:\n```\n" + code + "\n```"; // NOI18N
        return new String[] {label, prompt};
    }

    private void scheduleRender() {
        if (renderPending.compareAndSet(false, true)) {
            SwingUtilities.invokeLater(() -> {
                renderPending.set(false);
                render();
            });
        }
    }

    private void render() {
        Color fg = UIManager.getColor("TextPane.foreground"); // NOI18N
        Color bg = UIManager.getColor("TextPane.background"); // NOI18N
        Color accent = UIManager.getColor("Component.accentColor"); // NOI18N
        if (accent == null) {
            accent = UIManager.getColor("Component.focusColor"); // NOI18N
        }
        Font font = UIManager.getFont("Label.font"); // NOI18N

        SimpleAttributeSet text = new SimpleAttributeSet();
        StyleConstants.setFontFamily(text, font.getFamily());
        StyleConstants.setFontSize(text, font.getSize());
        StyleConstants.setForeground(text, fg);
        SimpleAttributeSet header = new SimpleAttributeSet(text);
        StyleConstants.setBold(header, true);
        SimpleAttributeSet assistantHeader = new SimpleAttributeSet(header);
        if (accent != null) {
            StyleConstants.setForeground(assistantHeader, accent);
        }
        SimpleAttributeSet muted = new SimpleAttributeSet(text);
        StyleConstants.setItalic(muted, true);
        StyleConstants.setForeground(muted, mix(fg, bg, 0.45f));
        SimpleAttributeSet error = new SimpleAttributeSet(text);
        StyleConstants.setForeground(error, new Color(0xE5, 0x53, 0x53));
        SimpleAttributeSet code = new SimpleAttributeSet(text);
        StyleConstants.setFontFamily(code, Font.MONOSPACED);
        StyleConstants.setBackground(code, mix(bg, fg, 0.08f));

        StyledDocument doc = transcript.getStyledDocument();
        try {
            doc.remove(0, doc.getLength());
            if (history.isEmpty()) {
                doc.insertString(0, Bundle.LBL_Welcome(), muted);
            }
            for (ChatMessage m : history) {
                if (doc.getLength() > 0) {
                    doc.insertString(doc.getLength(), "\n\n", text);
                }
                switch (m.role) {
                    case USER -> {
                        doc.insertString(doc.getLength(), Bundle.LBL_You() + "\n", header);
                        appendUserText(doc, m.display, text, muted);
                    }
                    case ASSISTANT -> {
                        String body;
                        synchronized (m) {
                            body = m.content.toString();
                        }
                        doc.insertString(doc.getLength(), m.display + "\n", assistantHeader);
                        if (body.isEmpty()) {
                            doc.insertString(doc.getLength(), Bundle.LBL_Thinking(), muted);
                        } else {
                            appendMarkdown(doc, body, text, code);
                        }
                    }
                    case ERROR -> doc.insertString(doc.getLength(), m.content.toString(), error);
                }
            }
        } catch (BadLocationException ex) {
            throw new IllegalStateException(ex);
        }
        transcript.setCaretPosition(doc.getLength());
    }

    private static void appendUserText(StyledDocument doc, String display, SimpleAttributeSet text,
            SimpleAttributeSet muted) throws BadLocationException {
        int ctx = display.lastIndexOf("\n["); // NOI18N
        if (ctx >= 0 && display.endsWith("]")) { // NOI18N
            doc.insertString(doc.getLength(), display.substring(0, ctx + 1), text);
            doc.insertString(doc.getLength(), display.substring(ctx + 1), muted);
        } else {
            doc.insertString(doc.getLength(), display, text);
        }
    }

    /** Minimal Markdown: fenced code blocks get a monospaced style, everything else is plain text. */
    private static void appendMarkdown(StyledDocument doc, String markdown, SimpleAttributeSet text,
            SimpleAttributeSet code) throws BadLocationException {
        boolean inCode = false;
        String[] lines = markdown.split("\n", -1); // NOI18N
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().startsWith("```")) { // NOI18N
                inCode = !inCode;
                continue;
            }
            boolean last = i == lines.length - 1;
            doc.insertString(doc.getLength(), last ? line : line + "\n", inCode ? code : text);
        }
    }

    private static Color mix(Color a, Color b, float amount) {
        return new Color(
                Math.round(a.getRed() + (b.getRed() - a.getRed()) * amount),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * amount),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * amount));
    }
}
