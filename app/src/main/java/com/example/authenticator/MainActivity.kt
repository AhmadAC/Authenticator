package com.example.authenticator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
// Data Model
// -------------------------------------------------------------
data class Account(
    var websiteName: String = "Unknown",
    var websiteAddress: String = "-",
    var username: String = "-",
    var secret: String = "",
    var algorithm: String = "SHA1",
    var digits: Int = 6,
    var period: Int = 30,
    var showAdvanced: Boolean = false
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
                Toast.makeText(context, "No QR Code found in image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 1-second interval loop for TOTP calculation & auto-clipboard
    LaunchedEffect(activeAccount, selectedIndex) {
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

                // Auto copy to clipboard on rotation or account select
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
            // Header / Title
            Text(
                text = "Accounts",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )

            // Horizontal Accounts Selector
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
                                Text("ACTIVE", color = Color(0xFF1E88E5), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Quick Action Buttons
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { imagePicker.launch("image/*") },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Select QR Image", fontSize = 13.sp)
                }
                Button(
                    onClick = { showManualAddDialog = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF23283C)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Add Manually", fontSize = 13.sp)
                }
            }

            // -------------------------------------------------------------
            // Active Account Code Card
            // -------------------------------------------------------------
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF23283A))),
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

            // -------------------------------------------------------------
            // Account Details Card (Matching Screenshot)
            // -------------------------------------------------------------
            if (activeAccount != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF23283A))),
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

                        // Secret Key Field with reveal & copy
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
                // Advanced Options Card (Matching Screenshot)
                // -------------------------------------------------------------
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF23283A))),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
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
                                    activeAccount.showAdvanced = checked
                                    saveAccounts(context, accounts)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFF1E88E5),
                                    uncheckedThumbColor = Color.White,
                                    uncheckedTrackColor = Color(0xFF31364A)
                                )
                            )
                        }

                        if (activeAccount.showAdvanced) {
                            Spacer(modifier = Modifier.height(14.dp))

                            // Algorithm: SHA1 | SHA256 | SHA512
                            PillSelectorRow(
                                label = "Algorithm",
                                options = listOf("SHA1", "SHA256", "SHA512"),
                                selected = activeAccount.algorithm,
                                onSelect = {
                                    activeAccount.algorithm = it
                                    saveAccounts(context, accounts)
                                }
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // Digits: 6 | 8
                            PillSelectorRow(
                                label = "Digits",
                                options = listOf("6", "8"),
                                selected = activeAccount.digits.toString(),
                                onSelect = {
                                    activeAccount.digits = it.toInt()
                                    saveAccounts(context, accounts)
                                }
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // Period: 15 | 30 | 60 | 120
                            PillSelectorRow(
                                label = "Period",
                                options = listOf("15", "30", "60", "120"),
                                selected = activeAccount.period.toString(),
                                onSelect = {
                                    activeAccount.period = it.toInt()
                                    saveAccounts(context, accounts)
                                }
                            )
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

    // Manual Add Dialog
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

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF141824),
        title = { Text("Add Account Manually", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = websiteName,
                    onValueChange = { websiteName = it },
                    label = { Text("Website Name (e.g. GitHub)") }
                )
                OutlinedTextField(
                    value = websiteAddress,
                    onValueChange = { websiteAddress = it },
                    label = { Text("Website Address") }
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") }
                )
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it.uppercase().replace(" ", "") },
                    label = { Text("Secret Key (Base32)") }
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
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5))
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.White)
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
        // Plain Base32 fallback
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

// RFC 6238 / RFC 4226 pure Kotlin TOTP generator
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

// RFC 4648 Base32 Decoder
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
// Compose Theme
// -------------------------------------------------------------
@Composable
fun AuthenticatorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF1E88E5),
            surface = Color(0xFF141824),
            background = Color(0xFF0F121A)
        ),
        content = content
    )
}