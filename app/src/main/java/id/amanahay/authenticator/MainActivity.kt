package id.amanahay.authenticator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AuthenticatorApp() }
    }
}

data class OtpAccount(
    val issuer: String,
    val name: String,
    val secret: String,
    val algorithm: String = "SHA1",
    val digits: Int = 6,
    val period: Int = 30,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthenticatorApp() {
    val context = LocalContext.current
    val store = remember { AccountStore(context) }
    val accounts = remember { mutableStateListOf<OtpAccount>() }
    var showAddDialog by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { accounts.addAll(store.load()) }

    fun add(account: OtpAccount) {
        accounts.add(account)
        store.save(accounts)
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val content = result.contents ?: return@rememberLauncherForActivityResult
        val account = parseOtpAuthUri(content)
        if (account == null) message = "QR bukan kode TOTP yang didukung."
        else add(account)
    }

    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Android Authenticator") }) },
            floatingActionButton = {
                FloatingActionButton(onClick = { showAddDialog = true }) { Text("+") }
            },
        ) { padding ->
            if (accounts.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Belum ada akun", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { showAddDialog = true }) { Text("Tambah manual") }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        scanLauncher.launch(ScanOptions().setPrompt("Pindai QR TOTP").setBeepEnabled(false))
                    }) { Text("Pindai QR") }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item { Spacer(Modifier.height(2.dp)) }
                    items(accounts, key = { "${it.issuer}:${it.name}:${it.secret}" }) { account ->
                        OtpCard(account)
                    }
                    item {
                        Button(onClick = {
                            scanLauncher.launch(ScanOptions().setPrompt("Pindai QR TOTP").setBeepEnabled(false))
                        }, modifier = Modifier.fillMaxWidth()) { Text("Pindai QR baru") }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddAccountDialog(
            onDismiss = { showAddDialog = false },
            onSave = { account -> add(account); showAddDialog = false },
        )
    }
    message?.let { text ->
        AlertDialog(onDismissRequest = { message = null }, confirmButton = {
            Button(onClick = { message = null }) { Text("OK") }
        }, title = { Text("Tidak dapat menambahkan akun") }, text = { Text(text) })
    }
}

@Composable
private fun OtpCard(account: OtpAccount) {
    val context = LocalContext.current
    var code by remember { mutableStateOf(generateTotp(account)) }
    LaunchedEffect(account) {
        while (true) {
            code = generateTotp(account)
            kotlinx.coroutines.delay(1_000)
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(account.issuer.ifBlank { "TOTP" }, fontWeight = FontWeight.Bold)
                Text(account.name, style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("TOTP code", code))
            }) { Text(code) }
        }
    }
}

@Composable
private fun AddAccountDialog(onDismiss: () -> Unit, onSave: (OtpAccount) -> Unit) {
    var issuer by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tambah akun TOTP") },
        text = {
            Column {
                OutlinedTextField(issuer, { issuer = it }, label = { Text("Penerbit, mis. Google") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it }, label = { Text("Nama akun/email") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(secret, { secret = it }, label = { Text("Secret Base32") }, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (name.isBlank() || secret.isBlank()) error = "Nama dan secret wajib diisi."
                else if (decodeBase32(secret).isEmpty()) error = "Secret Base32 tidak valid."
                else onSave(OtpAccount(issuer.trim(), name.trim(), secret.trim()))
            }) { Text("Simpan") }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("Batal") } },
    )
}

private class AccountStore(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context,
        "encrypted_accounts",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun load(): List<OtpAccount> = runCatching {
        val array = JSONArray(preferences.getString("accounts", "[]"))
        List(array.length()) { index ->
            array.getJSONObject(index).let { value ->
                OtpAccount(value.getString("issuer"), value.getString("name"), value.getString("secret"), value.optString("algorithm", "SHA1"), value.optInt("digits", 6), value.optInt("period", 30))
            }
        }
    }.getOrDefault(emptyList())

    fun save(accounts: List<OtpAccount>) {
        val array = JSONArray()
        accounts.forEach { account -> array.put(JSONObject().apply {
            put("issuer", account.issuer); put("name", account.name); put("secret", account.secret)
            put("algorithm", account.algorithm); put("digits", account.digits); put("period", account.period)
        }) }
        preferences.edit().putString("accounts", array.toString()).apply()
    }
}

private fun parseOtpAuthUri(value: String): OtpAccount? = runCatching {
    val uri = Uri.parse(value)
    require(uri.scheme.equals("otpauth", true) && uri.host.equals("totp", true))
    val label = Uri.decode(uri.lastPathSegment ?: "")
    val issuer = uri.getQueryParameter("issuer") ?: label.substringBefore(":", "")
    val name = label.substringAfter(":", label)
    val secret = uri.getQueryParameter("secret") ?: error("Secret tidak ada")
    require(decodeBase32(secret).isNotEmpty())
    OtpAccount(issuer, name, secret, uri.getQueryParameter("algorithm")?.uppercase() ?: "SHA1", uri.getQueryParameter("digits")?.toIntOrNull() ?: 6, uri.getQueryParameter("period")?.toIntOrNull() ?: 30)
}.getOrNull()

private fun generateTotp(account: OtpAccount): String {
    val counter = System.currentTimeMillis() / 1_000L / account.period
    val data = ByteBuffer.allocate(8).putLong(counter).array()
    val algorithm = when (account.algorithm.uppercase()) { "SHA256" -> "HmacSHA256"; "SHA512" -> "HmacSHA512"; else -> "HmacSHA1" }
    val hash = Mac.getInstance(algorithm).doFinal(data, SecretKeySpec(decodeBase32(account.secret), algorithm))
    val offset = hash.last().toInt() and 0x0f
    val binary = ((hash[offset].toInt() and 0x7f) shl 24) or ((hash[offset + 1].toInt() and 0xff) shl 16) or ((hash[offset + 2].toInt() and 0xff) shl 8) or (hash[offset + 3].toInt() and 0xff)
    return (binary % 10.0.pow(account.digits).toInt()).toString().padStart(account.digits, '0')
}

private fun decodeBase32(input: String): ByteArray {
    val clean = input.uppercase().filter { it in 'A'..'Z' || it in '2'..'7' }
    if (clean.isEmpty()) return byteArrayOf()
    var buffer = 0
    var bits = 0
    val output = ArrayList<Byte>()
    clean.forEach { char ->
        buffer = (buffer shl 5) or (if (char in 'A'..'Z') char - 'A' else char - '2' + 26)
        bits += 5
        if (bits >= 8) { output += ((buffer shr (bits - 8)) and 0xff).toByte(); bits -= 8 }
    }
    return output.toByteArray()
}
