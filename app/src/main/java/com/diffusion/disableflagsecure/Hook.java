package com.diffusion.disableflagsecure;

import android.hardware.display.DisplayManager;
import android.os.Build;
import android.view.SurfaceControl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Hook implements IXposedHookLoadPackage {

    private static final String TAG = "DisableFlagSecure";

    private static final int SDK_UPSIDE_DOWN_CAKE   = 34;
    private static final int SDK_VANILLA_ICE_CREAM  = 35;
    private static final int SDK_BAKLAVA            = 36;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!"android".equals(lpp.packageName)) return;
        XposedBridge.log(TAG + ": loaded in " + lpp.packageName
                + " (sdk=" + Build.VERSION.SDK_INT + ")");
        int hooked = 0;
        hooked += safe(new Action() { public int run() { return hookTransactionSetSecure(); } },           "Transaction.setSecure");
        hooked += safe(new Action() { public int run() { return hookSurfaceControlBuilderSetSecure(); } }, "Builder.setSecure");
        hooked += safe(new Action() { public int run() { return hookWindowStateIsSecureLocked(lpp.classLoader); } },
                                                                                                           "WindowState.isSecureLocked");
        hooked += safe(new Action() { public int run() throws Throwable { return hookScreenCapture(lpp.classLoader); } },
                                                                                                           "ScreenCapture");
        hooked += safe(new Action() { public int run() { return hookScreenshotHardwareBuffer(lpp.classLoader); } },
                                                                                                           "ScreenshotHardwareBuffer");
        hooked += safe(new Action() { public int run() { return hookVirtualDisplayAdapter(lpp.classLoader); } },
                                                                                                           "VirtualDisplayAdapter");
        hooked += safe(new Action() { public int run() { return hookDisplayControl(lpp.classLoader); } },  "DisplayControl");
        if (Build.VERSION.SDK_INT >= SDK_UPSIDE_DOWN_CAKE) {
            hooked += safe(new Action() { public int run() { return hookActivityTaskManagerService(lpp.classLoader); } },
                                                                                                           "ATMS.registerScreenCaptureObserver");
        }
        if (Build.VERSION.SDK_INT >= SDK_VANILLA_ICE_CREAM) {
            hooked += safe(new Action() { public int run() { return hookWindowManagerService(lpp.classLoader); } },
                                                                                                           "WMS.registerScreenRecordingCallback");
        }
        if (Build.VERSION.SDK_INT < SDK_UPSIDE_DOWN_CAKE) {
            hooked += safe(new Action() { public int run() { return hookActivityManagerService(lpp.classLoader); } },
                                                                                                           "AMS.checkPermission (pre-U)");
        }
        XposedBridge.log(TAG + ": total hooks installed = " + hooked);
    }

    private interface Action { int run() throws Throwable; }

    private int safe(Action a, String label) {
        try { return a.run(); }
        catch (Throwable t) {
            XposedBridge.log(TAG + ": hook " + label + " FAILED: " + t);
            return 0;
        }
    }

    private int hookAllNamed(Class<?> cls, String name, XC_MethodHook cb) {
        int count = 0;
        for (Method m : cls.getDeclaredMethods()) {
            if (!name.equals(m.getName())) continue;
            try {
                XposedBridge.hookMethod(m, cb);
                XposedBridge.log(TAG + ": hooked " + m);
                count++;
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": failed to hook " + m + ": " + t);
            }
        }
        return count;
    }

    private static Field getDeclaredFieldIfExists(Class<?> c, String name) {
        try { return c.getDeclaredField(name); }
        catch (NoSuchFieldException e) { return null; }
    }

    private static void forceBooleansFalse(XC_MethodHook.MethodHookParam p) {
        if (p.args == null) return;
        for (int i = 0; i < p.args.length; i++) {
            if (p.args[i] instanceof Boolean) p.args[i] = Boolean.FALSE;
        }
    }

    private int hookTransactionSetSecure() {
        return hookAllNamed(SurfaceControl.Transaction.class, "setSecure",
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        forceBooleansFalse(p);
                    }
                });
    }

    private int hookSurfaceControlBuilderSetSecure() {
        return hookAllNamed(SurfaceControl.Builder.class, "setSecure",
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        forceBooleansFalse(p);
                    }
                });
    }

    private int hookWindowStateIsSecureLocked(ClassLoader cl) {
        Class<?> cls = XposedHelpers.findClassIfExists(
                "com.android.server.wm.WindowState", cl);
        if (cls == null) return 0;
        final ClassLoader systemServerCl = cls.getClassLoader();
        int count = 0;
        for (Method m : cls.getDeclaredMethods()) {
            if (!"isSecureLocked".equals(m.getName())) continue;
            if (m.getParameterTypes().length != 0) continue;
            if (m.getReturnType() != boolean.class) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (shouldPreserveFlagSecure(systemServerCl)) return;
                        p.setResult(Boolean.FALSE);
                    }
                });
                XposedBridge.log(TAG + ": hooked " + m);
                count++;
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": failed hook on " + m + ": " + t);
            }
        }
        return count;
    }

    private static boolean shouldPreserveFlagSecure(ClassLoader systemServerCl) {
        StackTraceElement[] stack = new Throwable().getStackTrace();
        for (StackTraceElement e : stack) {
            String mn = e.getMethodName();
            if (!"setInitialSurfaceControlProperties".equals(mn)
                    && !"createSurfaceLocked".equals(mn)) continue;
            try {
                Class<?> c = Class.forName(e.getClassName(), false, systemServerCl);
                if (c.getClassLoader() == systemServerCl) return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private int hookScreenCapture(ClassLoader cl) throws Throwable {
        Class<?> screenCaptureClazz = null;
        Field flagField = null;

        Class<?> c = XposedHelpers.findClassIfExists(
                "android.window.ScreenCaptureInternal", cl);
        if (c != null) {
            Class<?> args = XposedHelpers.findClassIfExists(
                    "android.window.ScreenCaptureInternal$CaptureArgs", cl);
            if (args != null) {
                Field f = getDeclaredFieldIfExists(args, "mSecureContentPolicy");
                if (f != null) { screenCaptureClazz = c; flagField = f; }
            }
        }
        if (flagField == null) {
            c = XposedHelpers.findClassIfExists("android.window.ScreenCapture", cl);
            if (c != null) {
                Class<?> args = XposedHelpers.findClassIfExists(
                        "android.window.ScreenCapture$CaptureArgs", cl);
                if (args != null) {
                    Field f = getDeclaredFieldIfExists(args, "mCaptureSecureLayers");
                    if (f != null) { screenCaptureClazz = c; flagField = f; }
                }
            }
        }
        if (flagField == null) {
            Class<?> args = XposedHelpers.findClassIfExists(
                    "android.view.SurfaceControl$CaptureArgs", cl);
            if (args != null) {
                Field f = getDeclaredFieldIfExists(args, "mCaptureSecureLayers");
                if (f != null) {
                    screenCaptureClazz = SurfaceControl.class;
                    flagField = f;
                }
            }
        }
        if (flagField == null || screenCaptureClazz == null) {
            XposedBridge.log(TAG + ": ScreenCapture classes/field not found, skipping");
            return 0;
        }

        flagField.setAccessible(true);
        final boolean useIntFlag = (flagField.getType() == int.class);
        final Field finalField   = flagField;

        XC_MethodHook cb = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    if (p.args == null || p.args.length == 0 || p.args[0] == null) return;
                    Object arg0 = p.args[0];
                    if (!finalField.getDeclaringClass().isAssignableFrom(arg0.getClass())) return;
                    if (useIntFlag) finalField.setInt(arg0, 1);
                    else            finalField.setBoolean(arg0, true);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": ScreenCapture hook inner failure: " + t);
                }
            }
        };

        int count = 0;
        count += hookAllNamed(screenCaptureClazz, "nativeCaptureDisplay", cb);
        count += hookAllNamed(screenCaptureClazz, "nativeCaptureLayers", cb);
        return count;
    }

    private int hookScreenshotHardwareBuffer(ClassLoader cl) {
        String name = Build.VERSION.SDK_INT >= SDK_UPSIDE_DOWN_CAKE
                ? "android.window.ScreenCapture$ScreenshotHardwareBuffer"
                : "android.view.SurfaceControl$ScreenshotHardwareBuffer";
        Class<?> c = XposedHelpers.findClassIfExists(name, cl);
        if (c == null) return 0;
        return hookAllNamed(c, "containsSecureLayers",
                XC_MethodReplacement.returnConstant(Boolean.FALSE));
    }

    private int hookVirtualDisplayAdapter(ClassLoader cl) {
        Class<?> c = XposedHelpers.findClassIfExists(
                "com.android.server.display.VirtualDisplayAdapter", cl);
        if (c == null) return 0;
        return hookAllNamed(c, "createVirtualDisplayLocked", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args == null) return;
                try {
                    Object projection = p.args.length > 1 ? p.args[1] : null;
                    int callingUid = -1;
                    if (p.args.length > 2 && p.args[2] instanceof Integer) {
                        callingUid = (Integer) p.args[2];
                    }
                    if (callingUid >= 10000 && projection == null) return;
                    for (int i = 3; i < p.args.length; i++) {
                        if (p.args[i] instanceof Integer) {
                            int flags = (Integer) p.args[i];
                            flags |= DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE;
                            p.args[i] = Integer.valueOf(flags);
                            return;
                        }
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": VirtualDisplayAdapter inner failure: " + t);
                }
            }
        });
    }

    private int hookDisplayControl(ClassLoader cl) {
        Class<?> c = Build.VERSION.SDK_INT >= SDK_UPSIDE_DOWN_CAKE
                ? XposedHelpers.findClassIfExists(
                        "com.android.server.display.DisplayControl", cl)
                : SurfaceControl.class;
        if (c == null) return 0;
        String target = Build.VERSION.SDK_INT >= SDK_VANILLA_ICE_CREAM
                ? "createVirtualDisplay" : "createDisplay";
        return hookAllNamed(c, target, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args == null) return;
                for (int i = 0; i < p.args.length; i++) {
                    if (p.args[i] instanceof Boolean) {
                        p.args[i] = Boolean.TRUE;
                        return;
                    }
                }
            }
        });
    }

    private int hookActivityTaskManagerService(ClassLoader cl) {
        Class<?> c = XposedHelpers.findClassIfExists(
                "com.android.server.wm.ActivityTaskManagerService", cl);
        if (c == null) return 0;
        return hookAllNamed(c, "registerScreenCaptureObserver",
                XC_MethodReplacement.returnConstant(null));
    }

    private int hookWindowManagerService(ClassLoader cl) {
        Class<?> c = XposedHelpers.findClassIfExists(
                "com.android.server.wm.WindowManagerService", cl);
        if (c == null) return 0;
        return hookAllNamed(c, "registerScreenRecordingCallback",
                XC_MethodReplacement.returnConstant(Boolean.FALSE));
    }

    private int hookActivityManagerService(ClassLoader cl) {
        Class<?> c = XposedHelpers.findClassIfExists(
                "com.android.server.am.ActivityManagerService", cl);
        if (c == null) return 0;
        return hookAllNamed(c, "checkPermission", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args != null && p.args.length > 0
                        && "android.permission.CAPTURE_BLACKOUT_CONTENT"
                                .equals(p.args[0])) {
                    p.args[0] = "android.permission.READ_FRAME_BUFFER";
                }
            }
        });
    }
}