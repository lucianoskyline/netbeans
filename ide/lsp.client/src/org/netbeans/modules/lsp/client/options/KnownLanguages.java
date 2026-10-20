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
package org.netbeans.modules.lsp.client.options;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;
import javax.swing.ImageIcon;
import org.netbeans.api.editor.mimelookup.MimeRegistration;
import org.netbeans.modules.lsp.client.options.LanguageStorage.LanguageDescription;
import org.netbeans.modules.lsp.client.spi.LanguageIdResolver;
import org.netbeans.modules.lsp.client.spi.LanguageServerProvider;
import org.openide.awt.NotificationDisplayer;
import org.openide.modules.InstalledFileLocator;
import org.openide.modules.OnStart;
import org.openide.util.Lookup;
import org.openide.util.NbBundle;
import org.openide.util.NbPreferences;
import org.openide.util.RequestProcessor;
import org.openide.util.Utilities;
import org.openide.util.lookup.Lookups;

/**
 * Turns on language support automatically for language servers that are
 * already installed (Python: pyright/basedpyright/pylsp, Rust: rust-analyzer,
 * Go: gopls), using the bundled TextMate grammars. Users can still change or
 * remove the entries in Options &gt; Editor &gt; Language Servers; removed
 * entries are not added again.
 */
@NbBundle.Messages({
    "# {0} - language",
    "# {1} - server",
    "MSG_LanguageEnabled={0} support enabled",
    "# {0} - server path",
    "DESC_LanguageEnabled=Using the installed language server {0}. Configure in Options > Editor > Language Servers."
})
public final class KnownLanguages {

    private static final Logger LOG = Logger.getLogger(KnownLanguages.class.getName());
    private static final String CODE_NAME = "org.netbeans.modules.lsp.client"; // NOI18N
    private static final String DIR = "lsp-languages/"; // NOI18N
    /** Ids added automatically once; if the user removes one, it stays removed. */
    private static final String HANDLED = "autodetected.languages"; // NOI18N

    private static final class Known {
        final String id;
        final String name;
        final String extensions;
        final String languageId;
        final String[][] servers;

        Known(String id, String name, String extensions, String languageId, String[]... servers) {
            this.id = id;
            this.name = name;
            this.extensions = extensions;
            this.languageId = languageId;
            this.servers = servers;
        }
    }

    private static final List<Known> LANGUAGES = List.of(
            new Known("python", "Python", "py pyi", "python", // NOI18N
                    new String[] {"pyright-langserver", "--stdio"}, // NOI18N
                    new String[] {"basedpyright-langserver", "--stdio"}, // NOI18N
                    new String[] {"pylsp"}), // NOI18N
            new Known("rust", "Rust", "rs", "rust", // NOI18N
                    new String[] {"rust-analyzer"})); // NOI18N

    private KnownLanguages() {
    }

    @OnStart
    public static final class Detect implements Runnable {

        @Override
        public void run() {
            // after LanguageStorage.StartupHandler re-applied the stored descriptions
            RequestProcessor.getDefault().post(KnownLanguages::detect, 2000);
        }
    }

    private static Preferences prefs() {
        return NbPreferences.forModule(KnownLanguages.class);
    }

    static synchronized void detect() {
        List<LanguageDescription> languages = new ArrayList<>(LanguageStorage.load());
        Set<String> handled = new LinkedHashSet<>(Arrays.asList(prefs().get(HANDLED, "").split(","))); // NOI18N
        boolean changed = false;
        for (Known k : LANGUAGES) {
            if (handled.contains(k.id) || alreadyConfigured(languages, k)) {
                continue;
            }
            String[] command = findServer(k.servers);
            File grammar = InstalledFileLocator.getDefault().locate(DIR + k.id + ".tmLanguage.json", CODE_NAME, false); // NOI18N
            File icon = InstalledFileLocator.getDefault().locate(DIR + k.id + ".png", CODE_NAME, false); // NOI18N
            if (command == null || grammar == null) {
                continue;
            }
            LanguageDescription d = new LanguageDescription(k.id, k.extensions, grammar.getAbsolutePath(),
                    String.join(" ", command), k.name, icon != null ? icon.getAbsolutePath() : null, false, k.languageId); // NOI18N
            languages.add(d);
            handled.add(k.id);
            changed = true;
            NotificationDisplayer.getDefault().notify(Bundle.MSG_LanguageEnabled(k.name, command[0]),
                    icon != null ? new ImageIcon(icon.getAbsolutePath()) : null,
                    Bundle.DESC_LanguageEnabled(command[0]), null);
            LOG.log(Level.INFO, "Enabled {0} with {1}", new Object[] {k.name, String.join(" ", command)}); // NOI18N
        }
        if (changed) {
            handled.remove(""); // NOI18N
            prefs().put(HANDLED, String.join(",", handled)); // NOI18N
            LanguageStorage.store(languages);
        }
    }

    private static boolean alreadyConfigured(List<LanguageDescription> languages, Known k) {
        Set<String> exts = Set.of(k.extensions.split(" +")); // NOI18N
        for (LanguageDescription d : languages) {
            if (k.id.equals(d.id)) {
                return true;
            }
            if (d.extensions != null) {
                for (String e : d.extensions.split(" +")) { // NOI18N
                    if (exts.contains(e)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** First installed server, with the executable resolved to an absolute path. */
    static String[] findServer(String[][] candidates) {
        for (String[] command : candidates) {
            File exe = findExecutable(command[0]);
            if (exe != null) {
                String[] resolved = command.clone();
                resolved[0] = exe.getAbsolutePath();
                return resolved;
            }
        }
        return null;
    }

    /**
     * Looks on PATH and in the usual per-user tool folders, which a desktop
     * launcher's PATH often lacks (~/go/bin, ~/.cargo/bin, nvm, ...).
     */
    static File findExecutable(String name) {
        List<File> dirs = new ArrayList<>();
        String path = System.getenv("PATH"); // NOI18N
        if (path != null) {
            for (String p : path.split(File.pathSeparator)) {
                dirs.add(new File(p));
            }
        }
        String home = System.getProperty("user.home"); // NOI18N
        for (String env : new String[] {"GOBIN", "CARGO_HOME"}) { // NOI18N
            String v = System.getenv(env);
            if (v != null) {
                dirs.add(env.equals("CARGO_HOME") ? new File(v, "bin") : new File(v)); // NOI18N
            }
        }
        String gopath = System.getenv("GOPATH"); // NOI18N
        if (gopath != null) {
            dirs.add(new File(gopath, "bin")); // NOI18N
        }
        for (String rel : new String[] {"go/bin", ".cargo/bin", ".local/bin", ".volta/bin", ".local/share/pnpm"}) { // NOI18N
            dirs.add(new File(home, rel));
        }
        File nvm = new File(home, ".nvm/versions/node"); // NOI18N
        File[] nodes = nvm.listFiles(File::isDirectory);
        if (nodes != null) {
            Arrays.sort(nodes, Comparator.comparing(File::getName).reversed());
            for (File n : nodes) {
                dirs.add(new File(n, "bin")); // NOI18N
            }
        }
        for (String abs : new String[] {"/usr/local/bin", "/usr/local/go/bin", "/opt/homebrew/bin", "/usr/bin"}) { // NOI18N
            dirs.add(new File(abs));
        }
        String[] suffixes = Utilities.isWindows() ? new String[] {".exe", ".cmd", ".bat", ""} : new String[] {""}; // NOI18N
        for (File dir : dirs) {
            for (String suffix : suffixes) {
                File f = new File(dir, name + suffix);
                if (f.isFile() && f.canExecute()) {
                    return f;
                }
            }
        }
        return null;
    }

    /**
     * gopls for the Go editor that the IDE already has (syntax highlighting
     * comes from that module; gopls adds completion, navigation and errors).
     */
    @MimeRegistration(mimeType = "text/x-go", service = LanguageServerProvider.class)
    public static final class Gopls implements LanguageServerProvider {

        @Override
        public LanguageServerDescription startServer(Lookup lookup) {
            File gopls = findExecutable("gopls"); // NOI18N
            if (gopls == null) {
                return null;
            }
            try {
                Process p = new ProcessBuilder(gopls.getAbsolutePath(), "serve") // NOI18N
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                return LanguageServerDescription.create(p.getInputStream(), p.getOutputStream(), p,
                        Lookups.fixed((LanguageIdResolver) fo -> "go")); // NOI18N
            } catch (IOException ex) {
                LOG.log(Level.FINE, null, ex);
                return null;
            }
        }
    }
}
