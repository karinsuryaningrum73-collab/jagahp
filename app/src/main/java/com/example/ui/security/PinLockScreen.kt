package com.example.ui.security

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.ui.theme.AlertRed
import kotlinx.coroutines.launch

@Composable
fun PinLockScreen(
    title: String = "JagaCam Terkunci",
    subtitle: String = "Masukkan PIN rahasia (4–8 digit) untuk mengakses kamera & kontrol.",
    errorMessage: String? = null,
    lockoutRemainingSeconds: Int = 0,
    onClearError: () -> Unit = {},
    onVerifyPin: suspend (String) -> Boolean
) {
    var enteredPin by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val effectiveError = errorMessage ?: localError
    val isLockedOut = lockoutRemainingSeconds > 0

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        Color(0xFF0A111D),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .safeDrawingPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Ikon Kunci PIN",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(18.dp)
                        .size(34.dp)
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            // PIN Dots
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.height(32.dp)
            ) {
                val displaySlots = maxOf(4, enteredPin.length)
                for (index in 0 until displaySlots) {
                    val filled = index < enteredPin.length
                    Box(
                        modifier = Modifier
                            .size(if (filled) 16.dp else 14.dp)
                            .background(
                                color = if (filled) MaterialTheme.colorScheme.primary else Color(0xFF283447),
                                shape = CircleShape
                            )
                    )
                }
            }

            if (!effectiveError.isNullOrBlank()) {
                Text(
                    text = effectiveError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AlertRed,
                    textAlign = TextAlign.Center
                )
            } else {
                Spacer(modifier = Modifier.height(20.dp))
            }

            NumericPinPad(
                enabled = !isLockedOut,
                canSubmit = enteredPin.length >= 4 && !isLockedOut,
                onDigit = { digit ->
                    if (enteredPin.length < 8) {
                        localError = null
                        onClearError()
                        enteredPin += digit
                    }
                },
                onBackspace = {
                    if (enteredPin.isNotEmpty()) {
                        localError = null
                        enteredPin = enteredPin.dropLast(1)
                    }
                },
                onSubmit = {
                    if (enteredPin.length < 4) {
                        localError = "Minimal 4 digit PIN"
                        return@NumericPinPad
                    }
                    val attempt = enteredPin
                    scope.launch {
                        val ok = onVerifyPin(attempt)
                        if (!ok) {
                            enteredPin = ""
                        }
                    }
                }
            )
        }
    }
}

@Composable
fun PinVerifyDialog(
    title: String = "Masukkan PIN",
    subtitle: String = "Verifikasi PIN untuk membuka Layar Hitam CCTV.",
    onDismiss: () -> Unit,
    onVerifyPin: suspend (String) -> Boolean,
    onVerified: () -> Unit
) {
    var enteredPin by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(24.dp)
                ) {
                    val slots = maxOf(4, enteredPin.length)
                    for (i in 0 until slots) {
                        Box(
                            modifier = Modifier
                                .size(if (i < enteredPin.length) 14.dp else 12.dp)
                                .background(
                                    if (i < enteredPin.length) MaterialTheme.colorScheme.primary else Color(0xFF283447),
                                    CircleShape
                                )
                        )
                    }
                }

                if (errorText != null) {
                    Text(
                        text = errorText.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AlertRed,
                        textAlign = TextAlign.Center
                    )
                }

                NumericPinPad(
                    enabled = true,
                    canSubmit = enteredPin.length >= 4,
                    compact = true,
                    onDigit = { d ->
                        if (enteredPin.length < 8) {
                            errorText = null
                            enteredPin += d
                        }
                    },
                    onBackspace = {
                        if (enteredPin.isNotEmpty()) {
                            errorText = null
                            enteredPin = enteredPin.dropLast(1)
                        }
                    },
                    onSubmit = {
                        val attempt = enteredPin
                        scope.launch {
                            if (onVerifyPin(attempt)) {
                                onVerified()
                            } else {
                                enteredPin = ""
                                errorText = "PIN salah. Coba lagi."
                            }
                        }
                    }
                )

                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("Kembali ke Layar Hitam")
                }
            }
        }
    }
}

@Composable
private fun NumericPinPad(
    enabled: Boolean,
    canSubmit: Boolean,
    compact: Boolean = false,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit
) {
    val keyHeight = if (compact) 54.dp else 64.dp
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val rows = listOf(
            listOf('1', '2', '3'),
            listOf('4', '5', '6'),
            listOf('7', '8', '9')
        )
        rows.forEach { rowDigits ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowDigits.forEach { digit ->
                    Surface(
                        onClick = { onDigit(digit) },
                        enabled = enabled,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(keyHeight)
                            .testTag("pin_key_$digit")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = digit.toString(),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                onClick = onBackspace,
                enabled = enabled,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(keyHeight)
                    .testTag("pin_key_backspace")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Backspace,
                        contentDescription = "Hapus digit",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Surface(
                onClick = { onDigit('0') },
                enabled = enabled,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(keyHeight)
                    .testTag("pin_key_0")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "0",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Button(
                onClick = onSubmit,
                enabled = canSubmit,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(keyHeight)
                    .testTag("pin_key_submit")
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Buka Kunci"
                )
            }
        }
    }
}
