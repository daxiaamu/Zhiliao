package com.shatyuka.zhiliao.ui

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDialogLayoutTest {
    @Test
    fun longChangelogUsesOneBoundedScrollableContainer() {
        val source = Files.readString(
            File("src/main/java/com/shatyuka/zhiliao/MainActivity.kt").toPath(),
            StandardCharsets.UTF_8,
        )
        val start = source.indexOf("private fun UpdateDialog(info: UpdateInfo)")
        val end = source.indexOf("private fun checkForUpdates", start)
        val dialog = source.substring(start, end)

        assertTrue(dialog.contains("LazyColumn("))
        assertTrue(dialog.contains(".heightIn(max = 420.dp)"))
        assertTrue(dialog.contains("SimpleMarkdownText(markdown = info.changelog)"))
        assertFalse(dialog.contains("verticalScroll("))
    }

    @Test
    fun updateCardKeepsTrailingSlotSizeWhileChecking() {
        val source = Files.readString(
            File("src/main/java/com/shatyuka/zhiliao/MainActivity.kt").toPath(),
            StandardCharsets.UTF_8,
        )
        val start = source.indexOf("private fun UpdateCard()")
        val end = source.indexOf("private fun AboutCard", start)
        val card = source.substring(start, end)

        assertTrue(card.contains("Modifier.size(width = 64.dp, height = 48.dp)"))
        assertTrue(card.contains("modifier = Modifier.fillMaxSize()"))
        assertTrue(card.contains("CircularProgressIndicator(Modifier.size(24.dp)"))
    }
}