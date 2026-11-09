package com.weblauncher.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android compiles regular expressions with ICU, not with the JVM engine used by these tests:
 * some JVM-only syntax (e.g. the inline flag (?U)) passes here and crashes on the phone.
 * This test scans the sources for it.
 */
class AndroidRegexCompatTest {
    private val sources = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }

    @Test fun nessunaOpzioneNonSupportataDaAndroid() {
        val icuFlags = setOf('i', 's', 'm', 'w', 'x', '-')
        val problems = mutableListOf<String>()
        sources.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            f.readLines().forEachIndexed { n, line ->
                Regex("""\(\?([a-zA-Z\-]+)[):]""").findAll(line).forEach { m ->
                    if (m.groupValues[1].any { it !in icuFlags }) problems += "${f.name}:${n + 1} ${m.value}"
                }
            }
        }
        assertTrue("Opzioni regex non supportate da Android: $problems", problems.isEmpty())
    }

    @Test fun accentiRiconosciutiComeLettere() {
        val r = Regex("${WB}venerdì$WB")
        assertTrue(r.containsMatchIn("alle 9 venerdì sera"))
        assertTrue(r.containsMatchIn("venerdì"))
        assertTrue(!r.containsMatchIn("venerdìx"))
    }
}
