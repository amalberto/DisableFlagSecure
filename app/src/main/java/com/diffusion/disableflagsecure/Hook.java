package com.diffusion.disableflagsecure;

import android.view.SurfaceControl;

import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Hook implements IXposedHookLoadPackage {

    private static final String TAG = "DisableFlagSecure";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        // Only operate inside system_server (package name "android").
        // FLAG_SECURE is enforced there via WindowState / SurfaceControl.
        if (!"android".equals(lpp.packageName)) return;

        XposedBridge.log(TAG + ": loaded in " + lpp.packageName);

        int hookedCount = 0;
        hookedCount += hookTransactionSetSecure();
        hookedCount += hookWindowStateIsSecure(lpp.classLoader);
        hookedCount += hookSurfaceControlBuilderSetSecure();
        XposedBridge.log(TAG + ": total hooks installed = " + hookedCount);
    }

    // Hook 1: force SurfaceControl$Transaction.setSecure(sc, isSecure) -> isSecure=false
    private int hookTransactionSetSecure() {
        int count = 0;
        try {
            Class<?> tx = SurfaceControl.Transaction.class;
            for (Method m : tx.getDeclaredMethods()) {
                if (!"setSecure".equals(m.getName())) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        if (p.args == null) return;
                        for (int i = 0; i < p.args.length; i++) {
                            if (p.args[i] instanceof Boolean) {
                                p.args[i] = Boolean.FALSE;
                            }
                        }
                    }
                });
                XposedBridge.log(TAG + ": hooked " + m);
                count++;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Transaction.setSecure hook FAILED: " + t);
        }
        return count;
    }

    // Hook 2: WindowState.isSecureLocked() -> false (backup path used by some capture flows)
    private int hookWindowStateIsSecure(ClassLoader cl) {
        int count = 0;
        String[] classNames = new String[]{
                "com.android.server.wm.WindowState",
                "com.android.server.wm.WindowStateAnimator"
        };
        String[] methodNames = new String[]{"isSecureLocked", "isSecure"};
        for (String clsName : classNames) {
            Class<?> cls = XposedHelpers.findClassIfExists(clsName, cl);
            if (cls == null) continue;
            for (Method m : cls.getDeclaredMethods()) {
                for (String mn : methodNames) {
                    if (!mn.equals(m.getName())) continue;
                    if (m.getParameterTypes().length != 0) continue;
                    if (m.getReturnType() != boolean.class) continue;
                    try {
                        XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(Boolean.FALSE));
                        XposedBridge.log(TAG + ": hooked " + m);
                        count++;
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": failed hook on " + m + ": " + t);
                    }
                }
            }
        }
        return count;
    }

    // Hook 3: SurfaceControl.Builder.setSecure(boolean) -> false
    // Covers the creation path (older Android) where secure is set on the Builder.
    private int hookSurfaceControlBuilderSetSecure() {
        int count = 0;
        try {
            Class<?> builder = SurfaceControl.Builder.class;
            for (Method m : builder.getDeclaredMethods()) {
                if (!"setSecure".equals(m.getName())) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        if (p.args == null) return;
                        for (int i = 0; i < p.args.length; i++) {
                            if (p.args[i] instanceof Boolean) {
                                p.args[i] = Boolean.FALSE;
                            }
                        }
                    }
                });
                XposedBridge.log(TAG + ": hooked " + m);
                count++;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Builder.setSecure hook skipped: " + t);
        }
        return count;
    }
}
