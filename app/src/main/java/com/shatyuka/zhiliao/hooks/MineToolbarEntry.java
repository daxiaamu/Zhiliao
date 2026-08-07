package com.shatyuka.zhiliao.hooks;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.shatyuka.zhiliao.DexResolver;
import com.shatyuka.zhiliao.Helper;
import com.shatyuka.zhiliao.R;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import com.shatyuka.zhiliao.xposed.XC_MethodHook;
import com.shatyuka.zhiliao.xposed.XposedBridge;

/** Adds the module entry after Zhihu's settings icon on the Mine page. */
public final class MineToolbarEntry implements IHook {
    private static final String ENTRY_TAG = "zhiliao:mine-toolbar-entry";
    private static final String MODULE_PACKAGE = "com.shatyuka.zhiliao";

    private final List<Class<?>> minePageFragments = new ArrayList<>();

    @Override
    public String getName() {
        return "“我的”页知了入口";
    }

    @Override
    public void init(ClassLoader classLoader) throws Throwable {
        addIfPresent(classLoader, "com.zhihu.android.app.ui.fragment.more.mine.MineTabFragment");
        addIfPresent(classLoader, "com.zhihu.android.app.ui.fragment.more.MineProfileEntryFragment");
        addIfPresent(classLoader, "com.zhihu.android.profile.profile.ui.ProfileFragment2");
        if (minePageFragments.isEmpty()) {
            Class<?> fallback = DexResolver.findClassByMethodName("mine_tab_fragment",
                    "com.zhihu.android.app.ui.fragment.more", "onSendPageId");
            if (fallback != null)
                minePageFragments.add(fallback);
        }
        if (minePageFragments.isEmpty())
            throw new ClassNotFoundException("MineTabFragment");
    }

    private void addIfPresent(ClassLoader classLoader, String name) {
        try {
            Class<?> candidate = classLoader.loadClass(name);
            if (!minePageFragments.contains(candidate))
                minePageFragments.add(candidate);
        } catch (ClassNotFoundException ignored) {
        }
    }

    @Override
    public void hook() {
        for (Class<?> fragmentClass : minePageFragments) {
            XposedBridge.hookAllMethods(fragmentClass, "onCreateView", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.getResult() instanceof View))
                        return;
                    View root = (View) param.getResult();
                    root.post(() -> addEntry(root));
                }
            });
        }
    }

    private static void addEntry(View root) {
        try {
            ViewGroup rightIcons = findRightIconContainer(root);
            if (rightIcons == null || containsEntry(rightIcons))
                return;

            Context context = root.getContext();
            ImageButton button = new ImageButton(context);
            button.setTag(ENTRY_TAG);
            button.setContentDescription(Helper.modRes.getString(R.string.zhihu_toolbar_entry));
            button.setScaleType(ImageButton.ScaleType.CENTER);
            button.setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10));

            Drawable icon = Helper.modRes.getDrawable(R.drawable.ic_cicada_outline, context.getTheme()).mutate();
            button.setImageDrawable(icon);
            button.setImageTintList(resolveToolbarTint(context));

            TypedValue background = new TypedValue();
            if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless,
                    background, true)) {
                button.setBackgroundResource(background.resourceId);
            } else {
                button.setBackgroundColor(0x00000000);
            }

            button.setOnClickListener(view -> {
                try {
                    Intent intent = new Intent();
                    intent.setClassName(MODULE_PACKAGE, MODULE_PACKAGE + ".MainActivity");
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                } catch (Throwable throwable) {
                    Toast.makeText(context, Helper.modRes.getString(R.string.operation_failed),
                            Toast.LENGTH_SHORT).show();
                }
            });

            int size = dp(context, 40);
            rightIcons.addView(button, new ViewGroup.LayoutParams(size, size));
            if (isProfileStaticToolbar(rightIcons)) {
                rightIcons.post(() -> positionInProfileToolbar(rightIcons, button, size));
            }
        } catch (Throwable throwable) {
            XposedBridge.log("[Zhiliao] Add Mine toolbar entry failed: " + throwable);
        }
    }

    private static ViewGroup findRightIconContainer(View root) {
        int profileToolbarId = root.getResources().getIdentifier(
                "profile_static_toolbar", "id", root.getContext().getPackageName());
        View profileToolbar = profileToolbarId == 0 ? null : root.findViewById(profileToolbarId);
        if (profileToolbar instanceof ViewGroup)
            return (ViewGroup) profileToolbar;

        int toolbarId = root.getResources().getIdentifier(
                "toolbar2_container", "id", root.getContext().getPackageName());
        View toolbar = toolbarId == 0 ? root : root.findViewById(toolbarId);
        if (toolbar == null)
            toolbar = root;

        LinearLayout getterResult = findByGetter(toolbar);
        if (getterResult != null)
            return getterResult;

        List<LinearLayout> candidates = new ArrayList<>();
        collectLinearLayouts(toolbar, candidates);
        LinearLayout rightMost = null;
        float rightMostX = Float.NEGATIVE_INFINITY;
        for (LinearLayout candidate : candidates) {
            float x = candidate.getX() + candidate.getWidth();
            if (candidate.getOrientation() == LinearLayout.HORIZONTAL && x > rightMostX) {
                rightMostX = x;
                rightMost = candidate;
            }
        }
        return rightMost;
    }

    private static boolean isProfileStaticToolbar(ViewGroup group) {
        int id = group.getResources().getIdentifier(
                "profile_static_toolbar", "id", group.getContext().getPackageName());
        return id != 0 && group.getId() == id;
    }

    private static void positionInProfileToolbar(ViewGroup toolbar, View button, int size) {
        if (toolbar.getWidth() <= 0 || button.getParent() != toolbar)
            return;
        int sidebarId = toolbar.getResources().getIdentifier(
                "sidebar_entry", "id", toolbar.getContext().getPackageName());
        int moreId = toolbar.getResources().getIdentifier(
                "more", "id", toolbar.getContext().getPackageName());
        View anchor = moreId == 0 ? null : toolbar.findViewById(moreId);

        for (int i = 0; i < toolbar.getChildCount(); i++) {
            View child = toolbar.getChildAt(i);
            if (child != button && child.getId() != sidebarId)
                child.setTranslationX(-size);
        }

        button.setX(toolbar.getWidth() - size - dp(toolbar.getContext(), 10));
        button.setY(anchor == null ? dp(toolbar.getContext(), 44) : anchor.getY());
        button.bringToFront();
    }

    private static LinearLayout findByGetter(View view) {
        try {
            Method method = view.getClass().getMethod("getRightIconContainer");
            Object result = method.invoke(view);
            if (result instanceof LinearLayout)
                return (LinearLayout) result;
        } catch (Throwable ignored) {
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                LinearLayout result = findByGetter(group.getChildAt(i));
                if (result != null)
                    return result;
            }
        }
        return null;
    }

    private static void collectLinearLayouts(View view, List<LinearLayout> output) {
        if (view instanceof LinearLayout)
            output.add((LinearLayout) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                collectLinearLayouts(group.getChildAt(i), output);
        }
    }

    private static boolean containsEntry(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            if (ENTRY_TAG.equals(group.getChildAt(i).getTag()))
                return true;
        }
        return false;
    }

    private static ColorStateList resolveToolbarTint(Context context) {
        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.textColorPrimary, value, true)) {
            if (value.resourceId != 0)
                return context.getColorStateList(value.resourceId);
            return ColorStateList.valueOf(value.data);
        }
        return ColorStateList.valueOf(Helper.getDarkMode() ? 0xffffffff : 0xff222222);
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
