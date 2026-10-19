/*
 * Copyright 2026 Luciano Lopes.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 */
package org.netbeans.modules.newui;

import java.util.Arrays;
import java.util.Comparator;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectInformation;
import org.netbeans.api.project.ProjectUtils;
import org.netbeans.api.project.ui.OpenProjects;
import org.openide.util.NbBundle;

/** Current project name; the popup switches the main project and opens/closes projects. */
@NbBundle.Messages({
    "LBL_NoProject=No Project",
    "# {0} - project name",
    "LBL_CloseProject=Close {0}",
    "HINT_ProjectWidget=Current project"
})
public final class ProjectWidget extends WidgetButton {

    private static final String PROJECT_ACTIONS = "Actions/Project/"; // NOI18N

    public ProjectWidget() {
        setToolTipText(Bundle.HINT_ProjectWidget());
        CurrentProject.get().addChangeListener(e -> update());
        update();
    }

    private void update() {
        Project p = CurrentProject.get().project();
        if (p == null) {
            setText(Bundle.LBL_NoProject());
            setIcon(null);
        } else {
            ProjectInformation info = ProjectUtils.getInformation(p);
            setText(info.getDisplayName());
            setIcon(info.getIcon());
        }
    }

    @Override
    protected void fillPopup(JPopupMenu popup) {
        Project current = CurrentProject.get().project();
        Project[] open = OpenProjects.getDefault().getOpenProjects();
        Arrays.sort(open, Comparator.comparing(p -> ProjectUtils.getInformation(p).getDisplayName().toLowerCase()));
        for (Project p : open) {
            ProjectInformation info = ProjectUtils.getInformation(p);
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(info.getDisplayName(), info.getIcon(), p == current);
            item.addActionListener(e -> OpenProjects.getDefault().setMainProject(p));
            popup.add(item);
        }
        if (open.length > 0) {
            popup.addSeparator();
        }
        add(popup, WidgetButton.actionItem(PROJECT_ACTIONS + "org-netbeans-modules-project-ui-NewProject.instance", null, null));
        add(popup, WidgetButton.actionItem(PROJECT_ACTIONS + "org-netbeans-modules-project-ui-OpenProject.instance", null, null));
        add(popup, WidgetButton.actionItem(PROJECT_ACTIONS + "org-netbeans-modules-project-ui-RecentProjects.instance", null, null));
        if (current != null) {
            popup.addSeparator();
            JMenuItem close = new JMenuItem(Bundle.LBL_CloseProject(ProjectUtils.getInformation(current).getDisplayName()));
            close.addActionListener(e -> OpenProjects.getDefault().close(new Project[] {current}));
            popup.add(close);
        }
    }

    private static void add(JPopupMenu popup, JMenuItem item) {
        if (item != null) {
            popup.add(item);
        }
    }
}
