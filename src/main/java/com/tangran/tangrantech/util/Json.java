package com.tangran.tangrantech.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析/序列化（无外部依赖）。
 *
 * <p>原 JS 脚本大量使用 {@code JSON.parse/stringify} 读写存档（浮生当铺缓存.json、
 * BOSS战/_xu_res.json、终刻合成台配方/*.json 等）。原生化后由此类承担同样的读写语义：
 * 对象 → {@code LinkedHashMap}（保持键序），数组 → {@code ArrayList}，数值 → {@code Double}。</p>
 */
public final class Json {

    private Json() {}

    /** 解析任意 JSON 值。 */
    public static Object parse(String text) {
        if (text == null || text.isBlank()) return null;
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> parseArray(String text) {
        Object v = parse(text);
        return v instanceof List ? (List<Object>) v : new ArrayList<>();
    }

    /** 序列化为紧凑 JSON 文本。 */
    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o);
        return sb.toString();
    }

    /**
     * 序列化为 2 空格缩进的 JSON 文本。
     *
     * <p>原脚本写存档用的是 {@code JSON.stringify(data, null, 2)}，产出的文件管理员会直接打开看，
     * 因此这里保留同样的缩进风格（缩进不影响任何读取方的解析结果，纯粹是可读性）。</p>
     */
    public static String writePretty(Object o) {
        StringBuilder sb = new StringBuilder();
        writePretty(sb, o, 0);
        return sb.toString();
    }

    private static void writePretty(StringBuilder sb, Object o, int depth) {
        if (o instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                indent(sb, depth + 1);
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                writePretty(sb, e.getValue(), depth + 1);
                if (++i < map.size()) sb.append(',');
                sb.append('\n');
            }
            indent(sb, depth);
            sb.append('}');
            return;
        }
        if (o instanceof Iterable<?> it) {
            java.util.Iterator<?> iter = it.iterator();
            if (!iter.hasNext()) {
                sb.append("[]");
                return;
            }
            sb.append("[\n");
            while (iter.hasNext()) {
                indent(sb, depth + 1);
                writePretty(sb, iter.next(), depth + 1);
                if (iter.hasNext()) sb.append(',');
                sb.append('\n');
            }
            indent(sb, depth);
            sb.append(']');
            return;
        }
        write(sb, o);
    }

    private static void indent(StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) sb.append("  ");
    }

    private static void write(StringBuilder sb, Object o) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof String s) { writeString(sb, s); return; }
        if (o instanceof Boolean b) { sb.append(b.booleanValue()); return; }
        if (o instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d);
            else sb.append(d);
            return;
        }
        if (o instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (o instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object item : it) {
                if (!first) sb.append(',');
                first = false;
                write(sb, item);
            }
            sb.append(']');
            return;
        }
        writeString(sb, String.valueOf(o));
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ---------- 类型安全取值 ----------

    public static String str(Map<String, Object> m, String key, String def) {
        Object v = m == null ? null : m.get(key);
        return v == null ? def : String.valueOf(v);
    }

    public static int i(Map<String, Object> m, String key, int def) {
        Object v = m == null ? null : m.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) { try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { } }
        return def;
    }

    public static long lng(Map<String, Object> m, String key, long def) {
        Object v = m == null ? null : m.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) { try { return Long.parseLong(s.trim()); } catch (NumberFormatException ignored) { } }
        return def;
    }

    public static double dbl(Map<String, Object> m, String key, double def) {
        Object v = m == null ? null : m.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) { try { return Double.parseDouble(s.trim()); } catch (NumberFormatException ignored) { } }
        return def;
    }

    public static boolean bool(Map<String, Object> m, String key, boolean def) {
        Object v = m == null ? null : m.get(key);
        if (v instanceof Boolean b) return b.booleanValue();
        if (v instanceof String s) return Boolean.parseBoolean(s.trim());
        return def;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Map ? (Map<String, Object>) v : Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof List ? (List<Object>) v : Collections.emptyList();
    }

    // ---------- 解析器 ----------

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) { this.s = s; }

        void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        char peek() { return i < s.length() ? s.charAt(i) : '\0'; }

        Object value() {
            char c = peek();
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return number();
            }
        }

        private void expect(String lit) {
            if (s.startsWith(lit, i)) i += lit.length();
            else throw new IllegalArgumentException("JSON 解析失败 @ " + i + "，期望 " + lit);
        }

        private Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            i++; // '{'
            skipWs();
            if (peek() == '}') { i++; return map; }
            while (true) {
                skipWs();
                String key = string();
                skipWs();
                if (peek() == ':') i++;
                else throw new IllegalArgumentException("JSON 解析失败 @ " + i + "，期望 ':'");
                skipWs();
                map.put(key, value());
                skipWs();
                char c = peek();
                if (c == ',') { i++; continue; }
                if (c == '}') { i++; return map; }
                throw new IllegalArgumentException("JSON 解析失败 @ " + i + "，期望 ',' 或 '}'");
            }
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            i++; // '['
            skipWs();
            if (peek() == ']') { i++; return list; }
            while (true) {
                skipWs();
                list.add(value());
                skipWs();
                char c = peek();
                if (c == ',') { i++; continue; }
                if (c == ']') { i++; return list; }
                throw new IllegalArgumentException("JSON 解析失败 @ " + i + "，期望 ',' 或 ']'");
            }
        }

        private String string() {
            if (peek() != '"') throw new IllegalArgumentException("JSON 解析失败 @ " + i + "，期望字符串");
            i++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw new IllegalArgumentException("JSON 解析失败：字符串未闭合");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                if (i >= s.length()) throw new IllegalArgumentException("JSON 解析失败：转义序列不完整");
                char e = s.charAt(i++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) throw new IllegalArgumentException("JSON 解析失败：\\u 序列不完整");
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> throw new IllegalArgumentException("JSON 解析失败：未知转义 \\" + e);
                }
            }
        }

        private Number number() {
            int start = i;
            if (peek() == '-' || peek() == '+') i++;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.'
                    || s.charAt(i) == 'e' || s.charAt(i) == 'E' || s.charAt(i) == '-' || s.charAt(i) == '+')) {
                i++;
            }
            String raw = s.substring(start, i);
            if (raw.isEmpty()) throw new IllegalArgumentException("JSON 解析失败 @ " + start + "，期望数值");
            try {
                return Double.valueOf(raw);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("JSON 解析失败：非法数值 " + raw, ex);
            }
        }
    }
}
