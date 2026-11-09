package com.weblauncher.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {
    private val json = """
        {"tag_name":"v7.3.0-b1062","name":"Web Launcher 7.3.0 (build 1062)","body":"Novità",
         "assets":[{"name":"notes.txt","browser_download_url":"https://x/notes.txt","size":10},
                   {"name":"web-launcher.apk","browser_download_url":"https://x/web-launcher.apk","size":12781397}]}
    """.trimIndent()

    @Test fun numeroDiBuildDalTag() {
        assertEquals(1062, UpdateLogic.codeFromTag("v7.3.0-b1062"))
        assertNull(UpdateLogic.codeFromTag("v7.3.0"))
    }

    @Test fun leggeLaRelease() {
        val r = UpdateLogic.parseRelease(json)!!
        assertEquals(1062, r.code)
        assertEquals("https://x/web-launcher.apk", r.apkUrl)
        assertEquals(12781397L, r.size)
        assertEquals("Novità", r.notes)
    }

    @Test fun senzaApkONumeroNonCeAggiornamento() {
        assertNull(UpdateLogic.parseRelease("""{"tag_name":"v1-b5","assets":[]}"""))
        assertNull(UpdateLogic.parseRelease("""{"tag_name":"v1","assets":[{"name":"a.apk","browser_download_url":"u"}]}"""))
        assertNull(UpdateLogic.parseRelease("non è json"))
    }

    @Test fun confrontoVersioni() {
        val r = UpdateLogic.parseRelease(json)
        assertTrue(UpdateLogic.isNewer(r, 45))
        assertFalse(UpdateLogic.isNewer(r, 1062))
        assertFalse(UpdateLogic.isNewer(null, 1))
    }

    @Test fun canaleDiProva() {
        val list = """[
          {"tag_name":"v7.4.0-prova-b1100","prerelease":true,"assets":[{"name":"a.apk","browser_download_url":"u1"}]},
          {"tag_name":"v7.3.0-b1062","prerelease":false,"assets":[{"name":"a.apk","browser_download_url":"u2"}]},
          {"tag_name":"v7.2.0-b1000","prerelease":false,"assets":[{"name":"a.apk","browser_download_url":"u3"}]}
        ]"""
        assertEquals(1062, UpdateLogic.pickBest(list, beta = false)!!.code)
        assertEquals(1100, UpdateLogic.pickBest(list, beta = true)!!.code)
        assertNull(UpdateLogic.pickBest("[]", beta = true))
    }
}
