package com.shatyuka.zhiliao.hooks;

import com.shatyuka.zhiliao.CompatibilityRegistry;
import com.shatyuka.zhiliao.DexResolver;
import com.shatyuka.zhiliao.Helper;
import com.shatyuka.zhiliao.xposed.XC_MethodHook;
import com.shatyuka.zhiliao.xposed.XposedBridge;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public class AutoRefresh implements IHook {
    static Method tryRefresh;
    static Method reasonRefresh;
    static Method postRefreshSucceed;
    static final List<Method> reasonRefreshMethods = new ArrayList<>();

    private static final Map<Object, Integer> initialRefreshResults = new WeakHashMap<>();

    @Override
    public String getName() {
        return "禁止自动刷新";
    }

    @Override
    public void init(ClassLoader classLoader) throws Throwable {
        tryRefresh = null;
        reasonRefresh = null;
        postRefreshSucceed = null;
        reasonRefreshMethods.clear();

        Class<?> feedFragment = loadFirstClass(classLoader,
                CompatibilityRegistry.getSymbolCandidates("AutoRefresh.feedFragmentClasses"));
        if (feedFragment == null) {
            try {
                feedFragment = classLoader.loadClass(
                        "com.zhihu.android.app.feed.ui2.feed.FeedFragment");
            } catch (ClassNotFoundException ignored) {
            }
        }
        if (feedFragment == null)
            feedFragment = DexResolver.findClassByRule("AutoRefresh.feedFragment");
        if (feedFragment == null)
            feedFragment = DexResolver.findClassByMethodName("auto_refresh_feed_fragment",
                    "com.zhihu.android.app.feed", "postRefreshSucceed");
        if (feedFragment != null) {
            Set<String> configuredReasonTypes = new HashSet<>(
                    CompatibilityRegistry.getSymbolCandidates("autoRefreshReasonTypes"));
            List<Method> structuralCandidates = new ArrayList<>();
            for (Method method : feedFragment.getDeclaredMethods()) {
                if (method.getReturnType() == void.class
                        && method.getParameterCount() == 2
                        && method.getParameterTypes()[0] == boolean.class
                        && !method.getParameterTypes()[1].isPrimitive()) {
                    method.setAccessible(true);
                    structuralCandidates.add(method);
                    if (configuredReasonTypes.contains(method.getParameterTypes()[1].getName()))
                        reasonRefreshMethods.add(method);
                } else if (method.getName().equals("postRefreshSucceed")
                        && method.getParameterCount() == 1) {
                    method.setAccessible(true);
                    postRefreshSucceed = method;
                }
            }
            if (reasonRefreshMethods.isEmpty()) {
                // Kotlin commonly emits an outer delegate and an inner implementation with the
                // same (fromUser, refreshType) signature, so every validated wrapper is hooked.
                for (Method method : structuralCandidates) {
                    Class<?> reasonType = method.getParameterTypes()[1];
                    if (reasonType.isInterface() || reasonType.isEnum()
                            || Modifier.isAbstract(reasonType.getModifiers()))
                        reasonRefreshMethods.add(method);
                }
            }
            if (!reasonRefreshMethods.isEmpty())
                reasonRefresh = reasonRefreshMethods.get(0);
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

        for (Method refreshBoundary : reasonRefreshMethods) {
            XposedBridge.hookMethod(refreshBoundary, new XC_MethodHook() {
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

    private static Class<?> loadFirstClass(ClassLoader classLoader, List<String> candidates) {
        for (String candidate : candidates) {
            try {
                return classLoader.loadClass(candidate);
            } catch (ClassNotFoundException ignored) {
            }
        }
        return null;
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
