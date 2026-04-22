package de.robv.android.xposed;

public abstract class XC_MethodHook {
    public XC_MethodHook() {}
    public XC_MethodHook(int priority) {}

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static class MethodHookParam {
        public Object thisObject;
        public Object[] args;
        public java.lang.reflect.Member method;
        public void setResult(Object result) {}
        public Object getResult() { return null; }
        public Throwable getThrowable() { return null; }
        public void setThrowable(Throwable t) {}
        public boolean hasThrowable() { return false; }
        public Object getResultOrThrowable() throws Throwable { return null; }
    }

    public static class Unhook {
        public void unhook() {}
    }
}
