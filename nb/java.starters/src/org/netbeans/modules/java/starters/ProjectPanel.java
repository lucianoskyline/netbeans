/*
 * Copyright 2026 Luciano Lopes.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 */
package org.netbeans.modules.java.starters;

import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.netbeans.spi.project.ui.support.ProjectChooser;
import org.openide.WizardDescriptor;
import org.openide.util.ChangeSupport;
import org.openide.util.HelpCtx;
import org.openide.util.NbBundle;

/** First wizard step: where the project goes and its Maven coordinates. */
@NbBundle.Messages({
    "LBL_ProjectStep=Name and Location",
    "LBL_Name=Project Name:",
    "LBL_Location=Project Location:",
    "LBL_Folder=Project Folder:",
    "LBL_Browse=Browse...",
    "LBL_Group=Group Id:",
    "LBL_Artifact=Artifact Id:",
    "LBL_Package=Package:",
    "LBL_Java=Java Version:",
    "LBL_Jakarta=Jakarta EE:",
    "ERR_Name=Enter a project name (letters, digits, '-', '_' or '.').",
    "ERR_Location=Choose an existing folder for the project.",
    "ERR_Exists=The project folder already exists and is not empty.",
    "ERR_Group=Group Id must look like com.example.",
    "ERR_Artifact=Artifact Id may use lowercase letters, digits, '-', '_' and '.'.",
    "ERR_Package=Package must be a valid Java package name, e.g. com.example.demo."
})
final class ProjectPanel implements WizardDescriptor.Panel<WizardDescriptor> {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*"); // NOI18N
    private static final Pattern GROUP = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*(\\.[A-Za-z_][A-Za-z0-9_-]*)*"); // NOI18N
    private static final Pattern ARTIFACT = Pattern.compile("[a-z0-9][a-z0-9._-]*"); // NOI18N
    private static final Pattern PACKAGE = Pattern.compile("[a-z_][a-z0-9_]*(\\.[a-z_][a-z0-9_]*)*"); // NOI18N

    private final Generator.Kind kind;
    private final Generator.Settings settings;
    private final ChangeSupport changes = new ChangeSupport(this);
    private JPanel component;
    private WizardDescriptor wizard;
    private final JTextField name = new JTextField("demo", 30); // NOI18N
    private final JTextField location = new JTextField(30);
    private final JTextField folder = new JTextField(30);
    private final JTextField group = new JTextField("com.example", 30); // NOI18N
    private final JTextField artifact = new JTextField("demo", 30); // NOI18N
    private final JTextField pkg = new JTextField("com.example.demo", 30); // NOI18N
    private final JComboBox<String> java = new JComboBox<>(new String[] {"25", "21", "17"}); // NOI18N
    private final JComboBox<String> jakarta = new JComboBox<>(new String[] {"11", "10"}); // NOI18N
    /** Artifact and package follow the name until the user edits them. */
    private boolean artifactEdited;
    private boolean packageEdited;
    private boolean syncing;

    ProjectPanel(Generator.Kind kind, Generator.Settings settings) {
        this.kind = kind;
        this.settings = settings;
    }

    @Override
    public Component getComponent() {
        if (component == null) {
            component = build();
        }
        return component;
    }

    private JPanel build() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setName(Bundle.LBL_ProjectStep());
        File projects = ProjectChooser.getProjectsFolder();
        location.setText(projects == null ? System.getProperty("user.home") : projects.getAbsolutePath()); // NOI18N
        folder.setEditable(false);
        java.setSelectedItem("21"); // NOI18N  LTS that matches the IDE's own JDK; 25 is offered too
        int row = 0;
        row = add(p, row, Bundle.LBL_Name(), name, null);
        JButton browse = new JButton(Bundle.LBL_Browse());
        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(location.getText());
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(p) == JFileChooser.APPROVE_OPTION) {
                location.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });
        row = add(p, row, Bundle.LBL_Location(), location, browse);
        row = add(p, row, Bundle.LBL_Folder(), folder, null);
        row = add(p, row, Bundle.LBL_Group(), group, null);
        row = add(p, row, Bundle.LBL_Artifact(), artifact, null);
        row = add(p, row, Bundle.LBL_Package(), pkg, null);
        row = add(p, row, Bundle.LBL_Java(), java, null);
        if (kind == Generator.Kind.WEB) {
            row = add(p, row, Bundle.LBL_Jakarta(), jakarta, null);
        }
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridy = row;
        filler.weighty = 1;
        p.add(new JPanel(), filler);

        name.getDocument().addDocumentListener(listener(() -> {
            if (!artifactEdited) {
                set(artifact, name.getText().trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-")); // NOI18N
            }
            updatePackage();
        }));
        group.getDocument().addDocumentListener(listener(this::updatePackage));
        artifact.getDocument().addDocumentListener(listener(() -> {
            if (!syncing) {
                artifactEdited = true;
            }
            updatePackage();
        }));
        pkg.getDocument().addDocumentListener(listener(() -> {
            if (!syncing) {
                packageEdited = true;
            }
        }));
        location.getDocument().addDocumentListener(listener(() -> { }));
        java.addActionListener(e -> changes.fireChange());
        updateFolder();
        return p;
    }

    private void updatePackage() {
        if (!packageEdited) {
            String last = artifact.getText().trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", ""); // NOI18N
            if (!last.isEmpty() && Character.isDigit(last.charAt(0))) {
                last = "_" + last; // NOI18N
            }
            set(pkg, group.getText().trim().toLowerCase(Locale.ROOT).replace('-', '_') + (last.isEmpty() ? "" : "." + last)); // NOI18N
        }
    }

    private void set(JTextField field, String text) {
        syncing = true;
        try {
            field.setText(text);
        } finally {
            syncing = false;
        }
    }

    private DocumentListener listener(Runnable r) {
        return new DocumentListener() {
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

            private void changed() {
                r.run();
                updateFolder();
                changes.fireChange();
            }
        };
    }

    private void updateFolder() {
        folder.setText(new File(location.getText().trim(), name.getText().trim()).getAbsolutePath());
    }

    private static int add(JPanel p, int row, String label, Component field, Component extra) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(4, 0, 4, 12);
        JLabel l = new JLabel(label);
        l.setLabelFor(field);
        p.add(l, c);
        c = new GridBagConstraints();
        c.gridx = 1;
        c.gridy = row;
        c.fill = field instanceof JComboBox ? GridBagConstraints.NONE : GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        c.weightx = 1;
        c.insets = new Insets(4, 0, 4, 0);
        p.add(field, c);
        if (extra != null) {
            c = new GridBagConstraints();
            c.gridx = 2;
            c.gridy = row;
            c.insets = new Insets(4, 8, 4, 0);
            p.add(extra, c);
        }
        return row + 1;
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
        settings.name = name.getText().trim();
        settings.location = new File(location.getText().trim());
        settings.groupId = group.getText().trim();
        settings.artifactId = artifact.getText().trim();
        settings.packageName = pkg.getText().trim();
        settings.javaVersion = (String) java.getSelectedItem();
        if (kind == Generator.Kind.WEB) {
            settings.platform = (String) jakarta.getSelectedItem();
        }
    }

    @Override
    public boolean isValid() {
        String error = error();
        if (wizard != null) {
            wizard.putProperty(WizardDescriptor.PROP_ERROR_MESSAGE, error);
        }
        return error == null;
    }

    /** The problem blocking this step, or null. */
    String error() {
        if (!NAME.matcher(name.getText().trim()).matches()) {
            return Bundle.ERR_Name();
        }
        File loc = new File(location.getText().trim());
        if (!loc.isDirectory()) {
            return Bundle.ERR_Location();
        }
        File dir = new File(loc, name.getText().trim());
        String[] existing = dir.list();
        if (existing != null && existing.length > 0) {
            return Bundle.ERR_Exists();
        }
        if (!GROUP.matcher(group.getText().trim()).matches()) {
            return Bundle.ERR_Group();
        }
        if (!ARTIFACT.matcher(artifact.getText().trim()).matches()) {
            return Bundle.ERR_Artifact();
        }
        if (!PACKAGE.matcher(pkg.getText().trim()).matches()) {
            return Bundle.ERR_Package();
        }
        return null;
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
