package com.mapmouse.pro;

import com.mapmouse.pro.IServerCallback;

/** Binder interface of the shell-privileged input server (a Shizuku UserService). */
interface IInputService {
    /** Called by Shizuku when the service is unbound with remove=true. */
    void destroy() = 16777114;

    int version() = 1;
    void setCallback(IServerCallback cb) = 2;
    /** rotation = Display.getRotation() (0..3), used to map real finger touches. */
    void setScreen(int width, int height, int rotation) = 3;
    void setProfile(String json) = 4;
    void setEnabled(boolean on) = 5;
    void setPaused(boolean on) = 6;
    String listDevices() = 7;
    /** Profile JSON; only hotkey/cursor/recoil settings are applied, controls are kept. */
    void updateSettings(String json) = 8;
    /** Global options (polling rate, smoothing, touch-compatible mode, …) as JSON. */
    void setOptions(String json) = 9;
    /** Keyboard typing mode: release the keyboard to Android while the mouse keeps playing. */
    void setTyping(boolean on) = 10;
    /** FPS / CPU / temperature as JSON. */
    String getStats() = 11;
    /** Override the display size/density (0 = reset to default). */
    void setDisplaySize(int width, int height, int density) = 12;
    /** Package name of the app in the foreground, or empty. */
    String foregroundPackage() = 13;
    /** Recent server log lines (start-up problems, devices found, grab/inject errors). */
    String log() = 14;
}
