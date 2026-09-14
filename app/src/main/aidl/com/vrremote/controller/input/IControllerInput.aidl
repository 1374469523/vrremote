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
}
