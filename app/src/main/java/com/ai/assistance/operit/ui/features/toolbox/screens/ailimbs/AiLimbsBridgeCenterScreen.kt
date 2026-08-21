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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.AIForegroundService
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgeManager
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgePhase
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgeState
import com.ai.assistance.operit.integrations.ailimbs.BridgeAction
import com.ai.assistance.operit.integrations.ailimbs.BridgeProfile
import com.ai.assistance.operit.ui.components.CustomScaffold

@Composable
fun AiLimbsBridgeCenterScreen() {
    val context = LocalContext.current
    val profiles = remember { AiLimbsBridgeManager.availableProfiles() }
    var activeProviderId by remember {
        mutableStateOf(AiLimbsBridgeManager.activeProviderId(context))
    }
    val runtimeState by AiLimbsBridgeManager.runtimeState.collectAsState()

    LaunchedEffect(runtimeState.providerId) {
        if (
            runtimeState.providerId.isNotBlank() &&
                runtimeState.providerId == AiLimbsBridgeManager.activeProviderId(context)
        ) {
            activeProviderId = runtimeState.providerId
        }
    }

    val activeProfile = profiles.first { it.id == activeProviderId }
    val displayedState =
        if (runtimeState.providerId == activeProfile.id) {
            runtimeState
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
            BridgeActionsCard(
                actions = actions,
                onAction = { action ->
                    AIForegroundService.requestBridgeAction(context, action)
                }
            )
        }
    }
}

@Composable
private fun ProviderSelectionCard(
    profiles: List<BridgeProfile>,
    activeProviderId: String,
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
                        enabled = profile.enabled,
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
private fun BridgeActionsCard(
    actions: List<BridgeAction>,
    onAction: (BridgeAction) -> Unit
) {
    BridgeCenterCard {
        Text(
            text = stringResource(R.string.ai_limbs_bridge_actions_section),
            style = MaterialTheme.typography.titleMedium
        )
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
            AiLimbsBridgePhase.ERROR -> R.string.ai_limbs_bridge_phase_error
        }
    )

private fun bridgeActionLabel(action: BridgeAction): Int =
    when (action) {
        BridgeAction.CONNECT -> R.string.ai_limbs_bridge_action_connect
        BridgeAction.STOP -> R.string.ai_limbs_bridge_action_stop
        BridgeAction.RECONNECT -> R.string.ai_limbs_bridge_action_reconnect
        BridgeAction.REPAIR -> R.string.ai_limbs_bridge_action_repair
        BridgeAction.OPEN_AUTH -> R.string.ai_limbs_bridge_action_open_auth
        BridgeAction.REFRESH -> R.string.ai_limbs_bridge_action_refresh
    }
