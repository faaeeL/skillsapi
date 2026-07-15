package com.example.skillsapi.shape;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A tiny, deliberately restricted math expression evaluator - the engine
 * behind {@code shape: parametric}'s {@code formula_x}/{@code formula_y}/
 * {@code formula_z} fields, letting a skill author define a custom curve
 * from skills.yml without the plugin embedding a real scripting language.
 *
 * This is NOT a general-purpose scripting language on purpose: there's no
 * variable assignment, no loops/branches, no strings/arrays/objects, no I/O,
 * and no way to call anything outside the fixed function allowlist below. A
 * formula can only ever produce a single number from arithmetic and a
 * handful of trusted math functions - there's no arbitrary-code-execution
 * surface here the way embedding JavaScript/Groovy/a real scripting engine
 * would create for something that's ultimately just "place some particles."
 *
 * Grammar (standard precedence; {@code ^} is right-associative and binds
 * tighter than unary minus, so {@code -2^2} evaluates to -4, matching most
 * calculators and every mainstream language):
 *   expr   := term (('+' | '-') term)*
 *   term   := power (('*' | '/') power)*
 *   power  := unary ('^' power)?
 *   unary  := ('-' | '+')? atom
 *   atom   := number | identifier | identifier '(' expr (',' expr)* ')' | '(' expr ')'
 *
 * Variables a formula can reference: whatever's passed in the `variables`
 * map (for {@code parametric}, that's {@code t}, {@code i}, {@code n} - see
 * ShapeGenerator#parametric), plus the built-in constants {@code pi},
 * {@code tau} (2*pi), and {@code e}.
 *
 * Functions available: sin cos tan asin acos atan atan2 sqrt abs pow min
 * max floor ceil exp log.
 */
final class MathExpression {

    private final String source;
    private int pos;

    private MathExpression(String source) {
        this.source = source;
        this.pos = 0;
    }

    static double evaluate(String expression, Map<String, Double> variables) {
        MathExpression parser = new MathExpression(expression);
        double result = parser.parseExpression(variables);
        parser.skipWhitespace();
        if (parser.pos != parser.source.length()) {
            throw new IllegalArgumentException("Unexpected character '" + parser.source.charAt(parser.pos)
                    + "' at position " + parser.pos + " in expression: " + expression);
        }
        return result;
    }

    private double parseExpression(Map<String, Double> vars) {
        double value = parseTerm(vars);
        while (true) {
            skipWhitespace();
            if (peek('+')) {
                pos++;
                value += parseTerm(vars);
            } else if (peek('-')) {
                pos++;
                value -= parseTerm(vars);
            } else {
                break;
            }
        }
        return value;
    }

    private double parseTerm(Map<String, Double> vars) {
        double value = parsePower(vars);
        while (true) {
            skipWhitespace();
            if (peek('*')) {
                pos++;
                value *= parsePower(vars);
            } else if (peek('/')) {
                pos++;
                double divisor = parsePower(vars);
                if (divisor == 0) {
                    throw new IllegalArgumentException("Division by zero in expression: " + source);
                }
                value /= divisor;
            } else {
                break;
            }
        }
        return value;
    }

    private double parsePower(Map<String, Double> vars) {
        double base = parseUnary(vars);
        skipWhitespace();
        if (peek('^')) {
            pos++;
            double exponent = parsePower(vars); // right-associative: 2^3^2 == 2^(3^2)
            return Math.pow(base, exponent);
        }
        return base;
    }

    private double parseUnary(Map<String, Double> vars) {
        skipWhitespace();
        if (peek('-')) {
            pos++;
            return -parseUnary(vars);
        }
        if (peek('+')) {
            pos++;
            return parseUnary(vars);
        }
        return parseAtom(vars);
    }

    private double parseAtom(Map<String, Double> vars) {
        skipWhitespace();
        if (pos >= source.length()) {
            throw new IllegalArgumentException("Unexpected end of expression: " + source);
        }

        char c = source.charAt(pos);

        if (c == '(') {
            pos++;
            double value = parseExpression(vars);
            skipWhitespace();
            expect(')');
            return value;
        }

        if (Character.isDigit(c) || c == '.') {
            return parseNumber();
        }

        if (Character.isLetter(c) || c == '_') {
            String name = parseIdentifier();
            skipWhitespace();
            if (peek('(')) {
                pos++;
                List<Double> args = new ArrayList<>();
                skipWhitespace();
                if (!peek(')')) {
                    args.add(parseExpression(vars));
                    skipWhitespace();
                    while (peek(',')) {
                        pos++;
                        args.add(parseExpression(vars));
                        skipWhitespace();
                    }
                }
                expect(')');
                return callFunction(name, args);
            }
            return resolveVariable(name, vars);
        }

        throw new IllegalArgumentException(
                "Unexpected character '" + c + "' at position " + pos + " in expression: " + source);
    }

    private double parseNumber() {
        int start = pos;
        while (pos < source.length() && (Character.isDigit(source.charAt(pos)) || source.charAt(pos) == '.')) {
            pos++;
        }
        return Double.parseDouble(source.substring(start, pos));
    }

    private String parseIdentifier() {
        int start = pos;
        while (pos < source.length() && (Character.isLetterOrDigit(source.charAt(pos)) || source.charAt(pos) == '_')) {
            pos++;
        }
        return source.substring(start, pos);
    }

    private double resolveVariable(String name, Map<String, Double> vars) {
        String key = name.toLowerCase(Locale.ROOT);
        return switch (key) {
            case "pi" -> Math.PI;
            case "tau" -> Math.PI * 2;
            case "e" -> Math.E;
            default -> {
                Double value = vars.get(key);
                if (value == null) {
                    throw new IllegalArgumentException("Unknown variable '" + name
                            + "' in expression (available: " + String.join(", ", vars.keySet())
                            + ", pi, tau, e): " + source);
                }
                yield value;
            }
        };
    }

    private double callFunction(String name, List<Double> args) {
        String key = name.toLowerCase(Locale.ROOT);
        return switch (key) {
            case "sin" -> Math.sin(arg(args, 0, key));
            case "cos" -> Math.cos(arg(args, 0, key));
            case "tan" -> Math.tan(arg(args, 0, key));
            case "asin" -> Math.asin(arg(args, 0, key));
            case "acos" -> Math.acos(arg(args, 0, key));
            case "atan" -> Math.atan(arg(args, 0, key));
            case "atan2" -> Math.atan2(arg(args, 0, key), arg(args, 1, key));
            case "sqrt" -> Math.sqrt(arg(args, 0, key));
            case "abs" -> Math.abs(arg(args, 0, key));
            case "pow" -> Math.pow(arg(args, 0, key), arg(args, 1, key));
            case "min" -> Math.min(arg(args, 0, key), arg(args, 1, key));
            case "max" -> Math.max(arg(args, 0, key), arg(args, 1, key));
            case "floor" -> Math.floor(arg(args, 0, key));
            case "ceil" -> Math.ceil(arg(args, 0, key));
            case "exp" -> Math.exp(arg(args, 0, key));
            case "log" -> Math.log(arg(args, 0, key));
            default -> throw new IllegalArgumentException("Unknown function '" + name
                    + "' in expression (available: sin cos tan asin acos atan atan2 sqrt abs pow min max floor "
                    + "ceil exp log): " + source);
        };
    }

    private double arg(List<Double> args, int index, String fnName) {
        if (index >= args.size()) {
            throw new IllegalArgumentException(
                    "Function '" + fnName + "' is missing an argument in expression: " + source);
        }
        return args.get(index);
    }

    private void skipWhitespace() {
        while (pos < source.length() && Character.isWhitespace(source.charAt(pos))) pos++;
    }

    private boolean peek(char c) {
        return pos < source.length() && source.charAt(pos) == c;
    }

    private void expect(char c) {
        if (!peek(c)) {
            throw new IllegalArgumentException("Expected '" + c + "' at position " + pos + " in expression: " + source);
        }
        pos++;
    }
}
