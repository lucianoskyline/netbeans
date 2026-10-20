/*
 * Copyright 2026 Luciano Lopes.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 */
package org.netbeans.modules.spring.boot;

import java.beans.PropertyChangeEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import org.netbeans.api.editor.EditorRegistry;
import org.netbeans.api.editor.document.EditorDocumentUtils;
import org.netbeans.spi.editor.hints.ErrorDescription;
import org.netbeans.spi.editor.hints.ErrorDescriptionFactory;
import org.netbeans.spi.editor.hints.HintsController;
import org.netbeans.spi.editor.hints.Severity;
import org.openide.filesystems.FileObject;
import org.openide.modules.OnStart;
import org.openide.util.NbBundle;
import org.openide.util.RequestProcessor;

/**
 * Marks unknown and deprecated keys and obviously wrong values in Spring Boot
 * configuration files, re-checked shortly after each edit.
 */
@OnStart
@NbBundle.Messages({
    "# {0} - key",
    "WARN_Unknown=Unknown property ''{0}''",
    "# {0} - key",
    "WARN_Deprecated=''{0}'' is deprecated",
    "# {0} - key",
    "# {1} - replacement",
    "WARN_DeprecatedUse=''{0}'' is deprecated, use ''{1}''",
    "# {0} - value",
    "# {1} - type",
    "WARN_BadValue=''{0}'' is not a valid {1}"
})
public final class ConfigValidator implements Runnable {

    private static final String LAYER = "spring-boot-config"; // NOI18N
    private static final RequestProcessor RP = new RequestProcessor(ConfigValidator.class);
    private static final int DELAY_MS = 700;

    private final Timer timer = new Timer(DELAY_MS, e -> validate());
    private final DocumentListener docListener = new DocumentListener() {
        @Override
        public void insertUpdate(DocumentEvent e) {
            changed();
        }

        @Override
        public void removeUpdate(DocumentEvent e) {
            changed();
        }

        @Override
        public void changedUpdate(DocumentEvent e) {
        }
    };
    private JTextComponent component;
    private int version;

    @Override
    public void run() {
        timer.setRepeats(false);
        SwingUtilities.invokeLater(() -> {
            EditorRegistry.addPropertyChangeListener(this::focusChanged);
            attach(EditorRegistry.focusedComponent());
        });
    }

    private void focusChanged(PropertyChangeEvent evt) {
        if (EditorRegistry.FOCUS_GAINED_PROPERTY.equals(evt.getPropertyName())) {
            attach(EditorRegistry.focusedComponent());
        }
    }

    private void attach(JTextComponent c) {
        if (c == component) {
            return;
        }
        if (component != null) {
            component.getDocument().removeDocumentListener(docListener);
        }
        component = c != null && Metadata.isConfigFile(EditorDocumentUtils.getFileObject(c.getDocument())) ? c : null;
        if (component != null) {
            component.getDocument().addDocumentListener(docListener);
            timer.restart();
        }
    }

    private void changed() {
        SwingUtilities.invokeLater(() -> {
            version++;
            timer.restart();
        });
    }

    private void validate() {
        JTextComponent c = component;
        if (c == null) {
            return;
        }
        Document doc = c.getDocument();
        FileObject fo = EditorDocumentUtils.getFileObject(doc);
        String text = c.getText();
        int v = version;
        RP.post(() -> {
            Metadata md = Metadata.forFile(fo);
            List<Problem> problems = md == null || md.isEmpty() ? List.of() : check(md, text, ConfigSyntax.isYaml(fo.getNameExt()), fo);
            SwingUtilities.invokeLater(() -> {
                if (version != v || component != c) {
                    return;
                }
                List<ErrorDescription> errors = new ArrayList<>();
                for (Problem p : problems) {
                    try {
                        errors.add(ErrorDescriptionFactory.createErrorDescription(p.severity, p.message, doc,
                                doc.createPosition(p.start), doc.createPosition(p.end)));
                    } catch (BadLocationException ex) {
                        // shorter document now; skip
                    }
                }
                HintsController.setErrors(doc, LAYER, errors);
            });
        });
    }

    static final class Problem {
        final Severity severity;
        final String message;
        final int start;
        final int end;

        Problem(Severity severity, String message, int start, int end) {
            this.severity = severity;
            this.message = message;
            this.start = start;
            this.end = end;
        }
    }

    /** @param file used to resolve enum types; null skips enum checks */
    static List<Problem> check(Metadata md, String text, boolean yaml, FileObject file) {
        List<Problem> problems = new ArrayList<>();
        for (ConfigSyntax.Entry e : yaml ? ConfigSyntax.parseYaml(text) : ConfigSyntax.parseProperties(text)) {
            if (e.parent) {
                // a YAML node with children; only flag it if nothing at all lives under it
                if (!md.isGroup(e.key) && !md.isKnown(e.key)) {
                    problems.add(new Problem(Severity.WARNING, Bundle.WARN_Unknown(e.key), e.keyStart, e.keyEnd));
                }
                continue;
            }
            Metadata.Property p = md.find(e.key);
            if (p == null) {
                if (!md.isKnown(e.key) && e.keyEnd > e.keyStart) {
                    problems.add(new Problem(Severity.WARNING, Bundle.WARN_Unknown(e.key), e.keyStart, e.keyEnd));
                }
                continue;
            }
            if (p.deprecated) {
                problems.add(new Problem(p.deprecationError ? Severity.ERROR : Severity.WARNING,
                        p.replacement != null ? Bundle.WARN_DeprecatedUse(e.key, p.replacement) : Bundle.WARN_Deprecated(e.key),
                        e.keyStart, e.keyEnd));
            }
            String value = ConfigSyntax.unquote(e.value);
            if (value.isEmpty() || value.contains("${")) { // NOI18N  placeholders resolve at runtime
                continue;
            }
            String bad = badValue(p, value);
            if (bad == null && file != null && p.values.isEmpty()) {
                java.util.List<String> constants = EnumValues.of(file, p);
                if (!constants.isEmpty() && !EnumValues.matches(constants, value)) {
                    bad = p.simpleType() + " (" + String.join(", ", constants) + ")"; // NOI18N
                }
            }
            if (bad != null) {
                problems.add(new Problem(Severity.WARNING, Bundle.WARN_BadValue(value, bad),
                        e.valueStart, e.valueStart + e.value.length()));
            }
        }
        return problems;
    }

    /** The expected type name when {@code value} cannot be converted, else null. */
    private static String badValue(Metadata.Property p, String value) {
        String t = p.type == null ? "" : p.type; // NOI18N
        switch (t) {
            case "java.lang.Boolean", "boolean" -> { // NOI18N
                return value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false") ? null : "boolean"; // NOI18N
            }
            case "java.lang.Integer", "int", "java.lang.Long", "long", "java.lang.Short", "short" -> { // NOI18N
                try {
                    Long.parseLong(value.replace("_", "")); // NOI18N
                    return null;
                } catch (NumberFormatException ex) {
                    return "integer"; // NOI18N
                }
            }
            case "java.lang.Double", "double", "java.lang.Float", "float" -> { // NOI18N
                try {
                    Double.parseDouble(value);
                    return null;
                } catch (NumberFormatException ex) {
                    return "number"; // NOI18N
                }
            }
            default -> {
                return null;
            }
        }
    }
}
