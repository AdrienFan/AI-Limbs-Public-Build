package com.ai.assistance.operit.ui.features.chat.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.ApiKeyFormatValidator
import com.ai.assistance.operit.ui.common.input.bringIntoViewOnImeFocus
import com.ai.assistance.operit.ui.features.chat.components.config.TokenInfoDialog
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgePhase
import com.ai.assistance.operit.integrations.ailimbs.chat.LanerChatPresenceState

/** 简洁风格的AI助手配置界面 */
@Composable
fun ConfigurationScreen(
        apiKey: String,
        isSaving: Boolean,
        bridgePhase: AiLimbsBridgePhase,
        bridgeAgentPresence: LanerChatPresenceState,
        bridgePendingCount: Int,
        onSaveApiKey: (String) -> Unit,
        onUseApiChat: () -> Unit,
        onUseLanerBridge: () -> Unit,
        onNavigateToTokenConfig: () -> Unit = {},
        onNavigateToModelConfig: () -> Unit = {}
) {
        var apiKeyInput by remember(apiKey) { mutableStateOf(apiKey) }
        var showApiKeyFormatError by remember { mutableStateOf(false) }
        var showTokenInfoDialog by remember { mutableStateOf(false) }
        val normalizedApiKey = ApiKeyFormatValidator.normalize(apiKeyInput)
        val hasEnteredToken = normalizedApiKey.isNotEmpty()

        // 密钥信息对话框
        if (showTokenInfoDialog) {
                TokenInfoDialog(
                        onDismiss = { showTokenInfoDialog = false },
                        onConfirm = {
                                showTokenInfoDialog = false
                                onNavigateToTokenConfig()
                        }
                )
        }

        // 主界面 - 简洁设计
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
                        // 标题和说明
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

                        Spacer(modifier = Modifier.height(16.dp))

                        ElevatedCard(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                        ) {
                                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                                        Text(
                                                text = stringResource(R.string.laner_chat_bridge_title),
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                                text =
                                                        stringResource(
                                                                if (bridgePhase == AiLimbsBridgePhase.ONLINE) {
                                                                        R.string.laner_chat_rdc_online
                                                                } else {
                                                                        R.string.laner_chat_rdc_offline
                                                                }
                                                        ),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                                text = stringResource(R.string.laner_chat_core_available),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                                text =
                                                        stringResource(
                                                                when (bridgeAgentPresence) {
                                                                        LanerChatPresenceState.ACTIVE -> R.string.laner_chat_session_online
                                                                        LanerChatPresenceState.RECENT -> R.string.laner_chat_session_recent
                                                                        LanerChatPresenceState.WAITING -> R.string.laner_chat_session_offline
                                                                }
                                                        ),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                                text =
                                                        stringResource(
                                                                R.string.laner_chat_pending_replies,
                                                                bridgePendingCount
                                                        ),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Button(
                                                onClick = onUseLanerBridge,
                                                enabled = !isSaving,
                                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                                shape = RoundedCornerShape(6.dp)
                                        ) {
                                                Icon(
                                                        imageVector = Icons.Default.Link,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                        stringResource(R.string.laner_chat_bridge_enter),
                                                        fontWeight = FontWeight.Medium
                                                )
                                        }
                                }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                        ) {
                                HorizontalDivider(modifier = Modifier.weight(1f))
                                Text(
                                        text = stringResource(R.string.laner_chat_api_section),
                                        modifier = Modifier.padding(horizontal = 10.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                HorizontalDivider(modifier = Modifier.weight(1f))
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedButton(
                                onClick = onUseApiChat,
                                enabled = !isSaving,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                                shape = RoundedCornerShape(6.dp)
                        ) {
                                Text(
                                        stringResource(R.string.laner_chat_api_enter),
                                        fontWeight = FontWeight.Medium
                                )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // API密钥输入框 - 简洁设计
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
                                                stringResource(
                                                        id = R.string.config_api_key_placeholder
                                                ),
                                                fontSize = 12.sp
                                        )
                                },
                                leadingIcon = {
                                        Icon(
                                                imageVector = Icons.Default.Key,
                                                contentDescription =
                                                        stringResource(
                                                                id = R.string.config_api_key_label
                                                        ),
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
                                                                text =
                                                                        stringResource(
                                                                                R.string.config_api_key_invalid_format
                                                                        )
                                                        )
                                                }
                                        } else null,
                                colors =
                                        OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor =
                                                        MaterialTheme.colorScheme.primary,
                                                unfocusedBorderColor =
                                                        MaterialTheme.colorScheme.outline.copy(
                                                                alpha = 0.7f
                                                        )
                                        ),
                                textStyle =
                                        MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                singleLine = true
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // 主按钮 - 根据输入状态动态变化
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
                                                        if (hasEnteredToken)
                                                                MaterialTheme.colorScheme
                                                                        .primaryContainer
                                                        else MaterialTheme.colorScheme.primary,
                                                contentColor =
                                                        if (hasEnteredToken)
                                                                MaterialTheme.colorScheme
                                                                        .onPrimaryContainer
                                                        else MaterialTheme.colorScheme.onPrimary
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
                                                if (isSaving)
                                                        stringResource(R.string.config_saving)
                                                else if (hasEnteredToken)
                                                        stringResource(
                                                                id = R.string.config_save_button
                                                        )
                                                else stringResource(id = R.string.config_get_token),
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
