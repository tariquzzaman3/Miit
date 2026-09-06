package com.miit.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object MiitSettingsStore {
    private const val PREF = "miit_settings"
    fun theme(context: Context): String = context.getSharedPreferences(PREF, 0).getString("theme", "system") ?: "system"
    fun setTheme(context: Context, value: String) { context.getSharedPreferences(PREF, 0).edit().putString("theme", value).apply() }
    fun aiProvider(context: Context): String = context.getSharedPreferences(PREF, 0).getString("ai_provider", "") ?: ""
    fun setAiProvider(context: Context, value: String) { context.getSharedPreferences(PREF, 0).edit().putString("ai_provider", value).apply() }
    fun aiApiKey(context: Context): String = AiKeyStore.get(context)
    fun setAiApiKey(context: Context, value: String) { AiKeyStore.put(context, value) }
}

private object AiKeyStore {
    private const val PREF = "miit_ai_secret"
    private const val ALIAS = "miit_ai_api_key"
    fun put(context: Context, value: String) {
        if (value.isBlank()) { context.getSharedPreferences(PREF, 0).edit().remove("data").apply(); return }
        runCatching {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            var key = ks.getKey(ALIAS, null) as? javax.crypto.SecretKey
            if (key == null) {
                val gen = javax.crypto.KeyGenerator.getInstance("AES", "AndroidKeyStore")
                gen.init(android.security.keystore.KeyGenParameterSpec.Builder(ALIAS, 3).setBlockModes("GCM").setEncryptionPaddings("NoPadding").build())
                key = gen.generateKey()
            }
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
            val packed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            context.getSharedPreferences(PREF, 0).edit().putString("data", android.util.Base64.encodeToString(packed, 2)).apply()
        }
    }
    fun get(context: Context): String = runCatching {
        val raw = context.getSharedPreferences(PREF, 0).getString("data", null) ?: return ""
        val packed = android.util.Base64.decode(raw, 2)
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = ks.getKey(ALIAS, null) as javax.crypto.SecretKey
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, packed.copyOfRange(0, 12)))
        String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8)
    }.getOrDefault("")
}

data class GithubLatest(val tag: String, val url: String)

object GithubRelease {
    fun latest(): Result<GithubLatest> = runCatching {
        val c = URL("https://api.github.com/repos/tariquzzaman3/Miit/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000; c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (c.responseCode !in 200..299) error("GitHub HTTP ${c.responseCode}")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val j = JSONObject(body)
            GithubLatest(j.optString("tag_name"), j.optString("html_url", "https://github.com/tariquzzaman3/Miit/releases/latest"))
        } finally { c.disconnect() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiitSettingsScreen(
    themeMode: String,
    onThemeChange: (String) -> Unit,
    aiProvider: String,
    onAiProviderChange: (String) -> Unit,
    aiApiKey: String,
    onAiApiKeyChange: (String) -> Unit,
    onBack: () -> Unit,
    onGithub: () -> Unit,
    onDocs: () -> Unit,
    onRelease: () -> Unit
) {
    val context = LocalContext.current
    var key by remember(aiApiKey) { mutableStateOf(aiApiKey) }
    var updateText by remember { mutableStateOf("") }
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onClick = onBack) { Text("‹") } }) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("Appearance", style = MaterialTheme.typography.titleLarge)
                Text("Choose how MIIT looks.", color = Color.Gray, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                    listOf("light" to "White", "dark" to "Dark", "system" to "System").forEach { (v, n) ->
                        OutlinedButton(onClick = { onThemeChange(v) }) { Text(if (themeMode == v) "✓ $n" else n) }
                    }
                }
            }
            item {
                Text("AI", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(aiProvider, onAiProviderChange, Modifier.fillMaxWidth(), label = { Text("Provider") }, placeholder = { Text("OpenAI / Gemini / OpenRouter") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(key, { key = it; onAiApiKeyChange(it) }, Modifier.fillMaxWidth(), label = { Text("API key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                Text("Stored using Android Keystore encryption.", color = Color.Gray, fontSize = 10.sp)
            }
            item {
                Text("GitHub & updates", style = MaterialTheme.typography.titleLarge)
                OutlinedButton(onClick = onGithub, Modifier.fillMaxWidth()) { Text("GitHub repository") }
                OutlinedButton(onClick = onDocs, Modifier.fillMaxWidth()) { Text("Documentation") }
                OutlinedButton(onClick = onRelease, Modifier.fillMaxWidth()) { Text("Open latest release") }
                Button(onClick = {
                    updateText = "Checking…"
                    Thread {
                        val result = GithubRelease.latest()
                        val message = result.fold({ if (it.tag == "0.1.0") "MIIT is up to date (${it.tag})." else "Update available: ${it.tag}." }, { "Update check failed." })
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            updateText = message
                            result.getOrNull()?.let { if (it.tag != BuildConfig.VERSION_NAME) onRelease() }
                        }
                    }.start()
                }, Modifier.fillMaxWidth()) { Text("Check for updates") }
                if (updateText.isNotBlank()) Text(updateText, color = Color.Gray, fontSize = 11.sp)
            }
            item {
                Text("About", style = MaterialTheme.typography.titleLarge)
                Text("MIIT ${BuildConfig.VERSION_NAME}", color = Color.Gray)
                Text("Xiaomi Band watch-face editor and connection tools.", color = Color.Gray, fontSize = 12.sp)
            }
        }
    }
}