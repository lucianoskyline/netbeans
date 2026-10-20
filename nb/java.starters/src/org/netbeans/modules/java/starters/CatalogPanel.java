/*
 * Copyright 2026 Luciano Lopes.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 */
package org.netbeans.modules.java.starters;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import org.openide.WizardDescriptor;
import org.openide.util.ChangeSupport;
import org.openide.util.HelpCtx;
import org.openide.util.NbBundle;
import org.openide.util.RequestProcessor;

/** Second step for Spring Boot / Quarkus: platform version and dependencies from the online catalog. */
@NbBundle.Messages({
    "LBL_SpringStep=Spring Boot Version and Dependencies",
    "LBL_QuarkusStep=Quarkus Platform and Extensions",
    "LBL_SpringVersion=Spring Boot:",
    "LBL_QuarkusVersion=Platform:",
    "LBL_Filter=Search:",
    "LBL_LoadingCatalog=Loading the catalog...",
    "# {0} - count",
    "# {1} - names",
    "LBL_SelectedCount={0} selected: {1}",
    "LBL_NoneSelected=Nothing selected yet; you can also add dependencies to pom.xml later.",
    "COL_Use=Use",
    "COL_Name=Name",
    "COL_Group=Group",
    "COL_Description=Description",
    "# {0} - error",
    "ERR_Catalog=Could not load the catalog (is the Internet reachable?): {0}"
})
final class CatalogPanel implements WizardDescriptor.Panel<WizardDescriptor> {

    private static final RequestProcessor RP = new RequestProcessor(CatalogPanel.class);

    private final Generator.Kind kind;
    private final Generator.Settings settings;
    private final ChangeSupport changes = new ChangeSupport(this);
    private final JComboBox<Catalog.Version> versions = new JComboBox<>();
    private final JTextField filter = new JTextField(24);
    private final JLabel status = new JLabel();
    private final Model model = new Model();
    private final Set<String> selected = new LinkedHashSet<>();
    private List<Catalog.Item> all = List.of();
    private JPanel component;
    private WizardDescriptor wizard;
    private String error;
    private boolean loaded;
    private boolean populating;

    CatalogPanel(Generator.Kind kind, Generator.Settings settings) {
        this.kind = kind;
        this.settings = settings;
    }

    @Override
    public Component getComponent() {
        if (component == null) {
            component = build();
            load();
        }
        return component;
    }

    private JPanel build() {
        JPanel top = new JPanel(new BorderLayout(8, 0));
        JPanel left = new JPanel(new BorderLayout(6, 0));
        left.add(new JLabel(kind == Generator.Kind.SPRING ? Bundle.LBL_SpringVersion() : Bundle.LBL_QuarkusVersion()), BorderLayout.WEST);
        left.add(versions, BorderLayout.CENTER);
        JPanel right = new JPanel(new BorderLayout(6, 0));
        right.add(new JLabel(Bundle.LBL_Filter()), BorderLayout.WEST);
        filter.putClientProperty("JTextField.placeholderText", "web, jpa, security..."); // NOI18N
        filter.putClientProperty("JTextField.showClearButton", true); // NOI18N
        right.add(filter, BorderLayout.CENTER);
        top.add(left, BorderLayout.WEST);
        top.add(right, BorderLayout.CENTER);

        JTable table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setMaxWidth(50);
        table.getColumnModel().getColumn(1).setPreferredWidth(190);
        table.getColumnModel().getColumn(2).setPreferredWidth(120);
        table.getColumnModel().getColumn(3).setPreferredWidth(420);
        table.setAutoCreateRowSorter(false);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(760, 340));

        status.setForeground(UIManager.getColor("Label.disabledForeground")); // NOI18N
        status.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));

        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setName(kind == Generator.Kind.SPRING ? Bundle.LBL_SpringStep() : Bundle.LBL_QuarkusStep());
        p.add(top, BorderLayout.NORTH);
        p.add(scroll, BorderLayout.CENTER);
        p.add(status, BorderLayout.SOUTH);

        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                model.refilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                model.refilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
            }
        });
        versions.addActionListener(e -> {
            // Quarkus extensions depend on the platform stream; Spring's list is the same for all versions
            if (!populating && kind == Generator.Kind.QUARKUS && versions.getSelectedItem() != null) {
                loadExtensions(((Catalog.Version) versions.getSelectedItem()).id);
            }
        });
        return p;
    }

    private void load() {
        status.setText(Bundle.LBL_LoadingCatalog());
        RP.post(() -> {
            try {
                Catalog c = kind == Generator.Kind.SPRING ? Catalog.spring() : Catalog.quarkusStreams();
                List<Catalog.Item> items = kind == Generator.Kind.SPRING ? c.items
                        : c.defaultVersion == null ? List.of() : Catalog.quarkusExtensions(c.defaultVersion.id);
                SwingUtilities.invokeLater(() -> {
                    populating = true;
                    c.versions.forEach(versions::addItem);
                    versions.setSelectedItem(c.defaultVersion);
                    populating = false;
                    show(items);
                });
            } catch (Exception ex) {
                fail(ex);
            }
        });
    }

    private void loadExtensions(String stream) {
        status.setText(Bundle.LBL_LoadingCatalog());
        loaded = false;
        changes.fireChange();
        RP.post(() -> {
            try {
                List<Catalog.Item> items = Catalog.quarkusExtensions(stream);
                SwingUtilities.invokeLater(() -> show(items));
            } catch (Exception ex) {
                fail(ex);
            }
        });
    }

    private void fail(Exception ex) {
        SwingUtilities.invokeLater(() -> {
            error = Bundle.ERR_Catalog(ex.getMessage() != null ? ex.getMessage() : ex.toString());
            status.setText(error);
            changes.fireChange();
        });
    }

    private void show(List<Catalog.Item> items) {
        all = items;
        // a Quarkus stream change keeps only the selections that still exist
        Set<String> ids = new LinkedHashSet<>();
        items.forEach(i -> ids.add(i.id));
        selected.retainAll(ids);
        if (kind == Generator.Kind.QUARKUS && selected.isEmpty() && ids.contains("io.quarkus:quarkus-rest")) { // NOI18N
            selected.add("io.quarkus:quarkus-rest"); // NOI18N  a hello endpoint to start from
        }
        error = null;
        loaded = true;
        model.refilter();
        updateStatus();
        changes.fireChange();
    }

    private void updateStatus() {
        if (selected.isEmpty()) {
            status.setText(Bundle.LBL_NoneSelected());
            return;
        }
        List<String> names = new ArrayList<>();
        for (Catalog.Item i : all) {
            if (selected.contains(i.id)) {
                names.add(i.name);
            }
        }
        status.setText(Bundle.LBL_SelectedCount(names.size(), String.join(", ", names))); // NOI18N
    }

    private final class Model extends AbstractTableModel {

        private List<Catalog.Item> rows = new ArrayList<>();

        void refilter() {
            String q = filter.getText().trim().toLowerCase(Locale.ROOT);
            List<Catalog.Item> r = new ArrayList<>();
            for (Catalog.Item i : all) {
                if (q.isEmpty() || (i.name + " " + i.group + " " + i.description + " " + i.id) // NOI18N
                        .toLowerCase(Locale.ROOT).contains(q)) {
                    r.add(i);
                }
            }
            // selected ones first, so they stay visible while browsing
            r.sort((a, b) -> Boolean.compare(!selected.contains(a.id), !selected.contains(b.id)));
            rows = r;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 4;
        }

        @Override
        public String getColumnName(int c) {
            return switch (c) {
                case 0 -> Bundle.COL_Use();
                case 1 -> Bundle.COL_Name();
                case 2 -> Bundle.COL_Group();
                default -> Bundle.COL_Description();
            };
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return c == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c == 0;
        }

        @Override
        public Object getValueAt(int r, int c) {
            Catalog.Item i = rows.get(r);
            return switch (c) {
                case 0 -> selected.contains(i.id);
                case 1 -> i.name;
                case 2 -> i.group;
                default -> i.description;
            };
        }

        @Override
        public void setValueAt(Object value, int r, int c) {
            Catalog.Item i = rows.get(r);
            if (Boolean.TRUE.equals(value)) {
                selected.add(i.id);
            } else {
                selected.remove(i.id);
            }
            fireTableCellUpdated(r, c);
            updateStatus();
        }
    }

    @Override
    public HelpCtx getHelp() {
        return null;
    }

    @Override
    public void readSettings(WizardDescriptor wd) {
        wizard = wd;
    }

    @Override
    public void storeSettings(WizardDescriptor wd) {
        Catalog.Version v = (Catalog.Version) versions.getSelectedItem();
        settings.platform = v == null ? null : v.id;
        settings.dependencies = new ArrayList<>(selected);
    }

    /** The problem blocking this step (catalog not loaded or failed), or null. */
    String error() {
        if (error != null) {
            return error;
        }
        return loaded && versions.getSelectedItem() != null ? null : Bundle.LBL_LoadingCatalog();
    }

    @Override
    public boolean isValid() {
        if (wizard != null) {
            wizard.putProperty(WizardDescriptor.PROP_ERROR_MESSAGE, error);
        }
        return loaded && error == null && versions.getSelectedItem() != null;
    }

    @Override
    public void addChangeListener(ChangeListener l) {
        changes.addChangeListener(l);
    }

    @Override
    public void removeChangeListener(ChangeListener l) {
        changes.removeChangeListener(l);
    }
}
