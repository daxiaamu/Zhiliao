package com.shatyuka.zhiliao.update;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class UpdateInfo {
    public final long versionCode;
    public final String versionName;
    public final String downloadUrl;
    public final List<String> downloadUrls;
    public final String sha256;
    public final String changelog;
    public final String publishedAt;

    public UpdateInfo(long versionCode, String versionName, String downloadUrl,
                      String sha256, String changelog, String publishedAt) {
        this(versionCode, versionName, Collections.singletonList(downloadUrl), sha256,
                changelog, publishedAt);
    }

    public UpdateInfo(long versionCode, String versionName, List<String> downloadUrls,
                      String sha256, String changelog, String publishedAt) {
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.downloadUrls = Collections.unmodifiableList(new ArrayList<>(downloadUrls));
        this.downloadUrl = this.downloadUrls.get(0);
        this.sha256 = sha256;
        this.changelog = changelog;
        this.publishedAt = publishedAt;
    }
}
