package me.bers.spiazzetta.data.agua

import me.bers.spiazzetta.domain.AguaException

/**
 * Evaluator for the small arithmetic expressions the Agua platform ships inside register
 * definitions ("formula" and "formula_inverse", e.g. "#", "#*10", "#-1", "round(#/2)").
 *
 * The stock app uses the full math-expression-evaluator library; the platform only ever needs
 * + - * / % parentheses and a handful of unary functions, so this keeps the app dependency-free.
 */
object Formula {

    fun evaluate(expression: String, value: Int): Double = evaluate(expression, value.toDouble())

    fun evaluate(expression: String, value: Double): Double {
        val substituted = expression.replace("#", value.toString())
        return Parser(substituted).parse()
    }

    private class Parser(private val text: String) {
        private var position = 0

        fun parse(): Double {
            val result = expression()
            skipSpaces()
            if (position < text.length) {
                throw AguaException.InvalidResponse("formula: unexpected character at " + position + " in " + text)
            }
            return result
        }

        private fun expression(): Double {
            var value = term()
            while (true) {
                skipSpaces()
                value = when {
                    consume('+') -> value + term()
                    consume('-') -> value - term()
                    else -> return value
                }
            }
        }

        private fun term(): Double {
            var value = factor()
            while (true) {
                skipSpaces()
                value = when {
                    consume('*') -> value * factor()
                    consume('/') -> {
                        val divisor = factor()
                        if (divisor == 0.0) 0.0 else value / divisor
                    }
                    consume('%') -> {
                        val divisor = factor()
                        if (divisor == 0.0) 0.0 else value % divisor
                    }
                    else -> return value
                }
            }
        }

        private fun factor(): Double {
            skipSpaces()
            if (consume('-')) return -factor()
            if (consume('+')) return factor()
            if (consume('(')) {
                val value = expression()
                if (!consume(')')) throw AguaException.InvalidResponse("formula: missing closing parenthesis in " + text)
                return value
            }
            if (position < text.length && (text[position].isLetter() || text[position] == '_')) {
                val name = readIdentifier()
                if (!consume('(')) throw AguaException.InvalidResponse("formula: " + name + " is not a function")
                val argument = expression()
                if (!consume(')')) throw AguaException.InvalidResponse("formula: missing closing parenthesis after " + name)
                return when (name.lowercase()) {
                    "round" -> Math.round(argument).toDouble()
                    "floor" -> Math.floor(argument)
                    "ceil" -> Math.ceil(argument)
                    "abs" -> Math.abs(argument)
                    "int" -> argument.toInt().toDouble()
                    "sqrt" -> Math.sqrt(argument)
                    else -> throw AguaException.InvalidResponse("formula: unknown function " + name)
                }
            }
            return number()
        }

        private fun number(): Double {
            skipSpaces()
            val start = position
            while (position < text.length && (text[position].isDigit() || text[position] == '.')) position++
            if (start == position) throw AguaException.InvalidResponse("formula: expected a number in " + text)
            return text.substring(start, position).toDouble()
        }

        private fun readIdentifier(): String {
            val start = position
            while (position < text.length && (text[position].isLetterOrDigit() || text[position] == '_')) position++
            return text.substring(start, position)
        }

        private fun consume(char: Char): Boolean {
            skipSpaces()
            if (position < text.length && text[position] == char) {
                position++
                return true
            }
            return false
        }

        private fun skipSpaces() {
            while (position < text.length && text[position].isWhitespace()) position++
        }
    }
}
