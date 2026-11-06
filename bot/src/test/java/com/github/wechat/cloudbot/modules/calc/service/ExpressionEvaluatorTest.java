package com.github.wechat.cloudbot.modules.calc.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class ExpressionEvaluatorTest {

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '|', value = {
            "1+2           | 3",
            "10-4          | 6",
            "3*7           | 21",
            "20/4          | 5",
            "10%3          | 1",
            "2^10          | 1024",
            "(1+2)*3       | 9",
            "12.5+7        | 19.5",
            "1+2*3         | 7",
            "(1+2)*3       | 9",
            "1/3*3         | 1",
            "-5+3          | -2",
            "+5            | 5",
            "2*3+4*5       | 26",
            "2^(3^2)       | 512",
            "0/100         | 0",
            "100           | 100",
            "1.5+2.5       | 4",
            "10/3*3        | 10",
    })
    void testEvaluate(String expr, double expected) {
        assertEquals(expected, ExpressionEvaluator.evaluate(expr), 1e-10);
    }

    @Test
    void testChineseOperators() {
        assertEquals(6, ExpressionEvaluator.evaluate("2×3"), 1e-10);
        assertEquals(5, ExpressionEvaluator.evaluate("10÷2"), 1e-10);
    }

    @Test
    void testNegative() {
        assertEquals(-10, ExpressionEvaluator.evaluate("-10"), 1e-10);
        assertEquals(1, ExpressionEvaluator.evaluate("-5+6"), 1e-10);
    }

    @Test
    void testDivisionByZero() {
        assertThrows(ArithmeticException.class, () -> ExpressionEvaluator.evaluate("1/0"));
    }

    @Test
    void testModuloByZero() {
        assertThrows(ArithmeticException.class, () -> ExpressionEvaluator.evaluate("5%0"));
    }

    @Test
    void testInvalidCharacters() {
        assertThrows(IllegalArgumentException.class,
                () -> ExpressionEvaluator.evaluate("1+sin(2)"));
        assertThrows(IllegalArgumentException.class,
                () -> ExpressionEvaluator.evaluate("sqrt(4)"));
    }

    @Test
    void testBlankInput() {
        assertThrows(IllegalArgumentException.class,
                () -> ExpressionEvaluator.evaluate(""));
        assertThrows(IllegalArgumentException.class,
                () -> ExpressionEvaluator.evaluate("   "));
    }

    @Test
    void testNullInput() {
        assertThrows(IllegalArgumentException.class,
                () -> ExpressionEvaluator.evaluate(null));
    }

    @Test
    void testMissingParenthesis() {
        assertThrows(IllegalArgumentException.class,
                () -> ExpressionEvaluator.evaluate("(1+2"));
    }

    @Test
    void testUnparsedRemainder() {
        assertThrows(IllegalArgumentException.class,
                () -> ExpressionEvaluator.evaluate("1+2a"));
    }

    @Test
    void testDecimalPrecision() {
        double result = ExpressionEvaluator.evaluate("1/3");
        assertTrue(Math.abs(result - 0.3333333333) < 1e-10);
    }

    @Test
    void testComplexExpression() {
        double result = ExpressionEvaluator.evaluate("((1+2)*3-4)/2^3");
        // ((3)*3-4)/8 = (9-4)/8 = 5/8 = 0.625
        assertEquals(0.625, result, 1e-10);
    }
}
