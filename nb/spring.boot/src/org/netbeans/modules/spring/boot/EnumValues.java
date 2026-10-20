/*
 * Copyright 2026 Luciano Lopes.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 */
package org.netbeans.modules.spring.boot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import org.netbeans.api.java.project.JavaProjectConstants;
import org.netbeans.api.java.source.ClasspathInfo;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.api.project.FileOwnerQuery;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectUtils;
import org.netbeans.api.project.SourceGroup;
import org.openide.filesystems.FileObject;

/**
 * Constants of enum-typed properties (Spring metadata does not list them),
 * read from the enum class on the project's classpath.
 */
final class EnumValues {

    private static final Map<String, List<String>> CACHE = new ConcurrentHashMap<>();

    private EnumValues() {
    }

    /** Lower-case constant names, or an empty list if the type is not an enum on the classpath. */
    static List<String> of(FileObject configFile, Metadata.Property p) {
        if (p.type == null || p.type.startsWith("java.") || !p.type.matches("[\\w.$]+")) { // NOI18N
            return List.of();
        }
        Project project = FileOwnerQuery.getOwner(configFile);
        if (project == null) {
            return List.of();
        }
        String key = project.getProjectDirectory().getPath() + "|" + p.type; // NOI18N
        return CACHE.computeIfAbsent(key, k -> load(project, p.type.replace('$', '.')));
    }

    private static List<String> load(Project project, String type) {
        SourceGroup[] groups = ProjectUtils.getSources(project).getSourceGroups(JavaProjectConstants.SOURCES_TYPE_JAVA);
        if (groups.length == 0) {
            return List.of();
        }
        List<String> constants = new ArrayList<>();
        try {
            JavaSource js = JavaSource.create(ClasspathInfo.create(groups[0].getRootFolder()));
            if (js == null) {
                return List.of();
            }
            js.runUserActionTask(cc -> {
                TypeElement te = cc.getElements().getTypeElement(type);
                if (te != null && te.getKind() == ElementKind.ENUM) {
                    for (Element e : te.getEnclosedElements()) {
                        if (e.getKind() == ElementKind.ENUM_CONSTANT) {
                            constants.add(e.getSimpleName().toString().toLowerCase(Locale.ROOT));
                        }
                    }
                }
            }, true);
        } catch (IOException ex) {
            return List.of();
        }
        return List.copyOf(constants);
    }

    /** Spring binds enums leniently: case and '-' versus '_' do not matter. */
    static boolean matches(List<String> constants, String value) {
        String v = value.toLowerCase(Locale.ROOT).replace('-', '_');
        return constants.contains(v);
    }
}
