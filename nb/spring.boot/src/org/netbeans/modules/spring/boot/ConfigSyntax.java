/*
 * Copyright 2026 Luciano Lopes.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 */
package org.netbeans.modules.spring.boot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough .properties and YAML understanding for Spring Boot configuration:
 * the flattened keys with their offsets, and what the caret is on.
 */
final class ConfigSyntax {

    /** One "key = value" setting; for YAML the key is the full dotted path. */
    static final class Entry {
        final String key;
        final int keyStart;
        final int keyEnd;
        final String value;
        final int valueStart;
        /** True for YAML nodes that only hold children (server:), not values. */
        final boolean parent;

        Entry(String key, int keyStart, int keyEnd, String value, int valueStart, boolean parent) {
            this.key = key;
            this.keyStart = keyStart;
            this.keyEnd = keyEnd;
            this.value = value;
            this.valueStart = valueStart;
            this.parent = parent;
        }
    }

    /** Where the caret is: completing a key (relative to {@code parent} in YAML) or the value of {@code key}. */
    static final class Context {
        final boolean value;
        final String parent;
        final String prefix;
        final int prefixStart;
        final String key;

        Context(boolean value, String parent, String prefix, int prefixStart, String key) {
            this.value = value;
            this.parent = parent;
            this.prefix = prefix;
            this.prefixStart = prefixStart;
            this.key = key;
        }
    }

    private ConfigSyntax() {
    }

    static boolean isYaml(String fileName) {
        return fileName.endsWith(".yml") || fileName.endsWith(".yaml"); // NOI18N
    }

    // ---- .properties ------------------------------------------------------------------------

    static List<Entry> parseProperties(String text) {
        List<Entry> out = new ArrayList<>();
        int pos = 0;
        while (pos < text.length()) {
            int eol = lineEnd(text, pos);
            String line = text.substring(pos, eol);
            int i = skipBlanks(line, 0);
            boolean continued = false;
            if (i < line.length() && line.charAt(i) != '#' && line.charAt(i) != '!') {
                int keyEnd = i;
                while (keyEnd < line.length() && !isSeparator(line.charAt(keyEnd))) {
                    keyEnd += line.charAt(keyEnd) == '\\' ? 2 : 1;
                }
                keyEnd = Math.min(keyEnd, line.length());
                int v = skipBlanks(line, keyEnd);
                if (v < line.length() && (line.charAt(v) == '=' || line.charAt(v) == ':')) {
                    v = skipBlanks(line, v + 1);
                }
                out.add(new Entry(line.substring(i, keyEnd), pos + i, pos + keyEnd,
                        line.substring(Math.min(v, line.length())).trim(), pos + v, false));
                continued = line.endsWith("\\"); // NOI18N
            }
            pos = eol + 1;
            // skip continuation lines of a multi-line value
            while (continued && pos < text.length()) {
                eol = lineEnd(text, pos);
                continued = text.substring(pos, eol).endsWith("\\"); // NOI18N
                pos = eol + 1;
            }
        }
        return out;
    }

    static Context propertiesContext(String text, int caret) {
        int lineStart = text.lastIndexOf('\n', caret - 1) + 1;
        String before = text.substring(lineStart, caret);
        int i = skipBlanks(before, 0);
        if (i < before.length() && (before.charAt(i) == '#' || before.charAt(i) == '!')) {
            return null;
        }
        int sep = -1;
        for (int k = i; k < before.length(); k++) {
            if (isSeparator(before.charAt(k))) {
                sep = k;
                break;
            }
        }
        if (sep < 0) {
            return new Context(false, "", before.substring(i), lineStart + i, null); // NOI18N
        }
        String key = before.substring(i, sep);
        int v = skipBlanks(before, sep);
        if (v < before.length() && (before.charAt(v) == '=' || before.charAt(v) == ':')) {
            v = skipBlanks(before, v + 1);
        }
        return new Context(true, "", before.substring(v), lineStart + v, key); // NOI18N
    }

    private static boolean isSeparator(char c) {
        return c == '=' || c == ':' || c == ' ' || c == '\t';
    }

    // ---- YAML -------------------------------------------------------------------------------

    private static final class Node {
        final int indent;
        final String path;
        final Map<String, Integer> listIndex = new HashMap<>();

        Node(int indent, String path) {
            this.indent = indent;
            this.path = path;
        }
    }

    static List<Entry> parseYaml(String text) {
        List<Entry> out = new ArrayList<>();
        walkYaml(text, text.length(), out);
        return out;
    }

    /** The dotted path of the mapping that an indentation of {@code indent} at {@code offset} belongs to. */
    static String yamlParentAt(String text, int offset, int indent) {
        Deque<Node> stack = walkYaml(text, text.lastIndexOf('\n', offset - 1) + 1, null);
        while (!stack.isEmpty() && stack.peek().indent >= indent) {
            stack.pop();
        }
        return stack.isEmpty() ? "" : stack.peek().path; // NOI18N
    }

    private static Deque<Node> walkYaml(String text, int limit, List<Entry> out) {
        Deque<Node> stack = new ArrayDeque<>();
        int pos = 0;
        int blockIndent = -1; // inside a | or > block scalar
        while (pos < limit) {
            int eol = Math.min(lineEnd(text, pos), text.length());
            String line = text.substring(pos, eol);
            int indent = skipSpaces(line, 0);
            String content = line.substring(indent);
            if (blockIndent >= 0) {
                if (content.isEmpty() || indent > blockIndent) {
                    pos = eol + 1;
                    continue;
                }
                blockIndent = -1;
            }
            if (content.isEmpty() || content.startsWith("#")) { // NOI18N
                pos = eol + 1;
                continue;
            }
            if (content.startsWith("---") || content.startsWith("...")) { // NOI18N  new document
                stack.clear();
                pos = eol + 1;
                continue;
            }
            while (!stack.isEmpty() && stack.peek().indent >= indent) {
                stack.pop();
            }
            String parentPath = stack.isEmpty() ? "" : stack.peek().path; // NOI18N
            int keyIndent = indent;
            if (content.startsWith("- ") || content.equals("-")) { // NOI18N  list element
                Node parent = stack.peek();
                int index = 0;
                if (parent != null) {
                    index = parent.listIndex.merge(parentPath, 1, Integer::sum) - 1;
                }
                parentPath = parentPath + "[" + index + "]"; // NOI18N
                int after = skipSpaces(content, 1);
                keyIndent = indent + after;
                content = content.substring(after);
                if (!content.contains(":")) { // NOI18N  scalar list item
                    if (out != null) {
                        out.add(new Entry(parentPath, pos + keyIndent, pos + keyIndent, content, pos + keyIndent, false));
                    }
                    pos = eol + 1;
                    continue;
                }
                stack.push(new Node(indent, parentPath));
            }
            int colon = keyColon(content);
            if (colon < 0) {
                pos = eol + 1;
                continue;
            }
            String rawKey = content.substring(0, colon).trim();
            String key = unquote(rawKey);
            String path = parentPath.isEmpty() ? key : parentPath + "." + key; // NOI18N
            String value = stripComment(content.substring(colon + 1)).trim();
            int keyStart = pos + keyIndent;
            int valueStart = pos + keyIndent + colon + 1 + skipSpaces(content.substring(colon + 1), 0);
            boolean parent = value.isEmpty();
            if (value.equals("|") || value.equals(">") || value.matches("[|>][+-]?\\d*")) { // NOI18N
                blockIndent = indent;
                parent = false;
            }
            if (out != null) {
                out.add(new Entry(path, keyStart, keyStart + rawKey.length(), value, valueStart, parent));
            }
            if (parent) {
                stack.push(new Node(keyIndent, path));
            }
            pos = eol + 1;
        }
        return stack;
    }

    static Context yamlContext(String text, int caret) {
        int lineStart = text.lastIndexOf('\n', caret - 1) + 1;
        String before = text.substring(lineStart, caret);
        int indent = skipSpaces(before, 0);
        String content = before.substring(indent);
        if (content.startsWith("#")) { // NOI18N
            return null;
        }
        int offset = indent;
        if (content.startsWith("- ")) { // NOI18N
            int after = skipSpaces(content, 1);
            offset += after;
            content = content.substring(after);
        }
        String parent = yamlParentAt(text, lineStart, indent);
        int colon = keyColon(content);
        if (colon < 0) {
            return new Context(false, parent, content, lineStart + offset, null);
        }
        String key = unquote(content.substring(0, colon).trim());
        String full = parent.isEmpty() ? key : parent + "." + key; // NOI18N
        int v = skipSpaces(content, colon + 1);
        return new Context(true, parent, content.substring(Math.min(v, content.length())),
                lineStart + offset + Math.min(v, content.length()), full);
    }

    /** The ':' that ends a key: followed by a blank or end of line, outside quotes. */
    private static int keyColon(String s) {
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == ':' && (i + 1 == s.length() || s.charAt(i + 1) == ' ' || s.charAt(i + 1) == '\t')) {
                return i;
            } else if (c == '#' && i > 0 && s.charAt(i - 1) == ' ') {
                return -1;
            }
        }
        return -1;
    }

    private static String stripComment(String value) {
        char quote = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '#' && (i == 0 || value.charAt(i - 1) == ' ')) {
                return value.substring(0, i);
            }
        }
        return value;
    }

    static String unquote(String s) {
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) { // NOI18N
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static int lineEnd(String text, int pos) {
        int nl = text.indexOf('\n', pos);
        return nl < 0 ? text.length() : nl;
    }

    private static int skipBlanks(String s, int i) {
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t' || s.charAt(i) == '\f')) {
            i++;
        }
        return i;
    }

    private static int skipSpaces(String s, int i) {
        while (i < s.length() && s.charAt(i) == ' ') {
            i++;
        }
        return i;
    }
}
