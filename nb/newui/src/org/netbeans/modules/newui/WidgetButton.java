/*
 * Copyright 2026 Luciano Lopes.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 */
package org.netbeans.modules.newui;

import java.awt.Graphics;
import java.awt.Insets;
import javax.swing.Action;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import org.openide.awt.Actions;
import org.openide.filesystems.FileUtil;
import org.openide.util.ImageUtilities;
import org.openide.util.Lookup;
import org.openide.util.ContextAwareAction;
import org.openide.util.actions.Presenter;

/**
 * Flat menu-bar button with a trailing chevron that opens a popup menu,
 * like the project and VCS widgets of IntelliJ's New UI.
 */
abstract class WidgetButton extends JButton {

    private static final int GAP = 4;
    private static final int PAD = 6;
    private final Icon chevron = ImageUtilities.loadImageIcon("org/netbeans/modules/newui/resources/chevronDown.svg", false); // NOI18N

    WidgetButton() {
        putClientProperty("JButton.buttonType", "toolBarButton"); // NOI18N
        setFocusable(false);
        setRolloverEnabled(true);
        setIconTextGap(6);
        setMargin(new Insets(2, PAD, 2, PAD + GAP + chevron.getIconWidth()));
        addActionListener(e -> {
            JPopupMenu popup = new JPopupMenu();
            fillPopup(popup);
            if (popup.getComponentCount() > 0) {
                popup.show(this, 0, getHeight());
            }
        });
    }

    protected abstract void fillPopup(JPopupMenu popup);

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        chevron.paintIcon(this, g, getWidth() - PAD - chevron.getIconWidth(),
                (getHeight() - chevron.getIconHeight()) / 2);
    }

    /** Menu item for an action registered in the layer, or null when that module is not installed. */
    static JMenuItem actionItem(String path, Lookup context, String iconBase) {
        Object o = FileUtil.getConfigObject(path, Object.class);
        if (!(o instanceof Action)) {
            return null;
        }
        Action action = (Action) o;
        if (context != null && action instanceof ContextAwareAction) {
            action = ((ContextAwareAction) action).createContextAwareInstance(context);
        }
        if (context == null && action instanceof Presenter.Menu) {
            return ((Presenter.Menu) action).getMenuPresenter();
        }
        JMenuItem item = new JMenuItem();
        Actions.connect(item, action, false);
        if (iconBase != null) {
            item.setIcon(ImageUtilities.loadImageIcon(iconBase, false));
        }
        return item;
    }
}
