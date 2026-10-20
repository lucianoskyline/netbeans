/*
 * Copyright 2026 Luciano Lopes.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 */
package org.netbeans.modules.spring.boot;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.netbeans.api.java.classpath.ClassPath;
import org.netbeans.api.java.project.JavaProjectConstants;
import org.netbeans.api.project.FileOwnerQuery;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectUtils;
import org.netbeans.api.project.SourceGroup;
import org.openide.filesystems.FileObject;

/**
 * Spring Boot configuration metadata (META-INF/spring-configuration-metadata.json
 * of every jar on the project's runtime classpath), indexed by the canonical
 * "relaxed binding" form of each property name.
 */
final class Metadata {

    private static final Logger LOG = Logger.getLogger(Metadata.class.getName());
    private static final String METADATA = "META-INF/spring-configuration-metadata.json"; // NOI18N
    private static final String ADDITIONAL = "META-INF/additional-spring-configuration-metadata.json"; // NOI18N
    private static final Pattern CONFIG_FILE = Pattern.compile("(application|bootstrap)(-[\\w.-]+)?\\.(properties|ya?ml)"); // NOI18N
    private static final Pattern INDEX = Pattern.compile("\\[[^\\]]*\\]"); // NOI18N

    static final class Value {
        final String value;
        final String description;

        Value(String value, String description) {
            this.value = value;
            this.description = description;
        }
    }

    static final class Property {
        final String name;
        final String type;
        final String description;
        final String defaultValue;
        final String sourceType;
        final boolean deprecated;
        final String replacement;
        final String deprecationReason;
        final boolean deprecationError;
        final List<Value> values = new ArrayList<>();

        Property(JSONObject p) {
            name = (String) p.get("name"); // NOI18N
            type = (String) p.get("type"); // NOI18N
            description = (String) p.get("description"); // NOI18N
            Object def = p.get("defaultValue"); // NOI18N
            defaultValue = def == null ? null : def instanceof JSONArray ? String.join(",", stringList((JSONArray) def)) : String.valueOf(def); // NOI18N
            sourceType = (String) p.get("sourceType"); // NOI18N
            JSONObject dep = (JSONObject) p.get("deprecation"); // NOI18N
            deprecated = Boolean.TRUE.equals(p.get("deprecated")) || dep != null; // NOI18N
            replacement = dep == null ? null : (String) dep.get("replacement"); // NOI18N
            deprecationReason = dep == null ? null : (String) dep.get("reason"); // NOI18N
            deprecationError = dep != null && "error".equals(dep.get("level")); // NOI18N
        }

        /** Map and collection properties accept arbitrary sub-keys or indexes. */
        boolean isContainer() {
            return type != null && (type.startsWith("java.util.Map") || type.startsWith("java.util.List") // NOI18N
                    || type.startsWith("java.util.Set") || type.startsWith("java.util.Collection") || type.endsWith("[]")); // NOI18N
        }

        boolean isBoolean() {
            return "java.lang.Boolean".equals(type) || "boolean".equals(type); // NOI18N
        }

        String simpleType() {
            if (type == null) {
                return ""; // NOI18N
            }
            return type.replaceAll("[\\w$]+\\.", "").replace('$', '.'); // NOI18N
        }
    }

    final Map<String, Property> byCanonicalName = new HashMap<>();
    final List<Property> properties = new ArrayList<>();
    private final List<String> containerPrefixes = new ArrayList<>();

    private static final Map<Project, Metadata> CACHE = new WeakHashMap<>();
    private static final Map<Project, List<String>> SIGNATURES = new WeakHashMap<>();

    private Metadata() {
    }

    static boolean isConfigFile(FileObject fo) {
        return fo != null && CONFIG_FILE.matcher(fo.getNameExt()).matches();
    }

    /** Metadata for the project owning {@code configFile}; empty when it has no Spring Boot jars (yet). */
    static Metadata forFile(FileObject configFile) {
        Project p = configFile == null ? null : FileOwnerQuery.getOwner(configFile);
        return p == null ? null : forProject(p);
    }

    static Metadata forProject(Project project) {
        List<FileObject> files = new ArrayList<>();
        for (SourceGroup g : ProjectUtils.getSources(project).getSourceGroups(JavaProjectConstants.SOURCES_TYPE_JAVA)) {
            ClassPath exec = ClassPath.getClassPath(g.getRootFolder(), ClassPath.EXECUTE);
            if (exec != null) {
                files.addAll(exec.findAllResources(METADATA));
                files.addAll(exec.findAllResources(ADDITIONAL));
            }
        }
        for (SourceGroup g : ProjectUtils.getSources(project).getSourceGroups(JavaProjectConstants.SOURCES_TYPE_RESOURCES)) {
            FileObject extra = g.getRootFolder().getFileObject(ADDITIONAL);
            if (extra != null) {
                files.add(extra);
            }
        }
        Set<FileObject> unique = new LinkedHashSet<>(files);
        List<String> signature = new ArrayList<>();
        for (FileObject fo : unique) {
            signature.add(fo.toURL() + ":" + fo.getSize() + ":" + fo.lastModified().getTime()); // NOI18N
        }
        synchronized (CACHE) {
            Metadata cached = CACHE.get(project);
            if (cached != null && signature.equals(SIGNATURES.get(project))) {
                return cached;
            }
        }
        Metadata m = new Metadata();
        for (FileObject fo : unique) {
            try (InputStream in = fo.getInputStream()) {
                m.read(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException ex) {
                LOG.log(Level.INFO, "Cannot read " + fo, ex); // NOI18N
            }
        }
        m.index();
        synchronized (CACHE) {
            CACHE.put(project, m);
            SIGNATURES.put(project, signature);
        }
        return m;
    }

    /** For tests: metadata from JSON streams. */
    static Metadata of(Collection<Reader> sources) throws IOException {
        Metadata m = new Metadata();
        for (Reader r : sources) {
            m.read(r);
        }
        m.index();
        return m;
    }

    private void read(Reader reader) throws IOException {
        Object parsed = JSONValue.parse(reader);
        if (!(parsed instanceof JSONObject)) {
            return;
        }
        JSONObject json = (JSONObject) parsed;
        Map<String, Property> local = new HashMap<>();
        JSONArray props = (JSONArray) json.get("properties"); // NOI18N
        if (props != null) {
            for (Object o : props) {
                Property p = new Property((JSONObject) o);
                if (p.name != null) {
                    local.put(p.name, p);
                    byCanonicalName.putIfAbsent(canonical(p.name), p);
                }
            }
        }
        JSONArray hints = (JSONArray) json.get("hints"); // NOI18N
        if (hints != null) {
            for (Object o : hints) {
                JSONObject h = (JSONObject) o;
                Property p = byCanonicalName.get(canonical(String.valueOf(h.get("name")))); // NOI18N
                JSONArray values = (JSONArray) h.get("values"); // NOI18N
                if (p != null && values != null && p.values.isEmpty()) {
                    for (Object v : values) {
                        JSONObject value = (JSONObject) v;
                        if (value.get("value") != null) { // NOI18N
                            p.values.add(new Value(String.valueOf(value.get("value")), (String) value.get("description"))); // NOI18N
                        }
                    }
                }
            }
        }
    }

    private void index() {
        Map<String, Property> sorted = new TreeMap<>();
        for (Property p : byCanonicalName.values()) {
            sorted.put(p.name, p);
            if (p.isContainer()) {
                containerPrefixes.add(canonical(p.name));
            }
        }
        properties.addAll(sorted.values());
    }

    boolean isEmpty() {
        return properties.isEmpty();
    }

    /**
     * Canonical relaxed-binding form: lower case, camelCase split with '-'.
     * {@code server.servlet.contextPath} and {@code server.servlet.context-path} are the same key.
     */
    static String canonical(String key) {
        StringBuilder sb = new StringBuilder(key.length() + 8);
        char prev = 0;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isUpperCase(c) && (Character.isLowerCase(prev) || Character.isDigit(prev))) {
                sb.append('-');
            }
            sb.append(Character.toLowerCase(c));
            prev = c;
        }
        return sb.toString();
    }

    Property find(String key) {
        String c = canonical(key);
        Property p = byCanonicalName.get(c);
        if (p == null) {
            p = byCanonicalName.get(INDEX.matcher(c).replaceAll("")); // NOI18N  list.items[0] -> list.items
        }
        return p;
    }

    /** Known exactly, or a sub-key of a map/collection property such as logging.level.org.example. */
    boolean isKnown(String key) {
        if (find(key) != null) {
            return true;
        }
        String c = canonical(key);
        for (String prefix : containerPrefixes) {
            if (c.startsWith(prefix + ".") || c.startsWith(prefix + "[")) { // NOI18N
                return true;
            }
        }
        return false;
    }

    /** A key that is a parent of known properties (a YAML intermediate node, for instance). */
    boolean isGroup(String key) {
        String c = canonical(key) + "."; // NOI18N
        for (String name : byCanonicalName.keySet()) {
            if (name.startsWith(c)) {
                return true;
            }
        }
        return false;
    }

    /** Properties whose name continues {@code prefix}; exact-prefix matches first, then segment matches. */
    List<Property> complete(String prefix) {
        String c = canonical(prefix);
        List<Property> starts = new ArrayList<>();
        List<Property> contains = new ArrayList<>();
        for (Property p : properties) {
            String n = canonical(p.name);
            if (n.startsWith(c)) {
                starts.add(p);
            } else if (!c.isEmpty() && !c.contains(".") && n.contains(c)) { // NOI18N
                contains.add(p);
            }
        }
        starts.addAll(contains);
        return starts;
    }

    private static List<String> stringList(JSONArray a) {
        List<String> r = new ArrayList<>();
        for (Object o : a) {
            r.add(String.valueOf(o));
        }
        return r;
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
