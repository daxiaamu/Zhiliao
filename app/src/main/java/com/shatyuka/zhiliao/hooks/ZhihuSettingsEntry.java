package com.shatyuka.zhiliao.hooks;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.shatyuka.zhiliao.Helper;
import com.shatyuka.zhiliao.R;
import com.shatyuka.zhiliao.xposed.XC_MethodHook;
import com.shatyuka.zhiliao.xposed.XposedBridge;
import com.shatyuka.zhiliao.xposed.XposedHelpers;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

/** Adds a module entry to the top of Zhihu's own settings list. */
public final class ZhihuSettingsEntry implements IHook {
    private static final String SETTINGS_FRAGMENT =
            "com.zhihu.android.settings.view.SettingsFragment";
    private static final String ENTRY_TAG = "zhiliao:settings-entry";

    private Method populateGroups;
    private Method onViewCreated;
    private Method recyclerOnLayout;
    private int groupContainerId;

    @Override
    public String getName() {
        return "知乎设置页知了入口";
    }

    @Override
    public void init(ClassLoader classLoader) throws Throwable {
        Class<?> fragment = classLoader.loadClass(SETTINGS_FRAGMENT);
        for (Method method : fragment.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getReturnType() == void.class
                    && !Modifier.isStatic(method.getModifiers())
                    && parameters.length == 1
                    && List.class.isAssignableFrom(parameters[0])) {
                populateGroups = method;
                break;
            }
        }
        if (populateGroups == null)
            throw new NoSuchMethodException(SETTINGS_FRAGMENT + "#*(List)");
        onViewCreated = fragment.getMethod("onViewCreated", View.class, Bundle.class);
        Class<?> recyclerView = classLoader.loadClass("androidx.recyclerview.widget.RecyclerView");
        recyclerOnLayout = recyclerView.getDeclaredMethod(
                "onLayout", boolean.class, int.class, int.class, int.class, int.class);
        try {
            Class<?> ids = classLoader.loadClass("com.zhihu.android.settings.R$id");
            for (String name : new String[]{"g0", "settings_container", "group_container"}) {
                try {
                    groupContainerId = ids.getField(name).getInt(null);
                    break;
                } catch (Throwable ignored) {
                    // Resource names may be optimized differently between distribution channels.
                }
            }
        } catch (Throwable ignored) {
            // Some Play builds inline and remove the feature R class entirely.
        }
    }

    @Override
    public void hook() {
        XposedBridge.hookMethod(populateGroups, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.args[0] instanceof List))
                    return;
                Object fragment = param.thisObject;
                int groupCount = ((List<?>) param.args[0]).size();
                Object value = XposedHelpers.callMethod(fragment, "getView");
                if (value instanceof View)
                    tryInsert(fragment, (View) value, groupCount);
            }
        });
        XposedBridge.hookMethod(onViewCreated, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.args[0] instanceof View))
                    return;
                View root = (View) param.args[0];
                Object fragment = param.thisObject;
                root.post(new Runnable() {
                    private int attempts;

                    @Override
                    public void run() {
                        boolean inserted = tryInsert(fragment, root, -1);
                        if (inserted || ++attempts >= 20
                                || !root.isAttachedToWindow())
                            return;
                        root.postDelayed(this, 250);
                    }
                });
            }
        });
        XposedBridge.hookMethod(recyclerOnLayout, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.thisObject instanceof View))
                    return;
                View recycler = (View) param.thisObject;
                View root = recycler.getRootView();
                if (!isSettingsRecycler(recycler) || findTaggedView(root) != null
                        || !containsExactText(root, "设置"))
                    return;
                recycler.post(() -> installRecyclerHeader(recycler));
            }
        });
    }

    private static boolean isSettingsRecycler(View view) {
        if (view.getId() == View.NO_ID)
            return false;
        try {
            return "recycler_view".equals(view.getResources().getResourceEntryName(view.getId()));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void installRecyclerHeader(View recycler) {
        if (!(recycler.getParent() instanceof ViewGroup))
            return;
        ViewGroup parent = (ViewGroup) recycler.getParent();
        View root = recycler.getRootView();
        if (findTaggedView(root) != null || !containsExactText(root, "设置"))
            return;

        int index = parent.indexOfChild(recycler);
        ViewGroup.LayoutParams originalParams = recycler.getLayoutParams();
        parent.removeViewAt(index);

        LinearLayout wrapper = new LinearLayout(parent.getContext());
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setLayoutParams(originalParams);
        parent.addView(wrapper, index);
        wrapper.addView(createEntry(parent.getContext()));
        recycler.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        wrapper.addView(recycler);
    }

    private static boolean containsExactText(View view, String expected) {
        if (view instanceof TextView
                && expected.contentEquals(((TextView) view).getText()))
            return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsExactText(group.getChildAt(i), expected))
                    return true;
            }
        }
        return false;
    }

    private boolean tryInsert(Object fragment, View root, int groupCount) {
        ViewGroup groupContainer = findKnownGroupContainer(fragment);
        if (groupContainer == null)
            groupContainer = findCachedGroupContainer(fragment);
        if (groupContainer == null)
            groupContainer = findGroupContainer(root, groupCount);
        if (groupContainer == null || groupContainer.getChildCount() == 0)
            return false;
        if (findTaggedView(groupContainer) == null)
            groupContainer.addView(createEntry(groupContainer.getContext()), 0);
        return true;
    }

    private ViewGroup findKnownGroupContainer(Object fragment) {
        if (groupContainerId == 0)
            return null;
        try {
            Object value = XposedHelpers.callMethod(
                    fragment, "_$_findCachedViewById", groupContainerId);
            return value instanceof ViewGroup ? (ViewGroup) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ViewGroup findCachedGroupContainer(Object fragment) {
        ViewGroup best = null;
        Class<?> type = fragment.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(field.getType()) || Modifier.isStatic(field.getModifiers()))
                    continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(fragment);
                    if (!(value instanceof Map))
                        continue;
                    for (Object cached : ((Map<?, ?>) value).values()) {
                        if (!(cached instanceof ViewGroup))
                            continue;
                        ViewGroup candidate = (ViewGroup) cached;
                        if (!candidate.getClass().getName().endsWith("ZHLinearLayout")
                                || candidate.getChildCount() == 0)
                            continue;
                        if (best == null || candidate.getChildCount() > best.getChildCount())
                            best = candidate;
                    }
                } catch (Throwable ignored) {
                    // Try the next cache field; its name and visibility are version-dependent.
                }
            }
            type = type.getSuperclass();
        }
        return best;
    }

    private static View findTaggedView(View view) {
        if (ENTRY_TAG.equals(view.getTag()))
            return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View result = findTaggedView(group.getChildAt(i));
                if (result != null)
                    return result;
            }
        }
        return null;
    }

    private static ViewGroup findGroupContainer(View view, int groupCount) {
        if (!(view instanceof ViewGroup))
            return null;
        ViewGroup group = (ViewGroup) view;
        String className = group.getClass().getName();
        if (className.endsWith("ZHLinearLayout") && group.getChildCount() > 0
                && (groupCount < 0 || group.getChildCount() == groupCount))
            return group;
        for (int i = 0; i < group.getChildCount(); i++) {
            ViewGroup result = findGroupContainer(group.getChildAt(i), groupCount);
            if (result != null)
                return result;
        }
        return null;
    }

    private static View createEntry(Context context) {
        final int primary = themeColor(context, android.R.attr.textColorPrimary, 0xff1a1a1a);
        final int secondary = themeColor(context, android.R.attr.textColorSecondary, 0xff808080);

        LinearLayout row = new LinearLayout(context);
        row.setTag(ENTRY_TAG);
        row.setContentDescription("知了设置");
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(16), 0);
        row.setClickable(true);
        row.setFocusable(true);
        TypedValue selectable = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, selectable, true))
            row.setBackgroundResource(selectable.resourceId);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        ImageView icon = new ImageView(context);
        Drawable drawable = Helper.modRes.getDrawable(R.drawable.ic_cicada_outline, null).mutate();
        drawable.setTint(primary);
        icon.setImageDrawable(drawable);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        iconParams.setMarginEnd(dp(16));
        row.addView(icon, iconParams);

        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(context);
        title.setText("知了");
        title.setTextColor(primary);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        TextView summary = new TextView(context);
        summary.setText("打开知了设置");
        summary.setTextColor(secondary);
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        labels.addView(title);
        labels.addView(summary);
        row.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = new TextView(context);
        arrow.setText("\u203a");
        arrow.setTextColor(secondary);
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        row.addView(arrow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        row.setOnClickListener(v -> {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(
                    "com.shatyuka.zhiliao", "com.shatyuka.zhiliao.MainActivity"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        });
        return row;
    }

    private static int themeColor(Context context, int attribute, int fallback) {
        TypedArray colors = context.obtainStyledAttributes(new int[]{attribute});
        try {
            return colors.getColor(0, fallback);
        } finally {
            colors.recycle();
        }
    }

    private static int dp(int value) {
        return Math.round(value * Helper.scale);
    }
}
