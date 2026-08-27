package com.shatyuka.zhiliao.hooks;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import com.shatyuka.zhiliao.CompatibilityRegistry;
import com.shatyuka.zhiliao.DexResolver;
import com.shatyuka.zhiliao.Helper;
import com.shatyuka.zhiliao.xposed.XC_MethodHook;
import com.shatyuka.zhiliao.xposed.XposedBridge;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Disables the task-marketing slot placed immediately before the home search bar. */
public final class CashEntry implements IHook {
    private static final String MAIN_ACTIVITY =
            "com.zhihu.android.app.ui.activity.MainActivity";
    private static final String MAIN_PAGE_FRAGMENT =
            "com.zhihu.android.app.feed.explore.view.MainPageFragment";
    private static final String USER_TASK_DATA =
            "Lcom/zhihu/android/app/feed/ui2/usertask/model/UserTaskData;";
    private static final Map<View, Integer> hiddenViews =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static int topContainerId;
    private static int searchContainerId;
    private static int iconId;

    private Class<?> viewClass;
    private Class<?> viewGroupClass;
    private Method displayMethod;

    @Override
    public String getName() {
        return "关闭搜索栏左侧营销位";
    }

    @Override
    public void init(ClassLoader classLoader) throws Throwable {
        viewClass = classLoader.loadClass("android.view.View");
        viewGroupClass = classLoader.loadClass("android.view.ViewGroup");
        try {
            Class<?> fragmentClass = classLoader.loadClass(MAIN_PAGE_FRAGMENT);
            displayMethod = configuredMethod(fragmentClass);
            if (displayMethod == null) {
                displayMethod = DexResolver.findMethodByInvokes(
                        "cash_entry_display",
                        "com.zhihu.android.app.feed.explore.view",
                        MAIN_PAGE_FRAGMENT,
                        1,
                        USER_TASK_DATA + "->getNavigationBarImageUrl()Ljava/lang/String;",
                        USER_TASK_DATA + "->getRouter()Ljava/lang/String;");
            }
        } catch (Throwable ignored) {
            displayMethod = null;
        }
    }

    @Override
    public void hook() {
        if (displayMethod != null) {
            XposedBridge.hookMethod(displayMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isEnabled())
                        param.setResult(null);
                }
            });
        }
        hookVisibilityWrites();
        hookStructuralFallback(true);
        registerLifecycleFallback();
    }

    /** Blocks guide/task callbacks from making the already-created entry visible again. */
    private void hookVisibilityWrites() {
        XposedBridge.hookAllMethods(viewClass, "setVisibility", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!(param.thisObject instanceof View) || param.args.length != 1
                        || !(param.args[0] instanceof Integer)
                        || (Integer) param.args[0] != View.VISIBLE)
                    return;
                View view = (View) param.thisObject;
                if (isCashEntry(view) && isEnabled()) {
                    hiddenViews.putIfAbsent(view, View.VISIBLE);
                    param.args[0] = View.GONE;
                }
            }
        });
    }

    private static Method configuredMethod(Class<?> fragmentClass) {
        for (String name : CompatibilityRegistry.getSymbolCandidates("cashEntryMethods")) {
            for (Method method : fragmentClass.getDeclaredMethods()) {
                if (name.equals(method.getName()) && method.getReturnType() == void.class
                        && method.getParameterCount() == 1)
                    return method;
            }
        }
        return null;
    }

    /** Last-resort protection for a new version until CI publishes a symbol profile. */
    private void hookStructuralFallback(boolean monitorAddView) {
        if (monitorAddView) {
            XposedBridge.hookAllMethods(viewGroupClass, "addView", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isEnabled() || !(param.thisObject instanceof ViewGroup)
                            || param.args.length == 0 || !(param.args[0] instanceof View))
                        return;
                    ViewGroup parent = (ViewGroup) param.thisObject;
                    View child = (View) param.args[0];
                    ensureIds(parent);
                    if (topContainerId != 0 && iconId != 0
                            && parent.getId() == topContainerId && child.getId() == iconId)
                        hide(child);
                }
            });
        }
    }

    private static void registerLifecycleFallback() {
        if (!(Helper.context instanceof Application))
            return;
        ((Application) Helper.context).registerActivityLifecycleCallbacks(
                new Application.ActivityLifecycleCallbacks() {
                    private void update(Activity activity) {
                        if (MAIN_ACTIVITY.equals(activity.getClass().getName()))
                            scheduleVisibilityUpdates(activity.getWindow().getDecorView());
                    }
                    @Override public void onActivityCreated(Activity activity, Bundle state) {
                        update(activity);
                    }
                    @Override public void onActivityResumed(Activity activity) {
                        update(activity);
                    }
                    @Override public void onActivityStarted(Activity activity) {}
                    @Override public void onActivityPaused(Activity activity) {}
                    @Override public void onActivityStopped(Activity activity) {}
                    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
                    @Override public void onActivityDestroyed(Activity activity) {}
                });
    }

    private static void scheduleVisibilityUpdates(View decor) {
        Runnable update = () -> updateVisibility(decor);
        decor.post(update);
        decor.postDelayed(update, 250L);
        decor.postDelayed(update, 750L);
        decor.postDelayed(update, 1500L);
        decor.postDelayed(update, 2500L);
    }

    private static void updateVisibility(View root) {
        ensureIds(root);
        if (topContainerId == 0 || searchContainerId == 0 || iconId == 0)
            return;
        updateTree(root);
    }

    private static void updateTree(View view) {
        if (!(view instanceof ViewGroup))
            return;
        ViewGroup group = (ViewGroup) view;
        if (view.getId() == topContainerId)
            updateHeader(group);
        for (int i = 0; i < group.getChildCount(); i++)
            updateTree(group.getChildAt(i));
    }

    private static void updateHeader(ViewGroup header) {
        View search = null;
        View entry = null;
        for (int i = 0; i < header.getChildCount(); i++) {
            View child = header.getChildAt(i);
            if (child.getId() == searchContainerId)
                search = child;
            else if (child.getId() == iconId)
                entry = child;
        }
        if (search == null || entry == null)
            return;
        if (isEnabled()) {
            hide(entry);
        } else {
            Integer visibility = hiddenViews.remove(entry);
            if (visibility != null)
                entry.setVisibility(visibility);
        }
    }

    private static boolean isEnabled() {
        return Helper.prefs.getBoolean("switch_mainswitch", false)
                && Helper.prefs.getBoolean("switch_cashentry", true);
    }

    private static void hide(View view) {
        hiddenViews.putIfAbsent(view, view.getVisibility());
        view.setVisibility(View.GONE);
    }

    private static boolean isCashEntry(View view) {
        ensureIds(view);
        if (iconId == 0 || view.getId() != iconId || !(view.getParent() instanceof ViewGroup))
            return false;
        return isCashEntry((ViewGroup) view.getParent(), view);
    }

    private static boolean isCashEntry(ViewGroup parent, View child) {
        ensureIds(parent);
        return topContainerId != 0 && iconId != 0
                && parent.getId() == topContainerId && child.getId() == iconId;
    }

    private static void ensureIds(View view) {
        if (topContainerId != 0 && searchContainerId != 0 && iconId != 0)
            return;
        topContainerId = id(view, "top_container");
        searchContainerId = id(view, "search_container");
        iconId = id(view, "icon");
    }

    private static int id(View view, String name) {
        return view.getResources().getIdentifier(name, "id", Helper.hookPackage);
    }
}