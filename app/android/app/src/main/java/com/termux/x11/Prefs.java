package com.termux.x11;

import java.util.HashMap;

public class Prefs {
    public static class Pref<T> {
        private T val;
        public Pref(T v) { val = v; }
        public T get() { return val; }
        public void put(T v) { val = v; }
    }
    
    public static class DummyPreference {
        public Pref<String> asList() {
            return new Pref<>("none");
        }
    }
    
    public Pref<Boolean> showMouseHelper = new Pref<>(false);
    public Pref<String> touchMode = new Pref<>("1");
    public Pref<Boolean> enforceCharBasedInput = new Pref<>(false);
    public Pref<Boolean> keepScreenOn = new Pref<>(true);
    public Pref<Boolean> fullscreen = new Pref<>(false);
    public Pref<Boolean> enableSoftKeyboardModifiers = new Pref<>(false);
    public Pref<Boolean> stylusButtonContactModifierMode = new Pref<>(false);
    public Pref<Boolean> pauseKeyInterceptingWithEsc = new Pref<>(false);
    public Pref<String> transformCapturedPointer = new Pref<>("none");
    public Pref<Boolean> pointerCapture = new Pref<>(false);
    public Pref<Boolean> ignoreGamepadEvents = new Pref<>(false);
    public Pref<String> displayResolutionMode = new Pref<>("native");
    public Pref<Integer> displayScale = new Pref<>(100);
    public Pref<String> displayResolutionExact = new Pref<>("1080x1920");
    public Pref<String> displayResolutionCustom = new Pref<>("1080x1920");
    public Pref<Boolean> adjustResolution = new Pref<>(true);
    public Pref<Boolean> displayStretch = new Pref<>(true);
    /**
     * 0 = use the panel's refresh rate. DroidDesk sets 60 in the power-saver profile:
     * llvmpipe cannot fill 120 frames per second of a full-screen video window, and the
     * stutter/tearing that results is what the user perceived as flicker.
     */
    public Pref<Integer> displayFramerateCap = new Pref<>(0);
    public Pref<String> displayFilteringMode = new Pref<>("nearest");
    public Pref<Boolean> hardwareKbdScancodesWorkaround = new Pref<>(false);
    public Pref<Boolean> clipboardEnable = new Pref<>(true);
    
    public Pref<Boolean> tapToMove = new Pref<>(false);
    public Pref<Boolean> preferScancodes = new Pref<>(false);
    public Pref<Boolean> scaleTouchpad = new Pref<>(false);
    public Pref<Integer> capturedPointerSpeedFactor = new Pref<>(100);
    public Pref<Boolean> dexMetaKeyCapture = new Pref<>(false);
    public Pref<Boolean> stylusIsMouse = new Pref<>(false);
    
    public HashMap<String, DummyPreference> keys = new HashMap<>();
}
