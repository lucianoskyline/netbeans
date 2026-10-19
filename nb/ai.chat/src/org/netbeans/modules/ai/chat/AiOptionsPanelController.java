/*
 * Copyright 2026 Luciano Lopes.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 */
package org.netbeans.modules.ai.chat;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.EnumMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.netbeans.spi.options.OptionsPanelController;
import org.openide.util.HelpCtx;
import org.openide.util.Lookup;
import org.openide.util.NbBundle;
import org.openide.util.RequestProcessor;

@OptionsPanelController.SubRegistration(
        location = "Advanced",
        displayName = "#AdvancedOption_DisplayName_AiChat",
        keywords = "#AdvancedOption_Keywords_AiChat",
        keywordsCategory = "Advanced/AiChat")
@NbBundle.Messages({
    "AdvancedOption_DisplayName_AiChat=AI Chat",
    "AdvancedOption_Keywords_AiChat=AI, Claude, ChatGPT, OpenAI, Gemini, API key, model",
    "LBL_ApiKey=API key:",
    "LBL_ModelField=Model:",
    "# {0} - environment variable",
    "HINT_KeyStorage=Stored in the system keyring. If empty, the {0} environment variable is used.",
    "HINT_Model=Leave the model empty to use the default."
})
public final class AiOptionsPanelController extends OptionsPanelController {

    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);
    private final Map<AiProvider, JPasswordField> keys = new EnumMap<>(AiProvider.class);
    private final Map<AiProvider, JTextField> models = new EnumMap<>(AiProvider.class);
    private final Map<AiProvider, String> loadedKeys = new EnumMap<>(AiProvider.class);
    private JPanel panel;
    private boolean changed;
    private boolean loading;

    @Override
    public void update() {
        getComponent(Lookup.EMPTY);
        loading = true;
        for (AiProvider p : AiProvider.values()) {
            String m = AiSettings.model(p);
            models.get(p).setText(m.equals(p.defaultModel) ? "" : m);
        }
        loading = false;
        // the keyring may prompt or block, keep it off the EDT
        RequestProcessor.getDefault().post(() -> {
            Map<AiProvider, String> read = new EnumMap<>(AiProvider.class);
            for (AiProvider p : AiProvider.values()) {
                String k = AiSettings.storedApiKey(p);
                read.put(p, k == null ? "" : k);
            }
            SwingUtilities.invokeLater(() -> {
                loadedKeys.putAll(read);
                loading = true;
                read.forEach((p, k) -> keys.get(p).setText(k));
                loading = false;
            });
        });
        changed = false;
    }

    @Override
    public void applyChanges() {
        Map<AiProvider, String> newKeys = new EnumMap<>(AiProvider.class);
        for (AiProvider p : AiProvider.values()) {
            AiSettings.setModel(p, models.get(p).getText());
            String k = new String(keys.get(p).getPassword());
            if (!k.equals(loadedKeys.getOrDefault(p, ""))) {
                newKeys.put(p, k);
            }
        }
        loadedKeys.putAll(newKeys);
        RequestProcessor.getDefault().post(() -> newKeys.forEach(AiSettings::setApiKey));
        changed = false;
    }

    @Override
    public void cancel() {
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public boolean isChanged() {
        return changed;
    }

    @Override
    public JComponent getComponent(Lookup masterLookup) {
        if (panel == null) {
            panel = createPanel();
        }
        return panel;
    }

    @Override
    public HelpCtx getHelpCtx() {
        return null;
    }

    @Override
    public void addPropertyChangeListener(PropertyChangeListener l) {
        pcs.addPropertyChangeListener(l);
    }

    @Override
    public void removePropertyChangeListener(PropertyChangeListener l) {
        pcs.removePropertyChangeListener(l);
    }

    private JPanel createPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        DocumentListener listener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                markChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                markChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                markChanged();
            }
        };
        int row = 0;
        for (AiProvider provider : AiProvider.values()) {
            JLabel title = new JLabel(provider.displayName);
            title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD));
            p.add(title, gbc(0, row, 2, row == 0 ? 0 : 14));
            row++;

            JPasswordField key = new JPasswordField(40);
            key.getDocument().addDocumentListener(listener);
            keys.put(provider, key);
            p.add(new JLabel(Bundle.LBL_ApiKey()), gbc(0, row, 1, 4));
            p.add(key, fill(gbc(1, row++, 1, 4)));

            JTextField model = new JTextField(40);
            model.putClientProperty("JTextField.placeholderText", provider.defaultModel); // NOI18N
            model.getDocument().addDocumentListener(listener);
            models.put(provider, model);
            p.add(new JLabel(Bundle.LBL_ModelField()), gbc(0, row, 1, 4));
            p.add(model, fill(gbc(1, row++, 1, 4)));

            JLabel hint = new JLabel(Bundle.HINT_KeyStorage(provider.envVariable));
            hint.setForeground(UIManager.getColor("Label.disabledForeground")); // NOI18N
            p.add(hint, gbc(1, row++, 1, 2));
        }
        JLabel modelHint = new JLabel(Bundle.HINT_Model());
        modelHint.setForeground(UIManager.getColor("Label.disabledForeground")); // NOI18N
        p.add(modelHint, gbc(0, row++, 2, 14));
        GridBagConstraints filler = gbc(0, row, 2, 0);
        filler.weighty = 1;
        p.add(new JPanel(), filler);
        return p;
    }

    private void markChanged() {
        if (loading) {
            return;
        }
        if (!changed) {
            changed = true;
            pcs.firePropertyChange(OptionsPanelController.PROP_CHANGED, false, true);
        }
        pcs.firePropertyChange(OptionsPanelController.PROP_VALID, null, null);
    }

    private static GridBagConstraints gbc(int x, int y, int width, int top) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x;
        c.gridy = y;
        c.gridwidth = width;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(top, 0, 0, 8);
        return c;
    }

    private static GridBagConstraints fill(GridBagConstraints c) {
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        return c;
    }
}
