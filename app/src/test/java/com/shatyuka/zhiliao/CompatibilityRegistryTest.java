package com.shatyuka.zhiliao;

import org.junit.Test;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CompatibilityRegistryTest {
    private static final String ASSET = "src/main/assets/compatibility/compatibility-v1.json";

    @Test
    public void playProfileOverridesDefaultsButKeepsFallbacks() throws Exception {
        initialize(29522);
        assertEquals("play-10.95.0-29522", CompatibilityRegistry.getActiveProfileId());
        assertEquals("retrofit2.converter.jackson.c",
                CompatibilityRegistry.getSymbolCandidates("searchResponseConverters").get(0));
        assertTrue(CompatibilityRegistry.getSymbolCandidates("searchResponseConverters")
                .contains("com.zhihu.android.net.b.b"));
    }

    @Test
    public void domesticProfileProvidesCashEntryMethod() throws Exception {
        initialize(40408);
        assertEquals("Ok",
                CompatibilityRegistry.getSymbolCandidates("cashEntryMethods").get(0));
    }

    @Test
    public void unknownVersionUsesFeatureFallback() throws Exception {
        initialize(999999);
        assertEquals("feature-fallback", CompatibilityRegistry.getActiveProfileId());
        assertFalse(CompatibilityRegistry.getSymbolCandidates("searchResponseConverters").isEmpty());
        assertTrue(CompatibilityRegistry.getSymbolCandidates("launchAdCloseViewIds")
                .contains("btn_skip"));
    }

    @Test
    public void catalogDrivesAdaptedVersionDialog() throws Exception {
        initialize(40408);
        assertEquals(7, CompatibilityRegistry.getAdaptedVersions().size());
        assertEquals("\u56fd\u5185\u7248", CompatibilityRegistry.getAdaptedVersions().get(0).displayName);
        assertEquals("11.4.0", CompatibilityRegistry.getAdaptedVersions().get(0).versionName);
        assertEquals(40408, CompatibilityRegistry.getAdaptedVersions().get(0).minVersionCode);
        assertEquals("Google Play \u7248", CompatibilityRegistry.getAdaptedVersions().get(1).displayName);
        assertEquals("10.95.0", CompatibilityRegistry.getAdaptedVersions().get(1).versionName);
        assertEquals("\u56fd\u5185\u7248", CompatibilityRegistry.getAdaptedVersions().get(2).displayName);
        assertEquals("11.5.0", CompatibilityRegistry.getAdaptedVersions().get(2).versionName);
        assertEquals(40530, CompatibilityRegistry.getAdaptedVersions().get(2).minVersionCode);
        assertEquals("Google Play 版", CompatibilityRegistry.getAdaptedVersions().get(3).displayName);
        assertEquals("11.5.0", CompatibilityRegistry.getAdaptedVersions().get(3).versionName);
        assertEquals(40530, CompatibilityRegistry.getAdaptedVersions().get(3).minVersionCode);
        assertEquals("国内版", CompatibilityRegistry.getAdaptedVersions().get(4).displayName);
        assertEquals("11.6.0", CompatibilityRegistry.getAdaptedVersions().get(4).versionName);
        assertEquals(40608, CompatibilityRegistry.getAdaptedVersions().get(4).minVersionCode);
        assertEquals("国内版", CompatibilityRegistry.getAdaptedVersions().get(5).displayName);
        assertEquals("11.7.0", CompatibilityRegistry.getAdaptedVersions().get(5).versionName);
        assertEquals(40714, CompatibilityRegistry.getAdaptedVersions().get(5).minVersionCode);
        assertEquals("国内版", CompatibilityRegistry.getAdaptedVersions().get(6).displayName);
        assertEquals("11.8.0", CompatibilityRegistry.getAdaptedVersions().get(6).versionName);
        assertEquals(40811, CompatibilityRegistry.getAdaptedVersions().get(6).minVersionCode);
    }

    @Test
    public void sameVersionCodeSelectsProfileByChannel() throws Exception {
        try (InputStream input = new FileInputStream(ASSET)) {
            CompatibilityRegistry.initialize(input, 40530, "domestic");
        }
        assertEquals("domestic-11.5.0-40530", CompatibilityRegistry.getActiveProfileId());
        try (InputStream input = new FileInputStream(ASSET)) {
            CompatibilityRegistry.initialize(input, 40530, "play");
        }
        assertEquals("play-11.5.0-40530", CompatibilityRegistry.getActiveProfileId());
        assertEquals("https://pan.quark.cn/s/4f43a6eab295",
                CompatibilityRegistry.getCompatibilityUrl());
    }

    @Test
    public void cloudReloadUpdatesPlayVersionShownInAbout() throws Exception {
        initialize(29522);
        assertEquals("10.95.0", CompatibilityRegistry.getAdaptedVersions().get(1).versionName);

        String remote = "{\"schemaVersion\":1,\"revision\":2026080803,"
                + "\"targetPackage\":\"com.zhihu.android\","
                + "\"compatibilityUrl\":\"https://example.com/zhihu\","
                + "\"defaults\":{\"symbols\":{}},\"profiles\":[{"
                + "\"id\":\"play-10.96.0-30000\",\"channel\":\"play\","
                + "\"displayName\":\"Google Play \u7248\",\"versionName\":\"10.96.0\","
                + "\"minVersionCode\":30000,\"maxVersionCode\":30000,"
                + "\"status\":\"adapted\",\"symbols\":{}}]}";
        InputStream input = new java.io.ByteArrayInputStream(remote.getBytes(StandardCharsets.UTF_8));
        CompatibilityRegistry.initialize(input, 30000);

        assertEquals("play-10.96.0-30000", CompatibilityRegistry.getActiveProfileId());
        assertEquals(1, CompatibilityRegistry.getAdaptedVersions().size());
        assertEquals("10.96.0", CompatibilityRegistry.getAdaptedVersions().get(0).versionName);
        assertEquals("play", CompatibilityRegistry.getAdaptedVersions().get(0).channel);
        assertEquals("https://example.com/zhihu", CompatibilityRegistry.getCompatibilityUrl());
    }

    @Test
    public void arbitraryTargetSlotsAndProfileFeatureOverridesAreDataDriven() {
        String config = "{\"schemaVersion\":1,\"revision\":2,"
                + "\"targetPackage\":\"com.zhihu.android\","
                + "\"defaults\":{\"symbols\":{\"futureHook.ownerClasses\":"
                + "[\"com.zhihu.future.Owner\"]},"
                + "\"features\":{\"AutoRefresh\":false,\"SearchAd\":false},"
                + "\"dexRules\":{\"futureHook.owner\":{\"result\":\"ownerClass\","
                + "\"searchPackages\":[\"com.zhihu.future\"],"
                + "\"methodNames\":[\"bind\"],\"paramCount\":1,"
                + "\"minCandidates\":1,\"maxCandidates\":1}}},"
                + "\"profiles\":[{\"id\":\"future\",\"channel\":\"play\","
                + "\"displayName\":\"Play\",\"versionName\":\"12.0\","
                + "\"minVersionCode\":50000,\"maxVersionCode\":50000,"
                + "\"status\":\"adapted\",\"symbols\":{},"
                + "\"features\":{\"AutoRefresh\":true}}]}";
        InputStream input = new java.io.ByteArrayInputStream(
                config.getBytes(StandardCharsets.UTF_8));
        CompatibilityRegistry.initialize(input, 50000);

        assertEquals("com.zhihu.future.Owner",
                CompatibilityRegistry.getSymbolCandidates("futureHook.ownerClasses").get(0));
        assertTrue(CompatibilityRegistry.isFeatureEnabled("AutoRefresh"));
        assertFalse(CompatibilityRegistry.isFeatureEnabled("SearchAd"));
        assertTrue(CompatibilityRegistry.isFeatureEnabled("UnspecifiedHook"));
        CompatibilityRegistry.DexRule rule =
                CompatibilityRegistry.getDexRule("futureHook.owner");
        assertEquals("ownerClass", rule.result);
        assertEquals("com.zhihu.future", rule.searchPackages.get(0));
        assertEquals("bind", rule.methodNames.get(0));
        assertEquals(1, rule.paramCount);
    }

    @Test
    public void unscopedDexRuleFailsClosed() {
        String invalid = "{\"schemaVersion\":1,\"revision\":3,"
                + "\"targetPackage\":\"com.zhihu.android\","
                + "\"defaults\":{\"symbols\":{},\"dexRules\":{\"unsafe\":{"
                + "\"result\":\"method\",\"searchPackages\":[],"
                + "\"methodNames\":[\"run\"]}}},\"profiles\":[]}";
        InputStream input = new java.io.ByteArrayInputStream(
                invalid.getBytes(StandardCharsets.UTF_8));
        CompatibilityRegistry.initialize(input, 40408);
        assertEquals(0, CompatibilityRegistry.getRevision());
        assertTrue(CompatibilityRegistry.getDexRule("unsafe") == null);
    }

    @Test
    public void invalidOrUnsupportedConfigFailsClosed() {
        String invalid = "{\"schemaVersion\":2,\"revision\":1,"
                + "\"targetPackage\":\"com.zhihu.android\",\"profiles\":[]}";
        InputStream input = new java.io.ByteArrayInputStream(invalid.getBytes(StandardCharsets.UTF_8));
        CompatibilityRegistry.initialize(input, 40408);
        assertTrue(CompatibilityRegistry.getAdaptedVersions().isEmpty());
        assertTrue(CompatibilityRegistry.getSymbolCandidates("searchResponseConverters").isEmpty());
    }

    private static void initialize(long versionCode) throws Exception {
        try (InputStream input = new FileInputStream(ASSET)) {
            CompatibilityRegistry.initialize(input, versionCode);
        }
    }
}
