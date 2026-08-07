package com.shatyuka.zhiliao.hooks;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.shatyuka.zhiliao.Helper;
import com.shatyuka.zhiliao.xposed.XC_MethodHook;
import com.shatyuka.zhiliao.xposed.XposedBridge;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * View-level fallback for explicitly labelled template ads. Model classes and API endpoints move
 * frequently, while the legally required visible "广告" label is substantially more stable.
 */
public final class TemplateAdView implements IHook {
    private static final Map<View, OriginalLayout> hiddenViews =
            Collections.synchronizedMap(new WeakHashMap<>());

    private Class<?> recyclerAdapter;
    private Field itemViewField;

    @Override
    public String getName() {
        return "新版模板广告兜底过滤";
    }

    @Override
    public void init(ClassLoader classLoader) throws Throwable {
        recyclerAdapter = classLoader.loadClass("androidx.recyclerview.widget.RecyclerView$Adapter");
        Class<?> viewHolder = classLoader.loadClass("androidx.recyclerview.widget.RecyclerView$ViewHolder");
        itemViewField = viewHolder.getField("itemView");
    }

    @Override
    public void hook() {
        XposedBridge.hookAllMethods(recyclerAdapter, "bindViewHolder", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws IllegalAccessException {
                View itemView = getItemView(param);
                if (itemView != null)
                    restore(itemView);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws IllegalAccessException {
                if (!Helper.prefs.getBoolean("switch_mainswitch", false)
                        || !(Helper.prefs.getBoolean("switch_answerad", true)
                        || Helper.prefs.getBoolean("switch_feedad", true)))
                    return;
                View itemView = getItemView(param);
                if (itemView != null)
                    itemView.post(() -> updateVisibility(itemView));
            }

            private View getItemView(MethodHookParam param) throws IllegalAccessException {
                if (param.args.length == 0 || param.args[0] == null)
                    return null;
                Object value = itemViewField.get(param.args[0]);
                return value instanceof View ? (View) value : null;
            }
        });
    }

    private static void updateVisibility(View itemView) {
        if (containsAdLabel(itemView))
            hide(itemView);
    }

    private static boolean containsAdLabel(View view) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && ("广告".contentEquals(text) || "的广告".contentEquals(text)))
                return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsAdLabel(group.getChildAt(i)))
                    return true;
            }
        }
        return false;
    }

    private static void hide(View view) {
        if (!hiddenViews.containsKey(view)) {
            ViewGroup.LayoutParams params = view.getLayoutParams();
            hiddenViews.put(view, new OriginalLayout(view.getVisibility(), params == null ? 0 : params.height));
        }
        view.setVisibility(View.GONE);
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null && params.height != 0) {
            params.height = 0;
            view.setLayoutParams(params);
        }
    }

    private static void restore(View view) {
        OriginalLayout original = hiddenViews.remove(view);
        if (original == null)
            return;
        view.setVisibility(original.visibility);
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null) {
            params.height = original.height;
            view.setLayoutParams(params);
        }
    }

    private static final class OriginalLayout {
        final int visibility;
        final int height;

        OriginalLayout(int visibility, int height) {
            this.visibility = visibility;
            this.height = height;
        }
    }
}
