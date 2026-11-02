# Keep the accessibility service and activities (referenced from the manifest).

# LSPosed / Xposed module: entry point is loaded by name from assets/xposed_init.
-keep class com.whixprs.phonelock.xposed.XposedEntry { *; }
# Hooked by the module in our own process to report that it is active; must not be inlined.
-keep class com.whixprs.phonelock.ModuleStatus { *; }
-dontwarn de.robv.android.xposed.**
