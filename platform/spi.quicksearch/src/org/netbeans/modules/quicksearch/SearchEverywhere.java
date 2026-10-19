/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.netbeans.modules.quicksearch;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.netbeans.modules.quicksearch.ResultsModel.ItemResult;
import org.netbeans.spi.quicksearch.SearchProvider;
import org.netbeans.spi.quicksearch.SearchRequest;
import org.netbeans.spi.quicksearch.SearchResponse;
import org.openide.util.NbBundle;
import org.openide.util.RequestProcessor;
import org.openide.windows.WindowManager;

/**
 * IntelliJ-style "Search Everywhere" popup on top of the Quick Search
 * providers: one field, tabs per category, results grouped by category.
 */
@NbBundle.Messages({
    "SE_All=All",
    "SE_Classes=Classes",
    "SE_Files=Files",
    "SE_Symbols=Symbols",
    "SE_Actions=Actions",
    "SE_Placeholder=Type to search classes, files, symbols and actions",
    "SE_Searching=Searching...",
    "SE_NothingFound=Nothing found",
    "SE_Hint=↑↓ navigate  ↵ open  Tab next tab  Esc close"
})
final class SearchEverywhere {

    private static final RequestProcessor RP = new RequestProcessor("Search Everywhere", 8); // NOI18N
    private static final int DEBOUNCE_MS = 150;

    private enum Tab {
        ALL(Bundle.SE_All(), "GoToType", "GoToFile", "GoToSymbol", "Actions", "GoToOption", "Projects"), // NOI18N
        CLASSES(Bundle.SE_Classes(), "GoToType"), // NOI18N
        FILES(Bundle.SE_Files(), "GoToFile"), // NOI18N
        SYMBOLS(Bundle.SE_Symbols(), "GoToSymbol"), // NOI18N
        ACTIONS(Bundle.SE_Actions(), "Actions", "GoToOption"); // NOI18N

        final String title;
        final Set<String> categories;

        Tab(String title, String... categories) {
            this.title = title;
            this.categories = Set.of(categories);
        }
    }

    private static SearchEverywhere showing;

    private final JDialog dialog;
    private final JTextField field = new JTextField();
    private final DefaultListModel<Object> model = new DefaultListModel<>();
    private final JList<Object> list = new JList<>(model);
    private final JLabel status = new JLabel();
    private final List<JToggleButton> tabButtons = new ArrayList<>();
    private final Timer debounce = new Timer(DEBOUNCE_MS, e -> search());
    private final Timer refresh = new Timer(100, e -> refreshList());
    private Tab tab = Tab.ALL;
    private List<CategoryResult> results = List.of();
    private List<org.openide.util.Task> evaluation = List.of();

    static void open() {
        if (showing != null) {
            showing.nextTab();
            return;
        }
        showing = new SearchEverywhere();
        showing.show();
    }

    private SearchEverywhere() {
        Frame owner = WindowManager.getDefault().getMainWindow();
        dialog = new JDialog(owner, false);
        dialog.setUndecorated(true);
        dialog.getRootPane().setWindowDecorationStyle(JRootPane.NONE);
        dialog.getRootPane().setBorder(BorderFactory.createLineBorder(
                color("PopupMenu.borderColor", "Component.borderColor"), 1)); // NOI18N
        dialog.setContentPane(createContent());
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowDeactivated(WindowEvent e) {
                close();
            }
        });
        debounce.setRepeats(false);
    }

    private JComponent createContent() {
        JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        tabs.setOpaque(false);
        ButtonGroup group = new ButtonGroup();
        for (Tab t : Tab.values()) {
            JToggleButton b = new JToggleButton(t.title, t == tab);
            b.putClientProperty("JButton.buttonType", "tab"); // NOI18N
            b.setFocusable(false);
            b.addActionListener(e -> selectTab(t));
            group.add(b);
            tabs.add(b);
            tabButtons.add(b);
        }

        field.putClientProperty("JTextField.placeholderText", Bundle.SE_Placeholder()); // NOI18N
        field.putClientProperty("JTextField.showClearButton", true); // NOI18N
        field.setFont(field.getFont().deriveFont(field.getFont().getSize2D() + 2f));
        field.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                debounce.restart();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                debounce.restart();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
            }
        });
        bind(field, KeyEvent.VK_DOWN, 0, () -> move(1));
        bind(field, KeyEvent.VK_UP, 0, () -> move(-1));
        bind(field, KeyEvent.VK_PAGE_DOWN, 0, () -> move(10));
        bind(field, KeyEvent.VK_PAGE_UP, 0, () -> move(-10));
        bind(field, KeyEvent.VK_ENTER, 0, this::openSelected);
        bind(field, KeyEvent.VK_ESCAPE, 0, this::close);
        bind(field, KeyEvent.VK_TAB, 0, this::nextTab);
        bind(field, KeyEvent.VK_TAB, KeyEvent.SHIFT_DOWN_MASK, this::previousTab);
        field.setFocusTraversalKeysEnabled(false);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFocusable(false);
        list.setCellRenderer(new Renderer());
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    int i = list.locationToIndex(e.getPoint());
                    if (i >= 0) {
                        list.setSelectedIndex(i);
                        openSelected();
                    }
                }
            }
        });
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, color("Separator.foreground", "Component.borderColor"))); // NOI18N

        status.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        status.setForeground(UIManager.getColor("Label.disabledForeground")); // NOI18N
        status.setText(Bundle.SE_Hint());

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.setBorder(BorderFactory.createEmptyBorder(6, 6, 0, 6));
        top.add(tabs, BorderLayout.NORTH);
        top.add(field, BorderLayout.CENTER);

        JPanel content = new JPanel(new BorderLayout());
        content.setBackground(UIManager.getColor("PopupMenu.background")); // NOI18N
        content.add(top, BorderLayout.NORTH);
        content.add(scroll, BorderLayout.CENTER);
        content.add(status, BorderLayout.SOUTH);
        return content;
    }

    private static void bind(JComponent c, int key, int modifiers, Runnable r) {
        String name = "se-" + key + "-" + modifiers; // NOI18N
        c.getInputMap().put(KeyStroke.getKeyStroke(key, modifiers), name);
        c.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        });
    }

    private void show() {
        Frame owner = WindowManager.getDefault().getMainWindow();
        Rectangle b = owner.getBounds();
        int width = Math.max(640, Math.min(900, b.width / 2));
        int height = Math.max(380, Math.min(520, b.height / 2));
        dialog.setSize(new Dimension(width, height));
        dialog.setLocation(b.x + (b.width - width) / 2, b.y + b.height / 7);
        dialog.setVisible(true);
        field.requestFocusInWindow();
    }

    private void close() {
        debounce.stop();
        refresh.stop();
        obsoleteResults();
        dialog.dispose();
        if (showing == this) {
            showing = null;
        }
    }

    private void selectTab(Tab t) {
        tab = t;
        tabButtons.get(t.ordinal()).setSelected(true);
        search();
        field.requestFocusInWindow();
    }

    private void nextTab() {
        selectTab(Tab.values()[(tab.ordinal() + 1) % Tab.values().length]);
    }

    private void previousTab() {
        selectTab(Tab.values()[(tab.ordinal() + Tab.values().length - 1) % Tab.values().length]);
    }

    private void obsoleteResults() {
        for (CategoryResult r : results) {
            r.setObsolete(true);
        }
        results = List.of();
    }

    private void search() {
        obsoleteResults();
        String text = field.getText().trim();
        model.clear();
        if (text.isEmpty()) {
            refresh.stop();
            status.setText(Bundle.SE_Hint());
            return;
        }
        boolean all = tab == Tab.ALL;
        SearchRequest request = Accessor.DEFAULT.createRequest(text, null);
        List<CategoryResult> started = new ArrayList<>();
        List<org.openide.util.Task> tasks = new ArrayList<>();
        // keep the Quick Search category order (layer positions)
        for (ProviderModel.Category cat : ProviderModel.getInstance().getCategories()) {
            if (!tab.categories.contains(cat.getName())) {
                continue;
            }
            CategoryResult result = new CategoryResult(cat, !all);
            SearchResponse response = Accessor.DEFAULT.createResponse(result, request);
            for (SearchProvider provider : cat.getProviders()) {
                tasks.add(RP.post(() -> provider.evaluate(request, response)));
            }
            started.add(result);
        }
        results = started;
        status.setText(Bundle.SE_Searching());
        evaluation = tasks;
        refresh.start();
    }

    private void refreshList() {
        boolean done = evaluation.stream().allMatch(org.openide.util.Task::isFinished);
        Object selected = list.getSelectedValue();
        List<Object> rows = new ArrayList<>();
        for (CategoryResult r : results) {
            List<ItemResult> items = r.getItems();
            if (items.isEmpty()) {
                continue;
            }
            if (tab == Tab.ALL) {
                rows.add(r.getCategory().getDisplayName());
            }
            rows.addAll(items);
        }
        boolean changed = rows.size() != model.size();
        for (int i = 0; !changed && i < rows.size(); i++) {
            changed = rows.get(i) != model.get(i);
        }
        if (changed) {
            model.clear();
            rows.forEach(model::addElement);
            int idx = selected != null ? rows.indexOf(selected) : -1;
            list.setSelectedIndex(idx >= 0 ? idx : firstItem(0, 1));
            list.ensureIndexIsVisible(Math.max(0, list.getSelectedIndex()));
        }
        if (done) {
            refresh.stop();
            status.setText(model.isEmpty() ? Bundle.SE_NothingFound() : Bundle.SE_Hint());
        }
    }

    private int firstItem(int from, int step) {
        for (int i = from; i >= 0 && i < model.size(); i += step) {
            if (model.get(i) instanceof ItemResult) {
                return i;
            }
        }
        return -1;
    }

    private void move(int delta) {
        if (model.isEmpty()) {
            return;
        }
        int step = delta > 0 ? 1 : -1;
        int target = Math.max(0, Math.min(model.size() - 1, list.getSelectedIndex() + delta));
        int idx = firstItem(target, step);
        if (idx < 0) {
            idx = firstItem(target, -step);
        }
        if (idx >= 0) {
            list.setSelectedIndex(idx);
            list.ensureIndexIsVisible(idx);
        }
    }

    private void openSelected() {
        Object value = list.getSelectedValue();
        if (!(value instanceof ItemResult)) {
            return;
        }
        ItemResult item = (ItemResult) value;
        Runnable action = item.getAction();
        if (action instanceof CategoryResult) {
            // the "more results" row: switch to that category's tab
            String name = ((CategoryResult) action).getCategory().getName();
            for (Tab t : Tab.values()) {
                if (t != Tab.ALL && t.categories.contains(name)) {
                    selectTab(t);
                    return;
                }
            }
            return;
        }
        close();
        if (action != null) {
            SwingUtilities.invokeLater(action);
        }
    }

    private static Color color(String key, String fallback) {
        Color c = UIManager.getColor(key);
        return c != null ? c : UIManager.getColor(fallback);
    }

    private static final class Renderer extends JPanel implements ListCellRenderer<Object> {

        private final JLabel name = new JLabel();
        private final JLabel hint = new JLabel();
        private final JLabel header = new JLabel();

        Renderer() {
            super(new BorderLayout(12, 0));
            setBorder(BorderFactory.createEmptyBorder(3, 12, 3, 12));
            add(name, BorderLayout.CENTER);
            add(hint, BorderLayout.EAST);
            header.setBorder(BorderFactory.createEmptyBorder(8, 10, 2, 10));
            header.setFont(header.getFont().deriveFont(Font.BOLD, header.getFont().getSize2D() - 1f));
        }

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean isSelected, boolean cellHasFocus) {
            if (!(value instanceof ItemResult)) {
                header.setText(String.valueOf(value));
                header.setForeground(UIManager.getColor("Label.disabledForeground")); // NOI18N
                return header;
            }
            ItemResult item = (ItemResult) value;
            String display = item.getDisplayName();
            name.setText(display.startsWith("<html>") ? display : "<html>" + display); // NOI18N
            String h = item.getDisplayHint();
            List<? extends KeyStroke> shortcut = item.getShortcut();
            if ((h == null || h.isEmpty()) && shortcut != null && !shortcut.isEmpty()) {
                h = String.join(" ", Arrays.asList(shortcut.stream() // NOI18N
                        .map(org.openide.awt.Actions::keyStrokeToString).toArray(String[]::new)));
            }
            hint.setText(h == null ? "" : h); // NOI18N
            setOpaque(true);
            setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            name.setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
            hint.setForeground(isSelected ? list.getSelectionForeground()
                    : UIManager.getColor("Label.disabledForeground")); // NOI18N
            return this;
        }
    }
}
