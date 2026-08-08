package com.shatyuka.zhiliao;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/** Prevents hook preferences from disappearing during future settings UI refactors. */
public class SettingsCoverageTest {
    private static final Pattern PREFERENCE_READ = Pattern.compile(
            "prefs\\.get(?:Boolean|String|Int)\\(\\\"([^\\\"]+)\\\"");

    @Test
    public void everyHookPreferenceIsRepresentedByTheSettingsApp() throws Exception {
        Set<String> usedKeys = new LinkedHashSet<>();
        collectPreferenceReads(new File("src/main/java/com/shatyuka/zhiliao/Helper.java"), usedKeys);

        File[] hooks = new File("src/main/java/com/shatyuka/zhiliao/hooks")
                .listFiles((directory, name) -> name.endsWith(".java"));
        if (hooks != null) {
            for (File hook : hooks) collectPreferenceReads(hook, usedKeys);
        }

        String settings = Files.readString(
                new File("src/main/java/com/shatyuka/zhiliao/MainActivity.kt").toPath(),
                StandardCharsets.UTF_8);
        for (String key : usedKeys) {
            assertTrue("Hook preference is missing from MainActivity: " + key,
                    settings.contains("\"" + key + "\""));
        }
    }

    private static void collectPreferenceReads(File source, Set<String> output) throws Exception {
        String content = Files.readString(source.toPath(), StandardCharsets.UTF_8);
        Matcher matcher = PREFERENCE_READ.matcher(content);
        while (matcher.find()) output.add(matcher.group(1));
    }
}
