package com.shatyuka.zhiliao.update;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class UpdateManagerTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void sha256MatchesKnownVector() throws Exception {
        File file = temporaryFolder.newFile("update.apk");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write("abc".getBytes(StandardCharsets.US_ASCII));
        }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                UpdateManager.sha256(file));
    }

    @Test
    public void githubReleaseGetsSixFallbackProxies() throws Exception {
        String primary = "https://github.com/daxiaamu/Zhiliao/releases/download/v1/Zhiliao.apk";
        List<String> urls = UpdateManager.expandReleaseUrls(primary);
        assertEquals(7, urls.size());
        assertEquals(primary, urls.get(0));
        assertEquals("https://ghfast.top/" + primary, urls.get(1));
    }

    @Test
    public void compatibilityManifestHasSixCdnSources() {
        assertEquals(6, UpdateManager.COMPATIBILITY_MANIFEST_URLS.length);
        for (String source : UpdateManager.COMPATIBILITY_MANIFEST_URLS)
            org.junit.Assert.assertTrue(source.startsWith("https://"));
    }

    @Test
    public void compatibilityTextSha256MatchesKnownVector() throws Exception {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                UpdateManager.sha256("abc"));
    }
}
