/*
 * Copyright 2026 Luciano Lopes.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 */
package org.netbeans.modules.db.tools;

import java.beans.PropertyChangeEvent;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import org.netbeans.api.db.explorer.DatabaseConnection;
import org.netbeans.api.editor.EditorRegistry;
import org.netbeans.modules.db.api.sql.execute.SQLExecution;
import org.netbeans.spi.editor.hints.ErrorDescription;
import org.netbeans.spi.editor.hints.ErrorDescriptionFactory;
import org.netbeans.spi.editor.hints.HintsController;
import org.netbeans.spi.editor.hints.Severity;
import org.openide.modules.OnStart;
import org.openide.util.RequestProcessor;
import org.openide.windows.TopComponent;

/**
 * Checks the statements of a SQL editor against its database after a typing
 * pause: each query/DML statement is planned with EXPLAIN (never executed) on a
 * private connection, and database errors become editor error annotations.
 */
@OnStart
public final class SqlValidator implements Runnable {

    private static final String LAYER = "db-tools-sql-validation"; // NOI18N
    private static final RequestProcessor RP = new RequestProcessor(SqlValidator.class);
    private static final int DELAY_MS = 1200;
    private static final int MAX_STATEMENTS = 100;
    private static final Set<String> PLANNABLE = Set.of(
            "select", "with", "insert", "update", "delete", "merge", "replace", "values", "table"); // NOI18N
    private static final Pattern PG_POSITION = Pattern.compile("Position: (\\d+)"); // NOI18N
    private static final Pattern MYSQL_NEAR = Pattern.compile("near '(.+?)' at line", Pattern.DOTALL); // NOI18N
    /** One private connection per database connection; never the SQL editor's own session. */
    private static final Map<DatabaseConnection, Connection> CONNECTIONS = new WeakHashMap<>();

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
        component = c != null && "text/x-sql".equals(c.getDocument().getProperty("mimeType")) ? c : null; // NOI18N
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
        TopComponent tc = (TopComponent) SwingUtilities.getAncestorOfClass(TopComponent.class, c);
        SQLExecution execution = tc == null ? null : tc.getLookup().lookup(SQLExecution.class);
        DatabaseConnection dc = execution == null ? null : execution.getDatabaseConnection();
        if (dc == null) {
            HintsController.setErrors(doc, LAYER, List.of());
            return;
        }
        String text = c.getText();
        int v = version;
        RP.post(() -> {
            List<int[]> ranges = new ArrayList<>();
            List<String> messages = new ArrayList<>();
            check(dc, text, ranges, messages);
            SwingUtilities.invokeLater(() -> {
                if (version != v || component != c) {
                    return; // text changed meanwhile; a newer check is scheduled
                }
                List<ErrorDescription> errors = new ArrayList<>();
                for (int i = 0; i < ranges.size(); i++) {
                    try {
                        int[] r = ranges.get(i);
                        errors.add(ErrorDescriptionFactory.createErrorDescription(Severity.ERROR, messages.get(i), doc,
                                doc.createPosition(r[0]), doc.createPosition(r[1])));
                    } catch (BadLocationException ex) {
                        // document shrank; skip
                    }
                }
                HintsController.setErrors(doc, LAYER, errors);
            });
        });
    }

    private static void check(DatabaseConnection dc, String text, List<int[]> ranges, List<String> messages) {
        Connection con = connection(dc);
        if (con == null) {
            return; // password not stored: cannot open a private connection, so no validation
        }
        try {
            Db.Dialect dialect = Db.Dialect.of(con.getMetaData());
            int count = 0;
            for (SqlSplitter.Statement s : SqlSplitter.split(text)) {
                if (++count > MAX_STATEMENTS) {
                    break;
                }
                if (!PLANNABLE.contains(s.firstKeyword()) || s.text.contains("?")) { // NOI18N
                    continue; // DDL cannot be planned; JDBC placeholders cannot be bound here
                }
                String explain = dialect.explain(s.text);
                if (explain == null) {
                    return;
                }
                try (Statement st = con.createStatement()) {
                    st.setQueryTimeout(5);
                    st.execute(explain);
                } catch (SQLException ex) {
                    String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                    ranges.add(errorRange(s, message, dialect));
                    messages.add(firstLine(message));
                }
            }
        } catch (SQLException ex) {
            synchronized (CONNECTIONS) {
                CONNECTIONS.remove(dc);
            }
        }
    }

    /** Narrows the error to the offending token when the database says where it is. */
    static int[] errorRange(SqlSplitter.Statement s, String message, Db.Dialect dialect) {
        int at = -1;
        Matcher pg = PG_POSITION.matcher(message);
        if (pg.find()) {
            at = Integer.parseInt(pg.group(1)) - 1 - dialect.explainPrefixLength();
        } else {
            Matcher my = MYSQL_NEAR.matcher(message);
            if (my.find()) {
                at = s.text.indexOf(my.group(1));
            }
        }
        if (at >= 0 && at < s.text.length()) {
            int end = at;
            while (end < s.text.length() && (Character.isLetterOrDigit(s.text.charAt(end)) || s.text.charAt(end) == '_'
                    || s.text.charAt(end) == '.' || s.text.charAt(end) == '"')) {
                end++;
            }
            return new int[] {s.start + at, s.start + Math.max(end, at + 1)};
        }
        int nl = s.text.indexOf('\n');
        return new int[] {s.start, s.start + (nl < 0 ? s.text.length() : nl)};
    }

    private static String firstLine(String message) {
        int nl = message.indexOf('\n');
        return (nl < 0 ? message : message.substring(0, nl)).trim();
    }

    private static Connection connection(DatabaseConnection dc) {
        synchronized (CONNECTIONS) {
            Connection c = CONNECTIONS.get(dc);
            try {
                if (c != null && c.isValid(2)) {
                    return c;
                }
            } catch (SQLException ex) {
                // reopen below
            }
            c = Db.openPrivate(dc, Db.driverFor(dc));
            if (c != null) {
                try {
                    c.setAutoCommit(true);
                } catch (SQLException ex) {
                    // keep driver default
                }
                CONNECTIONS.put(dc, c);
            }
            return c;
        }
    }
}
