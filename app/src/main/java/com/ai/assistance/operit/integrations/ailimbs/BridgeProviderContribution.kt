package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context

/**
 * Stable UI contribution targets owned by the Bridge parent contract.
 *
 * These ids do not define UI components. They only name the parent-owned screen instance and slot
 * that Bridge has explicitly opened to ai_limbs.bridge.provider children. Plugin Center remains the
 * sole owner of component types and rendering semantics.
 */
object BridgeProviderUiSlots {
    const val SCREEN_ID = "plugin.system.bridge.screen"
    const val PROVIDER_PANEL_COMPONENT_ID = "provider_panel"
    const val PROVIDER_PANEL_AFTER = "after"
}

/**
 * Bridge child-extension presentation contract.
 *
 * These field/action types belong to the Bridge provider API, not to Stable Kernel UI ABI. Child
 * providers publish Bridge business presentation state; the Bridge parent translates that state into
 * the active Plugin Center UI schema before rendering. Child extensions must not register Host
 * screens or depend on InProcessScreenBlock/InProcessPanel* UI types.
 */
enum class BridgeProviderPanelFieldKind { TEXT, SECRET }

data class BridgeProviderPanelField(
    val id: String,
    val label: String,
    val kind: BridgeProviderPanelFieldKind = BridgeProviderPanelFieldKind.TEXT,
    val value: String = "",
    val placeholder: String = "",
    val enabled: Boolean = true
)

data class BridgeProviderPanelAction(
    val id: String,
    val label: String,
    val enabled: Boolean = true,
    val requiredFieldIds: Set<String> = emptySet()
)

data class BridgeProviderPanelState(
    val title: String,
    val description: String = "",
    val statusLines: List<String> = emptyList(),
    val fields: List<BridgeProviderPanelField> = emptyList(),
    val actions: List<BridgeProviderPanelAction> = emptyList()
)

data class BridgeProviderPanelResult(
    val message: String = "",
    val fieldValues: Map<String, String> = emptyMap()
)

data class BridgeProviderNotificationAction(
    val id: String,
    val label: String,
    val priority: Int = 0,
    val enabled: Boolean = true
)

data class BridgeProviderNotificationState(
    val title: String,
    val summary: String = "",
    val statusLines: List<String> = emptyList(),
    val actions: List<BridgeProviderNotificationAction> = emptyList()
)

interface BridgeProviderControl {
    val profile: BridgeProfile
    val state: AiLimbsBridgeState
    val availableActions: List<BridgeAction>

    fun perform(action: BridgeAction): Boolean
    fun statusSummary(): String
}

interface BridgeProviderPanel {
    fun snapshot(
        context: Context,
        control: BridgeProviderControl
    ): BridgeProviderPanelState

    suspend fun perform(
        context: Context,
        actionId: String,
        fieldValues: Map<String, String>,
        control: BridgeProviderControl
    ): BridgeProviderPanelResult
}

interface BridgeProviderNotification {
    fun snapshot(
        context: Context,
        control: BridgeProviderControl
    ): BridgeProviderNotificationState

    suspend fun perform(
        context: Context,
        actionId: String,
        control: BridgeProviderControl
    )
}

data class BridgeProviderContribution(
    val factory: BridgeProviderFactory,
    val panel: BridgeProviderPanel,
    val notification: BridgeProviderNotification? = null
)
