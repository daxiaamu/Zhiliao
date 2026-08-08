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
    public void unknownVersionUsesFeatureFallback() throws Exception {
        initialize(999999);
        assertEquals("feature-fallback", CompatibilityRegistry.getActiveProfileId());
        assertFalse(CompatibilityRegistry.getSymbolCandidates("searchResponseConverters").isEmpty());
    }

    @Test
    public void catalogDrivesAdaptedVersionDialog() throws Exception {
        initialize(40408);
        assertEquals(2, CompatibilityRegistry.getAdaptedVersions().size());
        assertEquals("国内版", CompatibilityRegistry.getAdaptedVersions().get(0).displayName);
        assertEquals("https://pan.quark.cn/s/1bebc29ea350",
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
                + "\"displayName\":\"Google Play 版\",\"versionName\":\"10.96.0\","
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
