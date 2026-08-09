package com.shatyuka.zhiliao;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.widget.Toast;

import com.shatyuka.zhiliao.xposed.XC_MethodHook;
import com.shatyuka.zhiliao.xposed.XposedBridge;
import com.shatyuka.zhiliao.xposed.XposedHelpers;

import java.io.File;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/** Modern libxposed API 102 entry point. */
public final class MainHook extends XposedModule {
    private String processName;
    private boolean initialized;
    private BroadcastReceiver restartReceiver;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        processName = param.getProcessName();
        XposedBridge.attach(this);
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!Helper.hookPackage.equals(param.getPackageName())
                || !Helper.hookPackage.equals(processName)
                || !param.isFirstPackage()) return;

        ClassLoader classLoader = param.getClassLoader();
        Helper.hostApkPath = param.getApplicationInfo().sourceDir;
        try {
            Helper.modRes = Helper.getModuleRes(getModuleApplicationInfo().sourceDir);
        } catch (Throwable throwable) {
            XposedBridge.log("[Zhiliao] Cannot load module resources: " + throwable);
        }

        disableTinkerSafeMode(classLoader);
        hookApplicationCreate(classLoader);
        hookAllowXposedMarker();
    }

    private void disableTinkerSafeMode(ClassLoader classLoader) {
        try {
            XposedBridge.hookAllConstructors(
                    classLoader.loadClass("com.tencent.tinker.loader.app.TinkerApplication"),
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length > 0) param.args[0] = 0;
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    private void hookApplicationCreate(ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod(android.app.Instrumentation.class,
                "callApplicationOnCreate", Application.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (initialized || !(param.args[0] instanceof Application)) return;
                        initialized = true;
                        Application application = (Application) param.args[0];
                        Helper.context = application.getApplicationContext();
                        registerRestartReceiver(application);
                        Helper.prefs = getRemotePreferences("zhiliao_preferences");
                        try {
                            if (!Helper.init(classLoader, Helper.prefs)) {
                                Helper.toast("初始化失败，当前知乎版本可能暂不受支持："
                                        + Helper.packageInfo.versionName, Toast.LENGTH_SHORT);
                            } else {
                                Hooks.init(classLoader);
                            }
                        } finally {
                            DexResolver.close();
                        }
                    }
                });
    }

    private void registerRestartReceiver(Application application) {
        if (restartReceiver != null) return;
        restartReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (Helper.ACTION_RESTART_ZHIHU.equals(intent.getAction()))
                    Helper.doRestart(context);
            }
        };
        IntentFilter filter = new IntentFilter(Helper.ACTION_RESTART_ZHIHU);
        application.registerReceiver(restartReceiver, filter, Helper.RESTART_PERMISSION,
                null, Context.RECEIVER_EXPORTED);
    }

    private void hookAllowXposedMarker() {
        XposedHelpers.findAndHookMethod(File.class, "exists", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                File file = (File) param.thisObject;
                if (".allowXposed".equals(file.getName())) param.setResult(true);
            }
        });
    }
}
