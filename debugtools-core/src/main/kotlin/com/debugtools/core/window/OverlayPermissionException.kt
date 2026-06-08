package com.debugtools.core.window

class OverlayPermissionException : Exception(
    "SYSTEM_ALERT_WINDOW permission not granted. " +
    "Call Settings.canDrawOverlays(context) and prompt the user before calling init()."
)
