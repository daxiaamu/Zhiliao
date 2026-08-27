package com.shatyuka.zhiliao.hooks;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.View;

import com.shatyuka.zhiliao.CompatibilityRegistry;
import com.shatyuka.zhiliao.DexResolver;
import com.shatyuka.zhiliao.Helper;
import com.shatyuka.zhiliao.xposed.XC_MethodHook;
import com.shatyuka.zhiliao.xposed.XposedBridge;

import java.lang.reflect.Method;
public class LaunchAd implements IHook {
    static Method isShowLaunchAd;
    static Method requestLaunchFlow;
    static Method continueLaunch;

    @Override
    public String getName() {
        return "去启动页广告";
    }

    @Override
    public void init(final ClassLoader classLoader) throws Throwable {
        try {
            Class<?> launchAdInterface = classLoader.loadClass(
                    "com.zhihu.android.ad.LaunchAdInterface");
            Helper.findClass(classLoader, "com.zhihu.android.app.util.", 0, 28,
                    (Class<?> launchAdHelper) -> {
                        if (!launchAdInterface.isAssignableFrom(launchAdHelper)
                                || launchAdHelper.isInterface())
                            return false;
                        isShowLaunchAd = launchAdHelper.getMethod("isShowLaunchAd");
                        return true;
                    });
            if (isShowLaunchAd == null)
                isShowLaunchAd = DexResolver.findMethod("launch_ad_visibility",
                        "com.zhihu.android.app.util", launchAdInterface,
                        "isShowLaunchAd", boolean.class);
        } catch (Throwable ignored) {
            isShowLaunchAd = null;
        }

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
    }

    @Override
    public void hook() throws Throwable {
        if (isShowLaunchAd != null) {
            XposedBridge.hookMethod(isShowLaunchAd, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (Helper.prefs.getBoolean("switch_mainswitch", false)
                            && Helper.prefs.getBoolean("switch_launchad", true))
                        param.setResult(false);
                }
            });
        }
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
        registerLifecycleFallback();
    }

    private static void registerLifecycleFallback() {
        if (!(Helper.context instanceof Application))
            return;
        ((Application) Helper.context).registerActivityLifecycleCallbacks(
                new Application.ActivityLifecycleCallbacks() {
                    @Override public void onActivityCreated(Activity activity, Bundle state) {
                        scheduleCloseAttempts(activity, activity.getWindow().getDecorView());
                    }
                    @Override public void onActivityResumed(Activity activity) {
                        scheduleCloseAttempts(activity, activity.getWindow().getDecorView());
                    }
                    @Override public void onActivityStarted(Activity activity) {}
                    @Override public void onActivityPaused(Activity activity) {}
                    @Override public void onActivityStopped(Activity activity) {}
                    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
                    @Override public void onActivityDestroyed(Activity activity) {}
                });
    }

    private static void scheduleCloseAttempts(Activity activity, View decor) {
        Runnable skip = () -> clickLaunchAdClose(activity, decor);
        decor.post(skip);
        decor.postDelayed(skip, 250L);
        decor.postDelayed(skip, 750L);
        decor.postDelayed(skip, 1500L);
        decor.postDelayed(skip, 2500L);
    }

    private static void clickLaunchAdClose(Activity activity, View root) {
        if (!Helper.prefs.getBoolean("switch_mainswitch", false)
                || !Helper.prefs.getBoolean("switch_launchad", true)
                || activity.isFinishing()
                || !Helper.hookPackage.equals(activity.getPackageName()))
            return;
        String name = activity.getClass().getSimpleName();
        if (!name.contains("Launch") && !name.contains("Ad"))
            return;
        java.util.List<String> ids = CompatibilityRegistry.getSymbolCandidates(
                "launchAdCloseViewIds");
        if (ids.isEmpty())
            ids = java.util.List.of("btn_skip", "tv_ad_close");
        for (String idName : ids) {
            int id = root.getResources().getIdentifier(idName, "id", activity.getPackageName());
            View close = root.findViewById(id);
            if (close != null && close.isShown() && close.isClickable()) {
                close.performClick();
                return;
            }
        }
    }
}
