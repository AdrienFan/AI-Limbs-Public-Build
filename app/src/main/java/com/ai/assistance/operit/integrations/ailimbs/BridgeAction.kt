package com.ai.assistance.operit.integrations.ailimbs

enum class BridgeAction {
    CONNECT,
    STOP,
    RECONNECT,
    REPAIR,
    OPEN_AUTH,
    REFRESH;

    companion object {
        internal fun availableFor(
            state: AiLimbsBridgeState,
            supportedActions: Set<BridgeAction>
        ): List<BridgeAction> {
            val orderedActions =
                when (state.phase) {
                    AiLimbsBridgePhase.STOPPED ->
                        listOf(CONNECT, REPAIR, REFRESH)
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
                        listOf(STOP, RECONNECT, REFRESH)
                    AiLimbsBridgePhase.ERROR ->
                        listOf(RECONNECT, REPAIR, STOP, REFRESH)
                    AiLimbsBridgePhase.STARTING,
                    AiLimbsBridgePhase.CONNECTING,
                    AiLimbsBridgePhase.RECONNECTING ->
                        listOf(STOP, REPAIR, REFRESH)
                }

            return orderedActions.filter(supportedActions::contains)
        }
    }
}
