package com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal;

import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.CommandExecutionEvent;
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.SessionDirectoryEvent;

oneway interface ITerminalCallback {
    void onCommandExecutionUpdate(in CommandExecutionEvent event);
    void onSessionDirectoryChanged(in SessionDirectoryEvent event);
}
