package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.ApiKeyConfig
import chat.ChatContextBuilder
import chat.ChatMessage
import chat.ChatSender
import chat.ChatbotProvider
import chat.SmartChatbotProvider
import context.ExpectedState
import kotlinx.coroutines.launch
import overlay.OverlayManager
import tutor.TutorController

@Composable
fun ChatbotPanel(
    controller: TutorController,
    overlayManager: OverlayManager? = null,
    onAutoVerifyAction: ((ExpectedState) -> Unit)? = null,
    onCaptureScreen: (() -> Unit)? = null,
    chatbotProvider: ChatbotProvider = controller.conversationCoordinator.chatbotProvider,
    contextBuilder: ChatContextBuilder = controller.conversationCoordinator.contextBuilder,
    onRequestScreenshotHelp: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val coordinator = controller.conversationCoordinator
    val messages = coordinator.messages

    var inputText by remember { mutableStateOf("") }
    val isThinking = coordinator.isThinking
    var attachVisualContext by remember { mutableStateOf(false) }
    var showKeyInput by remember { mutableStateOf(false) }
    var keyInputValue by remember { mutableStateOf("") }
    var keyType by remember { mutableStateOf("GROQ") } // GROQ or GEMINI
    var activeProviderName by remember { mutableStateOf(ApiKeyConfig.getActiveProvider()) }
    val listState = rememberLazyListState()

    val currentStep = controller.currentStep

    fun sendUserQuery(query: String, autoHighlight: Boolean = false) {
        val trimmed = query.trim()
        if (trimmed.isBlank() || isThinking) return

        inputText = ""

        val lower = trimmed.lowercase()
        val isGoalQuery = lower.startsWith("goal:") ||
            lower.startsWith("plan:") ||
            lower.startsWith("i want to") ||
            lower.startsWith("i need to") ||
            (lower.startsWith("create ") && (lower.contains("sphere") || lower.contains("cube"))) ||
            (lower.startsWith("make ") && (lower.contains("sphere") || lower.contains("cube")))

        if (isGoalQuery) {
            val goalClean = trimmed.replace(Regex("""^(goal:|plan:|i want to|i need to)\s*""", RegexOption.IGNORE_CASE), "").trim()
            val finalGoal = if (goalClean.isNotBlank()) goalClean else trimmed
            coordinator.addMessage(
                ChatMessage(
                    sender = ChatSender.USER,
                    text = trimmed,
                    stepIndex = controller.state.currentStepIndex,
                    source = chat.ConversationSource.WORKSPACE_PANEL
                )
            )
            val plan = controller.startUserGoal(finalGoal)
            if (plan != null) {
                val firstStep = plan.steps.first()
                val planStepsText = plan.steps.mapIndexed { idx, s -> "${idx + 1}. ${s.title}" }.joinToString("\n")
                val tutorMsg = ChatMessage(
                    sender = ChatSender.TUTOR,
                    text = "🎯 Generated a ${plan.steps.size}-step plan for \"${plan.goalText}\":\n\n$planStepsText\n\n👉 Step 1: ${firstStep.instruction}",
                    targetControl = firstStep.targetControlName,
                    suggestedAction = if (firstStep.expectedState != null) "AUTO_VERIFY" else null,
                    stepIndex = 0,
                    source = chat.ConversationSource.WORKSPACE_PANEL
                )
                coordinator.addMessage(tutorMsg)
                coroutineScope.launch {
                    if (firstStep.targetControlName != null) {
                        controller.resolveTargetForName(firstStep.targetControlName)
                        overlayManager?.showOverlay()
                    }
                    listState.animateScrollToItem((messages.size - 1).coerceAtLeast(0))
                }
            }
            return
        }

        coroutineScope.launch {
            coordinator.askTutor(
                query = trimmed,
                source = chat.ConversationSource.WORKSPACE_PANEL,
                attachVisualContext = attachVisualContext,
                onResponseAction = { response ->
                    if (autoHighlight || response.targetControl != null) {
                        val targetName = response.targetControl ?: currentStep?.targetControlName
                        if (!targetName.isNullOrBlank()) {
                            controller.resolveTargetForName(targetName)
                            overlayManager?.showOverlay()
                        }
                    }
                }
            )
            listState.animateScrollToItem((messages.size - 1).coerceAtLeast(0))
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(vertical = 4.dp)
    ) {
        // API Key Status & Configuration Banner
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            if (activeProviderName != "Offline") Color(0xFF10B981) else Color(0xFFF59E0B),
                            CircleShape
                        )
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = when (activeProviderName) {
                        "Groq" -> "Groq Ultra-Fast AI (Active)"
                        "Gemini" -> "Gemini Multimodal (Active)"
                        else -> "Offline Tutor (.env key ready)"
                    },
                    color = if (activeProviderName != "Offline") Color(0xFF34D399) else Color(0xFFAAB0BD),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Text(
                text = if (showKeyInput) "Close" else if (activeProviderName != "Offline") "⚙️ Key Active" else "Config Key",
                color = if (activeProviderName != "Offline") Color(0xFF34D399) else Color(0xFFA78BFA),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { showKeyInput = !showKeyInput }
            )
        }

        if (showKeyInput) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .background(if (keyType == "GROQ") Color(0xFF6366F1) else Color(0xFF2D323F), RoundedCornerShape(4.dp))
                        .clickable { keyType = "GROQ" }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Text("Groq", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Box(
                    modifier = Modifier
                        .background(if (keyType == "GEMINI") Color(0xFF6366F1) else Color(0xFF2D323F), RoundedCornerShape(4.dp))
                        .clickable { keyType = "GEMINI" }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Text("Gemini", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedTextField(
                    value = keyInputValue,
                    onValueChange = { keyInputValue = it },
                    placeholder = { Text("Paste new $keyType key", color = Color(0xFF6B7280), fontSize = 11.sp) },
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        textColor = Color.White,
                        focusedBorderColor = Color(0xFF8B5CF6),
                        unfocusedBorderColor = Color(0xFF3A3F4B)
                    ),
                    singleLine = true
                )
                TutorButton(
                    text = "Save",
                    onClick = {
                        if (keyInputValue.isNotBlank()) {
                            if (keyType == "GROQ") {
                                ApiKeyConfig.setRuntimeGroqKey(keyInputValue)
                            } else {
                                ApiKeyConfig.setRuntimeKey(keyInputValue)
                            }
                            activeProviderName = ApiKeyConfig.getActiveProvider()
                            showKeyInput = false
                            keyInputValue = ""
                        }
                    },
                    enabled = keyInputValue.isNotBlank()
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Stuck & Live Action Guidance Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1.2f)
                    .background(Color(0xFF1E3A8A), RoundedCornerShape(6.dp))
                    .clickable {
                        coroutineScope.launch {
                            controller.resolveDynamicTarget()
                            overlayManager?.showOverlay()
                            val diag = controller.diagnoseCurrentStep()
                            if (diag.hasMismatch && diag.primaryMismatch != null) {
                                messages.add(
                                    ChatMessage(
                                        sender = ChatSender.TUTOR,
                                        text = "🔍 State Diagnosis: ${diag.primaryMismatch.explanation}\n\n💡 Corrective Action: ${diag.primaryMismatch.suggestedAction.description}",
                                        targetControl = currentStep?.targetControlName,
                                        suggestedAction = "AUTO_VERIFY"
                                    )
                                )
                                listState.animateScrollToItem((messages.size - 1).coerceAtLeast(0))
                            } else {
                                val stuckPrompt = if (currentStep != null) {
                                    "I'm stuck on Step ${currentStep.stepNumber}: '${currentStep.title}'. How do I locate '${currentStep.targetControlName ?: "the target tool"}' in Blender?"
                                } else {
                                    "I'm stuck and need help getting started in Blender."
                                }
                                sendUserQuery(
                                    query = stuckPrompt,
                                    autoHighlight = true
                                )
                            }
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "🆘 I'm Stuck",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(Color(0xFF374151), RoundedCornerShape(6.dp))
                    .clickable {
                        coroutineScope.launch {
                            val res = controller.resolveDynamicTarget()
                            overlayManager?.showOverlay()
                            val targetDesc = res.candidateName ?: currentStep?.targetControlName ?: "Screen Area"
                            val stepInfo = if (currentStep != null) "Step ${currentStep.stepNumber} ('${currentStep.title}')" else "Active View"
                            messages.add(
                                ChatMessage(
                                    sender = ChatSender.TUTOR,
                                    text = "✨ Target illuminated for $stepInfo: $targetDesc.",
                                    targetControl = res.candidateName ?: currentStep?.targetControlName
                                )
                            )
                            listState.animateScrollToItem((messages.size - 1).coerceAtLeast(0))
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✨ Show Glow",
                    color = Color(0xFFFDE047),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (currentStep?.expectedState != null) {
                Box(
                    modifier = Modifier
                        .weight(1.1f)
                        .background(Color(0xFF065F46), RoundedCornerShape(6.dp))
                        .clickable {
                            val exp = currentStep?.expectedState
                            exp?.let { onAutoVerifyAction?.invoke(it) }
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "🎯 Auto-Track",
                        color = Color(0xFFA7F3D0),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Message history list
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF1E2128), RoundedCornerShape(8.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages) { msg ->
                val isUser = msg.sender == ChatSender.USER
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (isUser) Color(0xFF6D28D9) else Color(0xFF333842),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = msg.text,
                            color = Color.White,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }

                    // Direct Interactive UI Directives: Glow Highlight and Action Tracking
                    if (!isUser) {
                        Row(
                            modifier = Modifier
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (!msg.targetControl.isNullOrBlank()) {
                                Box(
                                    modifier = Modifier
                                        .background(Color(0xFF1E1B4B), RoundedCornerShape(4.dp))
                                        .border(1.dp, Color(0xFF818CF8), RoundedCornerShape(4.dp))
                                        .clickable {
                                            coroutineScope.launch {
                                                controller.resolveTargetForName(msg.targetControl)
                                                overlayManager?.showOverlay()
                                            }
                                        }
                                        .padding(horizontal = 6.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = "✨ Glow '${msg.targetControl}'",
                                        color = Color(0xFFC7D2FE),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            if (msg.suggestedAction == "AUTO_VERIFY" || currentStep?.expectedState != null) {
                                Box(
                                    modifier = Modifier
                                        .background(Color(0xFF064E3B), RoundedCornerShape(4.dp))
                                        .border(1.dp, Color(0xFF34D399), RoundedCornerShape(4.dp))
                                        .clickable {
                                            val exp = currentStep?.expectedState
                                            if (exp != null) {
                                                onAutoVerifyAction?.invoke(exp)
                                            }
                                        }
                                        .padding(horizontal = 6.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = "🎯 Track Action in App",
                                        color = Color(0xFFA7F3D0),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (isThinking) {
                item {
                    Text(
                        text = "Tutor is thinking...",
                        color = Color(0xFFAAB0BD),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(4.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Visual Context toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { attachVisualContext = !attachVisualContext },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(14.dp)
                    .height(14.dp)
                    .background(
                        color = if (attachVisualContext) Color(0xFF8B5CF6) else Color.Transparent,
                        shape = RoundedCornerShape(3.dp)
                    )
                    .border(1.dp, Color(0xFF6B7280), RoundedCornerShape(3.dp))
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Attach screen crop to question",
                color = Color(0xFFAAB0BD),
                fontSize = 11.sp
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Input row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("Ask a question about Blender...", color = Color(0xFF6B7280), fontSize = 12.sp) },
                modifier = Modifier.weight(1f),
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    textColor = Color.White,
                    focusedBorderColor = Color(0xFF8B5CF6),
                    unfocusedBorderColor = Color(0xFF3A3F4B),
                    cursorColor = Color(0xFFA78BFA)
                ),
                singleLine = true
            )

            TutorButton(
                text = "Goal",
                onClick = {
                    sendUserQuery("goal: $inputText")
                },
                enabled = inputText.isNotBlank() && !isThinking
            )

            TutorButton(
                text = "Send",
                onClick = {
                    sendUserQuery(inputText)
                },
                enabled = inputText.isNotBlank() && !isThinking
            )
        }
    }
}
