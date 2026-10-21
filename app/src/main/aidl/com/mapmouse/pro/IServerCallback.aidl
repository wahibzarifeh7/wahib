package com.mapmouse.pro;

interface IServerCallback {
    oneway void onCursor(int x, int y, boolean visible);
    oneway void onState(boolean enabled, boolean cursorMode, boolean paused, int mice, int keyboards);
    /** Recoil/weapon/HUD state as JSON. */
    oneway void onHud(String json);
    /** Injected fingers as x0,y0,x1,y1,… for the touch-path overlay. */
    oneway void onPointers(in int[] xy);
}
