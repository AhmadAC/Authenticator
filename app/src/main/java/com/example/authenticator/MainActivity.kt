package com.example.authenticator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.delay
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

// -------------------------------------------------------------
// Data Model (Immutable for Compose Recomposition)
// -------------------------------------------------------------
data class Account(
    val websiteName: String = "Unknown",
    val websiteAddress: String = "-",
    val username: String = "-",
    val secret: String = "",
    val algorithm: String = "SHA1",
    val digits: Int = 6,
    val period: Int = 30,
    val showAdvanced: Boolean = false
)

// -------------------------------------------------------------
// Main Activity
// -------------------------------------------------------------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AuthenticatorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0F121A)
                ) {
                    AuthenticatorScreen()
                }
            }
        }
    }
}

// -------------------------------------------------------------
// UI Screen
// -------------------------------------------------------------
@Composable
fun AuthenticatorScreen() {
    val context = LocalContext.current
    var accounts by remember { mutableStateOf(loadAccounts(context)) }
    var selectedIndex by remember { mutableIntStateOf(0) }
    var currentOtpCode by remember { mutableStateOf("------") }
    var remainingSeconds by remember { mutableIntStateOf(30) }
    var secretRevealed by remember { mutableStateOf(false) }
    var showManualAddDialog by remember { mutableStateOf(false) }

    val activeAccount = accounts.getOrNull(selectedIndex)

    // Helper to immutably update the active account and trigger recomposition
    fun updateActiveAccount(transform: (Account) -> Account) {
        if (selectedIndex in accounts.indices) {
            val updated = transform(accounts[selectedIndex])
            accounts = accounts.toMutableList().also { it[selectedIndex] = updated }
            saveAccounts(context, accounts)
        }
    }

    // Image Picker for QR Code
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val qrText = decodeQrFromUri(context, uri)
            if (qrText != null) {
                val parsed = parseOtpUri(qrText)
                if (parsed != null) {
                    accounts = accounts + parsed
                    saveAccounts(context, accounts)
                    selectedIndex = accounts.lastIndex
                    Toast.makeText(context, "Account added & code copied!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Invalid 2FA QR code URI", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "No readable QR code found in image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 500ms loop for TOTP calculation & auto-copy to clipboard
    LaunchedEffect(activeAccount?.secret, activeAccount?.algorithm, activeAccount?.digits, activeAccount?.period) {
        var lastCode = ""
        while (true) {
            if (activeAccount != null && activeAccount.secret.isNotBlank()) {
                val period = activeAccount.period.coerceAtLeast(1)
                val curTime = System.currentTimeMillis() / 1000L
                remainingSeconds = (period - (curTime % period)).toInt()

                val newCode = generateTotp(
                    secret = activeAccount.secret,
                    digits = activeAccount.digits,
                    period = activeAccount.period,
                    algorithm = activeAccount.algorithm
                )
                currentOtpCode = newCode

                if (newCode != lastCode && newCode != "ERROR") {
                    lastCode = newCode
                    copyToClipboard(context, newCode, showToast = false)
                }
            } else {
                currentOtpCode = "------"
            }
            delay(500)
        }
    }

    Scaffold(
        containerColor = Color(0xFF0F121A)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Accounts",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )

            // Account List View
            if (accounts.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                        .background(Color(0xFF141824), RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFF23283A), RoundedCornerShape(8.dp))
                        .padding(4.dp)
                ) {
                    itemsIndexed(accounts) { index, item ->
                        val isSelected = index == selectedIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) Color(0xFF283049) else Color(0xFF1A1F30))
                                .clickable {
                                    selectedIndex = index
                                    secretRevealed = false
                                }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = item.websiteName,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = item.username,
                                    color = Color(0xFFA6ADC8),
                                    fontSize = 12.sp
                                )
                            }
                            if (isSelected) {
                                Text(
                                    text = "ACTIVE",
                                    color = Color(0xFF1E88E5),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // Top Action Buttons (High contrast, clearly visible)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { imagePicker.launch("image/*") },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Select QR", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }

                // Add Manually Button (Changed from dark purple to high-visibility teal/cyan)
                Button(
                    onClick = { showManualAddDialog = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0D9488),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Add Manually", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Live OTP Code Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF23283A))
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val half = (currentOtpCode.length / 2).coerceAtLeast(1)
                    val formatted = if (currentOtpCode.length > 3) {
                        "${currentOtpCode.substring(0, half)}  ${currentOtpCode.substring(half)}"
                    } else currentOtpCode

                    Text(
                        text = formatted,
                        color = Color(0xFF64B5F6),
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 4.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    val maxPeriod = (activeAccount?.period ?: 30).toFloat()
                    LinearProgressIndicator(
                        progress = { remainingSeconds / maxPeriod },
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = Color(0xFF1E88E5),
                        trackColor = Color(0xFF0F121A),
                    )

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Expires in: ${remainingSeconds}s",
                        color = Color(0xFF8B92A9),
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { copyToClipboard(context, currentOtpCode, showToast = true) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF23283C)),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy OTP Code", color = Color.White)
                    }
                }
            }

            // Account Details Card
            if (activeAccount != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF23283A))
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        DetailField(label = "Website Name", value = activeAccount.websiteName, isPrimary = true)
                        DetailField(label = "Website Address", value = activeAccount.websiteAddress)
                        DetailField(label = "Username", value = activeAccount.username)

                        Text(
                            text = "Secret Key (Seed, Shared Secret, ...)",
                            color = Color(0xFF7F869E),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (secretRevealed) activeAccount.secret else activeAccount.secret.take(3) + "••••••••••••",
                                color = Color(0xFFD0D4E4),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { secretRevealed = !secretRevealed }) {
                                Icon(
                                    imageVector = if (secretRevealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null,
                                    tint = Color(0xFFA6ADC8)
                                )
                            }
                            IconButton(onClick = { copyToClipboard(context, activeAccount.secret, showToast = true) }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Color(0xFFA6ADC8))
                            }
                        }
                    }
                }

                // -------------------------------------------------------------
                // Advanced Options Card (Clickable Row + Expanding Animation)
                // -------------------------------------------------------------
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF23283A))
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        // Entire row is clickable to expand/collapse
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    updateActiveAccount { it.copy(showAdvanced = !it.showAdvanced) }
                                },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Advanced Options",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Switch(
                                checked = activeAccount.showAdvanced,
                                onCheckedChange = { checked ->
                                    updateActiveAccount { it.copy(showAdvanced = checked) }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFF1E88E5),
                                    uncheckedThumbColor = Color.White,
                                    uncheckedTrackColor = Color(0xFF31364A)
                                )
                            )
                        }

                        // Smooth animated expansion
                        AnimatedVisibility(
                            visible = activeAccount.showAdvanced,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column(modifier = Modifier.padding(top = 16.dp)) {
                                // 1. Algorithm: SHA1 | SHA256 | SHA512
                                PillSelectorRow(
                                    label = "Algorithm",
                                    options = listOf("SHA1", "SHA256", "SHA512"),
                                    selected = activeAccount.algorithm,
                                    onSelect = { algo ->
                                        updateActiveAccount { it.copy(algorithm = algo) }
                                    }
                                )

                                Spacer(modifier = Modifier.height(14.dp))

                                // 2. Digits: 6 | 8
                                PillSelectorRow(
                                    label = "Digits",
                                    options = listOf("6", "8"),
                                    selected = activeAccount.digits.toString(),
                                    onSelect = { digitsStr ->
                                        updateActiveAccount { it.copy(digits = digitsStr.toIntOrNull() ?: 6) }
                                    }
                                )

                                Spacer(modifier = Modifier.height(14.dp))

                                // 3. Period: 15 | 30 | 60 | 120
                                PillSelectorRow(
                                    label = "Period",
                                    options = listOf("15", "30", "60", "120"),
                                    selected = activeAccount.period.toString(),
                                    onSelect = { periodStr ->
                                        updateActiveAccount { it.copy(period = periodStr.toIntOrNull() ?: 30) }
                                    }
                                )
                            }
                        }
                    }
                }

                // Delete Account Button
                Button(
                    onClick = {
                        accounts = accounts.toMutableList().also { it.removeAt(selectedIndex) }
                        selectedIndex = accounts.lastIndex.coerceAtLeast(0)
                        saveAccounts(context, accounts)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Remove Selected Account")
                }
            }
        }
    }

    // -------------------------------------------------------------
    // Manual Add Dialog (Fully Themed, No Material3 Purple)
    // -------------------------------------------------------------
    if (showManualAddDialog) {
        ManualAddDialog(
            onDismiss = { showManualAddDialog = false },
            onConfirm = { newAcc ->
                accounts = accounts + newAcc
                saveAccounts(context, accounts)
                selectedIndex = accounts.lastIndex
                showManualAddDialog = false
            }
        )
    }
}

// -------------------------------------------------------------
// Component Helpers
// -------------------------------------------------------------
@Composable
fun DetailField(label: String, value: String, isPrimary: Boolean = false) {
    Column {
        Text(text = label, color = Color(0xFF7F869E), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(
            text = value,
            color = if (isPrimary) Color.White else Color(0xFFD0D4E4),
            fontSize = if (isPrimary) 17.sp else 14.sp,
            fontWeight = if (isPrimary) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun PillSelectorRow(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = Color(0xFFD0D4E4), fontSize = 14.sp)
        Row(
            modifier = Modifier
                .border(1.dp, Color(0xFF2D3348), RoundedCornerShape(6.dp))
                .clip(RoundedCornerShape(6.dp))
        ) {
            options.forEach { opt ->
                val isSelected = opt.equals(selected, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .background(if (isSelected) Color(0xFF1E88E5) else Color(0xFF1A1E2D))
                        .clickable { onSelect(opt) }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = opt,
                        color = if (isSelected) Color.White else Color(0xFF9BA1B5),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
fun ManualAddDialog(onDismiss: () -> Unit, onConfirm: (Account) -> Unit) {
    var websiteName by remember { mutableStateOf("") }
    var websiteAddress by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }

    val customTextFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color(0xFFE4E7EE),
        focusedBorderColor = Color(0xFF1E88E5),
        unfocusedBorderColor = Color(0xFF333A54),
        focusedLabelColor = Color(0xFF64B5F6),
        unfocusedLabelColor = Color(0xFFA6ADC8),
        cursorColor = Color(0xFF1E88E5),
        focusedContainerColor = Color(0xFF1E2436),
        unfocusedContainerColor = Color(0xFF1A1F30)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF141824),
        title = {
            Text(
                text = "Add Account Manually",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = websiteName,
                    onValueChange = { websiteName = it },
                    label = { Text("Website Name (e.g. GitHub)") },
                    colors = customTextFieldColors,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = websiteAddress,
                    onValueChange = { websiteAddress = it },
                    label = { Text("Website Address") },
                    colors = customTextFieldColors,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    colors = customTextFieldColors,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it.uppercase().replace(" ", "") },
                    label = { Text("Secret Key (Base32)") },
                    colors = customTextFieldColors,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (secret.isNotBlank()) {
                        onConfirm(
                            Account(
                                websiteName = websiteName.ifBlank { "Custom" },
                                websiteAddress = websiteAddress.ifBlank { "-" },
                                username = username.ifBlank { "User" },
                                secret = secret
                            )
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF0D9488),
                    contentColor = Color.White
                )
            ) {
                Text("Add Account", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFFA6ADC8))
            }
        }
    )
}

// -------------------------------------------------------------
// Utilities: Storage, Clipboard, QR & TOTP Engine
// -------------------------------------------------------------
fun copyToClipboard(context: Context, text: String, showToast: Boolean) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("TOTP", text))
    if (showToast) {
        Toast.makeText(context, "Copied to clipboard!", Toast.LENGTH_SHORT).show()
    }
}

fun saveAccounts(context: Context, accounts: List<Account>) {
    val file = File(context.filesDir, "accounts.json")
    file.writeText(Gson().toJson(accounts))
}

fun loadAccounts(context: Context): List<Account> {
    val file = File(context.filesDir, "accounts.json")
    if (!file.exists()) return emptyList()
    return try {
        val type = object : TypeToken<List<Account>>() {}.type
        Gson().fromJson(file.readText(), type) ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }
}

fun decodeQrFromUri(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val bitmap = BitmapFactory.decodeStream(stream) ?: return null
            val intArray = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(intArray, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, intArray)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            MultiFormatReader().decode(binaryBitmap).text
        }
    } catch (e: Exception) {
        null
    }
}

fun parseOtpUri(uriString: String): Account? {
    val uri = uriString.trim()
    if (!uri.startsWith("otpauth://", ignoreCase = true)) {
        val clean = uri.uppercase().replace(" ", "")
        return if (clean.length >= 8) {
            Account(websiteName = "Custom Key", username = "Account", secret = clean)
        } else null
    }

    return try {
        val parsed = Uri.parse(uri)
        val secret = parsed.getQueryParameter("secret") ?: return null
        val issuer = parsed.getQueryParameter("issuer") ?: ""
        var name = parsed.path?.removePrefix("/") ?: "Account"

        var websiteName = issuer
        if (name.contains(":")) {
            val parts = name.split(":", limit = 2)
            if (websiteName.isBlank()) websiteName = parts[0]
            name = parts[1]
        }
        if (websiteName.isBlank()) websiteName = "Authenticator"

        val algorithm = parsed.getQueryParameter("algorithm")?.uppercase() ?: "SHA1"
        val digits = parsed.getQueryParameter("digits")?.toIntOrNull() ?: 6
        val period = parsed.getQueryParameter("period")?.toIntOrNull() ?: 30

        Account(
            websiteName = websiteName,
            websiteAddress = "https://${websiteName.lowercase().replace(" ", "")}.com",
            username = name,
            secret = secret.uppercase().replace(" ", ""),
            algorithm = if (algorithm in listOf("SHA1", "SHA256", "SHA512")) algorithm else "SHA1",
            digits = if (digits in listOf(6, 8)) digits else 6,
            period = if (period in listOf(15, 30, 60, 120)) period else 30
        )
    } catch (e: Exception) {
        null
    }
}

fun generateTotp(secret: String, digits: Int, period: Int, algorithm: String): String {
    return try {
        val keyBytes = decodeBase32(secret)
        val epochSeconds = System.currentTimeMillis() / 1000L
        val counter = epochSeconds / period

        val counterBytes = ByteBuffer.allocate(8).putLong(counter).array()
        val macAlgo = when (algorithm.uppercase(Locale.ROOT)) {
            "SHA256" -> "HmacSHA256"
            "SHA512" -> "HmacSHA512"
            else -> "HmacSHA1"
        }

        val mac = Mac.getInstance(macAlgo)
        mac.init(SecretKeySpec(keyBytes, macAlgo))
        val hash = mac.doFinal(counterBytes)

        val offset = hash.last().toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
                ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                (hash[offset + 3].toInt() and 0xFF)

        val otp = binary % 10.0.pow(digits.toDouble()).toInt()
        String.format(Locale.ROOT, "%0${digits}d", otp)
    } catch (e: Exception) {
        "ERROR"
    }
}

fun decodeBase32(input: String): ByteArray {
    val base32Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    val clean = input.uppercase().replace("=", "").replace(" ", "")
    val bytes = mutableListOf<Byte>()
    var buffer = 0
    var bitsLeft = 0

    for (c in clean) {
        val valIdx = base32Chars.indexOf(c)
        if (valIdx < 0) continue
        buffer = (buffer shl 5) or valIdx
        bitsLeft += 5
        if (bitsLeft >= 8) {
            bytes.add(((buffer shr (bitsLeft - 8)) and 0xFF).toByte())
            bitsLeft -= 8
        }
    }
    return bytes.toByteArray()
}

// -------------------------------------------------------------
// Theme
// -------------------------------------------------------------
@Composable
fun AuthenticatorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF1E88E5),
            secondary = Color(0xFF0D9488),
            surface = Color(0xFF141824),
            background = Color(0xFF0F121A)
        ),
        content = content
    )
}