package com.ai.limbs.plugins.ubuntu.runtime.terminal;

import com.ai.limbs.plugins.ubuntu.runtime.terminal.CommandExecutionEvent;
import com.ai.limbs.plugins.ubuntu.runtime.terminal.SessionDirectoryEvent;

oneway interface ITerminalCallback {
    void onCommandExecutionUpdate(in CommandExecutionEvent event);
    void onSessionDirectoryChanged(in SessionDirectoryEvent event);
} 