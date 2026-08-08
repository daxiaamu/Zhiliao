package com.shatyuka.zhiliao;

import android.content.SharedPreferences;
import android.content.res.Resources;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Data-driven compatibility profiles shared by hooks, tests and the module UI.
 *
 * The built-in asset is always the safe fallback. A future updater may install a
 * newer JSON document only after its SHA-256 has been authenticated by the update
 * channel; remote data can select symbols but cannot execute code.
 */
public final class CompatibilityRegistry {
    public static final String REMOTE_CONFIG_KEY = "compatibility_config_json_v1";
    private static final String ASSET_PATH = "compatibility/compatibility-v1.json";
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_CONFIG_BYTES = 256 * 1024;
    private static final Pattern SYMBOL = Pattern.compile("[A-Za-z_$][A-Za-z0-9_.$]*");
    private static final Set<String> SUPPORTED_SYMBOL_KEYS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    "searchResponseConverters", "cashEntryMethods")));

    private static Catalog catalog = Catalog.empty();
    private static Profile activeProfile;
    private static long activeVersionCode = -1;
    private static boolean remoteConfigActive;

    private CompatibilityRegistry() {
    }

    public static synchronized void initialize(Resources resources, SharedPreferences preferences,
                                               long versionCode) {
        Catalog builtIn = readAsset(resources);
        Catalog selected = builtIn;
        boolean selectedRemote = false;
        if (preferences != null) {
            String remote = preferences.getString(REMOTE_CONFIG_KEY, null);
            Catalog candidate = parse(remote);
            if (candidate != null && candidate.revision >= builtIn.revision) {
                if (candidate.compatibilityUrl.isEmpty())
                    candidate = candidate.withCompatibilityUrl(builtIn.compatibilityUrl);
                selected = candidate;
                selectedRemote = true;
            }
        }
        apply(selected, versionCode, selectedRemote);
    }

    /** Used by JVM compatibility tests so they consume exactly the shipped profile data. */
    public static synchronized void initialize(InputStream input, long versionCode) {
        Catalog parsed = parse(read(input));
        apply(parsed == null ? Catalog.empty() : parsed, versionCode, false);
    }

    public static synchronized List<String> getSymbolCandidates(String key) {
        Set<String> result = new LinkedHashSet<>();
        if (activeProfile != null)
            result.addAll(activeProfile.symbols.getOrDefault(key, Collections.emptyList()));
        result.addAll(catalog.defaults.getOrDefault(key, Collections.emptyList()));
        return new ArrayList<>(result);
    }

    public static synchronized List<CatalogEntry> getAdaptedVersions() {
        List<CatalogEntry> result = new ArrayList<>();
        for (Profile profile : catalog.profiles) {
            if ("adapted".equals(profile.status))
                result.add(new CatalogEntry(profile.displayName, profile.versionName,
                        profile.minVersionCode, profile.maxVersionCode, profile.channel));
        }
        return result;
    }

    public static synchronized String getCompatibilityUrl() {
        return catalog.compatibilityUrl;
    }

    public static synchronized String getActiveProfileId() {
        return activeProfile == null ? "feature-fallback" : activeProfile.id;
    }

    public static synchronized long getRevision() {
        return catalog.revision;
    }

    public static synchronized boolean isRemoteConfigActive() {
        return remoteConfigActive;
    }

    /**
     * Installs a future cloud profile after hash verification and full schema validation.
     * expectedSha256 must come from an authenticated manifest, not from the JSON response.
     */
    public static synchronized boolean installRemoteConfig(SharedPreferences preferences,
                                                           String json, String expectedSha256) {
        if (preferences == null || json == null || expectedSha256 == null
                || json.getBytes(StandardCharsets.UTF_8).length > MAX_CONFIG_BYTES)
            return false;
        Catalog candidate = parse(json);
        if (!sha256(json).equalsIgnoreCase(expectedSha256.trim()) || candidate == null
                || candidate.revision < catalog.revision)
            return false;
        if (!preferences.edit().putString(REMOTE_CONFIG_KEY, json).commit())
            return false;
        apply(candidate, activeVersionCode, true);
        return true;
    }

    private static void apply(Catalog selected, long versionCode, boolean remote) {
        catalog = selected;
        activeVersionCode = versionCode;
        remoteConfigActive = remote;
        activeProfile = null;
        for (Profile profile : selected.profiles) {
            if (versionCode >= profile.minVersionCode && versionCode <= profile.maxVersionCode) {
                activeProfile = profile;
                break;
            }
        }
    }

    private static Catalog readAsset(Resources resources) {
        if (resources == null)
            return Catalog.empty();
        try (InputStream input = resources.getAssets().open(ASSET_PATH)) {
            Catalog parsed = parse(read(input));
            return parsed == null ? Catalog.empty() : parsed;
        } catch (Throwable ignored) {
            return Catalog.empty();
        }
    }

    private static String read(InputStream input) {
        if (input == null)
            return null;
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = source.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_CONFIG_BYTES)
                    return null;
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Catalog parse(String json) {
        if (json == null || json.isEmpty()
                || json.getBytes(StandardCharsets.UTF_8).length > MAX_CONFIG_BYTES)
            return null;
        try {
            JSONObject root = new JSONObject(json);
            if (root.getInt("schemaVersion") != SCHEMA_VERSION
                    || !Helper.hookPackage.equals(root.getString("targetPackage")))
                return null;
            long revision = root.getLong("revision");
            if (revision < 1)
                return null;

            String compatibilityUrl = optionalHttpsUrl(root, "compatibilityUrl");
            Map<String, List<String>> defaults = readSymbols(
                    root.optJSONObject("defaults") == null ? null
                            : root.optJSONObject("defaults").optJSONObject("symbols"));
            JSONArray array = root.getJSONArray("profiles");
            if (array.length() > 100)
                return null;
            List<Profile> profiles = new ArrayList<>();
            Set<String> ids = new LinkedHashSet<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                String id = requiredText(item, "id");
                String channel = requiredText(item, "channel");
                String displayName = requiredText(item, "displayName");
                String versionName = requiredText(item, "versionName");
                String status = requiredText(item, "status");
                long min = item.getLong("minVersionCode");
                long max = item.getLong("maxVersionCode");
                if (!ids.add(id) || min < 1 || max < min)
                    return null;
                profiles.add(new Profile(id, channel, displayName, versionName,
                        min, max, status, readSymbols(item.optJSONObject("symbols"))));
            }
            return new Catalog(revision, compatibilityUrl, defaults, profiles);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String requiredText(JSONObject object, String key) throws Exception {
        String value = object.getString(key).trim();
        if (value.isEmpty() || value.length() > 100)
            throw new IllegalArgumentException(key);
        return value;
    }

    private static String optionalHttpsUrl(JSONObject object, String key) throws Exception {
        String value = object.optString(key, "").trim();
        if (value.isEmpty())
            return "";
        if (value.length() > 2048) throw new IllegalArgumentException(key);
        URI uri = new URI(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null)
            throw new IllegalArgumentException(key);
        return value;
    }

    private static Map<String, List<String>> readSymbols(JSONObject object) throws Exception {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (object == null)
            return result;
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!SYMBOL.matcher(key).matches() || !SUPPORTED_SYMBOL_KEYS.contains(key))
                throw new IllegalArgumentException("symbol key");
            JSONArray values = object.getJSONArray(key);
            if (values.length() > 100)
                throw new IllegalArgumentException("symbol count");
            List<String> symbols = new ArrayList<>();
            for (int i = 0; i < values.length(); i++) {
                String value = values.getString(i);
                if (!SYMBOL.matcher(value).matches())
                    throw new IllegalArgumentException("symbol value");
                symbols.add(value);
            }
            result.put(key, Collections.unmodifiableList(symbols));
        }
        return Collections.unmodifiableMap(result);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(digest.length * 2);
            for (byte item : digest)
                output.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            return output.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static final class CatalogEntry {
        public final String displayName;
        public final String versionName;
        public final long minVersionCode;
        public final long maxVersionCode;
        public final String channel;

        CatalogEntry(String displayName, String versionName, long minVersionCode,
                     long maxVersionCode, String channel) {
            this.displayName = displayName;
            this.versionName = versionName;
            this.minVersionCode = minVersionCode;
            this.maxVersionCode = maxVersionCode;
            this.channel = channel;
        }
    }

    private static final class Profile {
        final String id;
        final String channel;
        final String displayName;
        final String versionName;
        final long minVersionCode;
        final long maxVersionCode;
        final String status;
        final Map<String, List<String>> symbols;

        Profile(String id, String channel, String displayName, String versionName,
                long minVersionCode, long maxVersionCode, String status,
                Map<String, List<String>> symbols) {
            this.id = id;
            this.channel = channel;
            this.displayName = displayName;
            this.versionName = versionName;
            this.minVersionCode = minVersionCode;
            this.maxVersionCode = maxVersionCode;
            this.status = status;
            this.symbols = symbols;
        }
    }

    private static final class Catalog {
        final long revision;
        final String compatibilityUrl;
        final Map<String, List<String>> defaults;
        final List<Profile> profiles;

        Catalog(long revision, String compatibilityUrl, Map<String, List<String>> defaults,
                List<Profile> profiles) {
            this.revision = revision;
            this.compatibilityUrl = compatibilityUrl;
            this.defaults = defaults;
            this.profiles = Collections.unmodifiableList(profiles);
        }

        Catalog withCompatibilityUrl(String value) {
            return new Catalog(revision, value, defaults, profiles);
        }

        static Catalog empty() {
            return new Catalog(0, "", Collections.emptyMap(), Collections.emptyList());
        }
    }
}
