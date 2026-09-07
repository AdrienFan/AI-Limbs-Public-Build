package com.ai.assistance.operit.ui.features.chat.webview.computer

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.ai.assistance.operit.ui.features.toolbox.screens.PluginDeclarativeScreen

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun ComputerScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { },
                    onDoubleTap = { },
                    onLongPress = { },
                    onPress = { }
                )
            }
    ) {
        PluginDeclarativeScreen(screenId = SYSTEM_ENVIRONMENT_SCREEN_ID)
    }
}

private const val SYSTEM_ENVIRONMENT_SCREEN_ID = "plugin.system_environment.screen"
