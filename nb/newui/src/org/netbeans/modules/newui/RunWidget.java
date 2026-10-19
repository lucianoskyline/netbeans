/*
 * Copyright 2026 Luciano Lopes.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 */
package org.netbeans.modules.newui;

import java.awt.Component;
import java.awt.FlowLayout;
import javax.swing.AbstractButton;
import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JPanel;
import org.openide.awt.Actions;
import org.openide.filesystems.FileUtil;
import org.openide.util.actions.Presenter;

/**
 * Right side of the menu bar: run configuration, Stop and AI Chat,
 * all reusing the actions registered by their own modules.
 */
public final class RunWidget extends JPanel {

    private static final String[] ACTIONS = {
        // Run and Debug stay in the regular Build toolbar
        "Actions/Project/org-netbeans-modules-project-ui-actions-ActiveConfigAction.instance", // NOI18N
        "Actions/Project/org-netbeans-modules-project-ui-actions-StopBuildingAction.instance", // NOI18N
        "Actions/Window/org-netbeans-modules-ai-chat-AiChatTopComponent.instance", // NOI18N
    };

    public RunWidget() {
        super(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        setOpaque(false);
        for (String path : ACTIONS) {
            Component c = presenter(FileUtil.getConfigObject(path, Object.class));
            if (c != null) {
                add(c);
            }
        }
    }

    private static Component presenter(Object o) {
        Component c = null;
        if (o instanceof Presenter.Toolbar) {
            c = ((Presenter.Toolbar) o).getToolbarPresenter();
        } else if (o instanceof Action) {
            JButton b = new JButton();
            // must be set before connect(), which keeps the text in sync otherwise
            b.putClientProperty("hideActionText", Boolean.TRUE); // NOI18N
            Actions.connect(b, (Action) o);
            c = b;
        }
        if (c instanceof AbstractButton) {
            AbstractButton b = (AbstractButton) c;
            b.putClientProperty("JButton.buttonType", "toolBarButton"); // NOI18N
            b.putClientProperty("hideActionText", Boolean.TRUE); // NOI18N
            b.setText(null);
            b.setFocusable(false);
        }
        return c;
    }
}
