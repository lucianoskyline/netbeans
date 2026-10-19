/*
 * Copyright 2026 Luciano Lopes.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 */
package org.netbeans.modules.newui;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Objects;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.netbeans.api.project.Project;
import org.netbeans.spi.project.ui.LogicalViewProvider;
import org.openide.filesystems.FileUtil;
import org.openide.nodes.Node;
import org.openide.util.ImageUtilities;
import org.openide.util.Lookup;
import org.openide.util.NbBundle;
import org.openide.util.RequestProcessor;
import org.openide.util.lookup.Lookups;

/**
 * Git branch of the current project, read from .git/HEAD; the popup runs the
 * Git module's own actions on that project. Hidden outside Git repositories.
 */
@NbBundle.Messages({
    "# {0} - repository root",
    "HINT_BranchWidget=Git branch ({0})"
})
public final class BranchWidget extends WidgetButton {

    private static final RequestProcessor RP = new RequestProcessor(BranchWidget.class);
    private static final String GIT = "Actions/Git/org-netbeans-modules-git-ui-"; // NOI18N
    private static final String ICONS = "org/netbeans/modules/newui/resources/"; // NOI18N
    private static final int REFRESH_MS = 2000;

    private Project project;
    private String branch;

    public BranchWidget() {
        setIcon(ImageUtilities.loadImageIcon(ICONS + "branch.svg", false)); // NOI18N
        setVisible(false);
        CurrentProject.get().addChangeListener(e -> refresh());
        // HEAD changes on checkout from anywhere (IDE, terminal), so poll the tiny file
        Timer timer = new Timer(REFRESH_MS, e -> refresh());
        timer.start();
        refresh();
    }

    private void refresh() {
        Project p = CurrentProject.get().project();
        File dir = p == null ? null : FileUtil.toFile(p.getProjectDirectory());
        RP.post(() -> {
            File gitDir = dir == null ? null : findGitDir(dir);
            String b = gitDir == null ? null : readBranch(gitDir);
            String root = gitDir == null ? null : gitDir.getParent();
            SwingUtilities.invokeLater(() -> {
                project = p;
                if (!Objects.equals(b, branch) || isVisible() != (b != null)) {
                    branch = b;
                    setText(b);
                    setToolTipText(b == null ? null : Bundle.HINT_BranchWidget(root));
                    setVisible(b != null);
                    revalidate();
                }
            });
        });
    }

    static File findGitDir(File dir) {
        for (File d = dir; d != null; d = d.getParentFile()) {
            File git = new File(d, ".git"); // NOI18N
            if (git.isDirectory()) {
                return git;
            }
            if (git.isFile()) {
                // worktrees and submodules: ".git" is a file with "gitdir: <path>"
                String content = read(git);
                if (content != null && content.startsWith("gitdir:")) { // NOI18N
                    File target = new File(content.substring(7).trim());
                    return target.isAbsolute() ? target : new File(d, target.getPath());
                }
            }
        }
        return null;
    }

    static String readBranch(File gitDir) {
        String head = read(new File(gitDir, "HEAD")); // NOI18N
        if (head == null || head.isEmpty()) {
            return null;
        }
        if (head.startsWith("ref: refs/heads/")) { // NOI18N
            return head.substring("ref: refs/heads/".length()); // NOI18N
        }
        if (head.startsWith("ref: ")) { // NOI18N
            return head.substring(head.lastIndexOf('/') + 1);
        }
        // detached HEAD
        return head.length() > 8 ? head.substring(0, 8) : head;
    }

    private static String read(File f) {
        try {
            return Files.readString(f.toPath(), StandardCharsets.UTF_8).trim();
        } catch (IOException ex) {
            return null;
        }
    }

    @Override
    protected void fillPopup(JPopupMenu popup) {
        Lookup context = projectContext(project);
        if (context == null) {
            return;
        }
        add(popup, GIT + "commit-CommitAction.instance", context, "commit"); // NOI18N
        add(popup, GIT + "fetch-PullFromUpstreamAction.instance", context, "pull"); // NOI18N
        add(popup, GIT + "push-PushToUpstreamAction.instance", context, "push"); // NOI18N
        add(popup, GIT + "fetch-FetchFromUpstreamAction.instance", context, "fetch"); // NOI18N
        popup.addSeparator();
        add(popup, GIT + "status-StatusAction.instance", context, "changes"); // NOI18N
        add(popup, GIT + "history-SearchHistoryAction.instance", context, "history"); // NOI18N
        popup.addSeparator();
        add(popup, GIT + "checkout-SwitchBranchAction.instance", context, null); // NOI18N
        add(popup, GIT + "branch-CreateBranchAction.instance", context, null); // NOI18N
    }

    private static void add(JPopupMenu popup, String path, Lookup context, String icon) {
        JMenuItem item = WidgetButton.actionItem(path, context, icon == null ? null : ICONS + icon + ".svg"); // NOI18N
        if (item != null) {
            popup.add(item);
        }
    }

    /** The project's logical root node, which is what the VCS actions read their files from. */
    private static Lookup projectContext(Project p) {
        if (p == null) {
            return null;
        }
        LogicalViewProvider lvp = p.getLookup().lookup(LogicalViewProvider.class);
        Node node = lvp != null ? lvp.createLogicalView() : null;
        return node == null ? Lookups.singleton(p) : Lookups.singleton(node);
    }
}
