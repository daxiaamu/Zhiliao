package com.shatyuka.zhiliao.hooks;

import com.shatyuka.zhiliao.Helper;
import com.shatyuka.zhiliao.xposed.XC_MethodHook;
import com.shatyuka.zhiliao.xposed.XposedBridge;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public class AutoRefresh implements IHook {
    static Method tryRefresh;

    @Override
    public String getName() {
        return "禁止自动刷新";
    }

    @Override
    public void init(ClassLoader classLoader) throws Throwable {
        try {
            Class<?> manager = classLoader.loadClass("com.zhihu.android.app.feed.util.FeedAutoRefreshManager");
            findTryRefreshMethod(manager);
        } catch (ClassNotFoundException ignored) {
            Helper.findClass(classLoader, "com.zhihu.android.app.feed.util.", 0, 1, this::findTryRefreshMethod);
        }

    }

    @Override
    public void hook() throws Throwable {
        if (tryRefresh == null
                || !Helper.prefs.getBoolean("switch_mainswitch", false)
                || !Helper.prefs.getBoolean("switch_autorefresh", false)) {
            return;
        }

        // This is the manager's elapsed-time gate used by foreground/background automatic
        // refresh. Manual pull-to-refresh and pagination use the feed data source directly and
        // must remain untouched.
        XposedBridge.hookMethod(tryRefresh, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(null);
            }
        });
    }

    private boolean findTryRefreshMethod(Class<?> clazz) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getReturnType() == void.class
                    && method.getParameterCount() == 4
                    && method.getParameterTypes()[0] == long.class
                    && method.getParameterTypes()[1] == int.class
                    && (method.getParameterTypes()[2].getModifiers() & Modifier.INTERFACE) != 0
                    && (method.getParameterTypes()[3].getModifiers() & Modifier.ABSTRACT) != 0) {
                method.setAccessible(true);
                tryRefresh = method;
                return true;
            }
        }
        return false;
    }
}
