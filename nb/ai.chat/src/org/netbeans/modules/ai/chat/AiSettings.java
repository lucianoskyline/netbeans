/*
 * Copyright 2026 Luciano Lopes.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 */
package org.netbeans.modules.ai.chat;

import java.util.prefs.Preferences;
import org.netbeans.api.keyring.Keyring;
import org.openide.util.NbPreferences;

/**
 * Models and the last used provider live in NbPreferences; API keys live in the
 * OS keyring, with the provider's usual environment variable as fallback.
 */
final class AiSettings {

    private AiSettings() {
    }

    private static Preferences prefs() {
        return NbPreferences.forModule(AiSettings.class);
    }

    static String model(AiProvider p) {
        return prefs().get("model." + p.name(), p.defaultModel); // NOI18N
    }

    static void setModel(AiProvider p, String model) {
        if (model == null || model.isBlank() || model.trim().equals(p.defaultModel)) {
            prefs().remove("model." + p.name()); // NOI18N
        } else {
            prefs().put("model." + p.name(), model.trim()); // NOI18N
        }
    }

    static AiProvider lastProvider() {
        try {
            return AiProvider.valueOf(prefs().get("provider", AiProvider.CLAUDE.name())); // NOI18N
        } catch (IllegalArgumentException ex) {
            return AiProvider.CLAUDE;
        }
    }

    static void setLastProvider(AiProvider p) {
        prefs().put("provider", p.name()); // NOI18N
    }

    static boolean inlineEnabled() {
        return prefs().getBoolean("inline.enabled", false); // NOI18N
    }

    static void setInlineEnabled(boolean enabled) {
        prefs().putBoolean("inline.enabled", enabled); // NOI18N
    }

    static AiProvider inlineProvider() {
        try {
            return AiProvider.valueOf(prefs().get("inline.provider", AiProvider.CLAUDE.name())); // NOI18N
        } catch (IllegalArgumentException ex) {
            return AiProvider.CLAUDE;
        }
    }

    static void setInlineProvider(AiProvider p) {
        prefs().put("inline.provider", p.name()); // NOI18N
    }

    static String inlineModel(AiProvider p) {
        return prefs().get("inline.model." + p.name(), p.defaultFastModel); // NOI18N
    }

    static void setInlineModel(AiProvider p, String model) {
        if (model == null || model.isBlank() || model.trim().equals(p.defaultFastModel)) {
            prefs().remove("inline.model." + p.name()); // NOI18N
        } else {
            prefs().put("inline.model." + p.name(), model.trim()); // NOI18N
        }
    }

    private static String keyringKey(AiProvider p) {
        return "org.netbeans.modules.ai.chat." + p.name(); // NOI18N
    }

    /** May block on the keyring; don't call from the EDT. */
    static String storedApiKey(AiProvider p) {
        char[] key = Keyring.read(keyringKey(p));
        return key == null ? null : new String(key);
    }

    /** May block on the keyring; don't call from the EDT. */
    static String apiKey(AiProvider p) {
        String key = storedApiKey(p);
        if (key == null || key.isBlank()) {
            key = System.getenv(p.envVariable);
        }
        return key == null || key.isBlank() ? null : key.trim();
    }

    static void setApiKey(AiProvider p, String key) {
        if (key == null || key.isBlank()) {
            Keyring.delete(keyringKey(p));
        } else {
            Keyring.save(keyringKey(p), key.trim().toCharArray(), p.displayName + " API key (NetBeans AI Chat)");
        }
    }
}
