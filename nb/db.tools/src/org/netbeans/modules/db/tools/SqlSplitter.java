/*
 * Copyright 2026 Luciano Lopes.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 */
package org.netbeans.modules.db.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Splits a script on ';' outside strings, quoted identifiers, comments and $tag$ blocks. */
final class SqlSplitter {

    /** A statement as [start, end) offsets in the script, comments and blanks around it excluded. */
    static final class Statement {
        final int start;
        final int end;
        final String text;

        Statement(int start, int end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }

        String firstKeyword() {
            int i = 0;
            while (i < text.length() && (Character.isLetter(text.charAt(i)))) {
                i++;
            }
            return text.substring(0, i).toLowerCase(Locale.ROOT);
        }
    }

    private SqlSplitter() {
    }

    static List<Statement> split(String sql) {
        List<Statement> result = new ArrayList<>();
        int n = sql.length();
        int stmtStart = 0;
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : 0;
            if (c == '-' && next == '-') {
                i = lineEnd(sql, i);
            } else if (c == '/' && next == '*') {
                int close = sql.indexOf("*/", i + 2); // NOI18N
                i = close < 0 ? n : close + 2;
            } else if (c == '\'' || c == '"' || c == '`') {
                i = quoteEnd(sql, i, c);
            } else if (c == '$') {
                int tagEnd = dollarTagEnd(sql, i);
                if (tagEnd > 0) {
                    String tag = sql.substring(i, tagEnd + 1);
                    int close = sql.indexOf(tag, tagEnd + 1);
                    i = close < 0 ? n : close + tag.length();
                } else {
                    i++;
                }
            } else if (c == ';') {
                add(result, sql, stmtStart, i);
                i++;
                stmtStart = i;
            } else {
                i++;
            }
        }
        add(result, sql, stmtStart, n);
        return result;
    }

    private static int lineEnd(String sql, int i) {
        int nl = sql.indexOf('\n', i);
        return nl < 0 ? sql.length() : nl + 1;
    }

    private static int quoteEnd(String sql, int i, char quote) {
        int j = i + 1;
        while (j < sql.length()) {
            if (sql.charAt(j) == quote) {
                if (j + 1 < sql.length() && sql.charAt(j + 1) == quote) {
                    j += 2; // doubled quote is an escaped quote
                    continue;
                }
                return j + 1;
            }
            j++;
        }
        return sql.length();
    }

    /** Offset of the closing '$' of a $tag$ opener at i, or -1. */
    private static int dollarTagEnd(String sql, int i) {
        int j = i + 1;
        while (j < sql.length() && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) {
            j++;
        }
        return j < sql.length() && sql.charAt(j) == '$' && (j == i + 1 || !Character.isDigit(sql.charAt(i + 1))) ? j : -1;
    }

    private static void add(List<Statement> result, String sql, int from, int to) {
        int s = from;
        // skip leading blanks and comments so offsets point at the first keyword
        while (s < to) {
            char c = sql.charAt(s);
            if (Character.isWhitespace(c)) {
                s++;
            } else if (c == '-' && s + 1 < to && sql.charAt(s + 1) == '-') {
                s = Math.min(to, lineEnd(sql, s));
            } else if (c == '/' && s + 1 < to && sql.charAt(s + 1) == '*') {
                int close = sql.indexOf("*/", s + 2); // NOI18N
                s = close < 0 || close + 2 > to ? to : close + 2;
            } else {
                break;
            }
        }
        int e = to;
        while (e > s && Character.isWhitespace(sql.charAt(e - 1))) {
            e--;
        }
        if (e > s) {
            result.add(new Statement(s, e, sql.substring(s, e)));
        }
    }
}
