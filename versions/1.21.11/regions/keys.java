    // GLFW key codes (Minecraft 1.21.x reports keys as GLFW key codes)
    private static final int SC_A = 65, SC_Z = 90, SC_ENTER = 257, SC_ESC = 256, SC_BACKSPACE = 259,
        SC_SPACE = 32, SC_MINUS = 45, SC_COMMA = 44, SC_PERIOD = 46, SC_DELETE = 261, SC_KP_MINUS = 333, SC_KP_ENTER = 335,
        SC_KP_PERIOD = 330, SC_Y = 89,
        SC_LCTRL = 341, SC_LSHIFT = 340, SC_LGUI = 343, SC_RCTRL = 345, SC_RSHIFT = 344, SC_RGUI = 347;

    private static boolean down(int key) {
        try { return InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), key); } catch (Throwable t) { return false; }
    }

    /** The digit a key types (number row or keypad), or 0. */
    private static char digit(int k) {
        if (k >= 48 && k <= 57) return (char) ('0' + (k - 48));     // GLFW_KEY_0 .. GLFW_KEY_9
        if (k >= 320 && k <= 329) return (char) ('0' + (k - 320));  // GLFW_KEY_KP_0 .. GLFW_KEY_KP_9
        return 0;
    }
