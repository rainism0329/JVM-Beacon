package dev.jvmbeacon.core;

import javax.management.ObjectName;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Deliberately small, deterministic input language. Never loads a target-supplied class. */
public final class TypeCodec {
    private static final int MAX_INPUT = 65_536;
    private static final int MAX_ARRAY = 1_024;
    private static final Map<String, String> ARRAY_TYPES = Map.ofEntries(
            Map.entry("[Z", "boolean"), Map.entry("[B", "byte"), Map.entry("[S", "short"),
            Map.entry("[I", "int"), Map.entry("[J", "long"), Map.entry("[F", "float"),
            Map.entry("[D", "double"), Map.entry("[C", "char"),
            Map.entry("[Ljava.lang.String;", "java.lang.String"));

    private TypeCodec() { }

    public static boolean supports(String type) {
        return scalarSupported(type) || elementType(type) != null;
    }

    public static String inputHint(String type) {
        if (elementType(type) != null) {
            return "JSON array, maximum 1024 elements; strings and characters require double quotes. No null elements.";
        }
        return switch (type) {
            case "boolean", "java.lang.Boolean" -> "Exactly true or false (case sensitive).";
            case "char", "java.lang.Character" -> "Exactly one UTF-16 code unit; no surrounding quotes.";
            case "java.lang.String" -> "Literal text. Empty text is an empty string; null is not supported.";
            default -> supports(type) ? "A literal value; null is not supported." : "Unsupported input type: " + type;
        };
    }

    public static Object parse(String type, String text) {
        if (text == null) throw new IllegalArgumentException("Null input is not supported.");
        if (text.length() > MAX_INPUT) throw new IllegalArgumentException("Input exceeds 65536 characters.");
        String element = elementType(type);
        if (element != null) {
            List<Token> tokens = new ArrayParser(text).parse();
            Object result = Array.newInstance(primitiveClass(element), tokens.size());
            for (int i = 0; i < tokens.size(); i++) {
                Token token = tokens.get(i);
                boolean stringType = element.equals("java.lang.String") || element.equals("char");
                if (token.quoted != stringType) {
                    throw new IllegalArgumentException("Element " + i + (stringType ? " requires double quotes." : " must be an unquoted literal."));
                }
                Array.set(result, i, parseScalar(element, token.value));
            }
            return result;
        }
        if (!scalarSupported(type)) throw new IllegalArgumentException("Unsupported input type: " + type + ". Arbitrary Java objects are not constructed.");
        return parseScalar(type, text);
    }

    private static Object parseScalar(String type, String raw) {
        String value = type.equals("java.lang.String") || type.equals("char") || type.equals("java.lang.Character") ? raw : raw.trim();
        try {
            return switch (type) {
                case "java.lang.String" -> value;
                case "boolean", "java.lang.Boolean" -> {
                    if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("Expected exactly true or false.");
                    yield Boolean.valueOf(value);
                }
                case "byte", "java.lang.Byte" -> Byte.valueOf(integer(value));
                case "short", "java.lang.Short" -> Short.valueOf(integer(value));
                case "int", "java.lang.Integer" -> Integer.valueOf(integer(value));
                case "long", "java.lang.Long" -> Long.valueOf(integer(value));
                case "float", "java.lang.Float" -> {
                    float number = Float.parseFloat(decimal(value));
                    if (!Float.isFinite(number)) throw new IllegalArgumentException("Finite floating-point values only.");
                    yield number;
                }
                case "double", "java.lang.Double" -> {
                    double number = Double.parseDouble(decimal(value));
                    if (!Double.isFinite(number)) throw new IllegalArgumentException("Finite floating-point values only.");
                    yield number;
                }
                case "char", "java.lang.Character" -> {
                    if (value.length() != 1 || Character.isSurrogate(value.charAt(0))) throw new IllegalArgumentException("Expected one non-surrogate UTF-16 character.");
                    yield value.charAt(0);
                }
                case "java.math.BigInteger" -> new BigInteger(integer(value));
                case "java.math.BigDecimal" -> new BigDecimal(decimal(value));
                case "javax.management.ObjectName" -> new ObjectName(value);
                default -> throw new IllegalArgumentException("Unsupported input type: " + type);
            };
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid " + type + ": " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid " + type + ".", e);
        }
    }

    private static String integer(String value) {
        if (!value.matches("[+-]?[0-9]+")) throw new IllegalArgumentException("Expected a base-10 integer.");
        return value;
    }

    private static String decimal(String value) {
        if (!value.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")) throw new IllegalArgumentException("Expected a finite decimal number.");
        return value;
    }

    private static boolean scalarSupported(String type) {
        if (type == null) return false;
        return switch (type) {
            case "boolean", "byte", "short", "int", "long", "float", "double", "char",
                 "java.lang.Boolean", "java.lang.Byte", "java.lang.Short", "java.lang.Integer",
                 "java.lang.Long", "java.lang.Float", "java.lang.Double", "java.lang.Character",
                 "java.lang.String", "javax.management.ObjectName", "java.math.BigInteger", "java.math.BigDecimal" -> true;
            default -> false;
        };
    }

    private static String elementType(String type) {
        if (type == null) return null;
        String mapped = ARRAY_TYPES.get(type);
        if (mapped != null) return mapped;
        if (type.endsWith("[]")) {
            String element = type.substring(0, type.length() - 2);
            if (ARRAY_TYPES.containsValue(element)) return element;
        }
        return null;
    }

    private static Class<?> primitiveClass(String type) {
        return switch (type) {
            case "boolean" -> boolean.class;
            case "byte" -> byte.class;
            case "short" -> short.class;
            case "int" -> int.class;
            case "long" -> long.class;
            case "float" -> float.class;
            case "double" -> double.class;
            case "char" -> char.class;
            default -> String.class;
        };
    }

    private record Token(String value, boolean quoted) { }

    private static final class ArrayParser {
        private final String input;
        private int offset;
        private ArrayParser(String input) { this.input = input; }

        private List<Token> parse() {
            List<Token> tokens = new ArrayList<>();
            whitespace();
            expect('[');
            whitespace();
            if (take(']')) { finish(); return tokens; }
            while (true) {
                whitespace();
                if (tokens.size() == MAX_ARRAY) throw new IllegalArgumentException("Array exceeds 1024 elements.");
                if (take('"')) tokens.add(new Token(quoted(), true));
                else {
                    int begin = offset;
                    while (offset < input.length() && input.charAt(offset) != ',' && input.charAt(offset) != ']') offset++;
                    String value = input.substring(begin, offset).trim();
                    if (value.isEmpty() || value.equals("null")) throw new IllegalArgumentException("Empty and null elements are not supported.");
                    if (!value.matches("true|false|-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")) throw new IllegalArgumentException("Expected a JSON number or boolean literal.");
                    tokens.add(new Token(value, false));
                }
                whitespace();
                if (take(']')) break;
                expect(',');
            }
            finish();
            return tokens;
        }

        private String quoted() {
            StringBuilder value = new StringBuilder();
            while (offset < input.length()) {
                char c = input.charAt(offset++);
                if (c == '"') return value.toString();
                if (c < 0x20) throw new IllegalArgumentException("Control characters in strings must be escaped.");
                if (c == '\\') {
                    if (offset == input.length()) throw new IllegalArgumentException("Incomplete escape.");
                    char escaped = input.charAt(offset++);
                    switch (escaped) {
                        case '"', '\\', '/' -> value.append(escaped);
                        case 'n' -> value.append('\n');
                        case 'r' -> value.append('\r');
                        case 't' -> value.append('\t');
                        case 'b' -> value.append('\b');
                        case 'f' -> value.append('\f');
                        case 'u' -> {
                            if (offset + 4 > input.length()) throw new IllegalArgumentException("Incomplete Unicode escape.");
                            try { value.append((char) Integer.parseInt(input.substring(offset, offset + 4), 16)); }
                            catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid Unicode escape."); }
                            offset += 4;
                        }
                        default -> throw new IllegalArgumentException("Unsupported string escape.");
                    }
                } else value.append(c);
            }
            throw new IllegalArgumentException("Unterminated string.");
        }
        private void whitespace() { while (offset < input.length() && Character.isWhitespace(input.charAt(offset))) offset++; }
        private boolean take(char c) { if (offset < input.length() && input.charAt(offset) == c) { offset++; return true; } return false; }
        private void expect(char c) { if (!take(c)) throw new IllegalArgumentException("Expected '" + c + "' at position " + offset + "."); }
        private void finish() { whitespace(); if (offset != input.length()) throw new IllegalArgumentException("Unexpected text after array."); }
    }
}
