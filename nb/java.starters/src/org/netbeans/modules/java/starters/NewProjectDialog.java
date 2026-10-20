/*
 * Copyright 2026 Luciano Lopes.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 */
package org.netbeans.modules.java.starters;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.ChangeListener;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectManager;
import org.netbeans.api.project.ui.OpenProjects;
import org.netbeans.spi.project.ui.support.ProjectChooser;
import org.openide.WizardDescriptor;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionReferences;
import org.openide.awt.ActionRegistration;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.util.ImageUtilities;
import org.openide.util.NbBundle;
import org.openide.util.RequestProcessor;
import org.openide.windows.WindowManager;

/**
 * IntelliJ-style New Project dialog: generators on the left (Maven, Jakarta EE
 * web, Spring Boot, Quarkus), their form on the right, and a way out to the
 * classic NetBeans New Project wizard for every other project type.
 */
@NbBundle.Messages({
    "Template_Maven=Maven Application",
    "Template_Web=Java Web (Jakarta EE)",
    "Template_Spring=Spring Boot",
    "Template_Quarkus=Quarkus",
    "CTL_NewProject=Ne&w Project...",
    "TTL_NewProject=New Project",
    "LBL_Previous=< Previous",
    "LBL_Next=Next >",
    "LBL_Create=Create",
    "LBL_Cancel=Cancel",
    "LBL_MoreTypes=More project types...",
    "DESC_MoreTypes=Gradle, Ant, archetypes, samples",
    "DESC_Maven=Plain Java application",
    "DESC_Web=Jakarta EE WAR on Jetty",
    "DESC_Spring=from start.spring.io",
    "DESC_Quarkus=from code.quarkus.io",
    "# {0} - step number",
    "# {1} - step count",
    "# {2} - step title",
    "LBL_Step=Step {0} of {1}: {2}",
    "LBL_Creating=Creating the project...",
    "# {0} - error",
    "ERR_Create=Could not create the project: {0}"
})
final class NewProjectDialog {

    private static final RequestProcessor RP = new RequestProcessor(NewProjectDialog.class);
    private static final String ICONS = "org/netbeans/modules/java/starters/resources/"; // NOI18N
    private static final Object MORE = new Object();

    /** One generator: its settings and wizard steps, kept while the dialog is open. */
    private static final class Flow {
        final Generator.Kind kind;
        final Generator.Settings settings = new Generator.Settings();
        final List<WizardDescriptor.Panel<WizardDescriptor>> steps = new ArrayList<>();
        int step;

        Flow(Generator.Kind kind) {
            this.kind = kind;
            steps.add(new ProjectPanel(kind, settings));
            if (kind == Generator.Kind.SPRING || kind == Generator.Kind.QUARKUS) {
                steps.add(new CatalogPanel(kind, settings));
            }
        }

        String error() {
            WizardDescriptor.Panel<WizardDescriptor> p = steps.get(step);
            return p instanceof ProjectPanel ? ((ProjectPanel) p).error() : ((CatalogPanel) p).error();
        }
    }

    private final JDialog dialog;
    private final Map<Generator.Kind, Flow> flows = new EnumMap<>(Generator.Kind.class);
    private final DefaultListModel<Object> entries = new DefaultListModel<>();
    private final JList<Object> list = new JList<>(entries);
    private final JPanel cards = new JPanel(new CardLayout());
    private final JLabel title = new JLabel();
    private final JLabel stepLabel = new JLabel();
    private final JLabel message = new JLabel(" "); // NOI18N
    private final JProgressBar progress = new JProgressBar();
    private final JButton previous = new JButton(Bundle.LBL_Previous());
    private final JButton next = new JButton(Bundle.LBL_Next());
    private final JButton create = new JButton(Bundle.LBL_Create());
    private final JButton cancel = new JButton(Bundle.LBL_Cancel());
    private final ChangeListener validator = e -> SwingUtilities.invokeLater(this::update);
    private Flow current;
    private boolean busy;

    static void open() {
        new NewProjectDialog().dialog.setVisible(true);
    }

    private NewProjectDialog() {
        Frame owner = WindowManager.getDefault().getMainWindow();
        dialog = new JDialog(owner, Bundle.TTL_NewProject(), true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        for (Generator.Kind k : Generator.Kind.values()) {
            entries.addElement(k);
        }
        entries.addElement(MORE);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new EntryRenderer());
        list.setFixedCellHeight(-1);
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                select(list.getSelectedValue());
            }
        });
        JScrollPane listScroll = new JScrollPane(list, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        listScroll.setPreferredSize(new Dimension(250, 400));
        listScroll.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, color("Separator.foreground"))); // NOI18N

        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 4f));
        stepLabel.setForeground(color("Label.disabledForeground")); // NOI18N
        JPanel header = new JPanel(new BorderLayout(0, 2));
        header.setBorder(BorderFactory.createEmptyBorder(14, 18, 10, 18));
        header.add(title, BorderLayout.NORTH);
        header.add(stepLabel, BorderLayout.SOUTH);
        cards.setBorder(BorderFactory.createEmptyBorder(0, 18, 0, 18));
        JPanel right = new JPanel(new BorderLayout());
        right.add(header, BorderLayout.NORTH);
        right.add(cards, BorderLayout.CENTER);

        message.setForeground(new Color(0xE5, 0x53, 0x53));
        progress.setIndeterminate(true);
        progress.setVisible(false);
        previous.addActionListener(e -> move(-1));
        next.addActionListener(e -> move(1));
        create.addActionListener(e -> create());
        cancel.addActionListener(e -> dialog.dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(progress);
        buttons.add(previous);
        buttons.add(next);
        buttons.add(create);
        buttons.add(cancel);
        JPanel footer = new JPanel(new BorderLayout(12, 0));
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, color("Separator.foreground")), // NOI18N
                BorderFactory.createEmptyBorder(10, 14, 10, 14)));
        footer.add(message, BorderLayout.CENTER);
        footer.add(buttons, BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout());
        content.add(listScroll, BorderLayout.WEST);
        content.add(right, BorderLayout.CENTER);
        content.add(footer, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(create);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        dialog.setSize(980, 640);
        dialog.setLocationRelativeTo(owner);
        list.setSelectedIndex(0);
    }

    private static Color color(String key) {
        Color c = UIManager.getColor(key);
        return c != null ? c : Color.GRAY;
    }

    private void select(Object value) {
        if (value == MORE) {
            // hand over to the classic wizard with every other project type
            dialog.dispose();
            Action classic = FileUtil.getConfigObject(
                    "Actions/Project/org-netbeans-modules-project-ui-NewProject.instance", Action.class); // NOI18N
            if (classic != null) {
                SwingUtilities.invokeLater(() -> classic.actionPerformed(
                        new ActionEvent(this, ActionEvent.ACTION_PERFORMED, "classic"))); // NOI18N
            }
            return;
        }
        if (!(value instanceof Generator.Kind)) {
            return;
        }
        Generator.Kind kind = (Generator.Kind) value;
        current = flows.computeIfAbsent(kind, k -> {
            Flow f = new Flow(k);
            for (int i = 0; i < f.steps.size(); i++) {
                cards.add(f.steps.get(i).getComponent(), k.name() + i);
                f.steps.get(i).addChangeListener(validator);
            }
            return f;
        });
        title.setText(label(kind));
        showStep();
    }

    private void move(int delta) {
        current.steps.get(current.step).storeSettings(null);
        current.step = Math.max(0, Math.min(current.steps.size() - 1, current.step + delta));
        showStep();
    }

    private void showStep() {
        ((CardLayout) cards.getLayout()).show(cards, current.kind.name() + current.step);
        Component c = current.steps.get(current.step).getComponent();
        stepLabel.setText(current.steps.size() > 1
                ? Bundle.LBL_Step(current.step + 1, current.steps.size(), c.getName()) : c.getName());
        update();
    }

    private void update() {
        if (current == null) {
            return;
        }
        String error = busy ? null : current.error();
        if (!busy) {
            message.setText(error == null ? " " : error); // NOI18N
        }
        boolean last = current.step == current.steps.size() - 1;
        previous.setVisible(current.steps.size() > 1);
        next.setVisible(current.steps.size() > 1);
        previous.setEnabled(!busy && current.step > 0);
        next.setEnabled(!busy && !last && error == null);
        boolean allValid = error == null;
        for (int i = 0; allValid && i < current.step; i++) {
            allValid = errorOf(current.steps.get(i)) == null;
        }
        create.setEnabled(!busy && allValid && last);
        list.setEnabled(!busy);
    }

    private static String errorOf(WizardDescriptor.Panel<WizardDescriptor> p) {
        return p instanceof ProjectPanel ? ((ProjectPanel) p).error() : ((CatalogPanel) p).error();
    }

    private void create() {
        Flow flow = current;
        if (flow == null || !create.isEnabled()) {
            return;
        }
        for (WizardDescriptor.Panel<WizardDescriptor> p : flow.steps) {
            p.storeSettings(null);
        }
        busy = true;
        progress.setVisible(true);
        message.setForeground(color("Label.foreground")); // NOI18N
        message.setText(Bundle.LBL_Creating());
        update();
        RP.post(() -> {
            try {
                File dir = Generator.generate(flow.kind, flow.settings);
                FileObject fo = FileUtil.toFileObject(FileUtil.normalizeFile(dir));
                fo.refresh();
                Project p = ProjectManager.getDefault().findProject(fo);
                ProjectChooser.setProjectsFolder(flow.settings.location);
                SwingUtilities.invokeLater(dialog::dispose);
                if (p != null) {
                    OpenProjects.getDefault().open(new Project[] {p}, false, true);
                }
            } catch (Exception ex) {
                String msg = ex.getMessage() != null ? ex.getMessage() : ex.toString();
                SwingUtilities.invokeLater(() -> {
                    busy = false;
                    progress.setVisible(false);
                    message.setForeground(new Color(0xE5, 0x53, 0x53));
                    message.setText(Bundle.ERR_Create(msg));
                    update();
                });
            }
        });
    }

    private static String label(Generator.Kind k) {
        return switch (k) {
            case MAVEN -> Bundle.Template_Maven();
            case WEB -> Bundle.Template_Web();
            case SPRING -> Bundle.Template_Spring();
            case QUARKUS -> Bundle.Template_Quarkus();
        };
    }

    private static String description(Generator.Kind k) {
        return switch (k) {
            case MAVEN -> Bundle.DESC_Maven();
            case WEB -> Bundle.DESC_Web();
            case SPRING -> Bundle.DESC_Spring();
            case QUARKUS -> Bundle.DESC_Quarkus();
        };
    }

    static Icon icon(Generator.Kind k, boolean large) {
        String name = switch (k) {
            case MAVEN -> "maven"; // NOI18N
            case WEB -> "web"; // NOI18N
            case SPRING -> "spring"; // NOI18N
            case QUARKUS -> "quarkus"; // NOI18N
        };
        return ImageUtilities.loadImageIcon(ICONS + name + (large ? "32" : "") + ".svg", false); // NOI18N
    }

    private static final class EntryRenderer extends JPanel implements ListCellRenderer<Object> {

        private final JLabel icon = new JLabel();
        private final JLabel name = new JLabel();
        private final JLabel desc = new JLabel();
        private final JSeparator separator = new JSeparator();

        EntryRenderer() {
            super(new BorderLayout(10, 0));
            JPanel text = new JPanel(new BorderLayout(0, 1));
            text.setOpaque(false);
            name.setFont(name.getFont().deriveFont(Font.BOLD));
            desc.setFont(desc.getFont().deriveFont(desc.getFont().getSize2D() - 1f));
            text.add(name, BorderLayout.NORTH);
            text.add(desc, BorderLayout.SOUTH);
            add(icon, BorderLayout.WEST);
            add(text, BorderLayout.CENTER);
            add(separator, BorderLayout.NORTH);
        }

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean selected, boolean focus) {
            boolean more = value == MORE;
            separator.setVisible(more);
            if (more) {
                icon.setIcon(null);
                name.setText(Bundle.LBL_MoreTypes());
                desc.setText(Bundle.DESC_MoreTypes());
                setBorder(BorderFactory.createEmptyBorder(0, 14, 10, 10));
            } else {
                Generator.Kind k = (Generator.Kind) value;
                icon.setIcon(icon(k, true));
                name.setText(label(k));
                desc.setText(description(k));
                setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 10));
            }
            setOpaque(true);
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            name.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            desc.setForeground(selected ? list.getSelectionForeground() : color("Label.disabledForeground")); // NOI18N
            return this;
        }
    }

    /** File > New Project, toolbar button and Ctrl+Shift+N (moved here from the projectui module). */
    @ActionID(category = "Project", id = "org.netbeans.modules.java.starters.NewProjectDialog.OpenAction")
    @ActionRegistration(displayName = "#CTL_NewProject", iconBase = "org/netbeans/modules/java/starters/resources/newProject.svg")
    @ActionReferences({
        @ActionReference(path = "Shortcuts", name = "DS-N"),
        @ActionReference(path = "ProjectsTabActions", position = 100),
        @ActionReference(path = "Menu/File", position = 100),
        @ActionReference(path = "Toolbars/File", position = 200)
    })
    public static final class OpenAction extends AbstractAction {

        @Override
        public void actionPerformed(ActionEvent e) {
            open();
        }
    }
}
