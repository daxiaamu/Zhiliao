package com.shatyuka.zhiliao.hooks;

import android.content.Context;

import com.shatyuka.zhiliao.Helper;

import java.lang.reflect.Field;
import java.util.List;

import com.shatyuka.zhiliao.xposed.XC_MethodHook;
import com.shatyuka.zhiliao.xposed.XposedBridge;
import com.shatyuka.zhiliao.xposed.XposedHelpers;

public class FeedAd implements IHook {
    static Class<?> BasePagingFragment;
    static Class<?> FeedAdvert;
    static Class<?> ListAd;
    static Class<?> Advert;
    static Class<?> Ad;

    static Field FeedList_data;

    @Override
    public String getName() {
        return "去信息流广告";
    }

    @Override
    public void init(ClassLoader classLoader) throws Throwable {
        BasePagingFragment = classLoader.loadClass("com.zhihu.android.app.ui.fragment.paging.BasePagingFragment");
        try {
            FeedAdvert = classLoader.loadClass("com.zhihu.android.api.model.FeedAdvert");
            ListAd = classLoader.loadClass("com.zhihu.android.api.model.ListAd");
            Advert = classLoader.loadClass("com.zhihu.android.api.model.Advert");
            Ad = classLoader.loadClass("com.zhihu.android.api.model.Ad");
        } catch (ClassNotFoundException e) {
            FeedAdvert = classLoader.loadClass("com.zhihu.android.adbase.model.FeedAdvert");
            ListAd = classLoader.loadClass("com.zhihu.android.adbase.model.ListAd");
            Advert = classLoader.loadClass("com.zhihu.android.adbase.model.Advert");
            Ad = classLoader.loadClass("com.zhihu.android.adbase.model.Ad");
        }

        FeedList_data = classLoader.loadClass("com.zhihu.android.api.model.FeedList").getField("data");
    }

    @Override
    public void hook() throws Throwable {
        XposedBridge.hookAllMethods(BasePagingFragment, "postRefreshSucceed", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (Helper.prefs.getBoolean("switch_mainswitch", false) && Helper.prefs.getBoolean("switch_feedad", true)) {
                    if (param.args.length == 0 || param.args[0] == null
                            || !FeedList_data.getDeclaringClass().isInstance(param.args[0]))
                        return;
                    Object data = FeedList_data.get(param.args[0]);
                    if (!(data instanceof List))
                        return;
                    removeFeedAdItems((List<?>) data);
                }
            }
        });
        XposedHelpers.findAndHookMethod(BasePagingFragment, "insertDataRangeToList", int.class, List.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (Helper.prefs.getBoolean("switch_mainswitch", false) && Helper.prefs.getBoolean("switch_feedad", true)) {
                    if (param.args.length < 2 || !(param.args[1] instanceof List))
                        return;
                    List<?> list = (List<?>) param.args[1];
                    removeFeedAdItems(list);
                }
            }
        });
        try {
            XposedHelpers.findAndHookMethod(Helper.MorphAdHelper, "resolve", Context.class, FeedAdvert, boolean.class, Boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (Helper.prefs.getBoolean("switch_mainswitch", false) && Helper.prefs.getBoolean("switch_feedad", true)) {
                        param.setResult(false);
                    }
                }
            });
        } catch (Throwable ignore) {
        }
        try {
            XposedHelpers.findAndHookMethod(Helper.MorphAdHelper, "resolve", Context.class, ListAd, Boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (Helper.prefs.getBoolean("switch_mainswitch", false) && Helper.prefs.getBoolean("switch_feedad", true)) {
                        param.setResult(false);
                    }
                }
            });
        } catch (Throwable ignore) {
        }
        try {
            XposedHelpers.findAndHookMethod(Advert, "isSlidingWindow", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (Helper.prefs.getBoolean("switch_mainswitch", false) && Helper.prefs.getBoolean("switch_feedad", true)) {
                        param.setResult(false);
                    }
                }
            });
        } catch (Throwable ignore) {
        }
        try {
            XposedHelpers.findAndHookMethod(Ad, "isFloatAdCard", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (Helper.prefs.getBoolean("switch_mainswitch", false) && Helper.prefs.getBoolean("switch_feedad", true)) {
                        param.setResult(false);
                    }
                }
            });
        } catch (Throwable ignore) {
        }
    }

    private static void removeFeedAdItems(List<?> list) {
        for (int i = list.size() - 1; i >= 0; i--) {
            Object item = list.get(i);
            if (item != null && FeedAdvert.isInstance(item))
                list.remove(i);
        }
    }
}
