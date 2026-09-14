package com.vrremote.controller.input;

/**
 * Runs inside the Shizuku user service, i.e. with the shell identity, which is
 * what allows opening /dev/uhid on a non-rooted device.
 */
interface IControllerInput {

    /** Transaction reserved by the Shizuku server for destroying the service. */
    void destroy() = 16777114;

    /**
     * Creates the virtual devices.
     *
     * @return 0 on success, otherwise a negative errno style error code.
     */
    int createDevices() = 1;

    oneway void key(int keyCode, boolean down) = 2;

    oneway void mouseMove(int dx, int dy) = 3;

    oneway void mouseButton(int button, boolean down) = 4;

    void closeDevices() = 5;

    /**
     * Allows the app to draw its own on-screen hint.
     *
     * Some devices rewrite toast text (a DeX hook did on the test device), so
     * the mode hint is drawn by the app instead; this grants the appop needed
     * for that, using the shell identity that is already running this service.
     */
    boolean grantOverlayPermission(String packageName) = 6;

    /** Synthetic touch gestures, used by the per-app profiles. */
    oneway void swipe(int x1, int y1, int x2, int y2, int durationMs) = 7;

    oneway void tap(int x, int y) = 8;

    oneway void doubleTap(int x, int y) = 9;

    oneway void longPress(int x, int y) = 10;

    /** Package name of the app in the foreground, or null when it is unknown. */
    String foregroundPackage() = 11;

    /**
     * Sends a key through `input keyevent`.
     *
     * Safety net for key codes the virtual HID devices cannot express; it is
     * slower (it starts a process), so the HID path is preferred whenever the
     * usage table covers the key.
     */
    oneway void keyEvent(int keyCode) = 12;
}
