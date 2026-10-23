package io.github.raincandyfx.rainium;

import java.util.List;
import java.util.Map;

/** Pretty JSON writer for config files: objects indented, short number arrays inline. */
final class JsonWriter {
    private JsonWriter() {}

    static String write(Object o) {
        StringBuilder b = new StringBuilder();
        write(b, o, 0);
        return b.append('\n').toString();
    }

    private static void write(StringBuilder b, Object o, int ind) {
        if (o == null) b.append("null");
        else if (o instanceof String s) str(b, s);
        else if (o instanceof Boolean x) b.append(x);
        else if (o instanceof Number n) num(b, n.doubleValue());
        else if (o instanceof Map<?, ?> m) {
            if (m.isEmpty()) { b.append("{}"); return; }
            b.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                pad(b, ind + 1);
                str(b, String.valueOf(e.getKey()));
                b.append(": ");
                write(b, e.getValue(), ind + 1);
                if (++i < m.size()) b.append(',');
                b.append('\n');
            }
            pad(b, ind);
            b.append('}');
        } else if (o instanceof List<?> l) {
            boolean simple = l.stream().allMatch(x -> x instanceof Number || x instanceof String || x instanceof Boolean);
            if (simple) {
                b.append('[');
                for (int i = 0; i < l.size(); i++) { if (i > 0) b.append(", "); write(b, l.get(i), ind); }
                b.append(']');
                return;
            }
            b.append("[\n");
            for (int i = 0; i < l.size(); i++) {
                pad(b, ind + 1);
                write(b, l.get(i), ind + 1);
                if (i + 1 < l.size()) b.append(',');
                b.append('\n');
            }
            pad(b, ind);
            b.append(']');
        } else str(b, String.valueOf(o));
    }

    private static void pad(StringBuilder b, int n) { for (int i = 0; i < n; i++) b.append("  "); }

    static void num(StringBuilder b, double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) { b.append('0'); return; }
        double r = Math.round(v * 10000.0) / 10000.0;
        if (r == Math.rint(r) && Math.abs(r) < 1e12) { b.append((long) r); return; }
        b.append(new java.math.BigDecimal(r).setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());
    }

    private static void str(StringBuilder b, String s) {
        b.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\t' -> b.append("\\t");
                case '\r' -> b.append("\\r");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        b.append('"');
    }
}
