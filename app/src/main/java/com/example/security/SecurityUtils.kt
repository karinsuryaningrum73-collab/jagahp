package com.example.security

import java.math.BigDecimal
import java.math.MathContext
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

object PinHasher {

    fun isValidPin(pin: String): Boolean {
        return pin.length in 4..8 && pin.all { it.isDigit() }
    }

    fun hashPin(pin: String, saltHex: String = generateSaltHex()): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val payload = "$saltHex:$pin".toByteArray(StandardCharsets.UTF_8)
        val hashBytes = digest.digest(payload)
        return "$saltHex:${hashBytes.toHexString()}"
    }

    fun verifyPin(pin: String, storedValue: String?): Boolean {
        if (storedValue.isNullOrBlank() || !isValidPin(pin)) return false
        val parts = storedValue.split(":", limit = 2)
        if (parts.size != 2) return false
        val saltHex = parts[0]
        val expectedHashHex = parts[1]
        if (saltHex.isBlank() || expectedHashHex.isBlank()) return false

        val candidate = hashPin(pin, saltHex).substringAfter(":")
        return MessageDigest.isEqual(
            candidate.toByteArray(StandardCharsets.UTF_8),
            expectedHashHex.toByteArray(StandardCharsets.UTF_8)
        )
    }

    private fun generateSaltHex(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.toHexString()
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }
}

/**
 * Pure calculator state machine used by the disguised Calculator launcher UI.
 * Also supports detecting a pure digit entry without operators so pressing '='
 * can silently check if the entered digits match the user's secret PIN.
 */
data class CalculatorState(
    val display: String = "0",
    val expressionPreview: String = "",
    val pendingLeftOperand: BigDecimal? = null,
    val pendingOperator: Char? = null,
    val resetInputOnNextDigit: Boolean = false,
    val hasOperatorInSession: Boolean = false,
    val isError: Boolean = false
)

object CalculatorEngine {

    private val mathContext = MathContext.DECIMAL64

    fun onDigit(state: CalculatorState, digit: Char): CalculatorState {
        if (!digit.isDigit()) return state
        if (state.isError) {
            return CalculatorState(display = digit.toString())
        }
        val nextDisplay = when {
            state.resetInputOnNextDigit || state.display == "0" -> digit.toString()
            state.display.replace("-", "").replace(".", "").length >= 12 -> state.display
            else -> state.display + digit
        }
        return state.copy(
            display = nextDisplay,
            resetInputOnNextDigit = false
        )
    }

    fun onDecimal(state: CalculatorState): CalculatorState {
        if (state.isError) return CalculatorState(display = "0.")
        if (state.resetInputOnNextDigit) {
            return state.copy(display = "0.", resetInputOnNextDigit = false)
        }
        if (state.display.contains(".")) return state
        return state.copy(display = state.display + ".")
    }

    fun onToggleSign(state: CalculatorState): CalculatorState {
        if (state.isError || state.display == "0") return state
        val toggled = if (state.display.startsWith("-")) {
            state.display.drop(1)
        } else {
            "-${state.display}"
        }
        return state.copy(display = toggled, hasOperatorInSession = true)
    }

    fun onPercent(state: CalculatorState): CalculatorState {
        if (state.isError) return state
        val current = state.display.toBigDecimalOrNull() ?: return state
        val result = current.divide(BigDecimal(100), mathContext)
        return state.copy(
            display = formatDecimal(result),
            resetInputOnNextDigit = true,
            hasOperatorInSession = true
        )
    }

    fun onBackspace(state: CalculatorState): CalculatorState {
        if (state.isError || state.resetInputOnNextDigit) {
            return state.copy(display = "0", isError = false, resetInputOnNextDigit = false)
        }
        val trimmed = state.display.dropLast(1)
        val next = if (trimmed.isEmpty() || trimmed == "-") "0" else trimmed
        return state.copy(display = next)
    }

    fun onClear(): CalculatorState = CalculatorState()

    fun onOperator(state: CalculatorState, operator: Char): CalculatorState {
        if (state.isError) return state
        val current = state.display.toBigDecimalOrNull() ?: return state

        if (state.pendingLeftOperand != null && state.pendingOperator != null && !state.resetInputOnNextDigit) {
            val evaluated = compute(state.pendingLeftOperand, current, state.pendingOperator)
                ?: return CalculatorState(display = "Error", isError = true)
            val formatted = formatDecimal(evaluated)
            return state.copy(
                display = formatted,
                expressionPreview = "$formatted $operator",
                pendingLeftOperand = evaluated,
                pendingOperator = operator,
                resetInputOnNextDigit = true,
                hasOperatorInSession = true
            )
        }

        return state.copy(
            expressionPreview = "${formatDecimal(current)} $operator",
            pendingLeftOperand = current,
            pendingOperator = operator,
            resetInputOnNextDigit = true,
            hasOperatorInSession = true
        )
    }

    /**
     * Returns non-null raw digit string ONLY when the user typed a pure integer sequence
     * without pressing any arithmetic operator or decimal point (used for silent PIN unlock).
     */
    fun extractPlainPinCandidate(state: CalculatorState): String? {
        if (state.isError || state.hasOperatorInSession || state.pendingOperator != null) return null
        val text = state.display
        return if (PinHasher.isValidPin(text)) text else null
    }

    fun onEquals(state: CalculatorState): CalculatorState {
        if (state.isError) return CalculatorState()
        val left = state.pendingLeftOperand
        val op = state.pendingOperator
        val right = state.display.toBigDecimalOrNull() ?: return state

        if (left == null || op == null) {
            return state.copy(
                expressionPreview = "",
                resetInputOnNextDigit = true,
                hasOperatorInSession = false
            )
        }

        val evaluated = compute(left, right, op)
            ?: return CalculatorState(display = "Error", isError = true)
        val formatted = formatDecimal(evaluated)
        return CalculatorState(
            display = formatted,
            expressionPreview = "${formatDecimal(left)} $op ${formatDecimal(right)} =",
            pendingLeftOperand = null,
            pendingOperator = null,
            resetInputOnNextDigit = true,
            hasOperatorInSession = false
        )
    }

    private fun compute(left: BigDecimal, right: BigDecimal, op: Char): BigDecimal? {
        return try {
            when (op) {
                '+' -> left.add(right, mathContext)
                '-' -> left.subtract(right, mathContext)
                '×', '*' -> left.multiply(right, mathContext)
                '÷', '/' -> {
                    if (right.compareTo(BigDecimal.ZERO) == 0) null
                    else left.divide(right, mathContext)
                }
                else -> right
            }
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun formatDecimal(value: BigDecimal): String {
        val stripped = value.stripTrailingZeros()
        val plain = if (stripped.scale() <= 0 && stripped.abs() < BigDecimal("1000000000000")) {
            stripped.toBigInteger().toString()
        } else {
            stripped.toPlainString()
        }
        return if (plain.length > 14) value.round(MathContext(9)).stripTrailingZeros().toString() else plain
    }
}
