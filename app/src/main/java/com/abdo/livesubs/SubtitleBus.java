package com.abdo.livesubs;

/** Passes subtitle text from the service to the floating (PiP) window. */
final class SubtitleBus {
    interface Listener { void onText(String text, long holdMs); void onClose(); }
    private static volatile Listener listener;
    static void set(Listener l) { listener = l; }
    static void clear(Listener l) { if (listener == l) listener = null; }
    static void post(String t, long ms) { Listener l = listener; if (l != null) l.onText(t, ms); }
    static void close() { Listener l = listener; if (l != null) l.onClose(); }
}
