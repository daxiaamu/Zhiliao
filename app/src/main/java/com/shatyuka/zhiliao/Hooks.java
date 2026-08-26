package com.shatyuka.zhiliao;

import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import com.shatyuka.zhiliao.hooks.AnswerAd;
import com.shatyuka.zhiliao.hooks.AnswerListAd;
import com.shatyuka.zhiliao.hooks.Article;
import com.shatyuka.zhiliao.hooks.AutoRefresh;
import com.shatyuka.zhiliao.hooks.CashEntry;
import com.shatyuka.zhiliao.hooks.Cleaner;
import com.shatyuka.zhiliao.hooks.ColorMode;
import com.shatyuka.zhiliao.hooks.CommentAd;
import com.shatyuka.zhiliao.hooks.CustomFilter;
import com.shatyuka.zhiliao.hooks.ExternLink;
import com.shatyuka.zhiliao.hooks.FeedAd;
import com.shatyuka.zhiliao.hooks.FeedTopHotBanner;
import com.shatyuka.zhiliao.hooks.FollowButton;
import com.shatyuka.zhiliao.hooks.HeadZoneBanner;
import com.shatyuka.zhiliao.hooks.Horizontal;
import com.shatyuka.zhiliao.hooks.HotBanner;
import com.shatyuka.zhiliao.hooks.IHook;
import com.shatyuka.zhiliao.hooks.LaunchAd;
import com.shatyuka.zhiliao.hooks.LiveButton;
import com.shatyuka.zhiliao.hooks.MineHybridView;
import com.shatyuka.zhiliao.hooks.NavButton;
import com.shatyuka.zhiliao.hooks.NavRes;
import com.shatyuka.zhiliao.hooks.FullScreen;
import com.shatyuka.zhiliao.hooks.RedDot;
import com.shatyuka.zhiliao.hooks.SearchAd;
import com.shatyuka.zhiliao.hooks.ShareAd;
import com.shatyuka.zhiliao.hooks.StatusBar;
import com.shatyuka.zhiliao.hooks.Tag;
import com.shatyuka.zhiliao.hooks.TemplateAdView;
import com.shatyuka.zhiliao.hooks.ThirdPartyLogin;
import com.shatyuka.zhiliao.hooks.VIPBanner;
import com.shatyuka.zhiliao.hooks.WebView;
import com.shatyuka.zhiliao.xposed.XposedBridge;

public class Hooks {
    static final IHook[] hooks = {
            new LaunchAd(),
            new CustomFilter(),
            new FeedAd(),
            new AnswerListAd(),
            new CommentAd(),
            new AnswerAd(),
            new TemplateAdView(),
            new ShareAd(),
            new LiveButton(),
            new Horizontal(),
            new RedDot(),
            new ExternLink(),
            new VIPBanner(),
            new NavButton(),
            new HotBanner(),
            new ColorMode(),
            new Article(),
            new Tag(),
            new SearchAd(),
            new CashEntry(),
            new StatusBar(),
            new ThirdPartyLogin(),
            new NavRes(),
            new WebView(),
            new Cleaner(),
            new FeedTopHotBanner(),
            new HeadZoneBanner(),
            new MineHybridView(),
            new FollowButton(),
            new FullScreen(),
            new AutoRefresh(),
    };

    public static void init(final ClassLoader classLoader) {
        List<String> failedHooks = new ArrayList<>();
        for (IHook hook : hooks) {
            if (!CompatibilityRegistry.isFeatureEnabled(hook.getClass().getSimpleName())) {
                XposedBridge.log("[Zhiliao] Hook disabled by compatibility config: "
                        + hook.getClass().getSimpleName());
                continue;
            }
            try {
                hook.init(classLoader);
                hook.hook();
            } catch (Throwable e) {
                String message = e.getMessage();
                failedHooks.add(hook.getName() + "（" + e.getClass().getSimpleName()
                        + (message == null ? "" : "：" + message) + "）");
                String failure = "[Zhiliao] Hook failed: "
                        + hook.getClass().getSimpleName() + ": " + e;
                XposedBridge.log(failure);
                android.util.Log.e("Zhiliao", failure, e);
            }
        }
        if (!failedHooks.isEmpty()
                && Helper.prefs.getBoolean("switch_mainswitch", false)
                && !Helper.prefs.getBoolean("switch_hidetoast", false)) {
            Helper.toast("部分功能与当前知乎版本不兼容（" + failedHooks.size()
                    + " 项）：" + String.join("、", failedHooks), Toast.LENGTH_LONG);
        }
    }
}
