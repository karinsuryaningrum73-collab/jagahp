package com.example.ui.security

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.data.SecurityConfig
import com.example.security.PinHasher
import com.example.ui.theme.AlertRed
import com.example.ui.theme.AmberTertiary

@Composable
fun StealthSettingsDialog(
    config: SecurityConfig,
    onDismiss: () -> Unit,
    onSavePin: (String, (Boolean) -> Unit) -> Unit,
    onTogglePinLock: (Boolean) -> Unit,
    onToggleDisguise: (Boolean) -> Unit,
    onToggleBlackScreenPin: (Boolean) -> Unit,
    onDisableAll: () -> Unit,
    onLockNow: () -> Unit
) {
    val context = LocalContext.current
    var showPinEditor by remember { mutableStateOf(!config.hasPin) }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var pinFormError by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Icon(
                            imageVector = Icons.Default.VisibilityOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(8.dp)
                                .size(22.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Sembunyikan & Kunci",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (config.isProtectionActive) {
                                "Perlindungan Aktif"
                            } else {
                                "Lindungi aplikasi dari orang lain"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (config.isProtectionActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // PIN Setup / Change Section
                if (showPinEditor) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = if (config.hasPin) "Ubah PIN Rahasia" else "Buat PIN Rahasia (Wajib 4–8 Angka)",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            OutlinedTextField(
                                value = newPin,
                                onValueChange = { input ->
                                    if (input.length <= 8 && input.all { it.isDigit() }) {
                                        newPin = input
                                        pinFormError = null
                                    }
                                },
                                label = { Text("PIN Baru (4–8 digit)") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("stealth_pin_input")
                            )
                            OutlinedTextField(
                                value = confirmPin,
                                onValueChange = { input ->
                                    if (input.length <= 8 && input.all { it.isDigit() }) {
                                        confirmPin = input
                                        pinFormError = null
                                    }
                                },
                                label = { Text("Ulangi PIN") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("stealth_pin_confirm_input")
                            )
                            if (pinFormError != null) {
                                Text(
                                    text = pinFormError.orEmpty(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AlertRed
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (config.hasPin) {
                                    OutlinedButton(
                                        onClick = { showPinEditor = false },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                    ) {
                                        Text("Batal")
                                    }
                                }
                                Button(
                                    onClick = {
                                        when {
                                            !PinHasher.isValidPin(newPin) -> {
                                                pinFormError = "PIN harus 4 sampai 8 angka."
                                            }
                                            newPin != confirmPin -> {
                                                pinFormError = "Konfirmasi PIN tidak cocok."
                                            }
                                            else -> {
                                                onSavePin(newPin) { ok ->
                                                    if (ok) {
                                                        newPin = ""
                                                        confirmPin = ""
                                                        showPinEditor = false
                                                        Toast.makeText(context, "PIN rahasia tersimpan.", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("stealth_save_pin_button")
                                ) {
                                    Text("Simpan PIN")
                                }
                            }
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = { showPinEditor = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Ubah PIN Rahasia")
                    }
                }

                // Toggles
                StealthToggleRow(
                    icon = Icons.Default.Calculate,
                    title = "Samarkan Jadi \"Kalkulator\"",
                    description = "Mengubah ikon & nama aplikasi menjadi Kalkulator. Ketik PIN lalu tekan '=' di kalkulator untuk membuka JagaCam.",
                    checked = config.disguiseAsCalculator,
                    enabled = config.hasPin,
                    testTag = "stealth_disguise_switch",
                    onCheckedChange = { checked ->
                        onToggleDisguise(checked)
                        Toast.makeText(
                            context,
                            if (checked) {
                                "Penyamaran Kalkulator aktif. Ikon launcher diperbarui dalam beberapa detik."
                            } else {
                                "Penyamaran dimatikan. Ikon kembali menjadi JagaCam."
                            },
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )

                StealthToggleRow(
                    icon = Icons.Default.Security,
                    title = "Kunci Aplikasi dengan PIN",
                    description = "Meminta PIN setiap kali aplikasi dibuka agar orang lain tidak bisa melihat atau mengubah pengaturan.",
                    checked = config.pinLockEnabled,
                    enabled = config.hasPin && !config.disguiseAsCalculator,
                    testTag = "stealth_pin_lock_switch",
                    onCheckedChange = onTogglePinLock
                )

                StealthToggleRow(
                    icon = Icons.Default.DarkMode,
                    title = "Kunci Layar Hitam Kamera",
                    description = "Saat HP Kamera dalam Layar Hitam Hemat Daya, wajibkan PIN untuk membukanya agar tidak dimatikan sembarang orang.",
                    checked = config.lockBlackScreenUnlock,
                    enabled = config.hasPin,
                    testTag = "stealth_black_screen_lock_switch",
                    onCheckedChange = onToggleBlackScreenPin
                )

                if (config.disguiseAsCalculator) {
                    Surface(
                        color = AmberTertiary.copy(alpha = 0.14f),
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, AmberTertiary.copy(alpha = 0.45f))
                    ) {
                        Text(
                            text = "Ingat cara masuk saat disamarkan: buka ikon Kalkulator -> ketik PIN Anda -> tekan tombol '='.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                if (config.isProtectionActive) {
                    Button(
                        onClick = {
                            onLockNow()
                            onDismiss()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("stealth_lock_now_dialog_button")
                    ) {
                        Text(
                            text = if (config.disguiseAsCalculator) "Tutup & Samarkan Sekarang" else "Kunci Aplikasi Sekarang",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (config.hasPin) {
                    OutlinedButton(
                        onClick = {
                            onDisableAll()
                            Toast.makeText(context, "Kunci PIN & penyamaran dinonaktifkan.", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AlertRed),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("stealth_disable_all_button")
                    ) {
                        Text("Hapus PIN & Matikan Perlindungan")
                    }
                }

                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("Tutup")
                }
            }
        }
    }
}

@Composable
private fun StealthToggleRow(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (checked && enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                modifier = Modifier.testTag(testTag)
            )
        }
    }
}
