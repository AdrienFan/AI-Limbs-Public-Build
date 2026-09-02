package com.ai.assistance.operit.integrations.ailimbs

enum class BridgeAction {
    CONNECT,
    STOP,
    RECONNECT,
    RECOVER,
    REPAIR,
    OPEN_AUTH,
    REFRESH;

    companion object {
        fun availableFor(
            state: AiLimbsBridgeState,
            supportedActions: Set<BridgeAction>
        ): List<BridgeAction> {
            val orderedActions =
                when (state.phase) {
                    AiLimbsBridgePhase.STOPPED ->
                        listOf(CONNECT, RECOVER, REPAIR, REFRESH)
                    AiLimbsBridgePhase.PAIRING ->
                        if (
                            !state.verificationUri.isNullOrBlank() &&
                                OPEN_AUTH in supportedActions
                        ) {
                            listOf(OPEN_AUTH, RECONNECT, STOP, REFRESH)
                        } else {
                            listOf(RECONNECT, STOP, REFRESH)
                        }
                    AiLimbsBridgePhase.ONLINE ->
                        listOf(STOP, RECONNECT, RECOVER, REFRESH)
                    AiLimbsBridgePhase.RECONNECTING ->
                        listOf(RECOVER, STOP, REPAIR, REFRESH)
                    AiLimbsBridgePhase.RECOVERY_FAILED ->
                        listOf(RECOVER, RECONNECT, REPAIR, STOP, REFRESH)
                    AiLimbsBridgePhase.ERROR ->
                        listOf(RECOVER, RECONNECT, REPAIR, STOP, REFRESH)
                    AiLimbsBridgePhase.STARTING,
                    AiLimbsBridgePhase.CONNECTING ->
                        listOf(STOP, REFRESH)
                    AiLimbsBridgePhase.RECOVERING ->
                        emptyList()
                }

            return orderedActions.filter(supportedActions::contains)
        }
    }
}
