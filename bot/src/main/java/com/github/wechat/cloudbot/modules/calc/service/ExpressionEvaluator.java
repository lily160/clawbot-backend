package com.github.wechat.cloudbot.modules.calc.service;

/**
 * 安全算术表达式求值：支持 + - * / % ^ ( ) 与小数，禁止变量与函数调用。
 */
public final class ExpressionEvaluator {

    private ExpressionEvaluator() {}

    public static double evaluate(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("expression empty");
        }
        String cleaned = expression.replace(" ", "").replace("×", "*").replace("÷", "/");
        if (!cleaned.matches("[0-9.+\\-*/%^()]+")) {
            throw new IllegalArgumentException("仅支持数字与 + - * / % ^ ( )：" + expression);
        }
        Parser parser = new Parser(cleaned);
        double value = parser.parseExpression();
        if (!parser.done()) {
            throw new IllegalArgumentException("表达式未完整解析：" + expression);
        }
        return value;
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        boolean done() {
            return i >= s.length();
        }

        double parseExpression() {
            double v = parseTerm();
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '+') {
                    i++;
                    v += parseTerm();
                } else if (c == '-') {
                    i++;
                    v -= parseTerm();
                } else {
                    break;
                }
            }
            return v;
        }

        private double parseTerm() {
            double v = parsePower();
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '*') {
                    i++;
                    v *= parsePower();
                } else if (c == '/') {
                    i++;
                    double d = parsePower();
                    if (d == 0.0) {
                        throw new ArithmeticException("division by zero");
                    }
                    v /= d;
                } else if (c == '%') {
                    i++;
                    double d = parsePower();
                    if (d == 0.0) {
                        throw new ArithmeticException("modulo by zero");
                    }
                    v %= d;
                } else {
                    break;
                }
            }
            return v;
        }

        private double parsePower() {
            double base = parseUnary();
            if (i < s.length() && s.charAt(i) == '^') {
                i++;
                double exp = parseUnary();
                return Math.pow(base, exp);
            }
            return base;
        }

        private double parseUnary() {
            if (i < s.length() && s.charAt(i) == '+') {
                i++;
                return parseUnary();
            }
            if (i < s.length() && s.charAt(i) == '-') {
                i++;
                return -parseUnary();
            }
            return parsePrimary();
        }

        private double parsePrimary() {
            if (i < s.length() && s.charAt(i) == '(') {
                i++;
                double v = parseExpression();
                if (i >= s.length() || s.charAt(i) != ')') {
                    throw new IllegalArgumentException("缺少右括号");
                }
                i++;
                return v;
            }
            int start = i;
            while (i < s.length()
                    && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                i++;
            }
            if (start == i) {
                throw new IllegalArgumentException("期望数字，位置 " + i);
            }
            return Double.parseDouble(s.substring(start, i));
        }
    }
}
