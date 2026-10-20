/*
 * Copyright 2026 Luciano Lopes.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 */
package org.netbeans.modules.db.tools;

import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JToolBar;
import org.netbeans.api.db.explorer.ConnectionManager;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.explorer.ExplorerManager;
import org.openide.explorer.ExplorerUtils;
import org.openide.explorer.view.BeanTreeView;
import org.openide.filesystems.FileUtil;
import org.openide.nodes.FilterNode;
import org.openide.nodes.Node;
import org.openide.util.ContextAwareAction;
import org.openide.util.ImageUtilities;
import org.openide.util.NbBundle;
import org.openide.util.actions.SystemAction;
import org.openide.util.lookup.Lookups;
import org.openide.windows.TopComponent;

/**
 * Dedicated "Database" tool window (like IntelliJ's): the Database explorer
 * tree from the Services window, with a toolbar for the common operations.
 */
@TopComponent.Description(
        preferredID = DatabaseTopComponent.ID,
        iconBase = "org/netbeans/modules/db/tools/resources/database.svg",
        persistenceType = TopComponent.PERSISTENCE_ALWAYS)
@TopComponent.Registration(mode = "properties", openAtStartup = true, position = 100)
@ActionID(category = "Window", id = "org.netbeans.modules.db.tools.DatabaseTopComponent")
@ActionReference(path = "Menu/Window", position = 205)
@TopComponent.OpenActionRegistration(displayName = "#CTL_DatabaseAction", preferredID = DatabaseTopComponent.ID)
@NbBundle.Messages({
    "CTL_DatabaseAction=Database",
    "CTL_DatabaseTopComponent=Database",
    "HINT_DatabaseTopComponent=Database connections, schemas and tables",
    "LBL_NewConnection=New Connection...",
    "LBL_RefreshSelected=Refresh",
    "LBL_SqlConsole=Open SQL Console",
    "LBL_ErDiagramButton=Show ER Diagram",
    "LBL_ExportButton=Export Table Data...",
    "LBL_ImportButton=Import CSV into Table..."
})
public final class DatabaseTopComponent extends TopComponent implements ExplorerManager.Provider {

    static final String ID = "DatabaseTopComponent"; // NOI18N
    private static final String ROOT_NODE = "UI/Runtime/org-netbeans-modules-db-explorer-node-RootNode-instance.instance"; // NOI18N

    private final ExplorerManager manager = new ExplorerManager();
    private final JButton refresh = button("refresh", Bundle.LBL_RefreshSelected()); // NOI18N
    private final JButton console = button("console", Bundle.LBL_SqlConsole()); // NOI18N
    private final JButton diagram = button("diagram", Bundle.LBL_ErDiagramButton()); // NOI18N
    private final JButton export = button("export", Bundle.LBL_ExportButton()); // NOI18N
    private final JButton importCsv = button("import", Bundle.LBL_ImportButton()); // NOI18N

    public DatabaseTopComponent() {
        setName(Bundle.CTL_DatabaseTopComponent());
        setToolTipText(Bundle.HINT_DatabaseTopComponent());
        setLayout(new BorderLayout());

        JButton add = button("add", Bundle.LBL_NewConnection()); // NOI18N
        add.addActionListener(e -> ConnectionManager.getDefault().showAddConnectionDialog(null));
        refresh.addActionListener(e -> runNodeAction("RefreshAction")); // NOI18N
        console.addActionListener(e -> runNodeAction("ExecuteCommandAction")); // NOI18N
        diagram.addActionListener(e -> runOwnAction(DbActions.ShowErDiagram.class));
        export.addActionListener(e -> runOwnAction(DbActions.ExportData.class));
        importCsv.addActionListener(e -> runOwnAction(DbActions.ImportCsv.class));

        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        bar.add(add);
        bar.add(refresh);
        bar.addSeparator();
        bar.add(console);
        bar.add(diagram);
        bar.addSeparator();
        bar.add(export);
        bar.add(importCsv);

        BeanTreeView tree = new BeanTreeView();
        tree.setRootVisible(false);
        tree.setBorder(BorderFactory.createEmptyBorder());
        add(bar, BorderLayout.NORTH);
        add(tree, BorderLayout.CENTER);

        Object root = FileUtil.getConfigObject(ROOT_NODE, Node.class);
        // a node has one parent: the Services window owns the original, so show a filter of it
        manager.setRootContext(root instanceof Node ? new FilterNode((Node) root) : Node.EMPTY);
        manager.addPropertyChangeListener(e -> {
            if (ExplorerManager.PROP_SELECTED_NODES.equals(e.getPropertyName())) {
                updateButtons();
            }
        });
        associateLookup(ExplorerUtils.createLookup(manager, getActionMap()));
        updateButtons();
    }

    private static JButton button(String icon, String tooltip) {
        JButton b = new JButton(ImageUtilities.loadImageIcon(ErDiagramTopComponent.ICONS + icon + ".svg", false)); // NOI18N
        b.setToolTipText(tooltip);
        b.setFocusable(false);
        return b;
    }

    @Override
    public ExplorerManager getExplorerManager() {
        return manager;
    }

    private Node selected() {
        Node[] nodes = manager.getSelectedNodes();
        return nodes.length == 1 ? nodes[0] : null;
    }

    private void updateButtons() {
        Node n = selected();
        boolean underConnection = n != null && Db.connectionAbove(n, new ArrayList<>()) != null;
        refresh.setEnabled(findAction(n, "RefreshAction") != null); // NOI18N
        console.setEnabled(underConnection);
        diagram.setEnabled(underConnection);
        boolean table = n != null && Db.tableOf(n) != null;
        export.setEnabled(table);
        importCsv.setEnabled(table);
    }

    /** Finds one of the explorer's own actions on the node, e.g. its RefreshAction. */
    private static Action findAction(Node n, String simpleClassName) {
        if (n == null) {
            return null;
        }
        for (Action a : n.getActions(true)) {
            if (a != null && a.getClass().getName().endsWith("." + simpleClassName)) { // NOI18N
                return a instanceof ContextAwareAction
                        ? ((ContextAwareAction) a).createContextAwareInstance(Lookups.singleton(n)) : a;
            }
        }
        return null;
    }

    private void runNodeAction(String simpleClassName) {
        Node n = selected();
        // the SQL console needs a connection-level node when e.g. a column is selected
        for (Node cur = n; cur != null; cur = cur.getParentNode()) {
            Action a = findAction(cur, simpleClassName);
            if (a != null && a.isEnabled()) {
                a.actionPerformed(new ActionEvent(this, ActionEvent.ACTION_PERFORMED, simpleClassName));
                return;
            }
        }
    }

    private void runOwnAction(Class<? extends SystemAction> type) {
        Node n = selected();
        if (n != null) {
            Action a = ((ContextAwareAction) SystemAction.get(type)).createContextAwareInstance(Lookups.singleton(n));
            if (a.isEnabled()) {
                a.actionPerformed(new ActionEvent(this, ActionEvent.ACTION_PERFORMED, type.getSimpleName()));
            }
        }
    }

    @Override
    protected void componentActivated() {
        ExplorerUtils.activateActions(manager, true);
    }

    @Override
    protected void componentDeactivated() {
        ExplorerUtils.activateActions(manager, false);
    }
}
