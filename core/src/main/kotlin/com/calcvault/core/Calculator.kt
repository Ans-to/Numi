package com.calcvault.core

import java.math.BigDecimal
import java.math.MathContext

/** Bounded recursive-descent parser. Percent is postfix division by 100, not a tax shortcut. */
object Calculator {
    private val math = MathContext.DECIMAL128
    fun evaluate(expression: String): String {
        require(expression.length in 1..256) { "Enter a calculation" }
        val parser = Parser(expression.replace('×', '*').replace('÷', '/').replace('−', '-'))
        val value = parser.expression()
        require(parser.finished()) { "Check the expression" }
        require(value.precision() <= 100 && value.scale() in -100..100) { "Result is too large" }
        return value.stripTrailingZeros().toPlainString().let { if (it == "-0") "0" else it }
    }
    private class Parser(val text: String) {
        var position = 0
        var depth = 0
        fun take(c: Char): Boolean {
            while (position < text.length && text[position].isWhitespace()) position++
            return if (position < text.length && text[position] == c) { position++; true } else false
        }
        fun finished(): Boolean { take(' '); return position == text.length }
        fun expression(): BigDecimal {
            var a = term()
            while (true) a = when { take('+') -> a.add(term(), math); take('-') -> a.subtract(term(), math); else -> return a }
        }
        fun term(): BigDecimal {
            var a = factor()
            while (true) a = when {
                take('*') -> a.multiply(factor(), math)
                take('/') -> { val b = factor(); require(b.signum() != 0) { "Cannot divide by zero" }; a.divide(b, math) }
                else -> return a
            }
        }
        fun factor(): BigDecimal {
            require(++depth <= 32) { "Expression is too complex" }
            var a = when {
                take('+') -> factor()
                take('-') -> factor().negate()
                take('(') -> expression().also { require(take(')')) { "Close the parentheses" } }
                else -> {
                    val start = position
                    while (position < text.length && (text[position].isDigit() || text[position] == '.')) position++
                    require(position > start) { "Enter a number" }
                    text.substring(start, position).toBigDecimalOrNull() ?: error("Check the number")
                }
            }
            while (take('%')) a = a.divide(BigDecimal(100), math)
            depth--
            return a
        }
    }
}
