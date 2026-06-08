package com.debugtools.core.ipc;

import com.debugtools.core.ipc.model.DebugEvent;
import com.debugtools.core.ipc.model.CrashInfo;
import com.debugtools.core.ipc.IDebugToolsCallback;

interface IDebugToolsService {
    void sendEvent(in DebugEvent event);
    void reportCrash(in CrashInfo crash);
    void updateModuleData(String moduleId, in Bundle data);
    void registerCallback(IDebugToolsCallback callback);
    void unregisterCallback(IDebugToolsCallback callback);
}
