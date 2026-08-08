package com.shatyuka.zhiliao.hooks;

import android.view.View;
import android.view.ViewGroup;

import com.shatyuka.zhiliao.Helper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.shatyuka.zhiliao.xposed.XC_MethodHook;
import com.shatyuka.zhiliao.xposed.XC_MethodReplacement;
import com.shatyuka.zhiliao.xposed.XposedBridge;
import com.shatyuka.zhiliao.xposed.XposedHelpers;

public class NextAnswer implements IHook {
    static Class<?> NextContentAnimationView;
    static Class<?> NextContentAnimationView_short;
    static Class<?> MixShortContainerFragment;
    static Class<?> PopupMenuNextButton;

    static Method initView;
    static Method initLayout;

    static Field nextButton;

    @Override
    public String getName() {
        return "移除下一个回答按钮";
    }

    @Override
    public void init(ClassLoader classLoader) throws Throwable {
        NextContentAnimationView = classLoader.loadClass("com.zhihu.android.mix.widget.NextContentAnimationView");
        try {
            NextContentAnimationView_short = classLoader.loadClass("com.zhihu.android.mixshortcontainer.function.next.NextContentAnimationView");
            MixShortContainerFragment = classLoader.loadClass("com.zhihu.android.mixshortcontainer.MixShortContainerFragment");
        } catch (Throwable ignored) {
        }

        if (MixShortContainerFragment != null) {
            initView = Helper.getMethodByParameterTypes(MixShortContainerFragment, 0, View.class);
            initLayout = Helper.getMethodByParameterTypes(MixShortContainerFragment, 1, View.class);

            // Field names are obfuscated differently between domestic and Play builds.
            // Match the stable widget type instead of maintaining a per-version name list.
            for (Field field : MixShortContainerFragment.getDeclaredFields()) {
                if (field.getType().getName().equals("com.zhihu.android.base.widget.ZHFrameLayout")) {
                    nextButton = field;
                    nextButton.setAccessible(true);
                    break;
                }
            }
        }

        try {
            PopupMenuNextButton = classLoader.loadClass("com.zhihu.android.feature.short_container_feature.ui.widget.next.PopupMenuNextButton");
        } catch (ClassNotFoundException ignored) {
        }
    }

    @Override
    public void hook() throws Throwable {
        if (Helper.prefs.getBoolean("switch_mainswitch", false) && Helper.prefs.getBoolean("switch_nextanswer", false)) {
            XposedHelpers.findAndHookMethod(Helper.AnswerPagerFragment, "setupNextAnswerBtn", XC_MethodReplacement.returnConstant(null));
            if (initView != null && nextButton != null) {
                XposedBridge.hookMethod(initView, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        nextButton.set(param.thisObject, null);
                    }
                });
            }
            if (initLayout != null && nextButton != null) {
                XposedBridge.hookMethod(initLayout, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        nextButton.set(param.thisObject, null);
                    }
                });
            }

            XposedHelpers.findAndHookMethod(ViewGroup.class, "addView", View.class, ViewGroup.LayoutParams.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (NextContentAnimationView.isAssignableFrom(param.args[0].getClass()) || (NextContentAnimationView_short != null && NextContentAnimationView_short.isAssignableFrom(param.args[0].getClass())))
                        ((View) param.args[0]).setVisibility(View.GONE);
                }
            });

            XposedHelpers.findAndHookMethod(View.class, "setVisibility", int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (NextContentAnimationView.isAssignableFrom(param.thisObject.getClass()) || (NextContentAnimationView_short != null && NextContentAnimationView_short.isAssignableFrom(param.thisObject.getClass())))
                        param.args[0] = View.GONE;
                }
            });

            if (PopupMenuNextButton != null) {
                XposedBridge.hookAllConstructors(PopupMenuNextButton, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        View button = (View) param.thisObject;
                        button.setScaleX(0);
                        button.setScaleY(0);
                    }
                });
            }
        }
    }
}
