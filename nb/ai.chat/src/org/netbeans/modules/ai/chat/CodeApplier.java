/*
 * Copyright 2026 Luciano Lopes.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 */
package org.netbeans.modules.ai.chat;

import java.awt.Component;
import java.awt.Dimension;
import java.io.IOException;
import java.io.StringReader;
import javax.swing.JButton;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import javax.swing.text.Position;
import javax.swing.text.StyledDocument;
import org.netbeans.api.diff.Diff;
import org.netbeans.api.diff.DiffView;
import org.netbeans.api.diff.StreamSource;
import org.netbeans.api.editor.EditorRegistry;
import org.openide.DialogDescriptor;
import org.openide.DialogDisplayer;
import org.openide.NotifyDescriptor;
import org.openide.loaders.DataObject;
import org.openide.text.NbDocument;
import org.openide.util.NbBundle;

/**
 * Applies a code block from the chat to an editor document, after the user
 * accepted the change in a diff dialog.
 */
@NbBundle.Messages({
    "# {0} - file name",
    "TTL_ApplyDiff=Apply AI changes to {0}",
    "LBL_ApplyButton=Apply",
    "# {0} - file name",
    "LBL_DiffCurrent={0} (current)",
    "# {0} - file name",
    "LBL_DiffProposed={0} (AI proposal)",
    "MSG_NoEditor=Open a file in the editor first.",
    "MSG_NoChanges=The code is identical to the current content.",
    "# {0} - error",
    "MSG_ApplyFailed=Could not apply the change: {0}"
})
final class CodeApplier {

    private CodeApplier() {
    }

    /**
     * Region of a document the answer refers to. Positions follow edits made
     * while the answer is being generated.
     */
    static final class Target {

        final StyledDocument doc;
        final String name;
        private final Position start;
        private final Position end;
        private final boolean wholeFile;

        private Target(StyledDocument doc, String name, Position start, Position end, boolean wholeFile) {
            this.doc = doc;
            this.name = name;
            this.start = start;
            this.end = end;
            this.wholeFile = wholeFile;
        }

        int startOffset() {
            return wholeFile ? 0 : start.getOffset();
        }

        int endOffset() {
            return wholeFile ? doc.getLength() : end.getOffset();
        }

        /** The selection, or the whole file when nothing is selected. */
        static Target forContext(JTextComponent editor) {
            return create(editor, false);
        }

        /** The selection, or an empty region at the caret. */
        static Target forInsertion(JTextComponent editor) {
            return create(editor, true);
        }

        private static Target create(JTextComponent editor, boolean caretWhenNoSelection) {
            if (editor == null || !(editor.getDocument() instanceof StyledDocument)) {
                return null;
            }
            StyledDocument doc = (StyledDocument) editor.getDocument();
            int s = editor.getSelectionStart();
            int e = editor.getSelectionEnd();
            try {
                if (s != e) {
                    return new Target(doc, nameOf(doc), NbDocument.createPosition(doc, s, Position.Bias.Backward),
                            NbDocument.createPosition(doc, e, Position.Bias.Forward), false);
                }
                if (caretWhenNoSelection) {
                    int c = editor.getCaretPosition();
                    return new Target(doc, nameOf(doc), NbDocument.createPosition(doc, c, Position.Bias.Backward),
                            NbDocument.createPosition(doc, c, Position.Bias.Forward), false);
                }
                return new Target(doc, nameOf(doc), null, null, true);
            } catch (BadLocationException ex) {
                return null;
            }
        }
    }

    static String nameOf(Document doc) {
        Object source = doc.getProperty(Document.StreamDescriptionProperty);
        return source instanceof DataObject
                ? ((DataObject) source).getPrimaryFile().getNameExt()
                : "untitled"; // NOI18N
    }

    static void apply(Target target, String code) {
        if (target == null) {
            target = Target.forInsertion(EditorRegistry.lastFocusedComponent());
        }
        if (target == null) {
            message(Bundle.MSG_NoEditor(), NotifyDescriptor.INFORMATION_MESSAGE);
            return;
        }
        StyledDocument doc = target.doc;
        String original;
        int s;
        int e;
        try {
            original = doc.getText(0, doc.getLength());
            s = target.startOffset();
            e = target.endOffset();
        } catch (BadLocationException ex) {
            message(Bundle.MSG_ApplyFailed(ex.getMessage()), NotifyDescriptor.ERROR_MESSAGE);
            return;
        }
        String replaced = original.substring(s, e);
        // models drop the trailing newline of a code block; keep the one the region had
        String replacement = replaced.endsWith("\n") && !code.endsWith("\n") ? code + "\n" : code; // NOI18N
        String proposed = original.substring(0, s) + replacement + original.substring(e);
        if (proposed.equals(original)) {
            message(Bundle.MSG_NoChanges(), NotifyDescriptor.INFORMATION_MESSAGE);
            return;
        }

        Object mime = doc.getProperty("mimeType"); // NOI18N
        String mimeType = mime instanceof String ? (String) mime : "text/plain"; // NOI18N
        DiffView view;
        try {
            view = Diff.getDefault().createDiff(
                    StreamSource.createSource("current", Bundle.LBL_DiffCurrent(target.name), mimeType, new StringReader(original)), // NOI18N
                    StreamSource.createSource("proposed", Bundle.LBL_DiffProposed(target.name), mimeType, new StringReader(proposed))); // NOI18N
        } catch (IOException ex) {
            message(Bundle.MSG_ApplyFailed(ex.getMessage()), NotifyDescriptor.ERROR_MESSAGE);
            return;
        }
        Component diff = view.getComponent();
        diff.setPreferredSize(new Dimension(1000, 640));
        JButton applyButton = new JButton(Bundle.LBL_ApplyButton());
        DialogDescriptor dd = new DialogDescriptor(diff, Bundle.TTL_ApplyDiff(target.name), true,
                new Object[] {applyButton, DialogDescriptor.CANCEL_OPTION}, applyButton,
                DialogDescriptor.DEFAULT_ALIGN, null, null);
        if (DialogDisplayer.getDefault().notify(dd) != applyButton) {
            return;
        }

        Target t = target;
        BadLocationException[] failure = new BadLocationException[1];
        try {
            NbDocument.runAtomicAsUser(doc, () -> {
                try {
                    // re-read the region: the user may have typed while the dialog was open
                    int start = t.startOffset();
                    int end = t.endOffset();
                    doc.remove(start, end - start);
                    doc.insertString(start, replacement, null);
                } catch (BadLocationException ex) {
                    failure[0] = ex;
                }
            });
        } catch (BadLocationException ex) {
            // thrown when the region touches a guarded (read-only) block
            failure[0] = ex;
        }
        if (failure[0] != null) {
            message(Bundle.MSG_ApplyFailed(failure[0].getMessage()), NotifyDescriptor.ERROR_MESSAGE);
        }
    }

    private static void message(String text, int type) {
        DialogDisplayer.getDefault().notify(new NotifyDescriptor.Message(text, type));
    }
}
