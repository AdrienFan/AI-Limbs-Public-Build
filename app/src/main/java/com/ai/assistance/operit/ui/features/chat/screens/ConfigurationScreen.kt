package com.ai.assistance.operit.ui.features.chat.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.ApiKeyFormatValidator
import com.ai.assistance.operit.plugins.center.PluginChatModeRuntime
import com.ai.assistance.operit.ui.common.input.bringIntoViewOnImeFocus
import com.ai.assistance.operit.ui.features.chat.components.config.TokenInfoDialog
import com.ai.limbs.plugin.runtime.InProcessChatModeSlotIds
import kotlinx.coroutines.delay

/** Initial chat configuration surface. Plugin chat modes own their own cards and copy. */
@Composable
fun ConfigurationScreen(
    apiKey: String,
    isSaving: Boolean,
    onSaveApiKey: (String) -> Unit,
    onUseApiChat: () -> Unit,
    onUseChatMode: (String) -> Unit,
    onNavigateToTokenConfig: () -> Unit = {},
    onNavigateToModelConfig: () -> Unit = {}
) {
    var apiKeyInput by remember(apiKey) { mutableStateOf(apiKey) }
    var showApiKeyFormatError by remember { mutableStateOf(false) }
    var showTokenInfoDialog by remember { mutableStateOf(false) }
    val normalizedApiKey = ApiKeyFormatValidator.normalize(apiKeyInput)
    val hasEnteredToken = normalizedApiKey.isNotEmpty()
    val context = LocalContext.current

    val chatModes by produceState(
        initialValue = PluginChatModeRuntime.presentationBindings()
    ) {
        while (true) {
            value = PluginChatModeRuntime.presentationBindings()
            delay(1_000L)
        }
    }

    if (showTokenInfoDialog) {
        TokenInfoDialog(
            onDismiss = { showTokenInfoDialog = false },
            onConfirm = {
                showTokenInfoDialog = false
                onNavigateToTokenConfig()
            }
        )
    }

    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(id = R.string.config_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 20.sp
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = stringResource(id = R.string.config_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                fontSize = 14.sp
            )

            if (chatModes.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                chatModes.forEach { binding ->
                    key(binding.id) {
                        Box(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .clickable(enabled = !isSaving) {
                                        onUseChatMode(binding.provider.configurationTemplate())
                                    }
                        ) {
                            AndroidView(
                                modifier = Modifier.fillMaxWidth(),
                                factory = { baseContext ->
                                    binding.provider.createSlotView(
                                        InProcessChatModeSlotIds.CONFIGURATION_CARD,
                                        baseContext
                                    ) ?: android.widget.Space(baseContext)
                                }
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HorizontalDivider(modifier = Modifier.weight(1f))
                    Text(
                        text = "API",
                        modifier = Modifier.padding(horizontal = 10.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider(modifier = Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(12.dp))
            } else {
                Spacer(modifier = Modifier.height(16.dp))
            }

            OutlinedButton(
                onClick = onUseApiChat,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text("API Chat", fontWeight = FontWeight.Medium)
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = {
                    apiKeyInput = it
                    showApiKeyFormatError = false
                },
                label = {
                    Text(
                        stringResource(id = R.string.config_api_key_label),
                        fontSize = 12.sp
                    )
                },
                placeholder = {
                    Text(
                        stringResource(id = R.string.config_api_key_placeholder),
                        fontSize = 12.sp
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = stringResource(id = R.string.config_api_key_label),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().bringIntoViewOnImeFocus(),
                shape = RoundedCornerShape(6.dp),
                isError = showApiKeyFormatError,
                supportingText =
                    if (showApiKeyFormatError) {
                        {
                            Text(
                                text = stringResource(R.string.config_api_key_invalid_format)
                            )
                        }
                    } else null,
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor =
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                    ),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    if (hasEnteredToken) {
                        if (ApiKeyFormatValidator.isValid(normalizedApiKey)) {
                            onSaveApiKey(normalizedApiKey)
                        } else {
                            showApiKeyFormatError = true
                        }
                    } else {
                        showTokenInfoDialog = true
                    }
                },
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(6.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor =
                            if (hasEnteredToken) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        contentColor =
                            if (hasEnteredToken) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimary
                            }
                    )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = LocalContentColor.current,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    } else if (hasEnteredToken) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        if (isSaving) {
                            stringResource(R.string.config_saving)
                        } else if (hasEnteredToken) {
                            stringResource(id = R.string.config_save_button)
                        } else {
                            stringResource(id = R.string.config_get_token)
                        },
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onNavigateToModelConfig,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    stringResource(id = R.string.config_custom),
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
            }
        }
    }
}
