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
    private static final Set<String> DEX_RULE_FIELDS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("result", "searchPackages", "methodNames",
                    "paramTypes", "returnType", "paramCount", "usingStrings", "invokes",
                    "fieldNames", "fieldType", "minCandidates", "maxCandidates")));

    private static Catalog catalog = Catalog.empty();
    private static Profile activeProfile;
    private static long activeVersionCode = -1;
    private static String activeChannel = "";
    private static boolean remoteConfigActive;
    private static String lastInstallError = "";

    private CompatibilityRegistry() {
    }
    public static synchronized void initialize(Resources resources, SharedPreferences preferences,
                                               long versionCode) {
        initialize(resources, preferences, versionCode, "");
    }


    public static synchronized void initialize(Resources resources, SharedPreferences preferences,
                                               long versionCode, String channel) {
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
        apply(selected, versionCode, channel, selectedRemote);
    }

    /** Used by JVM compatibility tests so they consume exactly the shipped profile data. */
    public static synchronized void initialize(InputStream input, long versionCode) {
        Catalog parsed = parse(read(input));
        apply(parsed == null ? Catalog.empty() : parsed, versionCode, "", false);
    }

    /** Used by channel-collision tests where domestic and Play share a version code. */
    static synchronized void initialize(InputStream input, long versionCode, String channel) {
        Catalog parsed = parse(read(input));
        apply(parsed == null ? Catalog.empty() : parsed, versionCode, channel, false);
    }

    public static synchronized List<String> getSymbolCandidates(String key) {
        Set<String> result = new LinkedHashSet<>();
        if (activeProfile != null)
            result.addAll(activeProfile.symbols.getOrDefault(key, Collections.emptyList()));
        result.addAll(catalog.defaults.getOrDefault(key, Collections.emptyList()));
        return new ArrayList<>(result);
    }

    public static synchronized boolean isFeatureEnabled(String hookName) {
        if (!SYMBOL.matcher(hookName).matches())
            return false;
        if (activeProfile != null && activeProfile.features.containsKey(hookName))
            return activeProfile.features.get(hookName);
        return catalog.features.getOrDefault(hookName, true);
    }

    public static synchronized DexRule getDexRule(String key) {
        if (!SYMBOL.matcher(key).matches())
            return null;
        if (activeProfile != null && activeProfile.dexRules.containsKey(key))
            return activeProfile.dexRules.get(key);
        return catalog.dexRules.get(key);
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

    public static synchronized String getLastInstallError() {
        return lastInstallError;
    }

    /**
     * Installs a future cloud profile after hash verification and full schema validation.
     * expectedSha256 must come from an authenticated manifest, not from the JSON response.
     */
    public static synchronized boolean installRemoteConfig(SharedPreferences preferences,
                                                           String json, String expectedSha256) {
        lastInstallError = "";
        if (preferences == null || json == null || expectedSha256 == null
                || json.getBytes(StandardCharsets.UTF_8).length > MAX_CONFIG_BYTES) {
            lastInstallError = "invalid arguments or config size";
            return false;
        }
        Catalog candidate = parse(json);
        if (candidate == null) {
            lastInstallError = "schema validation failed";
            return false;
        }
        if (!sha256(json).equalsIgnoreCase(expectedSha256.trim())) {
            lastInstallError = "SHA-256 mismatch";
            return false;
        }
        if (candidate.revision < catalog.revision) {
            lastInstallError = "remote revision is older than built-in revision";
            return false;
        }
        if (!preferences.edit().putString(REMOTE_CONFIG_KEY, json).commit()) {
            lastInstallError = "preferences commit failed";
            return false;
        }
        apply(candidate, activeVersionCode, activeChannel, true);
        return true;
    }

    private static void apply(Catalog selected, long versionCode, String channel,
                              boolean remote) {
        catalog = selected;
        activeVersionCode = versionCode;
        activeChannel = channel == null ? "" : channel;
        remoteConfigActive = remote;
        activeProfile = null;
        for (Profile profile : selected.profiles) {
            if (versionCode >= profile.minVersionCode && versionCode <= profile.maxVersionCode
                    && (activeChannel.isEmpty() || activeChannel.equals(profile.channel))) {
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
            JSONObject defaultsObject = root.optJSONObject("defaults");
            Map<String, List<String>> defaults = readSymbols(
                    defaultsObject == null ? null : defaultsObject.optJSONObject("symbols"));
            Map<String, Boolean> defaultFeatures = readFeatures(
                    defaultsObject == null ? null : defaultsObject.optJSONObject("features"));
            Map<String, DexRule> defaultDexRules = readDexRules(
                    defaultsObject == null ? null : defaultsObject.optJSONObject("dexRules"));
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
                        min, max, status, readSymbols(item.optJSONObject("symbols")),
                        readFeatures(item.optJSONObject("features")),
                        readDexRules(item.optJSONObject("dexRules"))));
            }
            return new Catalog(revision, compatibilityUrl, defaults, defaultFeatures,
                    defaultDexRules, profiles);
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
        int keyCount = 0;
        while (keys.hasNext()) {
            String key = keys.next();
            if (++keyCount > 256 || !SYMBOL.matcher(key).matches())
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

    private static Map<String, Boolean> readFeatures(JSONObject object) throws Exception {
        Map<String, Boolean> result = new LinkedHashMap<>();
        if (object == null)
            return Collections.unmodifiableMap(result);
        Iterator<String> keys = object.keys();
        int count = 0;
        while (keys.hasNext()) {
            String key = keys.next();
            if (++count > 100 || !SYMBOL.matcher(key).matches()
                    || !(object.get(key) instanceof Boolean))
                throw new IllegalArgumentException("feature flag");
            result.put(key, object.getBoolean(key));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, DexRule> readDexRules(JSONObject object) throws Exception {
        Map<String, DexRule> result = new LinkedHashMap<>();
        if (object == null)
            return Collections.unmodifiableMap(result);
        Iterator<String> keys = object.keys();
        int count = 0;
        while (keys.hasNext()) {
            String key = keys.next();
            if (++count > 100 || !SYMBOL.matcher(key).matches())
                throw new IllegalArgumentException("dex rule key");
            JSONObject rule = object.getJSONObject(key);
            Iterator<String> fieldKeys = rule.keys();
            while (fieldKeys.hasNext()) {
                if (!DEX_RULE_FIELDS.contains(fieldKeys.next()))
                    throw new IllegalArgumentException("dex rule field");
            }
            String resultType = rule.optString("result", "method").trim();
            if (!"method".equals(resultType) && !"ownerClass".equals(resultType)
                    && !"field".equals(resultType) && !"fieldOwnerClass".equals(resultType))
                throw new IllegalArgumentException("dex rule result");
            List<String> searchPackages = readStringList(rule, "searchPackages", 1, 8, true);
            List<String> methodNames = readStringList(rule, "methodNames", 0, 16, true);
            boolean hasParamTypes = rule.has("paramTypes");
            List<String> paramTypes = readStringList(rule, "paramTypes", 0, 32, true);
            List<String> usingStrings = readStringList(rule, "usingStrings", 0, 16, false);
            List<String> invokes = readStringList(rule, "invokes", 0, 16, false);
            List<String> fieldNames = readStringList(rule, "fieldNames", 0, 16, true);
            String returnType = rule.optString("returnType", "").trim();
            String fieldType = rule.optString("fieldType", "").trim();
            if ((!returnType.isEmpty() && !SYMBOL.matcher(returnType).matches())
                    || (!fieldType.isEmpty() && !SYMBOL.matcher(fieldType).matches()))
                throw new IllegalArgumentException("dex rule type");
            int paramCount = rule.has("paramCount") ? rule.getInt("paramCount") : -1;
            int minCandidates = rule.optInt("minCandidates", 1);
            int maxCandidates = rule.optInt("maxCandidates", 1);
            boolean methodRule = "method".equals(resultType) || "ownerClass".equals(resultType);
            boolean fieldRule = "field".equals(resultType) || "fieldOwnerClass".equals(resultType);
            if (paramCount < -1 || paramCount > 32 || minCandidates < 1
                    || maxCandidates < minCandidates || maxCandidates > 16
                    || (hasParamTypes && paramCount >= 0 && paramTypes.size() != paramCount)
                    || (methodRule && (!fieldNames.isEmpty() || !fieldType.isEmpty()))
                    || (fieldRule && (!methodNames.isEmpty() || hasParamTypes
                    || !usingStrings.isEmpty() || !invokes.isEmpty()
                    || !returnType.isEmpty() || paramCount >= 0))
                    || (methodRule && methodNames.isEmpty() && !hasParamTypes
                    && usingStrings.isEmpty() && invokes.isEmpty()
                    && returnType.isEmpty() && paramCount < 0)
                    || (fieldRule && fieldNames.isEmpty() && fieldType.isEmpty()))
                throw new IllegalArgumentException("dex rule bounds");
            result.put(key, new DexRule(resultType, searchPackages, methodNames, returnType,
                    paramCount, hasParamTypes, paramTypes, usingStrings, invokes, fieldNames,
                    fieldType, minCandidates, maxCandidates));
        }
        return Collections.unmodifiableMap(result);
    }

    private static List<String> readStringList(JSONObject object, String key, int min, int max,
                                               boolean symbolsOnly) throws Exception {
        JSONArray values = object.optJSONArray(key);
        if (values == null) {
            if (min > 0)
                throw new IllegalArgumentException(key);
            return Collections.emptyList();
        }
        if (values.length() < min || values.length() > max)
            throw new IllegalArgumentException(key);
        List<String> result = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) {
            String value = values.getString(i).trim();
            if (value.isEmpty() || value.length() > 200
                    || (symbolsOnly && !SYMBOL.matcher(value).matches()))
                throw new IllegalArgumentException(key);
            result.add(value);
        }
        return Collections.unmodifiableList(result);
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

    public static final class DexRule {
        public final String result;
        public final List<String> searchPackages;
        public final List<String> methodNames;
        public final String returnType;
        public final int paramCount;
        public final boolean hasParamTypes;
        public final List<String> paramTypes;
        public final List<String> usingStrings;
        public final List<String> invokes;
        public final List<String> fieldNames;
        public final String fieldType;
        public final int minCandidates;
        public final int maxCandidates;

        DexRule(String result, List<String> searchPackages, List<String> methodNames,
                String returnType, int paramCount, boolean hasParamTypes,
                List<String> paramTypes, List<String> usingStrings, List<String> invokes,
                List<String> fieldNames, String fieldType, int minCandidates,
                int maxCandidates) {
            this.result = result;
            this.searchPackages = searchPackages;
            this.methodNames = methodNames;
            this.returnType = returnType;
            this.paramCount = paramCount;
            this.hasParamTypes = hasParamTypes;
            this.paramTypes = paramTypes;
            this.usingStrings = usingStrings;
            this.invokes = invokes;
            this.fieldNames = fieldNames;
            this.fieldType = fieldType;
            this.minCandidates = minCandidates;
            this.maxCandidates = maxCandidates;
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
        final Map<String, Boolean> features;
        final Map<String, DexRule> dexRules;

        Profile(String id, String channel, String displayName, String versionName,
                long minVersionCode, long maxVersionCode, String status,
                Map<String, List<String>> symbols, Map<String, Boolean> features,
                Map<String, DexRule> dexRules) {
            this.id = id;
            this.channel = channel;
            this.displayName = displayName;
            this.versionName = versionName;
            this.minVersionCode = minVersionCode;
            this.maxVersionCode = maxVersionCode;
            this.status = status;
            this.symbols = symbols;
            this.features = features;
            this.dexRules = dexRules;
        }
    }

    private static final class Catalog {
        final long revision;
        final String compatibilityUrl;
        final Map<String, List<String>> defaults;
        final Map<String, Boolean> features;
        final Map<String, DexRule> dexRules;
        final List<Profile> profiles;

        Catalog(long revision, String compatibilityUrl, Map<String, List<String>> defaults,
                Map<String, Boolean> features, Map<String, DexRule> dexRules,
                List<Profile> profiles) {
            this.revision = revision;
            this.compatibilityUrl = compatibilityUrl;
            this.defaults = defaults;
            this.features = features;
            this.dexRules = dexRules;
            this.profiles = Collections.unmodifiableList(profiles);
        }

        Catalog withCompatibilityUrl(String value) {
            return new Catalog(revision, value, defaults, features, dexRules, profiles);
        }

        static Catalog empty() {
            return new Catalog(0, "", Collections.emptyMap(), Collections.emptyMap(),
                    Collections.emptyMap(), Collections.emptyList());
        }
    }
}
