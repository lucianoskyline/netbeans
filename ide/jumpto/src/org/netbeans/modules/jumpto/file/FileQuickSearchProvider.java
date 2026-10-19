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
package org.netbeans.modules.jumpto.file;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.event.ListDataListener;
import org.netbeans.modules.jumpto.common.Models;
import org.netbeans.modules.jumpto.common.Utils;
import org.netbeans.modules.jumpto.settings.GoToSettings;
import org.netbeans.modules.parsing.spi.indexing.support.QuerySupport;
import org.netbeans.spi.jumpto.file.FileDescriptor;
import org.netbeans.spi.quicksearch.SearchProvider;
import org.netbeans.spi.quicksearch.SearchRequest;
import org.netbeans.spi.quicksearch.SearchResponse;

/**
 * Quick Search category for files, backed by the same workers as the
 * Go to File dialog (file index plus file providers).
 */
public final class FileQuickSearchProvider implements SearchProvider {

    private final AtomicReference<Worker[]> running = new AtomicReference<>();

    @Override
    public void evaluate(SearchRequest request, SearchResponse response) {
        String text = request.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        QuerySupport.Kind kind = Utils.toQueryKind(Utils.getSearchType(text, false, false,
                FileSearchAction.CAMEL_CASE_SEPARATOR, FileSearchAction.CAMEL_CASE_PART_CASE_INSENSITIVE));
        if (kind == QuerySupport.Kind.REGEXP || kind == QuerySupport.Kind.CASE_INSENSITIVE_REGEXP) {
            text = Utils.removeNonNeededWildCards(text);
        }

        Collected files = new Collected();
        Worker.Request wr = Worker.newRequest(text, kind, null, false, -1);
        Worker.Collector collector = Worker.newCollector(files, () -> { }, () -> { }, System.currentTimeMillis());
        Worker.Type[] types = Worker.Type.values();
        Worker[] workers = new Worker[types.length];
        // all workers must exist before any runs, the collector counts them
        for (int i = 0; i < types.length; i++) {
            workers[i] = Worker.newWorker(wr, collector, types[i]);
        }
        Worker[] previous = running.getAndSet(workers);
        if (previous != null) {
            for (Worker w : previous) {
                w.cancel();
            }
        }
        try {
            for (Worker w : workers) {
                if (response.isObsolete()) {
                    return;
                }
                w.run();
            }
        } finally {
            running.compareAndSet(workers, null);
        }

        List<FileDescriptor> sorted = files.snapshot();
        sorted.sort(FileComarator.create(GoToSettings.getDefault().getSortingType(), text, false, false,
                GoToSettings.getDefault().isSortingPreferOpenProjects()));
        for (FileDescriptor fd : sorted) {
            String name = fd.getFileName();
            String project = fd.getProjectName();
            String html = project != null && !project.isEmpty()
                    ? name + " <font color=\"#808080\">[" + project + "]</font>" // NOI18N
                    : name;
            if (!response.addResult(fd::open, html, fd.getFileDisplayPath(), null)) {
                break;
            }
        }
    }

    /** Plain thread-safe sink; the quick search UI does its own presentation. */
    private static final class Collected implements Models.MutableListModel<FileDescriptor> {

        private final List<FileDescriptor> items = new ArrayList<>();

        synchronized List<FileDescriptor> snapshot() {
            return new ArrayList<>(items);
        }

        @Override
        public synchronized void add(Collection<? extends FileDescriptor> values) {
            items.addAll(values);
        }

        @Override
        public synchronized void remove(Collection<? extends FileDescriptor> values) {
            items.removeAll(values);
        }

        @Override
        public synchronized void clear() {
            items.clear();
        }

        @Override
        public synchronized int getSize() {
            return items.size();
        }

        @Override
        public synchronized FileDescriptor getElementAt(int index) {
            return items.get(index);
        }

        @Override
        public void addListDataListener(ListDataListener l) {
        }

        @Override
        public void removeListDataListener(ListDataListener l) {
        }
    }
}
