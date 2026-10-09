package ai.opencode.mobile

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private val okHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

private val gson = Gson()

fun resolveChatEndpoint(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    return when {
        trimmed.endsWith("/chat/completions") -> trimmed
        trimmed.endsWith("/api/chat") -> trimmed
        trimmed.endsWith("/v1") -> "$trimmed/chat/completions"
        trimmed.endsWith("/api") -> "$trimmed/chat"
        else -> "$trimmed/v1/chat/completions"
    }
}

fun sanitizeModelName(model: String, targetUrl: String): String {
    if (!targetUrl.contains("/api/chat") && model.startsWith("bifrost-")) {
        return model.removePrefix("bifrost-")
    }
    return model
}

data class ChatMessage(
    val role: String,
    val content: String,
    val agent: String? = null,
    val mode: String? = null
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF3B82F6),
                    background = Color(0xFF0F172A),
                    surface = Color(0xFF1E293B),
                    onBackground = Color(0xFFF8FAFC),
                    onSurface = Color(0xFFF8FAFC)
                )
            ) {
                OpenCodeAppScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenCodeAppScreen() {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("opencode_config", Context.MODE_PRIVATE) }

    var proxyUrl by remember {
        mutableStateOf(sharedPrefs.getString("proxy_url", "http://127.0.0.1:4000/v1") ?: "http://127.0.0.1:4000/v1")
    }
    var apiKey by remember {
        mutableStateOf(sharedPrefs.getString("api_key", "placeholder-token") ?: "placeholder-token")
    }
    var selectedModel by remember {
        mutableStateOf(sharedPrefs.getString("selected_model", "bifrost-gemini/gemini-3.6-flash") ?: "bifrost-gemini/gemini-3.6-flash")
    }

    var selectedAgent by remember { mutableStateOf("explore") }
    var selectedMode by remember {
        mutableStateOf(sharedPrefs.getString("selected_mode", "standard") ?: "standard")
    }
    var inputText by remember { mutableStateOf("") }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    val messages = remember {
        mutableStateListOf(
            ChatMessage("assistant", "Welcome to OpenCode Mobile!\nProxy URL: $proxyUrl\nActive Model: $selectedModel")
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("OpenCode Mobile", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            text = "Proxy: $proxyUrl | Mode: $selectedMode",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8),
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { showSettingsDialog = true }) {
                        Text("⚙ Settings", color = Color(0xFF60A5FA), fontWeight = FontWeight.SemiBold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1E293B),
                    titleContentColor = Color.White
                )
            )
        },
        containerColor = Color(0xFF0F172A)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Agent selector bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF182232))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Agent: @$selectedAgent",
                    color = Color(0xFF60A5FA),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Row(
                    modifier = Modifier.padding(start = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf("explore", "librarian", "oracle", "metis", "momus", "artistry", "ultrabrain").forEach { agent ->
                        FilterChip(
                            selected = selectedAgent == agent,
                            onClick = { selectedAgent = agent },
                            label = { Text(agent, fontSize = 10.sp) }
                        )
                    }
                }
            }

            // Mode selector bar (oh-my-openagent modes)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Mode: $selectedMode",
                    color = Color(0xFFF59E0B),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Row(
                    modifier = Modifier.padding(start = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf("standard", "ultrawork", "architect", "deep-research").forEach { mode ->
                        FilterChip(
                            selected = selectedMode == mode,
                            onClick = {
                                selectedMode = mode
                                sharedPrefs.edit().putString("selected_mode", mode).apply()
                            },
                            label = { Text(mode, fontSize = 10.sp) }
                        )
                    }
                }
            }

            // Message list
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages) { msg ->
                    ChatBubble(msg)
                }
            }

            // Input bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E293B))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Ask OpenCode...", color = Color.Gray) },
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF3B82F6),
                        unfocusedBorderColor = Color(0xFF475569),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    maxLines = 4
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    enabled = !isSending,
                    onClick = {
                        if (inputText.isNotBlank() && !isSending) {
                            val userPrompt = inputText
                            inputText = ""
                            isSending = true

                            messages.add(ChatMessage("user", userPrompt, selectedAgent, selectedMode))
                            val assistantMessageIndex = messages.size
                            messages.add(ChatMessage("assistant", "Thinking...", selectedAgent, selectedMode))

                            coroutineScope.launch(Dispatchers.IO) {
                                try {
                                    val targetUrl = resolveChatEndpoint(proxyUrl)
                                    val jsonMediaType = "application/json; charset=utf-8".toMediaType()

                                    val requestPayload = JsonObject().apply {
                                        addProperty("agent", selectedAgent)
                                        addProperty("mode", selectedMode)
                                        if (targetUrl.contains("/api/chat")) {
                                            addProperty("modelOverride", selectedModel)
                                            val msgsArray = JsonArray()
                                            messages.filter { (it.role == "user" || it.role == "assistant") && it.content != "Thinking..." }
                                                .forEach { msg ->
                                                    msgsArray.add(JsonObject().apply {
                                                        addProperty("role", msg.role)
                                                        addProperty("content", msg.content)
                                                    })
                                                }
                                            add("messages", msgsArray)
                                        } else {
                                            addProperty("model", sanitizeModelName(selectedModel, targetUrl))
                                            addProperty("stream", false)
                                            val msgsArray = JsonArray()
                                            messages.filter { (it.role == "user" || it.role == "assistant") && it.content != "Thinking..." }
                                                .forEach { msg ->
                                                    msgsArray.add(JsonObject().apply {
                                                        addProperty("role", msg.role)
                                                        addProperty("content", msg.content)
                                                    })
                                                }
                                            add("messages", msgsArray)
                                        }
                                    }

                                    val body = requestPayload.toString().toRequestBody(jsonMediaType)
                                    val requestBuilder = Request.Builder()
                                        .url(targetUrl)
                                        .post(body)

                                    if (apiKey.isNotBlank() && apiKey != "placeholder-token") {
                                        requestBuilder.addHeader("Authorization", "Bearer $apiKey")
                                    }

                                    val response = okHttpClient.newCall(requestBuilder.build()).execute()
                                    val responseBodyStr = response.body?.string() ?: ""

                                    val finalResponseText = if (!response.isSuccessful) {
                                        "Error [HTTP ${response.code}]: ${responseBodyStr.ifBlank { response.message }}"
                                    } else {
                                        try {
                                            val parsed = gson.fromJson(responseBodyStr, JsonObject::class.java)
                                            if (parsed.has("choices") && parsed.getAsJsonArray("choices").size() > 0) {
                                                val choice = parsed.getAsJsonArray("choices")[0].asJsonObject
                                                if (choice.has("message") && choice.getAsJsonObject("message").has("content")) {
                                                    choice.getAsJsonObject("message").get("content").asString
                                                } else if (choice.has("delta") && choice.getAsJsonObject("delta").has("content")) {
                                                    choice.getAsJsonObject("delta").get("content").asString
                                                } else {
                                                    responseBodyStr
                                                }
                                            } else if (parsed.has("content")) {
                                                parsed.get("content").asString
                                            } else if (parsed.has("text")) {
                                                parsed.get("text").asString
                                            } else {
                                                responseBodyStr
                                            }
                                        } catch (e: Exception) {
                                            responseBodyStr
                                        }
                                    }

                                    withContext(Dispatchers.Main) {
                                        if (assistantMessageIndex < messages.size) {
                                            messages[assistantMessageIndex] = ChatMessage("assistant", finalResponseText, selectedAgent, selectedMode)
                                        }
                                        isSending = false
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        val errorText = "Network Error: ${e.localizedMessage ?: "Failed to connect to $proxyUrl"}"
                                        if (assistantMessageIndex < messages.size) {
                                            messages[assistantMessageIndex] = ChatMessage("assistant", errorText, selectedAgent, selectedMode)
                                        }
                                        isSending = false
                                    }
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6))
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Send")
                    }
                }
            }
        }
    }

    // Settings Modal Dialog
    if (showSettingsDialog) {
        var tempProxyUrl by remember { mutableStateOf(proxyUrl) }
        var tempApiKey by remember { mutableStateOf(apiKey) }
        var tempModel by remember { mutableStateOf(selectedModel) }

        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("OpenCode Engine Settings", fontWeight = FontWeight.Bold, color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Configure your Proxy Base URL and Model credentials below:", fontSize = 13.sp, color = Color.LightGray)

                    OutlinedTextField(
                        value = tempProxyUrl,
                        onValueChange = { tempProxyUrl = it },
                        label = { Text("Proxy Base URL") },
                        placeholder = { Text("e.g. http://127.0.0.1:4000/v1") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = tempApiKey,
                        onValueChange = { tempApiKey = it },
                        label = { Text("API Key / Bearer Token") },
                        placeholder = { Text("placeholder-token") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = tempModel,
                        onValueChange = { tempModel = it },
                        label = { Text("Default Model Target") },
                        placeholder = { Text("bifrost-gemini/gemini-3.6-flash") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        proxyUrl = tempProxyUrl
                        apiKey = tempApiKey
                        selectedModel = tempModel

                        sharedPrefs.edit()
                            .putString("proxy_url", tempProxyUrl)
                            .putString("api_key", tempApiKey)
                            .putString("selected_model", tempModel)
                            .apply()

                        showSettingsDialog = false
                    }
                ) {
                    Text("Save Config")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF1E293B),
            titleContentColor = Color.White,
            textContentColor = Color.White
        )
    }
}

@Composable
fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    val bgColor = if (isUser) Color(0xFF2563EB) else Color(0xFF1E293B)
    val alignment = if (isUser) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        if (!isUser && (!message.agent.isNullOrEmpty() || !message.mode.isNullOrEmpty())) {
            val agentLabel = if (!message.agent.isNullOrEmpty()) "@${message.agent}" else ""
            val modeLabel = if (!message.mode.isNullOrEmpty() && message.mode != "standard") "⚡ ${message.mode}" else ""
            val tagText = listOf(agentLabel, modeLabel).filter { it.isNotBlank() }.joinToString(" | ")

            if (tagText.isNotBlank()) {
                Text(
                    text = tagText,
                    color = Color(0xFFF59E0B),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
        Box(
            modifier = Modifier
                .background(bgColor, shape = RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Text(
                text = message.content,
                color = Color.White,
                fontSize = 14.sp
            )
        }
    }
}
