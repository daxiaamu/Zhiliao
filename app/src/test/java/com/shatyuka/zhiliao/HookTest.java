package com.shatyuka.zhiliao;

import com.shatyuka.zhiliao.hooks.AnswerAd;
import com.shatyuka.zhiliao.hooks.AnswerListAd;
import com.shatyuka.zhiliao.hooks.Article;
import com.shatyuka.zhiliao.hooks.AutoRefresh;
import com.shatyuka.zhiliao.hooks.CashEntry;
import com.shatyuka.zhiliao.hooks.CustomFilter;
import com.shatyuka.zhiliao.hooks.ExternLink;
import com.shatyuka.zhiliao.hooks.FeedAd;
import com.shatyuka.zhiliao.hooks.FeedTopHotBanner;
import com.shatyuka.zhiliao.hooks.FollowButton;
import com.shatyuka.zhiliao.hooks.FullScreen;
import com.shatyuka.zhiliao.hooks.HeadZoneBanner;
import com.shatyuka.zhiliao.hooks.Horizontal;
import com.shatyuka.zhiliao.hooks.HotBanner;
import com.shatyuka.zhiliao.hooks.IHook;
import com.shatyuka.zhiliao.hooks.LaunchAd;
import com.shatyuka.zhiliao.hooks.LiveButton;
import com.shatyuka.zhiliao.hooks.MineHybridView;
import com.shatyuka.zhiliao.hooks.NavButton;
import com.shatyuka.zhiliao.hooks.NavRes;
import com.shatyuka.zhiliao.hooks.RedDot;
import com.shatyuka.zhiliao.hooks.SearchAd;
import com.shatyuka.zhiliao.hooks.ShareAd;
import com.shatyuka.zhiliao.hooks.StatusBar;
import com.shatyuka.zhiliao.hooks.Tag;
import com.shatyuka.zhiliao.hooks.TemplateAdView;
import com.shatyuka.zhiliao.hooks.ThirdPartyLogin;
import com.shatyuka.zhiliao.hooks.VIPBanner;
import com.shatyuka.zhiliao.hooks.ZhihuSettingsEntry;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public class HookTest {
    static class PackageInfo {
        String name;
        int versionCode;
        ClassLoader classLoader;
    }

    interface TestAutoRefreshCallback {}

    abstract static class TestAutoRefreshReason {}

    static class TestAutoRefreshManager {
        @SuppressWarnings("unused")
        private void automaticGate(long elapsed, int threshold,
                                   TestAutoRefreshCallback callback,
                                   TestAutoRefreshReason reason) {}

        @SuppressWarnings("unused")
        private void manualRefresh(boolean force) {}

        @SuppressWarnings("unused")
        private void loadMore(String nextUrl) {}
    }

    static LinkedList<PackageInfo> packageInfos = new LinkedList<>();

    static {
        System.out.println((new File("")).getAbsolutePath());
        File path = new File("test");
        File[] files = path.listFiles();
        List<File> testFiles = new ArrayList<>();
        if (files != null)
            Collections.addAll(testFiles, files);

        String externalTestJar = System.getenv("ZHILIAO_TEST_JAR");
        if (externalTestJar != null && !externalTestJar.isEmpty())
            testFiles.add(new File(externalTestJar));

        for (File file : testFiles) {
            try {
                String fileName = file.getName();
                int index1 = fileName.lastIndexOf(".");
                int index2 = fileName.lastIndexOf(" ");
                if (!"jar".equalsIgnoreCase(fileName.substring(index1 + 1)))
                    continue;
                PackageInfo packageInfo = new PackageInfo();
                packageInfo.name = fileName.substring(0, index2);
                packageInfo.versionCode = Integer.parseInt(fileName.substring(index2 + 1, index1));
                packageInfo.classLoader = new Dex2JarClassLoader(new URL[]{file.toURI().toURL()});
                packageInfos.add(packageInfo);
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    public static void resetState(Class<? extends IHook> clazz) throws IllegalAccessException {
        Field[] fields = clazz.getDeclaredFields();
        for (Field field : fields) {
            if (Modifier.isStatic(field.getModifiers())) {
                Class<?> type = field.getType();
                if (type == Class.class || type == Method.class || type == Field.class) {
                    field.setAccessible(true);
                    field.set(null, null);
                }
            }
        }
    }

    /** @noinspection RedundantSuppression*/
    @SuppressWarnings("deprecation")
    void checkHook(IHook hook) {
        checkHook(hook, 0);
    }

    /**
     * dex2jar sometimes copies DEX-only top-level static/final interface flags into JVM
     * class access flags. ART accepts the original DEX, but the JVM rejects the converted
     * class before compatibility tests can inspect it. Normalize only those impossible JVM
     * flag combinations while leaving methods, fields and bytecode untouched.
     */
    static final class Dex2JarClassLoader extends URLClassLoader {
        Dex2JarClassLoader(URL[] urls) {
            super(urls);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            URL resource = findResource(name.replace('.', '/') + ".class");
            if (resource == null)
                return super.findClass(name);
            try (InputStream input = resource.openStream()) {
                byte[] bytes = input.readAllBytes();
                byte[] normalized = normalizeClassAccess(bytes);
                return defineClass(name, normalized, 0, normalized.length);
            } catch (IOException | IllegalArgumentException error) {
                throw new ClassNotFoundException(name, error);
            }
        }

        private static byte[] normalizeClassAccess(byte[] bytes) {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public void visit(int version, int access, String name, String signature,
                                  String superName, String[] interfaces) {
                    super.visit(version, legalAccess(access), name, signature, superName, interfaces);
                }

                @Override
                public void visitInnerClass(String name, String outerName, String innerName, int access) {
                    super.visitInnerClass(name, outerName, innerName, legalAccess(access));
                }

                private int legalAccess(int access) {
                    access &= ~Opcodes.ACC_STATIC;
                    if ((access & Opcodes.ACC_ABSTRACT) != 0)
                        access &= ~Opcodes.ACC_FINAL;
                    return access;
                }
            }, 0);
            return writer.toByteArray();
        }
    }

    void checkHook(IHook hook, int minimumVersionCode) {
        for (PackageInfo packageInfo : packageInfos) {
            if (packageInfo.versionCode < minimumVersionCode)
                continue;
            try {
                resetState(hook.getClass());
                Helper.packageInfo = new android.content.pm.PackageInfo();
                Helper.packageInfo.versionCode = packageInfo.versionCode;
                Helper.versionCode = packageInfo.versionCode;
                try (InputStream compatibility = new FileInputStream(
                        "src/main/assets/compatibility/compatibility-v1.json")) {
                    CompatibilityRegistry.initialize(compatibility, packageInfo.versionCode);
                }
                Helper.initSharedClasses(packageInfo.classLoader);
                hook.init(packageInfo.classLoader);
            } catch (Throwable e) {
                throw new AssertionError(hook.getName() + ", " + packageInfo.name, e);
            }
        }
    }

    @Test
    public void templateAdViewTest() {
        checkHook(new TemplateAdView(), 29522);
    }

    @Test
    public void zhihuSettingsEntryTest() {
        checkHook(new ZhihuSettingsEntry(), 29522);
    }

    @Test
    public void launchAdTest() {
        checkHook(new LaunchAd());
    }

    @Test
    public void customFilterTest() {
        checkHook(new CustomFilter());
    }

    @Test
    public void feedAdTest() {
        checkHook(new FeedAd());
    }

    @Test
    public void answerListAdTest() {
        checkHook(new AnswerListAd());
    }

    @Test
    public void answerAdTest() {
        checkHook(new AnswerAd());
    }

    @Test
    public void shareAdTest() {
        checkHook(new ShareAd());
    }

    @Test
    public void liveButtonTest() {
        checkHook(new LiveButton());
    }

    @Test
    public void horizontalTest() {
        checkHook(new Horizontal(), 2615);
    }

    @Test
    public void redDotTest() {
        checkHook(new RedDot());
    }

    @Test
    public void externLinkTest() {
        checkHook(new ExternLink());
    }

    @Test
    public void vipBannerTest() {
        checkHook(new VIPBanner());
    }

    @Test
    public void navButtonTest() {
        checkHook(new NavButton());
    }

    @Test
    public void hotBannerTest() {
        checkHook(new HotBanner());
    }

    @Test
    public void articleTest() {
        checkHook(new Article(), 2615);
    }

    @Test
    public void tagTest() {
        checkHook(new Tag());
    }

    @Test
    public void searchAdTest() {
        checkHook(new SearchAd());
    }

    @Test
    public void cashEntryTest() {
        checkHook(new CashEntry(), 40408);
    }

    @Test
    public void statusBarTest() {
        checkHook(new StatusBar());
    }

    @Test
    public void thirdPartyLoginTest() {
        checkHook(new ThirdPartyLogin());
    }

    @Test
    public void autoRefreshTest() {
        checkHook(new AutoRefresh());
    }

    @Test
    public void autoRefreshResolverTargetsOnlyAutomaticGate() throws Throwable {
        resetState(AutoRefresh.class);
        AutoRefresh hook = new AutoRefresh();
        Method resolver = AutoRefresh.class.getDeclaredMethod("findTryRefreshMethod", Class.class);
        resolver.setAccessible(true);
        assertTrue((boolean) resolver.invoke(hook, TestAutoRefreshManager.class));

        Field targetField = AutoRefresh.class.getDeclaredField("tryRefresh");
        targetField.setAccessible(true);
        Method target = (Method) targetField.get(null);
        assertNotNull(target);
        assertEquals("automaticGate", target.getName());
        assertEquals(4, target.getParameterCount());
        assertEquals(long.class, target.getParameterTypes()[0]);
        assertEquals(int.class, target.getParameterTypes()[1]);
    }

    @Test
    public void autoRefreshKeepsFirstInitialResultAndAllowsManualRefresh() throws Throwable {
        Object fragment = new Object();
        Method markStarted = AutoRefresh.class.getDeclaredMethod(
                "markRefreshStarted", Object.class, boolean.class);
        Method shouldSuppress = AutoRefresh.class.getDeclaredMethod(
                "shouldSuppressRefreshResult", Object.class);
        markStarted.setAccessible(true);
        shouldSuppress.setAccessible(true);

        markStarted.invoke(null, fragment, false);
        assertFalse((boolean) shouldSuppress.invoke(null, fragment));
        assertTrue((boolean) shouldSuppress.invoke(null, fragment));
        assertTrue((boolean) shouldSuppress.invoke(null, fragment));

        markStarted.invoke(null, fragment, true);
        assertFalse((boolean) shouldSuppress.invoke(null, fragment));
    }

    @Test
    public void autoRefresh114TargetsOnlyAutomaticGate() throws Throwable {
        PackageInfo domestic114 = null;
        for (PackageInfo packageInfo : packageInfos) {
            if (packageInfo.versionCode == 40408) {
                domestic114 = packageInfo;
                break;
            }
        }
        assumeTrue("Zhihu 11.4.0 compatibility fixture is not available", domestic114 != null);

        resetState(AutoRefresh.class);
        Helper.packageInfo = new android.content.pm.PackageInfo();
        Helper.packageInfo.versionCode = domestic114.versionCode;
        Helper.versionCode = domestic114.versionCode;
        try (InputStream compatibility = new FileInputStream(
                "src/main/assets/compatibility/compatibility-v1.json")) {
            CompatibilityRegistry.initialize(compatibility, domestic114.versionCode);
        }
        Helper.initSharedClasses(domestic114.classLoader);
        new AutoRefresh().init(domestic114.classLoader);

        Field targetField = AutoRefresh.class.getDeclaredField("tryRefresh");
        targetField.setAccessible(true);
        Method target = (Method) targetField.get(null);
        assertNotNull("Automatic refresh gate was not resolved", target);
        assertEquals("com.zhihu.android.app.feed.util.FeedAutoRefreshManager",
                target.getDeclaringClass().getName());
        assertEquals(void.class, target.getReturnType());
        assertEquals(4, target.getParameterCount());
        assertEquals(long.class, target.getParameterTypes()[0]);
        assertEquals(int.class, target.getParameterTypes()[1]);

        Field reasonRefreshField = AutoRefresh.class.getDeclaredField("reasonRefresh");
        reasonRefreshField.setAccessible(true);
        Method reasonRefresh = (Method) reasonRefreshField.get(null);
        assertNotNull("Refresh reason boundary was not resolved", reasonRefresh);
        assertEquals(boolean.class, reasonRefresh.getParameterTypes()[0]);
        assertEquals("com.zhihu.android.feed.delegate.m",
                reasonRefresh.getParameterTypes()[1].getName());

        Field postResultField = AutoRefresh.class.getDeclaredField("postRefreshSucceed");
        postResultField.setAccessible(true);
        Method postResult = (Method) postResultField.get(null);
        assertNotNull("Refresh result boundary was not resolved", postResult);
        assertEquals("postRefreshSucceed", postResult.getName());
    }

    @Test
    public void navResTest() {
        checkHook(new NavRes());
    }

    @Test
    public void feedTopHotBannerTest() {
        checkHook(new FeedTopHotBanner());
    }

    @Test
    public void headZoneBannerTest() {
        checkHook(new HeadZoneBanner());
    }

    @Test
    public void mineHybridViewTest() {
        checkHook(new MineHybridView());
    }

    @Test
    public void followButtonTest() {
        checkHook(new FollowButton());
    }

    @Test
    public void fullScreen() {
        checkHook(new FullScreen());
    }
}
