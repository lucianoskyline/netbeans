/*
 * Copyright 2026 Luciano Lopes.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 */
package org.netbeans.modules.spring.boot;

import java.net.URL;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.swing.Action;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import org.netbeans.api.editor.document.EditorDocumentUtils;
import org.netbeans.api.editor.mimelookup.MimeRegistration;
import org.netbeans.api.editor.mimelookup.MimeRegistrations;
import org.netbeans.spi.editor.completion.CompletionDocumentation;
import org.netbeans.spi.editor.completion.CompletionProvider;
import org.netbeans.spi.editor.completion.CompletionResultSet;
import org.netbeans.spi.editor.completion.CompletionTask;
import org.netbeans.spi.editor.completion.support.AsyncCompletionQuery;
import org.netbeans.spi.editor.completion.support.AsyncCompletionTask;
import org.netbeans.spi.editor.completion.support.CompletionUtilities;
import org.openide.filesystems.FileObject;

/** Code completion of keys and values in application*.properties / application*.yml. */
@MimeRegistrations({
    @MimeRegistration(mimeType = "text/x-properties", service = CompletionProvider.class),
    @MimeRegistration(mimeType = "text/x-yaml", service = CompletionProvider.class)
})
public final class ConfigCompletion implements CompletionProvider {

    static final String ICON = "org/netbeans/modules/spring/boot/resources/property.svg"; // NOI18N
    private static final int MAX_ITEMS = 300;

    @Override
    public CompletionTask createTask(int queryType, JTextComponent component) {
        FileObject fo = EditorDocumentUtils.getFileObject(component.getDocument());
        if (!Metadata.isConfigFile(fo) || (queryType & COMPLETION_QUERY_TYPE) == 0) {
            return null;
        }
        return new AsyncCompletionTask(new Query(fo), component);
    }

    @Override
    public int getAutoQueryTypes(JTextComponent component, String typedText) {
        // pop up after "." in a key and after "=" / ": " for values, like IntelliJ
        if (".".equals(typedText) || "=".equals(typedText) || ": ".equals(typedText)) { // NOI18N
            FileObject fo = EditorDocumentUtils.getFileObject(component.getDocument());
            return Metadata.isConfigFile(fo) ? COMPLETION_QUERY_TYPE : 0;
        }
        return 0;
    }

    private static final class Query extends AsyncCompletionQuery {

        private final FileObject file;

        Query(FileObject file) {
            this.file = file;
        }

        @Override
        protected void query(CompletionResultSet result, Document doc, int caret) {
            try {
                Metadata md = Metadata.forFile(file);
                String text = doc.getText(0, doc.getLength());
                boolean yaml = ConfigSyntax.isYaml(file.getNameExt());
                ConfigSyntax.Context ctx = yaml ? ConfigSyntax.yamlContext(text, caret) : ConfigSyntax.propertiesContext(text, caret);
                if (md != null && !md.isEmpty() && ctx != null) {
                    if (ctx.value) {
                        values(result, md, ctx, caret);
                    } else {
                        keys(result, md, ctx, yaml, caret);
                    }
                }
            } catch (BadLocationException ex) {
                // document changed under us; the next query will be right
            }
            result.finish();
        }

        private void keys(CompletionResultSet result, Metadata md, ConfigSyntax.Context ctx, boolean yaml, int caret) {
            String base = ctx.parent.isEmpty() ? "" : ctx.parent + "."; // NOI18N
            List<Metadata.Property> found = md.complete(base + ctx.prefix);
            int count = 0;
            for (Metadata.Property p : found) {
                // metadata names are already canonical (kebab-case), so the base length lines up
                String canonicalBase = Metadata.canonical(base);
                boolean underBase = Metadata.canonical(p.name).startsWith(canonicalBase);
                if (yaml && !base.isEmpty() && !underBase) {
                    continue; // segment match outside the current YAML mapping
                }
                String relative = underBase ? p.name.substring(canonicalBase.length()) : p.name;
                String insert = relative + (yaml ? ": " : "="); // NOI18N
                String name = p.deprecated ? "<s>" + escape(relative) + "</s>" : "<b>" + escape(relative) + "</b>"; // NOI18N
                result.addItem(CompletionUtilities.newCompletionItemBuilder(insert)
                        .startOffset(ctx.prefixStart)
                        .endOffset(caret)
                        .iconResource(ICON)
                        .leftHtmlText(name)
                        .rightHtmlText(escape(p.simpleType()))
                        .sortPriority(p.deprecated ? 200 : 100)
                        .sortText(relative)
                        .documentationTask(() -> docTask(p))
                        .build());
                if (++count >= MAX_ITEMS) {
                    break;
                }
            }
        }

        private void values(CompletionResultSet result, Metadata md, ConfigSyntax.Context ctx, int caret) {
            Metadata.Property p = md.find(ctx.key);
            if (p == null) {
                return;
            }
            Set<String> seen = new LinkedHashSet<>();
            for (Metadata.Value v : p.values) {
                addValue(result, ctx, caret, v.value, v.description, p, seen);
            }
            if (p.isBoolean()) {
                addValue(result, ctx, caret, "true", null, p, seen); // NOI18N
                addValue(result, ctx, caret, "false", null, p, seen); // NOI18N
            }
            for (String constant : EnumValues.of(file, p)) {
                addValue(result, ctx, caret, constant, p.simpleType(), p, seen);
            }
            if (p.defaultValue != null) {
                addValue(result, ctx, caret, p.defaultValue, "default", p, seen); // NOI18N
            }
        }

        private void addValue(CompletionResultSet result, ConfigSyntax.Context ctx, int caret, String value,
                String description, Metadata.Property p, Set<String> seen) {
            if (!seen.add(value) || !value.toLowerCase().startsWith(ctx.prefix.toLowerCase())) {
                return;
            }
            result.addItem(CompletionUtilities.newCompletionItemBuilder(value)
                    .startOffset(ctx.prefixStart)
                    .endOffset(caret)
                    .iconResource(ICON)
                    .leftHtmlText("<b>" + escape(value) + "</b>") // NOI18N
                    .rightHtmlText(description == null ? "" : escape(description.length() > 60 ? description.substring(0, 57) + "..." : description)) // NOI18N
                    .sortPriority("default".equals(description) ? 50 : 100) // NOI18N
                    .sortText(value)
                    .documentationTask(() -> docTask(p))
                    .build());
        }
    }

    static CompletionTask docTask(Metadata.Property p) {
        return new AsyncCompletionTask(new AsyncCompletionQuery() {
            @Override
            protected void query(CompletionResultSet result, Document doc, int caretOffset) {
                result.setDocumentation(new Doc(p));
                result.finish();
            }
        });
    }

    /** HTML documentation shared by completion and hover. */
    static String documentation(Metadata.Property p) {
        StringBuilder sb = new StringBuilder();
        sb.append("<b>").append(escape(p.name)).append("</b>"); // NOI18N
        if (p.type != null) {
            sb.append("<br><code>").append(escape(p.type)).append("</code>"); // NOI18N
        }
        if (p.deprecated) {
            sb.append("<p><b>Deprecated</b>"); // NOI18N
            if (p.replacement != null) {
                sb.append(": use <code>").append(escape(p.replacement)).append("</code>"); // NOI18N
            }
            if (p.deprecationReason != null) {
                sb.append(". ").append(escape(p.deprecationReason)); // NOI18N
            }
        }
        if (p.description != null) {
            sb.append("<p>").append(p.description); // NOI18N  metadata descriptions are already HTML-ish text
        }
        if (p.defaultValue != null) {
            sb.append("<p>Default: <code>").append(escape(p.defaultValue)).append("</code>"); // NOI18N
        }
        if (!p.values.isEmpty()) {
            sb.append("<p>Values:<ul>"); // NOI18N
            for (Metadata.Value v : p.values) {
                sb.append("<li><code>").append(escape(v.value)).append("</code>"); // NOI18N
                if (v.description != null) {
                    sb.append(" &mdash; ").append(escape(v.description)); // NOI18N
                }
            }
            sb.append("</ul>"); // NOI18N
        }
        if (p.sourceType != null) {
            sb.append("<p><small>").append(escape(p.sourceType)).append("</small>"); // NOI18N
        }
        return sb.toString();
    }

    static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); // NOI18N
    }

    private static final class Doc implements CompletionDocumentation {

        private final Metadata.Property property;

        Doc(Metadata.Property property) {
            this.property = property;
        }

        @Override
        public String getText() {
            return documentation(property);
        }

        @Override
        public URL getURL() {
            return null;
        }

        @Override
        public CompletionDocumentation resolveLink(String link) {
            return null;
        }

        @Override
        public Action getGotoSourceAction() {
            return null;
        }
    }
}
