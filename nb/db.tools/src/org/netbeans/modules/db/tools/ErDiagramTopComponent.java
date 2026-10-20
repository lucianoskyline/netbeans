/*
 * Copyright 2026 Luciano Lopes.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 */
package org.netbeans.modules.db.tools;

import java.awt.BorderLayout;
import java.io.File;
import java.io.IOException;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JToolBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import org.netbeans.api.db.explorer.DatabaseConnection;
import org.netbeans.api.db.explorer.JDBCDriver;
import org.openide.DialogDisplayer;
import org.openide.NotifyDescriptor;
import org.openide.util.ImageUtilities;
import org.openide.util.NbBundle;
import org.openide.util.RequestProcessor;
import org.openide.windows.TopComponent;
import org.openide.windows.WindowManager;

@NbBundle.Messages({
    "# {0} - connection name",
    "TTL_ErDiagram=ER: {0}",
    "LBL_Schema=Schema:",
    "LBL_Reload=Reload",
    "LBL_ExportPng=Export PNG...",
    "LBL_ZoomIn=Zoom In",
    "LBL_ZoomOut=Zoom Out",
    "LBL_ActualSize=Actual Size (100%)",
    "LBL_FitWindow=Fit to Window",
    "LBL_Loading=Loading metadata...",
    "# {0} - table count",
    "# {1} - relationship count",
    "LBL_ErSummary={0} tables, {1} relationships",
    "# {0} - maximum",
    "LBL_ErTruncated=only the first {0} tables are shown",
    "LBL_Zoom=Ctrl+wheel: zoom",
    "# {0} - schema name",
    "LBL_NoTables=No tables in \"{0}\". Choose another schema in the toolbar above.",
    "# {0} - error",
    "ERR_ErLoad=Could not read the schema: {0}"
})
final class ErDiagramTopComponent extends TopComponent {

    private static final RequestProcessor RP = new RequestProcessor(ErDiagramTopComponent.class);

    private final DatabaseConnection connection;
    private final JDBCDriver driver;
    private final JComboBox<String> schemas = new JComboBox<>();
    private final JLabel status = new JLabel();
    private final JScrollPane scroll = new JScrollPane();
    private ErDiagramPanel panel;
    private ErModel model;
    private boolean populating;
    /** Schemas or catalogs, whichever the database uses for grouping tables. */
    private boolean usesCatalogs;
    /** Names of the explorer nodes the diagram was opened from; the first that is a schema/catalog is selected. */
    private final List<String> requestedOwners;

    static void open(DatabaseConnection connection, JDBCDriver driver, List<String> owners) {
        ErDiagramTopComponent tc = new ErDiagramTopComponent(connection, driver, owners);
        WindowManager.getDefault().findMode("editor").dockInto(tc); // NOI18N
        tc.open();
        tc.requestActive();
        tc.loadSchemas();
    }

    private ErDiagramTopComponent(DatabaseConnection connection, JDBCDriver driver, List<String> owners) {
        this.connection = connection;
        this.driver = driver;
        this.requestedOwners = owners;
        setName(Bundle.TTL_ErDiagram(connection.getDisplayName()));
        setIcon(ImageUtilities.loadImage(ICONS + "diagram.svg")); // NOI18N
        setLayout(new BorderLayout());

        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        bar.add(new JLabel(Bundle.LBL_Schema() + " ")); // NOI18N
        schemas.setMaximumSize(schemas.getPreferredSize());
        schemas.addActionListener(e -> {
            if (schemas.getSelectedItem() != null && !populating) {
                loadDiagram();
            }
        });
        bar.add(schemas);
        bar.addSeparator();
        bar.add(button("refresh", Bundle.LBL_Reload(), this::loadDiagram)); // NOI18N
        bar.add(button("export", Bundle.LBL_ExportPng(), this::exportPng)); // NOI18N
        bar.addSeparator();
        bar.add(button("zoomIn", Bundle.LBL_ZoomIn(), () -> zoom(1.2))); // NOI18N
        bar.add(button("zoomOut", Bundle.LBL_ZoomOut(), () -> zoom(1 / 1.2))); // NOI18N
        bar.add(button("actualZoom", Bundle.LBL_ActualSize(), () -> { // NOI18N
            if (panel != null) {
                panel.setZoom(1.0);
            }
        }));
        bar.add(button("fit", Bundle.LBL_FitWindow(), this::fit)); // NOI18N
        bar.addSeparator();
        status.setHorizontalAlignment(SwingConstants.LEFT);
        bar.add(status);

        scroll.getVerticalScrollBar().setUnitIncrement(24);
        scroll.getHorizontalScrollBar().setUnitIncrement(24);
        add(bar, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
    }

    static final String ICONS = "org/netbeans/modules/db/tools/resources/"; // NOI18N

    private static JButton button(String icon, String tooltip, Runnable action) {
        JButton b = new JButton(ImageUtilities.loadImageIcon(ICONS + icon + ".svg", false)); // NOI18N
        b.setToolTipText(tooltip);
        b.setFocusable(false);
        b.addActionListener(e -> action.run());
        return b;
    }

    private void zoom(double factor) {
        if (panel != null) {
            panel.setZoom(panel.getZoom() * factor);
        }
    }

    private void fit() {
        if (panel != null) {
            panel.fitTo(scroll.getViewport().getExtentSize());
        }
    }

    @Override
    public int getPersistenceType() {
        return PERSISTENCE_NEVER;
    }

    private void loadSchemas() {
        status.setText(Bundle.LBL_Loading());
        RP.post(() -> {
            List<String> names = new ArrayList<>();
            String preferred = connection.getSchema();
            boolean catalogs = false;
            String error = null;
            try (Db.Session s = Db.open(connection, driver, true)) {
                DatabaseMetaData md = s.connection.getMetaData();
                try (ResultSet rs = md.getSchemas()) {
                    while (rs.next()) {
                        names.add(rs.getString("TABLE_SCHEM")); // NOI18N
                    }
                }
                if (names.isEmpty()) {
                    catalogs = true;
                    try (ResultSet rs = md.getCatalogs()) {
                        while (rs.next()) {
                            names.add(rs.getString("TABLE_CAT")); // NOI18N
                        }
                    }
                    if (preferred == null || !names.contains(preferred)) {
                        preferred = s.connection.getCatalog();
                    }
                } else if (preferred == null || !names.contains(preferred)) {
                    preferred = s.connection.getSchema();
                }
            } catch (SQLException ex) {
                error = ex.getMessage();
            }
            for (String owner : requestedOwners) {
                if (names.contains(owner)) {
                    preferred = owner;
                    break;
                }
            }
            String selected = preferred;
            boolean useCatalogs = catalogs;
            String failure = error;
            SwingUtilities.invokeLater(() -> {
                if (failure != null) {
                    status.setText(Bundle.ERR_ErLoad(failure));
                    return;
                }
                usesCatalogs = useCatalogs;
                populating = true;
                names.forEach(schemas::addItem);
                if (selected != null) {
                    schemas.setSelectedItem(selected);
                }
                populating = false;
                schemas.setMaximumSize(schemas.getPreferredSize());
                loadDiagram();
            });
        });
    }

    private void loadDiagram() {
        String owner = (String) schemas.getSelectedItem();
        boolean catalogs = usesCatalogs;
        status.setText(Bundle.LBL_Loading());
        RP.post(() -> {
            try (Db.Session s = Db.open(connection, driver, true)) {
                DatabaseMetaData md = s.connection.getMetaData();
                ErModel model = catalogs ? ErModel.load(md, owner, null) : ErModel.load(md, null, owner);
                SwingUtilities.invokeLater(() -> show(model));
            } catch (SQLException ex) {
                SwingUtilities.invokeLater(() -> status.setText(Bundle.ERR_ErLoad(ex.getMessage())));
            }
        });
    }

    private void show(ErModel loaded) {
        model = loaded;
        panel = new ErDiagramPanel(loaded);
        panel.setZoomListener(this::updateStatus);
        if (loaded.tables.isEmpty()) {
            JLabel empty = new JLabel(Bundle.LBL_NoTables(schemas.getSelectedItem()), SwingConstants.CENTER);
            empty.setForeground(javax.swing.UIManager.getColor("Label.disabledForeground")); // NOI18N
            scroll.setViewportView(empty);
        } else {
            scroll.setViewportView(panel);
        }
        updateStatus();
    }

    private void updateStatus() {
        if (panel == null) {
            return;
        }
        String text = Bundle.LBL_ErSummary(model.tables.size(), model.foreignKeys.size())
                + "   " + Math.round(panel.getZoom() * 100) + "%   " + Bundle.LBL_Zoom(); // NOI18N
        if (model.truncated) {
            text += "   " + Bundle.LBL_ErTruncated(ErModel.MAX_TABLES); // NOI18N
        }
        status.setText(text);
    }

    private void exportPng() {
        if (panel == null) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("PNG", "png")); // NOI18N
        chooser.setSelectedFile(new File(String.valueOf(schemas.getSelectedItem()) + "-er.png")); // NOI18N
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File f = chooser.getSelectedFile();
        if (!f.getName().toLowerCase().endsWith(".png")) { // NOI18N
            f = new File(f.getParentFile(), f.getName() + ".png"); // NOI18N
        }
        try {
            ImageIO.write(panel.toImage(), "png", f); // NOI18N
        } catch (IOException ex) {
            DialogDisplayer.getDefault().notify(new NotifyDescriptor.Message(ex.getMessage(), NotifyDescriptor.ERROR_MESSAGE));
        }
    }
}
