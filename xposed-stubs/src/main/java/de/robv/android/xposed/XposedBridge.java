package de.robv.android.xposed;

public final class XposedBridge {
    private XposedBridge() {}

    public static void log(String text) { throw new UnsupportedOperationException(); }

    public static void log(Throwable t) { throw new UnsupportedOperationException(); }
}
