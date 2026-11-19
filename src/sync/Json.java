package src.sync;

import java.util.LinkedHashMap;
import java.util.Map;

/** Minimal JSON for flat row objects (string, number and null values). */
final class Json {

    private Json() {
    }

    static String write(Map<String, Object> row) {
        StringBuilder out = new StringBuilder("{");
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (out.length() > 1) {
                out.append(',');
            }
            string(out, entry.getKey());
            out.append(':');
            Object value = entry.getValue();
            if (value == null) {
                out.append("null");
            } else if (value instanceof Number) {
                out.append(value);
            } else {
                string(out, value.toString());
            }
        }
        return out.append('}').toString();
    }

    private static void string(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** Parses a flat JSON object; numbers become Long or Double. */
    static Map<String, Object> read(String json) {
        Map<String, Object> row = new LinkedHashMap<>();
        Parser parser = new Parser(json);
        parser.expect('{');
        if (parser.peek() == '}') {
            return row;
        }
        do {
            String key = parser.string();
            parser.expect(':');
            row.put(key, parser.value());
        } while (parser.consume(','));
        parser.expect('}');
        return row;
    }

    private static final class Parser {
        private final String text;
        private int at;

        Parser(String text) {
            this.text = text;
        }

        char peek() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
            return at < text.length() ? text.charAt(at) : '\0';
        }

        boolean consume(char c) {
            if (peek() == c) {
                at++;
                return true;
            }
            return false;
        }

        void expect(char c) {
            if (!consume(c)) {
                throw new IllegalArgumentException("Bad JSON near " + at + ": expected " + c);
            }
        }

        Object value() {
            char c = peek();
            if (c == '"') {
                return string();
            }
            if (text.startsWith("null", at)) {
                at += 4;
                return null;
            }
            if (text.startsWith("true", at)) {
                at += 4;
                return 1L;
            }
            if (text.startsWith("false", at)) {
                at += 5;
                return 0L;
            }
            int start = at;
            while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) {
                at++;
            }
            String number = text.substring(start, at);
            if (number.isEmpty()) {
                throw new IllegalArgumentException("Bad JSON value near " + start);
            }
            return number.matches("-?\\d+") ? (Object) Long.parseLong(number) : Double.parseDouble(number);
        }

        String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = text.charAt(at++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char e = text.charAt(at++);
                switch (e) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> {
                        out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        at += 4;
                    }
                    default -> out.append(e);
                }
            }
        }
    }
}
