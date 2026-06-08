package com.debugtools.core.ipc;

interface IDebugToolsCallback {
    void onSettingChanged(String moduleId, String key, in Bundle value);
    void onDisplayModeChanged(int mode);
}
