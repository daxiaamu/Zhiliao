package com.shatyuka.zhiliao.update;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class UpdateManager {
    public static final String[] UPDATE_JSON_URLS = {
            "https://cdn.jsdelivr.net/gh/daxiaamu/Zhiliao@update/update.json",
            "https://fastly.jsdelivr.net/gh/daxiaamu/Zhiliao@update/update.json",
            "https://gcore.jsdelivr.net/gh/daxiaamu/Zhiliao@update/update.json",
            "https://testingcf.jsdelivr.net/gh/daxiaamu/Zhiliao@update/update.json",
            "https://cdn.statically.io/gh/daxiaamu/Zhiliao/update/update.json",
            "https://raw.githubusercontent.com/daxiaamu/Zhiliao/update/update.json",
            "https://github.com/daxiaamu/Zhiliao/releases/latest/download/update.json"
    };

    private static final String[] RELEASE_PROXY_PREFIXES = {
            "https://ghfast.top/",
            "https://gh-proxy.com/",
            "https://ghproxy.net/",
            "https://gh.llkk.cc/",
            "https://ghp.keleyaa.com/",
            "https://gh.monlor.com/"
    };

    public interface CheckCallback {
        void onComplete(UpdateInfo info, Throwable error);
    }

    public interface DownloadCallback {
        void onProgress(int percent);

        void onComplete(File apk, Throwable error);
    }

    private static volatile UpdateManager instance;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<CheckCallback> checkCallbacks = new ArrayList<>();
    private final List<DownloadCallback> downloadCallbacks = new ArrayList<>();

    private boolean checking;
    private boolean downloading;
    private UpdateInfo sessionResult;
    private long automaticPromptedVersion = -1;

    private UpdateManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public static UpdateManager get(Context context) {
        if (instance == null) {
            synchronized (UpdateManager.class) {
                if (instance == null)
                    instance = new UpdateManager(context);
            }
        }
        return instance;
    }

    /** Manual and automatic callers share both the in-flight request and its session result. */
    public synchronized void check(CheckCallback callback) {
        if (sessionResult != null) {
            postCheck(callback, sessionResult, null);
            return;
        }
        checkCallbacks.add(callback);
        if (checking)
            return;
        checking = true;
        executor.execute(() -> {
            UpdateInfo info = null;
            Throwable error = null;
            try {
                info = fetchUpdateInfo();
            } catch (Throwable throwable) {
                error = throwable;
            }
            finishCheck(info, error);
        });
    }

    public synchronized boolean claimAutomaticPrompt(long versionCode) {
        if (automaticPromptedVersion == versionCode)
            return false;
        automaticPromptedVersion = versionCode;
        return true;
    }

    public synchronized void download(UpdateInfo info, DownloadCallback callback) {
        downloadCallbacks.add(callback);
        if (downloading)
            return;
        downloading = true;
        executor.execute(() -> {
            File apk = null;
            Throwable error = null;
            try {
                apk = downloadAndVerify(info);
            } catch (Throwable throwable) {
                error = throwable;
            }
            finishDownload(apk, error);
        });
    }

    private UpdateInfo fetchUpdateInfo() throws Exception {
        Throwable lastError = null;
        for (String source : UPDATE_JSON_URLS) {
            try {
                return fetchUpdateInfo(source);
            } catch (Throwable throwable) {
                lastError = throwable;
            }
        }
        throw new IllegalStateException("所有更新信息源均不可用", lastError);
    }

    private UpdateInfo fetchUpdateInfo(String source) throws Exception {
        HttpURLConnection connection = openHttps(source);
        try {
            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300)
                throw new IllegalStateException("更新服务器返回 HTTP " + responseCode);
            requireHttps(connection.getURL().toString());
            String json = readText(connection.getInputStream(), 1024 * 1024);
            JSONObject object = new JSONObject(json);
            long versionCode = object.getLong("versionCode");
            String versionName = object.getString("versionName").trim();
            String downloadUrl = requireHttps(object.getString("url").trim());
            List<String> downloadUrls = collectDownloadUrls(object, downloadUrl);
            String sha256 = object.getString("sha256").trim().toLowerCase(Locale.ROOT);
            if (!sha256.matches("[0-9a-f]{64}"))
                throw new IllegalStateException("更新信息中的 SHA-256 无效");
            return new UpdateInfo(versionCode, versionName, downloadUrls, sha256,
                    object.optString("changelog", "暂无更新说明"),
                    object.optString("publishedAt", ""));
        } finally {
            connection.disconnect();
        }
    }

    private File downloadAndVerify(UpdateInfo info) throws Exception {
        File updateDir = new File(context.getCacheDir(), "updates");
        if (!updateDir.exists() && !updateDir.mkdirs())
            throw new IllegalStateException("无法创建更新缓存目录");
        File destination = new File(updateDir, "Zhiliao_" + info.versionCode + ".apk");
        if (destination.isFile() && info.sha256.equals(sha256(destination))) {
            postProgress(100);
            return destination;
        }

        Throwable lastError = null;
        for (String downloadUrl : info.downloadUrls) {
            try {
                return downloadAndVerifyFrom(info, downloadUrl, destination);
            } catch (Throwable throwable) {
                lastError = throwable;
            }
        }
        throw new IllegalStateException("所有 APK 下载源均不可用或校验失败", lastError);
    }

    private File downloadAndVerifyFrom(UpdateInfo info, String downloadUrl,
                                       File destination) throws Exception {
        File partial = new File(destination.getParentFile(), destination.getName() + ".download");
        if (partial.exists() && !partial.delete())
            throw new IllegalStateException("无法清理上一次下载的临时文件");
        postProgress(-1);
        HttpURLConnection connection = openHttps(downloadUrl);
        try {
            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300)
                throw new IllegalStateException("下载服务器返回 HTTP " + responseCode);
            requireHttps(connection.getURL().toString());
            long total = connection.getContentLengthLong();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream output = new FileOutputStream(partial, false)) {
                byte[] buffer = new byte[64 * 1024];
                long downloaded = 0;
                int lastProgress = -1;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    downloaded += read;
                    int progress = total > 0 ? (int) Math.min(99, downloaded * 100 / total) : -1;
                    if (progress != lastProgress) {
                        lastProgress = progress;
                        postProgress(progress);
                    }
                }
                output.getFD().sync();
            }
            String actual = hex(digest.digest());
            if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),
                    info.sha256.getBytes(StandardCharsets.US_ASCII))) {
                //noinspection ResultOfMethodCallIgnored
                partial.delete();
                throw new SecurityException("APK SHA-256 校验失败\n期望: " + info.sha256 + "\n实际: " + actual);
            }
            if (destination.exists() && !destination.delete())
                throw new IllegalStateException("无法替换旧更新文件");
            if (!partial.renameTo(destination))
                throw new IllegalStateException("无法保存已校验的更新文件");
            postProgress(100);
            return destination;
        } catch (Throwable throwable) {
            //noinspection ResultOfMethodCallIgnored
            partial.delete();
            throw throwable;
        } finally {
            connection.disconnect();
        }
    }

    static List<String> collectDownloadUrls(JSONObject object, String primary) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        JSONArray array = object.optJSONArray("urls");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i, "").trim();
                if (!value.isEmpty())
                    urls.add(requireHttps(value));
            }
        }
        urls.addAll(expandReleaseUrls(primary));
        return new ArrayList<>(urls);
    }

    static List<String> expandReleaseUrls(String primary) {
        List<String> urls = new ArrayList<>();
        urls.add(primary);
        if (primary.startsWith("https://github.com/")) {
            for (String prefix : RELEASE_PROXY_PREFIXES)
                urls.add(prefix + primary);
        }
        return urls;
    }

    private static HttpURLConnection openHttps(String value) throws Exception {
        URL url = new URL(requireHttps(value));
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json, application/vnd.android.package-archive, */*");
        connection.setRequestProperty("User-Agent", "Zhiliao-UpdateClient");
        return connection;
    }

    private static String requireHttps(String value) {
        if (!value.toLowerCase(Locale.ROOT).startsWith("https://"))
            throw new IllegalArgumentException("更新地址必须使用 HTTPS");
        return value;
    }

    private static String readText(InputStream input, int maxBytes) throws Exception {
        StringBuilder builder = new StringBuilder();
        int count = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                count += read;
                if (count > maxBytes)
                    throw new IllegalStateException("更新信息过大");
                builder.append(buffer, 0, read);
            }
        }
        return builder.toString();
    }

    static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1)
                digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] value) {
        StringBuilder builder = new StringBuilder(value.length * 2);
        for (byte item : value)
            builder.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return builder.toString();
    }

    private void finishCheck(UpdateInfo info, Throwable error) {
        final List<CheckCallback> callbacks;
        synchronized (this) {
            checking = false;
            if (error == null)
                sessionResult = info;
            callbacks = new ArrayList<>(checkCallbacks);
            checkCallbacks.clear();
        }
        for (CheckCallback callback : callbacks)
            postCheck(callback, info, error);
    }

    private void postCheck(CheckCallback callback, UpdateInfo info, Throwable error) {
        mainHandler.post(() -> callback.onComplete(info, error));
    }

    private void postProgress(int percent) {
        final List<DownloadCallback> callbacks;
        synchronized (this) {
            callbacks = new ArrayList<>(downloadCallbacks);
        }
        mainHandler.post(() -> {
            for (DownloadCallback callback : callbacks)
                callback.onProgress(percent);
        });
    }

    private void finishDownload(File apk, Throwable error) {
        final List<DownloadCallback> callbacks;
        synchronized (this) {
            downloading = false;
            callbacks = new ArrayList<>(downloadCallbacks);
            downloadCallbacks.clear();
        }
        mainHandler.post(() -> {
            for (DownloadCallback callback : callbacks)
                callback.onComplete(apk, error);
        });
    }
}
