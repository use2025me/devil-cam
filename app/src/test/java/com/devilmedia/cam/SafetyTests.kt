package com.devilmedia.cam

import org.junit.Assert.assertEquals
import org.junit.Test

class SafetyTests {
    @Test
    fun weakPasswordsAreFlagged() {
        assertEquals(Finding.Level.BAD, Safety.password("admin", "12345").level)
        assertEquals(Finding.Level.BAD, Safety.password("admin", "admin").level)
        assertEquals(Finding.Level.BAD, Safety.password("admin", "hik12345").level)
        assertEquals(Finding.Level.WARN, Safety.password("admin", "Short1!").level)
        assertEquals(Finding.Level.GOOD, Safety.password("admin", "Red-Devils-1878!").level)
    }

    @Test
    fun readsRecorderReplies() {
        val xml = """<DeviceInfo version="2.0"><deviceName>Home DVR</deviceName><model>DS-7204HQHI-K1</model>
            <firmwareVersion>V4.30.085</firmwareVersion></DeviceInfo>"""
        assertEquals("DS-7204HQHI-K1", Isapi.tag(xml, "model"))
        assertEquals("V4.30.085", Isapi.tag(xml, "firmwareVersion"))
        assertEquals("Home DVR", Isapi.tag(xml, "deviceName"))
    }
}
