package com.shatyuka.zhiliao.hooks;

import com.shatyuka.zhiliao.Helper;
import com.shatyuka.zhiliao.xposed.XC_MethodHook;
import com.shatyuka.zhiliao.xposed.XposedBridge;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.WeakHashMap;

public class AutoRefresh implements IHook {
    static Method tryRefresh;
    static Method reasonRefresh;
    static Method postRefreshSucceed;

    private static final Map<Object, Integer> initialRefreshResults = new WeakHashMap<>();

    @Override
    public String getName() {
        return "禁止自动刷新";
    }

    @Override
    public void init(ClassLoader classLoader) throws Throwable {
        try {
            Class<?> feedFragment = classLoader.loadClass("com.zhihu.android.app.feed.ui2.feed.FeedFragment");
            for (Method method : feedFragment.getDeclaredMethods()) {
                if (method.getReturnType() == void.class
                        && method.getParameterCount() == 2
                        && method.getParameterTypes()[0] == boolean.class
                        && method.getParameterTypes()[1].getName()
                                .equals("com.zhihu.android.feed.delegate.m")) {
                    method.setAccessible(true);
                    reasonRefresh = method;
                } else if (method.getName().equals("postRefreshSucceed")
                        && method.getParameterCount() == 1) {
                    method.setAccessible(true);
                    postRefreshSucceed = method;
                }
            }
        } catch (ClassNotFoundException ignored) {
        }

        try {
            Class<?> manager = classLoader.loadClass("com.zhihu.android.app.feed.util.FeedAutoRefreshManager");
            findTryRefreshMethod(manager);
        } catch (ClassNotFoundException ignored) {
            Helper.findClass(classLoader, "com.zhihu.android.app.feed.util.", 0, 1, this::findTryRefreshMethod);
        }
    }

    @Override
    public void hook() throws Throwable {
        if (!Helper.prefs.getBoolean("switch_mainswitch", false)
                || !Helper.prefs.getBoolean("switch_autorefresh", false)) {
            return;
        }

        if (reasonRefresh != null) {
            XposedBridge.hookMethod(reasonRefresh, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    markRefreshStarted(param.thisObject, (boolean) param.args[0]);
                }
            });
        }

        if (postRefreshSucceed != null) {
            XposedBridge.hookMethod(postRefreshSucceed, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (shouldSuppressRefreshResult(param.thisObject)) {
                        param.setResult(null);
                    }
                }
            });
        }

        if (tryRefresh != null) {
            // Elapsed-time gate used when the process returns from the background.
            XposedBridge.hookMethod(tryRefresh, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(null);
                }
            });
        }
    }

    private static void markRefreshStarted(Object fragment, boolean userInitiated) {
        synchronized (initialRefreshResults) {
            if (userInitiated) {
                initialRefreshResults.remove(fragment);
            } else {
                initialRefreshResults.put(fragment, 0);
            }
        }
    }

    private static boolean shouldSuppressRefreshResult(Object fragment) {
        synchronized (initialRefreshResults) {
            Integer resultCount = initialRefreshResults.get(fragment);
            if (resultCount == null) {
                return false;
            }
            initialRefreshResults.put(fragment, resultCount + 1);
            return resultCount > 0;
        }
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
