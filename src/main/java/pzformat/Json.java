package pzformat;

import java.util.*;

/** Tiny JSON parser. The project has no dependencies and this keeps it that way. */
public final class Json {

    public static final class Value {
        public Map<String, Value> object;
        public List<Value> array;
        public String str;
        public double num;
        public boolean isNum, isBool, boolVal, isNull;

        public Value get(String key) { return object == null ? null : object.get(key); }

        public String asText() {
            if (str != null) return str;
            if (isNum) return num == Math.floor(num) && !Double.isInfinite(num)
                    ? String.valueOf((long) num) : String.valueOf(num);
            if (isBool) return String.valueOf(boolVal);
            return "";
        }
    }

    private final String s;
    private int p;

    private Json(String s) { this.s = s; }

    public static Value parse(String text) {
        Json j = new Json(text);
        j.ws();
        return j.value();
    }

    private void ws() { while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++; }

    private Value value() {
        ws();
        char c = s.charAt(p);
        switch (c) {
            case '{': return obj();
            case '[': return arr();
            case '"': { Value v = new Value(); v.str = string(); return v; }
            case 't': p += 4; return bool(true);
            case 'f': p += 5; return bool(false);
            case 'n': p += 4; { Value v = new Value(); v.isNull = true; return v; }
            default: return number();
        }
    }

    private Value bool(boolean b) { Value v = new Value(); v.isBool = true; v.boolVal = b; return v; }

    private Value obj() {
        Value v = new Value();
        v.object = new LinkedHashMap<>();
        p++;                       // {
        ws();
        if (s.charAt(p) == '}') { p++; return v; }
        while (true) {
            ws();
            String key = string();
            ws();
            p++;                   // :
            v.object.put(key, value());
            ws();
            if (s.charAt(p) == ',') { p++; continue; }
            p++;                   // }
            return v;
        }
    }

    private Value arr() {
        Value v = new Value();
        v.array = new ArrayList<>();
        p++;                       // [
        ws();
        if (s.charAt(p) == ']') { p++; return v; }
        while (true) {
            v.array.add(value());
            ws();
            if (s.charAt(p) == ',') { p++; continue; }
            p++;                   // ]
            return v;
        }
    }

    private String string() {
        StringBuilder sb = new StringBuilder();
        p++;                       // opening quote
        while (true) {
            char c = s.charAt(p++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            char e = s.charAt(p++);
            switch (e) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> { sb.append((char) Integer.parseInt(s.substring(p, p + 4), 16)); p += 4; }
                default -> sb.append(e);
            }
        }
    }

    private Value number() {
        int start = p;
        while (p < s.length() && "+-0123456789.eE".indexOf(s.charAt(p)) >= 0) p++;
        Value v = new Value();
        v.isNum = true;
        v.num = Double.parseDouble(s.substring(start, p));
        return v;
    }
}
