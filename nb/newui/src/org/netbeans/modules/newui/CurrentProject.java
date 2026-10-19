/*
 * Copyright 2026 Luciano Lopes.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 */
package org.netbeans.modules.newui;

import java.util.Arrays;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeListener;
import org.netbeans.api.project.FileOwnerQuery;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ui.OpenProjects;
import org.openide.loaders.DataObject;
import org.openide.nodes.Node;
import org.openide.util.ChangeSupport;
import org.openide.windows.TopComponent;

/**
 * The project the widgets refer to: the main project when one is set,
 * otherwise the project of the last selection (editor or explorer).
 */
final class CurrentProject {

    private static CurrentProject instance;

    private final ChangeSupport changes = new ChangeSupport(this);
    private Project selected;

    static synchronized CurrentProject get() {
        if (instance == null) {
            instance = new CurrentProject();
        }
        return instance;
    }

    private CurrentProject() {
        OpenProjects.getDefault().addPropertyChangeListener(e -> fire());
        TopComponent.getRegistry().addPropertyChangeListener(e -> {
            if (TopComponent.Registry.PROP_ACTIVATED_NODES.equals(e.getPropertyName())) {
                Project p = fromNodes(TopComponent.getRegistry().getActivatedNodes());
                if (p != null && p != selected) {
                    selected = p;
                    fire();
                }
            }
        });
    }

    Project project() {
        OpenProjects op = OpenProjects.getDefault();
        Project main = op.getMainProject();
        if (main != null) {
            return main;
        }
        Project[] open = op.getOpenProjects();
        if (selected != null && Arrays.asList(open).contains(selected)) {
            return selected;
        }
        return open.length > 0 ? open[0] : null;
    }

    void addChangeListener(ChangeListener l) {
        changes.addChangeListener(l);
    }

    private void fire() {
        SwingUtilities.invokeLater(changes::fireChange);
    }

    private static Project fromNodes(Node[] nodes) {
        for (Node n : nodes) {
            Project p = n.getLookup().lookup(Project.class);
            if (p != null) {
                return p;
            }
            DataObject d = n.getLookup().lookup(DataObject.class);
            if (d != null) {
                p = FileOwnerQuery.getOwner(d.getPrimaryFile());
                if (p != null) {
                    return p;
                }
            }
        }
        return null;
    }
}
