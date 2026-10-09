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

data class ChatMessage(
    val role: String,
    val content: String,
    val agent: String? = null
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
    var inputText by remember { mutableStateOf("") }
    var showSettingsDialog by remember { mutableStateOf(false) }

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
                            text = "Proxy: $proxyUrl | Model: $selectedModel",
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
                    .padding(horizontal = 8.dp, vertical = 4.dp),
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

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("explore", "librarian", "scout", "summary").forEach { agent ->
                        FilterChip(
                            selected = selectedAgent == agent,
                            onClick = { selectedAgent = agent },
                            label = { Text(agent, fontSize = 11.sp) }
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
                    onClick = {
                        if (inputText.isNotBlank()) {
                            messages.add(ChatMessage("user", inputText, selectedAgent))
                            val userMsg = inputText
                            inputText = ""

                            messages.add(
                                ChatMessage(
                                    "assistant",
                                    "Routing to [@$selectedAgent]\nProxy: $proxyUrl\nModel: $selectedModel\nRequest: \"$userMsg\"",
                                    selectedAgent
                                )
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6))
                ) {
                    Text("Send")
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
        if (!message.agent.isNullOrEmpty() && !isUser) {
            Text(
                text = "agent: @${message.agent}",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 2.dp)
            )
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
