package com.shatyuka.zhiliao.update;

import org.json.JSONArray;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
    public void newestUpdateMetadataWinsOverFirstStaleCdn() {
        UpdateInfo stale = new UpdateInfo(10, "old", "https://example.com/old.apk",
                "a", "", "");
        UpdateInfo newest = new UpdateInfo(11, "new", "https://example.com/new.apk",
                "b", "", "");

        UpdateInfo selected = UpdateManager.selectNewerUpdate(stale, newest);

        assertEquals(11, selected.versionCode);
        assertEquals("new", selected.versionName);
    }
    @Test
    public void manualCheckNeverReusesCompletedSessionResult() {
        UpdateInfo cached = new UpdateInfo(
                2, "cached", "https://example.com/module.apk",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "notes", "");

        assertTrue(UpdateManager.shouldReuseSessionResult(false, false, cached));
        assertFalse(UpdateManager.shouldReuseSessionResult(true, false, cached));
        assertFalse(UpdateManager.shouldReuseSessionResult(false, true, cached));
    }
    @Test
    public void compatibilityManifestHasSixCdnSources() {
        assertEquals(6, UpdateManager.COMPATIBILITY_MANIFEST_URLS.length);
        for (String source : UpdateManager.COMPATIBILITY_MANIFEST_URLS)
            org.junit.Assert.assertTrue(source.startsWith("https://"));
    }

    @Test
    public void newestCompatibilityManifestWinsOverFirstStaleCdn() {
        List<UpdateManager.CompatibilityManifest> manifests = new ArrayList<>();
        manifests.add(new UpdateManager.CompatibilityManifest(
                2026081001L, "old", new JSONArray()));
        manifests.add(new UpdateManager.CompatibilityManifest(
                2026081002L, "new", new JSONArray()));

        UpdateManager.sortCompatibilityManifests(manifests);

        assertEquals(2026081002L, manifests.get(0).revision);
        assertEquals("new", manifests.get(0).sha256);
    }
    @Test
    public void compatibilityManifestCacheBusterPreservesExistingQuery() {
        assertEquals("https://example.com/config.json?t=42",
                UpdateManager.withCacheBuster("https://example.com/config.json", 42));
        assertEquals("https://example.com/config.json?source=cdn&t=42",
                UpdateManager.withCacheBuster("https://example.com/config.json?source=cdn", 42));
    }

    @Test
    public void compatibilityTextSha256MatchesKnownVector() throws Exception {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                UpdateManager.sha256("abc"));
    }
}
