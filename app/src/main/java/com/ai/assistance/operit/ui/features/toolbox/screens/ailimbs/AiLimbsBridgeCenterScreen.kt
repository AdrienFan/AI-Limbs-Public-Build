package com.ai.assistance.operit.ui.features.toolbox.screens.ailimbs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.AIForegroundService
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgeManager
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgePhase
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgeState
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsUiCapabilityService
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsUiCapabilityStatus
import com.ai.assistance.operit.integrations.ailimbs.BridgeAction
import com.ai.assistance.operit.integrations.ailimbs.BridgeProfile
import com.ai.assistance.operit.integrations.ailimbs.providers.triggercmd.TriggerCmdBridgeProvider
import com.ai.assistance.operit.integrations.ailimbs.providers.triggercmd.TriggerCmdBridgeStorage
import com.ai.assistance.operit.ui.components.CustomScaffold
import kotlinx.coroutines.launch

@Composable
fun AiLimbsBridgeCenterScreen(onConfigureUiController: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiCapabilities = remember { AiLimbsUiCapabilityService(context) }
    var uiStatus by remember { mutableStateOf<AiLimbsUiCapabilityStatus?>(null) }
    val profiles = remember { AiLimbsBridgeManager.availableProfiles() }
    var activeProviderId by remember {
        mutableStateOf(AiLimbsBridgeManager.activeProviderId(context))
    }
    val controlState by AiLimbsBridgeManager.controlState.collectAsState()

    fun refreshUiStatus() {
        scope.launch {
            uiStatus = runCatching { uiCapabilities.readStatus() }.getOrNull()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshUiStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(controlState.providerId) {
        if (
            controlState.providerId.isNotBlank() &&
                controlState.providerId == AiLimbsBridgeManager.activeProviderId(context)
        ) {
            activeProviderId = controlState.providerId
        }
    }

    val activeProfile = profiles.first { it.id == activeProviderId }
    val displayedState =
        if (controlState.providerId == activeProfile.id) {
            controlState
        } else {
            AiLimbsBridgeState(
                providerId = activeProfile.id,
                providerLabel = activeProfile.label,
                detail = stringResource(R.string.ai_limbs_bridge_not_running)
            )
        }
    val actions =
        AiLimbsBridgeManager.availableActions(
            context,
            displayedState
        )
    val bridgeLifecycleLocked = displayedState.phase == AiLimbsBridgePhase.RECOVERING

    CustomScaffold { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ProviderSelectionCard(
                profiles = profiles,
                activeProviderId = activeProviderId,
                interactionEnabled = !bridgeLifecycleLocked,
                onSelect = { profile ->
                    activeProviderId = profile.id
                    AIForegroundService.requestBridgeProviderSelection(
                        context,
                        profile.id
                    )
                }
            )
            BridgeStatusCard(
                profile = activeProfile,
                state = displayedState
            )
            if (activeProfile.id == TriggerCmdBridgeProvider.PROFILE_ID) {
                TriggerCmdConfigurationCard(
                    context = context,
                    refreshKey = displayedState.lastHeartbeatAtMs ?: displayedState.phase.ordinal.toLong(),
                    onConnect = {
                        AIForegroundService.requestBridgeAction(
                            context,
                            BridgeAction.CONNECT,
                            activeProfile.id
                        )
                    },
                    onStop = {
                        AIForegroundService.requestBridgeAction(
                            context,
                            BridgeAction.STOP,
                            activeProfile.id
                        )
                    }
                )
            }
            BridgeActionsCard(
                actions = actions,
                recoveryLocked = bridgeLifecycleLocked,
                onAction = { action ->
                    AIForegroundService.requestBridgeAction(context, action, activeProfile.id)
                }
            )
            UiCapabilityCard(
                status = uiStatus,
                onEnableAccessibility = {
                    scope.launch {
                        uiCapabilities.selectAccessibilityMode()
                        val current = uiCapabilities.readStatus()
                        if (!current.accessibilityProviderInstalled) {
                            uiCapabilities.installAccessibilityProvider()
                        } else if (!current.accessibilityServiceEnabled) {
                            uiCapabilities.openAccessibilitySettings()
                        }
                        uiStatus = current
                    }
                },
                onConfigureUiController = onConfigureUiController,
                onRefresh = ::refreshUiStatus
            )
        }
    }
}

@Composable
private fun ProviderSelectionCard(
    profiles: List<BridgeProfile>,
    activeProviderId: String,
    interactionEnabled: Boolean,
    onSelect: (BridgeProfile) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val activeProfile = profiles.first { it.id == activeProviderId }

    BridgeCenterCard {
        Text(
            text = stringResource(R.string.ai_limbs_bridge_provider_section),
            style = MaterialTheme.typography.titleMedium
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = interactionEnabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text =
                        stringResource(
                            R.string.ai_limbs_notification_current_bridge,
                            activeProfile.label
                        ) + "  ▼"
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                profiles.forEach { profile ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text =
                                        if (profile.id == activeProviderId) {
                                            "✓ ${profile.label}"
                                        } else {
                                            profile.label
                                        }
                                )
                                Text(
                                    text =
                                        stringResource(
                                            R.string.ai_limbs_bridge_profile_metadata,
                                            profile.id,
                                            profile.type
                                        ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        enabled = profile.enabled && interactionEnabled,
                        onClick = {
                            expanded = false
                            if (profile.id != activeProviderId) {
                                onSelect(profile)
                            }
                        }
                    )
                }
            }
        }
        Text(
            text =
                stringResource(
                    if (activeProfile.enabled) {
                        R.string.ai_limbs_bridge_enabled
                    } else {
                        R.string.ai_limbs_bridge_disabled
                    }
                ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BridgeStatusCard(
    profile: BridgeProfile,
    state: AiLimbsBridgeState
) {
    BridgeCenterCard {
        Text(
            text = stringResource(R.string.ai_limbs_bridge_status_section),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text =
                stringResource(
                    R.string.ai_limbs_bridge_current_provider,
                    profile.label
                )
        )
        Text(
            text =
                stringResource(
                    R.string.ai_limbs_bridge_current_status,
                    bridgePhaseLabel(state.phase)
                )
        )
        if (state.detail.isNotBlank()) {
            Text(
                text = state.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        state.userCode?.takeIf { it.isNotBlank() }?.let { userCode ->
            Text(
                stringResource(
                    R.string.ai_limbs_bridge_authorization_code,
                    userCode
                )
            )
        }
        state.deviceId?.takeIf { it.isNotBlank() }?.let { deviceId ->
            Text(
                stringResource(
                    R.string.ai_limbs_bridge_device_id,
                    deviceId
                )
            )
        }
    }
}

@Composable
private fun TriggerCmdConfigurationCard(
    context: android.content.Context,
    refreshKey: Long,
    onConnect: () -> Unit,
    onStop: () -> Unit
) {
    val storage = remember { TriggerCmdBridgeStorage(context) }
    var tokenInput by remember { mutableStateOf("") }
    var computerNameInput by remember { mutableStateOf(storage.readConfig().computerName) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val config = remember(refreshKey, feedback) { storage.readConfig() }

    BridgeCenterCard {
        Text(
            text = stringResource(R.string.ai_limbs_triggercmd_config_title),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = stringResource(
                when {
                    !config.secureStorageAvailable -> R.string.ai_limbs_triggercmd_secure_storage_unavailable
                    config.configured -> R.string.ai_limbs_triggercmd_token_configured
                    else -> R.string.ai_limbs_triggercmd_token_not_configured
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = tokenInput,
            onValueChange = { tokenInput = it },
            label = { Text(stringResource(R.string.ai_limbs_triggercmd_agent_token)) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = computerNameInput,
            onValueChange = { computerNameInput = it },
            label = { Text(stringResource(R.string.ai_limbs_triggercmd_computer_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = stringResource(
                R.string.ai_limbs_triggercmd_computer_id,
                config.computerId ?: stringResource(R.string.ai_limbs_triggercmd_not_registered)
            ),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = stringResource(R.string.ai_limbs_triggercmd_provider_grant_full),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = {
                val result = runCatching {
                    storage.saveBinding(tokenInput, computerNameInput)
                }
                if (result.isSuccess) {
                    tokenInput = ""
                    computerNameInput = storage.readConfig().computerName
                    feedback = context.getString(R.string.ai_limbs_triggercmd_saved_connecting)
                    onConnect()
                } else {
                    feedback = result.exceptionOrNull()?.message
                }
            },
            enabled = tokenInput.isNotBlank() && config.secureStorageAvailable,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.ai_limbs_triggercmd_save_connect))
        }
        if (config.configured) {
            OutlinedButton(
                onClick = {
                    storage.clearBinding()
                    tokenInput = ""
                    computerNameInput = storage.readConfig().computerName
                    feedback = context.getString(R.string.ai_limbs_triggercmd_binding_cleared)
                    onStop()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.ai_limbs_triggercmd_clear_binding))
            }
        }
        feedback?.takeIf { it.isNotBlank() }?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BridgeActionsCard(
    actions: List<BridgeAction>,
    recoveryLocked: Boolean,
    onAction: (BridgeAction) -> Unit
) {
    BridgeCenterCard {
        Text(
            text = stringResource(R.string.ai_limbs_bridge_actions_section),
            style = MaterialTheme.typography.titleMedium
        )
        if (recoveryLocked) {
            // The UI mirrors the manager-side transaction lock. Keeping this disabled also makes
            // the long-running recovery visibly intentional instead of looking like a frozen page.
            Button(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.ai_limbs_bridge_recovery_locked))
            }
            Text(
                text = stringResource(R.string.ai_limbs_bridge_recovery_locked_detail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            actions.forEach { action ->
                Button(
                    onClick = { onAction(action) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(bridgeActionLabel(action)))
                }
            }
        }
    }
}

@Composable
private fun UiCapabilityCard(
    status: AiLimbsUiCapabilityStatus?,
    onEnableAccessibility: () -> Unit,
    onConfigureUiController: () -> Unit,
    onRefresh: () -> Unit
) {
    BridgeCenterCard {
        Text(
            text = stringResource(R.string.ai_limbs_ui_capability_section),
            style = MaterialTheme.typography.titleMedium
        )
        if (status == null) {
            Text(stringResource(R.string.ai_limbs_ui_capability_checking))
        } else {
            Text(
                stringResource(
                    R.string.ai_limbs_ui_permission_backend,
                    status.preferredPermissionLevel.name,
                    status.activeBackend
                )
            )
            Text(
                stringResource(
                    R.string.ai_limbs_ui_direct_status,
                    stringResource(
                        if (status.directUiReady) {
                            R.string.ai_limbs_ui_ready
                        } else {
                            R.string.ai_limbs_ui_not_ready
                        }
                    )
                )
            )
            Text(
                stringResource(
                    R.string.ai_limbs_ui_accessibility_status,
                    stringResource(
                        when {
                            !status.accessibilityProviderInstalled ->
                                R.string.ai_limbs_ui_provider_missing
                            status.accessibilityServiceEnabled ->
                                R.string.ai_limbs_ui_accessibility_enabled
                            else -> R.string.ai_limbs_ui_accessibility_disabled
                        }
                    )
                )
            )
            Text(
                stringResource(
                    R.string.ai_limbs_ui_subagent_status,
                    stringResource(
                        if (status.uiSubagentReady) {
                            R.string.ai_limbs_ui_ready
                        } else {
                            R.string.ai_limbs_ui_not_ready
                        }
                    )
                )
            )
            if (!status.directUiReady) {
                Button(
                    onClick = onEnableAccessibility,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.ai_limbs_ui_enable_accessibility))
                }
            }
            if (!status.uiSubagentReady) {
                OutlinedButton(
                    onClick = onConfigureUiController,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.ai_limbs_ui_configure_controller))
                }
            }
        }
        OutlinedButton(
            onClick = onRefresh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.ai_limbs_ui_refresh_status))
        }
    }
}

@Composable
private fun BridgeCenterCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
            )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

@Composable
private fun bridgePhaseLabel(phase: AiLimbsBridgePhase): String =
    stringResource(
        when (phase) {
            AiLimbsBridgePhase.STOPPED -> R.string.ai_limbs_bridge_phase_stopped
            AiLimbsBridgePhase.STARTING -> R.string.ai_limbs_bridge_phase_starting
            AiLimbsBridgePhase.CONNECTING -> R.string.ai_limbs_bridge_phase_connecting
            AiLimbsBridgePhase.PAIRING -> R.string.ai_limbs_bridge_phase_pairing
            AiLimbsBridgePhase.ONLINE -> R.string.ai_limbs_bridge_phase_online
            AiLimbsBridgePhase.RECONNECTING -> R.string.ai_limbs_bridge_phase_reconnecting
            AiLimbsBridgePhase.RECOVERING -> R.string.ai_limbs_bridge_phase_recovering
            AiLimbsBridgePhase.RECOVERY_FAILED -> R.string.ai_limbs_bridge_phase_recovery_failed
            AiLimbsBridgePhase.ERROR -> R.string.ai_limbs_bridge_phase_error
        }
    )

private fun bridgeActionLabel(action: BridgeAction): Int =
    when (action) {
        BridgeAction.CONNECT -> R.string.ai_limbs_bridge_action_connect
        BridgeAction.STOP -> R.string.ai_limbs_bridge_action_stop
        BridgeAction.RECONNECT -> R.string.ai_limbs_bridge_action_reconnect
        BridgeAction.RECOVER -> R.string.ai_limbs_bridge_action_recover
        BridgeAction.REPAIR -> R.string.ai_limbs_bridge_action_repair
        BridgeAction.OPEN_AUTH -> R.string.ai_limbs_bridge_action_open_auth
        BridgeAction.REFRESH -> R.string.ai_limbs_bridge_action_refresh
    }
