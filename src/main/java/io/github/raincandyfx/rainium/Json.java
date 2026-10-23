package io.github.raincandyfx.rainium;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small, dependency-free JSON reader. Produces Map (LinkedHashMap), List, Double,
 * String, Boolean and null. Large numeric arrays are common in mesh files, so numbers
 * are parsed without creating intermediate strings where possible.
 */
final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i < p.s.length()) throw p.err("unexpected trailing content");
        return v;
    }

    private RuntimeException err(String msg) {
        int line = 1, col = 1;
        for (int k = 0; k < Math.min(i, s.length()); k++) {
            if (s.charAt(k) == '\n') { line++; col = 1; } else col++;
        }
        return new IllegalArgumentException("JSON error at line " + line + ", column " + col + ": " + msg);
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') { i++; continue; }
            // tolerate // and /* */ comments in hand-written config files
            if (c == '/' && i + 1 < s.length()) {
                char d = s.charAt(i + 1);
                if (d == '/') { while (i < s.length() && s.charAt(i) != '\n') i++; continue; }
                if (d == '*') { int e = s.indexOf("*/", i + 2); i = e < 0 ? s.length() : e + 2; continue; }
            }
            break;
        }
    }

    private Object value() {
        if (i >= s.length()) throw err("unexpected end of input");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': lit("true"); return Boolean.TRUE;
            case 'f': lit("false"); return Boolean.FALSE;
            case 'n': lit("null"); return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw err("unexpected character '" + c + "'");
        }
    }

    private void lit(String w) {
        if (!s.startsWith(w, i)) throw err("expected " + w);
        i += w.length();
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; ws();
        if (peek() == '}') { i++; return m; }
        while (true) {
            ws();
            if (peek() != '"') throw err("expected property name");
            String k = string();
            ws();
            if (peek() != ':') throw err("expected ':'");
            i++; ws();
            m.put(k, value());
            ws();
            char c = peek();
            if (c == ',') { i++; ws(); if (peek() == '}') { i++; return m; } continue; } // trailing comma ok
            if (c == '}') { i++; return m; }
            throw err("expected ',' or '}'");
        }
    }

    private List<Object> array() {
        List<Object> a = new ArrayList<>();
        i++; ws();
        if (peek() == ']') { i++; return a; }
        while (true) {
            ws();
            a.add(value());
            ws();
            char c = peek();
            if (c == ',') { i++; ws(); if (peek() == ']') { i++; return a; } continue; }
            if (c == ']') { i++; return a; }
            throw err("expected ',' or ']'");
        }
    }

    private char peek() { return i < s.length() ? s.charAt(i) : '\0'; }

    private String string() {
        i++;
        StringBuilder b = null;
        int start = i;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"') {
                String r = b == null ? s.substring(start, i) : b.append(s, start, i).toString();
                i++;
                return r;
            }
            if (c == '\\') {
                if (b == null) b = new StringBuilder();
                b.append(s, start, i);
                i++;
                char e = peek();
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; break;
                    default: b.append(e);
                }
                i++;
                start = i;
                continue;
            }
            i++;
        }
        throw err("unterminated string");
    }

    private Double number() {
        int start = i;
        if (peek() == '-') i++;
        while (i < s.length()) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') i++;
            else break;
        }
        try {
            return Double.parseDouble(s.substring(start, i));
        } catch (NumberFormatException e) {
            throw err("bad number");
        }
    }
}
