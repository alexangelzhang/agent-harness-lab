package com.example.langgraph4j.tool;

/** 极简四则运算表达式求值器（递归下降），用于演示 Calculator 工具。 */
public final class Evaluator {

    private Evaluator() {
    }

    public static double eval(String expr) {
        return new Parser(expr).parseExpression();
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        double parseExpression() {
            double value = parseTerm();
            while (true) {
                skipSpaces();
                if (match('+')) value += parseTerm();
                else if (match('-')) value -= parseTerm();
                else return value;
            }
        }

        double parseTerm() {
            double value = parseFactor();
            while (true) {
                skipSpaces();
                if (match('*')) value *= parseFactor();
                else if (match('/')) value /= parseFactor();
                else return value;
            }
        }

        double parseFactor() {
            skipSpaces();
            if (match('-')) return -parseFactor();
            if (match('(')) {
                double value = parseExpression();
                skipSpaces();
                expect(')');
                return value;
            }
            int start = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalArgumentException("无法解析的表达式: " + s);
            }
            return Double.parseDouble(s.substring(start, pos));
        }

        private void skipSpaces() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        private boolean match(char c) {
            skipSpaces();
            if (pos < s.length() && s.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        private void expect(char c) {
            if (!match(c)) {
                throw new IllegalArgumentException("期望字符 " + c + "，实际: " + s.substring(pos));
            }
        }
    }
}
