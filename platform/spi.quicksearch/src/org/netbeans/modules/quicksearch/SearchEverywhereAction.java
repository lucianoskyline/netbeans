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

import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import javax.swing.SwingUtilities;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionRegistration;
import org.openide.util.NbBundle;
import org.openide.windows.OnShowing;

@ActionID(category = "Edit", id = "org.netbeans.modules.quicksearch.SearchEverywhereAction")
@ActionRegistration(displayName = "#CTL_SearchEverywhere", iconInMenu = false)
@ActionReference(path = "Menu/GoTo", position = 5)
@NbBundle.Messages("CTL_SearchEverywhere=Search Everywhere (Shift Shift)")
public final class SearchEverywhereAction implements ActionListener {

    @Override
    public void actionPerformed(ActionEvent e) {
        SearchEverywhere.open();
    }

    /** Opens Search Everywhere when Shift is pressed twice, alone, in quick succession. */
    @OnShowing
    public static final class DoubleShift implements Runnable, KeyEventDispatcher {

        private static final long MAX_INTERVAL_MS = 400;

        private boolean shiftDown;
        private boolean clean;
        private long lastRelease;

        @Override
        public void run() {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(this);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.VK_SHIFT) {
                if (e.getID() == KeyEvent.KEY_PRESSED && !shiftDown) {
                    // auto-repeat sends more KEY_PRESSED while held; only the first counts
                    shiftDown = true;
                    clean = e.getModifiersEx() == KeyEvent.SHIFT_DOWN_MASK;
                } else if (e.getID() == KeyEvent.KEY_RELEASED) {
                    shiftDown = false;
                    if (clean) {
                        if (e.getWhen() - lastRelease <= MAX_INTERVAL_MS) {
                            lastRelease = 0;
                            SwingUtilities.invokeLater(SearchEverywhere::open);
                        } else {
                            lastRelease = e.getWhen();
                        }
                    }
                }
            } else if (e.getID() == KeyEvent.KEY_PRESSED) {
                clean = false;
                lastRelease = 0;
            }
            return false;
        }
    }
}
