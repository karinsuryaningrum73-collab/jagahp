package com.example.ui.security

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.security.CalculatorEngine
import com.example.security.CalculatorState
import com.example.ui.theme.JetBrainsMonoFontFamily
import kotlinx.coroutines.launch

/**
 * Fully functional Dark-Mode Calculator screen used when Disguise Mode is active.
 * Entering the owner's secret PIN (without operators) and pressing '=' unlocks JagaCam silently.
 */
@Composable
fun CalculatorDisguiseScreen(
    onTryPinUnlock: suspend (String) -> Boolean
) {
    var calcState by remember { mutableStateOf(CalculatorState()) }
    val scope = rememberCoroutineScope()

    val surfaceBg = Color(0xFF0A0D14)
    val numKeyBg = Color(0xFF171E2B)
    val funcKeyBg = Color(0xFF232D3F)
    val opKeyBg = Color(0xFF0E7490)
    val equalsKeyBg = Color(0xFF10B981)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(surfaceBg)
            .safeDrawingPadding(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            // Subtle genuine header
            Text(
                text = "Kalkulator",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF64748B),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )

            Spacer(modifier = Modifier.weight(1f))

            // Expression & Result Display
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = calcState.expressionPreview,
                    fontFamily = JetBrainsMonoFontFamily,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF64748B),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = calcState.display,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = if (calcState.display.length > 9) 40.sp else 56.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFF8FAFC),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("calculator_display")
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Keypad Grid
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CalcRow {
                    CalcKey(
                        label = "AC",
                        bgColor = funcKeyBg,
                        textColor = Color(0xFFF87171),
                        modifier = Modifier.weight(1f),
                        testTag = "calc_ac",
                        onClick = { calcState = CalculatorEngine.onClear() }
                    )
                    CalcIconKey(
                        bgColor = funcKeyBg,
                        tint = Color(0xFFE2E8F0),
                        contentDescription = "Hapus satu digit",
                        modifier = Modifier.weight(1f),
                        testTag = "calc_backspace",
                        onClick = { calcState = CalculatorEngine.onBackspace(calcState) }
                    )
                    CalcKey(
                        label = "%",
                        bgColor = funcKeyBg,
                        textColor = Color(0xFFE2E8F0),
                        modifier = Modifier.weight(1f),
                        testTag = "calc_percent",
                        onClick = { calcState = CalculatorEngine.onPercent(calcState) }
                    )
                    CalcKey(
                        label = "÷",
                        bgColor = opKeyBg,
                        textColor = Color.White,
                        modifier = Modifier.weight(1f),
                        testTag = "calc_div",
                        onClick = { calcState = CalculatorEngine.onOperator(calcState, '÷') }
                    )
                }

                CalcRow {
                    listOf('7', '8', '9').forEach { digit ->
                        CalcKey(
                            label = digit.toString(),
                            bgColor = numKeyBg,
                            textColor = Color.White,
                            modifier = Modifier.weight(1f),
                            testTag = "calc_$digit",
                            onClick = { calcState = CalculatorEngine.onDigit(calcState, digit) }
                        )
                    }
                    CalcKey(
                        label = "×",
                        bgColor = opKeyBg,
                        textColor = Color.White,
                        modifier = Modifier.weight(1f),
                        testTag = "calc_mul",
                        onClick = { calcState = CalculatorEngine.onOperator(calcState, '×') }
                    )
                }

                CalcRow {
                    listOf('4', '5', '6').forEach { digit ->
                        CalcKey(
                            label = digit.toString(),
                            bgColor = numKeyBg,
                            textColor = Color.White,
                            modifier = Modifier.weight(1f),
                            testTag = "calc_$digit",
                            onClick = { calcState = CalculatorEngine.onDigit(calcState, digit) }
                        )
                    }
                    CalcKey(
                        label = "-",
                        bgColor = opKeyBg,
                        textColor = Color.White,
                        modifier = Modifier.weight(1f),
                        testTag = "calc_sub",
                        onClick = { calcState = CalculatorEngine.onOperator(calcState, '-') }
                    )
                }

                CalcRow {
                    listOf('1', '2', '3').forEach { digit ->
                        CalcKey(
                            label = digit.toString(),
                            bgColor = numKeyBg,
                            textColor = Color.White,
                            modifier = Modifier.weight(1f),
                            testTag = "calc_$digit",
                            onClick = { calcState = CalculatorEngine.onDigit(calcState, digit) }
                        )
                    }
                    CalcKey(
                        label = "+",
                        bgColor = opKeyBg,
                        textColor = Color.White,
                        modifier = Modifier.weight(1f),
                        testTag = "calc_add",
                        onClick = { calcState = CalculatorEngine.onOperator(calcState, '+') }
                    )
                }

                CalcRow {
                    CalcKey(
                        label = "+/-",
                        bgColor = numKeyBg,
                        textColor = Color(0xFFCBD5E1),
                        modifier = Modifier.weight(1f),
                        testTag = "calc_sign",
                        onClick = { calcState = CalculatorEngine.onToggleSign(calcState) }
                    )
                    CalcKey(
                        label = "0",
                        bgColor = numKeyBg,
                        textColor = Color.White,
                        modifier = Modifier.weight(1f),
                        testTag = "calc_0",
                        onClick = { calcState = CalculatorEngine.onDigit(calcState, '0') }
                    )
                    CalcKey(
                        label = ".",
                        bgColor = numKeyBg,
                        textColor = Color.White,
                        modifier = Modifier.weight(1f),
                        testTag = "calc_dot",
                        onClick = { calcState = CalculatorEngine.onDecimal(calcState) }
                    )
                    CalcKey(
                        label = "=",
                        bgColor = equalsKeyBg,
                        textColor = Color(0xFF04130D),
                        modifier = Modifier.weight(1f),
                        testTag = "calc_equals",
                        onClick = {
                            val pinCandidate = CalculatorEngine.extractPlainPinCandidate(calcState)
                            if (pinCandidate != null) {
                                scope.launch {
                                    val unlocked = onTryPinUnlock(pinCandidate)
                                    if (unlocked) {
                                        calcState = CalculatorEngine.onClear()
                                    } else {
                                        calcState = CalculatorEngine.onEquals(calcState)
                                    }
                                }
                            } else {
                                calcState = CalculatorEngine.onEquals(calcState)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CalcRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

@Composable
private fun CalcKey(
    label: String,
    bgColor: Color,
    textColor: Color,
    testTag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = bgColor,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .height(68.dp)
            .testTag(testTag)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = textColor
            )
        }
    }
}

@Composable
private fun CalcIconKey(
    bgColor: Color,
    tint: Color,
    contentDescription: String,
    testTag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = bgColor,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .height(68.dp)
            .testTag(testTag)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Backspace,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
