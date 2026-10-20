/*
 * Copyright 2026 Luciano Lopes.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 */
package org.netbeans.modules.db.tools;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.io.File;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.filechooser.FileNameExtensionFilter;
import org.netbeans.api.db.explorer.DatabaseMetaDataTransfer;
import org.netbeans.api.db.explorer.node.BaseNode;
import org.netbeans.api.db.sql.support.SQLIdentifiers;
import org.openide.DialogDisplayer;
import org.openide.NotifyDescriptor;
import org.openide.awt.StatusDisplayer;
import org.openide.nodes.Node;
import org.openide.util.HelpCtx;
import org.openide.util.NbBundle;
import org.openide.util.RequestProcessor;
import org.openide.util.actions.NodeAction;

/** Context actions added to the Database explorer nodes (see layer.xml). */
@NbBundle.Messages({
    "CTL_ShowErDiagram=Show ER Diagram",
    "CTL_ExportData=Export Data...",
    "CTL_ImportCsv=Import CSV...",
    "CTL_RenameTable=Rename Table...",
    "CTL_RenameColumn=Rename Column...",
    "# {0} - current name",
    "LBL_NewName=New name for {0}:",
    "TTL_Rename=Rename",
    "TTL_ConfirmSql=Execute this statement?",
    "LBL_ConfirmSql=The following DDL will be executed:",
    "LBL_Execute=Execute",
    "# {0} - rows",
    "# {1} - file",
    "MSG_Exported=Exported {0} rows to {1}",
    "# {0} - rows",
    "MSG_Exporting=Exporting... {0} rows",
    "# {0} - rows",
    "# {1} - table",
    "MSG_Imported=Imported {0} rows into {1}",
    "# {0} - rows",
    "MSG_Importing=Importing... {0} rows",
    "MSG_Renamed=Renamed. The explorer was refreshed.",
    "# {0} - error",
    "ERR_Operation=Operation failed: {0}",
    "# {0} - file",
    "MSG_ConfirmOverwrite={0} already exists. Overwrite it?"
})
public final class DbActions {

    private static final RequestProcessor RP = new RequestProcessor(DbActions.class);

    private DbActions() {
    }

    private abstract static class Base extends NodeAction {

        @Override
        protected boolean asynchronous() {
            return false;
        }

        @Override
        public HelpCtx getHelpCtx() {
            return null;
        }
    }

    public static final class ShowErDiagram extends Base {

        @Override
        protected boolean enable(Node[] nodes) {
            return nodes.length == 1 && Db.connectionAbove(nodes[0], new ArrayList<>()) != null;
        }

        @Override
        protected void performAction(Node[] nodes) {
            List<String> owners = new ArrayList<>();
            DatabaseMetaDataTransfer.Connection c = Db.connectionAbove(nodes[0], owners);
            if (c != null) {
                ErDiagramTopComponent.open(c.getDatabaseConnection(), c.getJDBCDriver(), owners);
            }
        }

        @Override
        protected String iconResource() {
            return ErDiagramTopComponent.ICONS + "diagram.svg"; // NOI18N
        }

        @Override
        public String getName() {
            return Bundle.CTL_ShowErDiagram();
        }
    }

    public static final class ExportData extends Base {

        @Override
        protected boolean enable(Node[] nodes) {
            return nodes.length == 1 && Db.tableOf(nodes[0]) != null;
        }

        @Override
        protected void performAction(Node[] nodes) {
            Db.TableRef t = Db.tableOf(nodes[0]);
            JFileChooser chooser = new JFileChooser();
            FileNameExtensionFilter csv = new FileNameExtensionFilter("CSV (*.csv)", "csv"); // NOI18N
            chooser.addChoosableFileFilter(csv);
            chooser.addChoosableFileFilter(new FileNameExtensionFilter("JSON (*.json)", "json")); // NOI18N
            chooser.addChoosableFileFilter(new FileNameExtensionFilter("SQL INSERT (*.sql)", "sql")); // NOI18N
            chooser.setAcceptAllFileFilterUsed(false);
            chooser.setFileFilter(csv);
            chooser.setSelectedFile(new File(t.table + ".csv")); // NOI18N
            if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) {
                return;
            }
            File file = chooser.getSelectedFile();
            String ext = ((FileNameExtensionFilter) chooser.getFileFilter()).getExtensions()[0];
            if (!file.getName().toLowerCase().endsWith("." + ext)) { // NOI18N
                String base = file.getName().replaceFirst("\\.(csv|json|sql)$", ""); // NOI18N
                file = new File(file.getParentFile(), base + "." + ext); // NOI18N
            }
            if (file.exists() && !confirm(Bundle.MSG_ConfirmOverwrite(file.getName()))) {
                return;
            }
            File target = file;
            DataTransfer.Format format = DataTransfer.Format.of(target);
            RP.post(() -> {
                try (Db.Session s = Db.open(t.connection, t.driver, true)) {
                    DatabaseMetaData md = s.connection.getMetaData();
                    t.resolve(md);
                    int rows = DataTransfer.export(s.connection, t.qualified(md), target, format,
                            n -> StatusDisplayer.getDefault().setStatusText(Bundle.MSG_Exporting(n)));
                    StatusDisplayer.getDefault().setStatusText(Bundle.MSG_Exported(rows, target.getAbsolutePath()));
                } catch (Exception ex) {
                    error(ex);
                }
            });
        }

        @Override
        protected String iconResource() {
            return ErDiagramTopComponent.ICONS + "export.svg"; // NOI18N
        }

        @Override
        public String getName() {
            return Bundle.CTL_ExportData();
        }
    }

    public static final class ImportCsv extends Base {

        @Override
        protected boolean enable(Node[] nodes) {
            return nodes.length == 1 && Db.tableOf(nodes[0]) != null;
        }

        @Override
        protected void performAction(Node[] nodes) {
            Db.TableRef t = Db.tableOf(nodes[0]);
            JFileChooser chooser = new JFileChooser();
            chooser.setFileFilter(new FileNameExtensionFilter("CSV (*.csv)", "csv")); // NOI18N
            if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) {
                return;
            }
            File file = chooser.getSelectedFile();
            RP.post(() -> {
                try (Db.Session s = Db.open(t.connection, t.driver, true)) {
                    DatabaseMetaData md = s.connection.getMetaData();
                    t.resolve(md);
                    String table = t.qualified(md);
                    int rows = DataTransfer.importCsv(s.connection, table, file,
                            n -> StatusDisplayer.getDefault().setStatusText(Bundle.MSG_Importing(n)));
                    StatusDisplayer.getDefault().setStatusText(Bundle.MSG_Imported(rows, table));
                    DialogDisplayer.getDefault().notify(new NotifyDescriptor.Message(Bundle.MSG_Imported(rows, table)));
                } catch (Exception ex) {
                    error(ex);
                }
            });
        }

        @Override
        protected String iconResource() {
            return ErDiagramTopComponent.ICONS + "import.svg"; // NOI18N
        }

        @Override
        public String getName() {
            return Bundle.CTL_ImportCsv();
        }
    }

    public static final class RenameTable extends Base {

        @Override
        protected boolean enable(Node[] nodes) {
            return nodes.length == 1 && Db.tableOf(nodes[0]) != null;
        }

        @Override
        protected void performAction(Node[] nodes) {
            Node node = nodes[0];
            Db.TableRef t = Db.tableOf(node);
            String newName = askName(t.table);
            if (newName == null) {
                return;
            }
            RP.post(() -> {
                try (Db.Session s = Db.open(t.connection, t.driver, true)) {
                    DatabaseMetaData md = s.connection.getMetaData();
                    t.resolve(md);
                    SQLIdentifiers.Quoter q = SQLIdentifiers.createQuoter(md);
                    String qualifier = t.schema != null ? q.quoteIfNeeded(t.schema)
                            : t.catalog != null ? q.quoteIfNeeded(t.catalog) : null;
                    String ddl = Db.Dialect.of(md).renameTable(t.qualified(md), t.table, qualifier, q.quoteIfNeeded(newName));
                    if (confirmSql(ddl)) {
                        execute(s, ddl);
                        refresh(node.getParentNode());
                    }
                } catch (Exception ex) {
                    error(ex);
                }
            });
        }

        @Override
        public String getName() {
            return Bundle.CTL_RenameTable();
        }
    }

    public static final class RenameColumn extends Base {

        @Override
        protected boolean enable(Node[] nodes) {
            return nodes.length == 1 && Db.columnOf(nodes[0]) != null;
        }

        @Override
        protected void performAction(Node[] nodes) {
            Node node = nodes[0];
            Object[] col = Db.columnOf(node);
            Db.TableRef t = (Db.TableRef) col[0];
            String column = (String) col[1];
            String newName = askName(column);
            if (newName == null) {
                return;
            }
            RP.post(() -> {
                try (Db.Session s = Db.open(t.connection, t.driver, true)) {
                    DatabaseMetaData md = s.connection.getMetaData();
                    t.resolve(md);
                    SQLIdentifiers.Quoter q = SQLIdentifiers.createQuoter(md);
                    String ddl = Db.Dialect.of(md).renameColumn(t.qualified(md), t.table,
                            q.quoteIfNeeded(column), q.quoteIfNeeded(newName));
                    if (confirmSql(ddl)) {
                        execute(s, ddl);
                        refresh(node.getParentNode());
                    }
                } catch (Exception ex) {
                    error(ex);
                }
            });
        }

        @Override
        public String getName() {
            return Bundle.CTL_RenameColumn();
        }
    }

    private static String askName(String current) {
        NotifyDescriptor.InputLine input = new NotifyDescriptor.InputLine(Bundle.LBL_NewName(current), Bundle.TTL_Rename());
        input.setInputText(current);
        if (DialogDisplayer.getDefault().notify(input) != NotifyDescriptor.OK_OPTION) {
            return null;
        }
        String name = input.getInputText().trim();
        return name.isEmpty() || name.equals(current) ? null : name;
    }

    /** Shows the exact DDL before running it. */
    private static boolean confirmSql(String sql) {
        JTextArea area = new JTextArea(sql);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, area.getFont().getSize()));
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(520, 90));
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.add(new JLabel(Bundle.LBL_ConfirmSql()), BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        Object execute = Bundle.LBL_Execute();
        NotifyDescriptor nd = new NotifyDescriptor(panel, Bundle.TTL_ConfirmSql(), NotifyDescriptor.OK_CANCEL_OPTION,
                NotifyDescriptor.QUESTION_MESSAGE, new Object[] {execute, NotifyDescriptor.CANCEL_OPTION}, execute);
        return DialogDisplayer.getDefault().notify(nd) == execute;
    }

    private static boolean confirm(String message) {
        return DialogDisplayer.getDefault().notify(new NotifyDescriptor.Confirmation(message, NotifyDescriptor.YES_NO_OPTION))
                == NotifyDescriptor.YES_OPTION;
    }

    private static void execute(Db.Session s, String sql) throws SQLException {
        try (Statement st = s.connection.createStatement()) {
            st.execute(sql);
        }
        if (!s.connection.getAutoCommit()) {
            s.connection.commit();
        }
    }

    private static void refresh(Node node) {
        if (node instanceof BaseNode) {
            ((BaseNode) node).refresh();
        }
        StatusDisplayer.getDefault().setStatusText(Bundle.MSG_Renamed());
    }

    private static void error(Exception ex) {
        DialogDisplayer.getDefault().notify(new NotifyDescriptor.Message(
                Bundle.ERR_Operation(ex.getMessage() != null ? ex.getMessage() : ex.toString()), NotifyDescriptor.ERROR_MESSAGE));
    }
}
