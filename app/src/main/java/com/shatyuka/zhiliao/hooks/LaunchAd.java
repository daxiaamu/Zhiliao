package com.shatyuka.zhiliao.hooks;

import com.shatyuka.zhiliao.DexResolver;
import com.shatyuka.zhiliao.Helper;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import android.app.Activity;
import android.view.View;

import com.shatyuka.zhiliao.xposed.XC_MethodHook;
import com.shatyuka.zhiliao.xposed.XposedBridge;
import com.shatyuka.zhiliao.xposed.XposedHelpers;

public class LaunchAd implements IHook {
    static Class<?> AdNetworkManager;

    static Method isShowLaunchAd;
    static Method requestLaunchAd;
    static Method requestLaunchFlow;
    static Method continueLaunch;
    static Method skipLauncherAdCheck;

    @Override
    public String getName() {
        return "去启动页广告";
    }

    @Override
    public void init(final ClassLoader classLoader) throws Throwable {
        AdNetworkManager = Helper.findClass(classLoader, "com.zhihu.android.sdk.launchad.", 0, 28,
                LaunchAd::isAdNetworkManager);
        if (AdNetworkManager == null) {
            throw new ClassNotFoundException("com.zhihu.android.sdk.launchad.AdNetworkManager");
        }
        Class<?> LaunchAdInterface = classLoader.loadClass("com.zhihu.android.ad.LaunchAdInterface");
        Helper.findClass(classLoader, "com.zhihu.android.app.util.", 0, 28,
                (Class<?> LaunchAdHelper) -> {
                    if (!LaunchAdInterface.isAssignableFrom(LaunchAdHelper) || LaunchAdHelper.isInterface())
                        return false;
                    isShowLaunchAd = LaunchAdHelper.getMethod("isShowLaunchAd");
                    return true;
                });
        if (isShowLaunchAd == null)
            isShowLaunchAd = DexResolver.findMethod("launch_ad_visibility",
                    "com.zhihu.android.app.util", LaunchAdInterface,
                    "isShowLaunchAd", boolean.class);
        if (isShowLaunchAd == null)
            throw new NoSuchMethodException("com.zhihu.android.app.util.LaunchAdHelper.isShowLaunchAd()");

        requestLaunchAd = Helper.getMethodByParameterTypes(AdNetworkManager,
                int.class, long.class, long.class, String.class);
        if (requestLaunchAd == null)
            throw new NoSuchMethodException("AdNetworkManager.requestLaunchAd(int,long,long,String)");

        // Zhihu 11.4 keeps the splash visible while this ad flow waits and later
        // budgets roughly 2600 ms for presentation. Calling its completion callback
        // skips only that launch-ad gate; Application initialization remains intact.
        try {
            Class<?> callback = classLoader.loadClass("com.zhihu.android.s1.a.a.b");
            Class<?> launchImpl = classLoader.loadClass("com.zhihu.android.launch.impl.LaunchImpl");
            requestLaunchFlow = launchImpl.getMethod("requestAd", String.class, callback, View.class);
            continueLaunch = callback.getMethod("b");
        } catch (Throwable ignored) {
            requestLaunchFlow = null;
            continueLaunch = null;
        }
        // Google Play 10.95 asks this predicate whether LauncherActivity can
        // take its own no-ad continuation. Returning true preserves Zhihu's
        // normal routing/initialization while avoiding the several-second ad wait.
        try {
            Class<?> launchAdDecision = classLoader.loadClass("com.zhihu.android.ad.utils.i1");
            for (Method method : launchAdDecision.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (Modifier.isStatic(method.getModifiers())
                        && method.getReturnType() == boolean.class
                        && parameters.length == 1
                        && parameters[0] == Activity.class) {
                    skipLauncherAdCheck = method;
                    break;
                }
            }
        } catch (Throwable ignored) {
            skipLauncherAdCheck = null;
        }
    }

    @Override
    public void hook() throws Throwable {
        XposedBridge.hookMethod(isShowLaunchAd, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (Helper.prefs.getBoolean("switch_mainswitch", false) && Helper.prefs.getBoolean("switch_launchad", true))
                    param.setResult(false);
            }
        });
        XposedBridge.hookMethod(requestLaunchAd, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (Helper.prefs.getBoolean("switch_mainswitch", false) && Helper.prefs.getBoolean("switch_launchad", true)) {
                    param.setResult("");
                }
            }
        });
        if (requestLaunchFlow != null && continueLaunch != null) {
            XposedBridge.hookMethod(requestLaunchFlow, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (Helper.prefs.getBoolean("switch_mainswitch", false)
                            && Helper.prefs.getBoolean("switch_launchad", true)) {
                        continueLaunch.invoke(param.args[1]);
                        param.setResult(null);
                    }
                }
            });
        }
        if (skipLauncherAdCheck != null) {
            XposedBridge.hookMethod(skipLauncherAdCheck, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (Helper.prefs.getBoolean("switch_mainswitch", false)
                            && Helper.prefs.getBoolean("switch_launchad", true)
                            && param.args[0] instanceof Activity
                            && "com.zhihu.android.app.ui.activity.LauncherActivity".equals(
                                    param.args[0].getClass().getName())) {
                        param.setResult(true);
                    }
                }
            });
        }
    }

    private static boolean isAdNetworkManager(Class<?> clazz) {
        boolean hasOkHttp = false;
        for (Field field : clazz.getDeclaredFields()) {
            if (field.getType().getName().startsWith("okhttp3.")) {
                hasOkHttp = true;
                break;
            }
        }
        if (!hasOkHttp)
            return false;
        return Helper.getMethodByParameterTypes(clazz,
                int.class, long.class, long.class, String.class) != null;
    }
}
