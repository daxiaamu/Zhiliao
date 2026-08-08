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
