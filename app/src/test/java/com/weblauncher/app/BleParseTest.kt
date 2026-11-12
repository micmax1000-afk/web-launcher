package com.weblauncher.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BleParseTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun bloodPressureWithPulse() {
        val r = BleParse.bloodPressure(b(0x04, 0x78, 0, 0x50, 0, 0x5D, 0, 0x48, 0))!!
        assertEquals(120.0, r.v1, 0.0); assertEquals(80.0, r.v2!!, 0.0); assertEquals(72, r.pulse)
    }
    @Test fun bloodPressureKpa() {
        // 16 kPa = 120 mmHg
        val r = BleParse.bloodPressure(b(0x01, 0x10, 0, 0x0B, 0, 0x0D, 0))!!
        assertEquals(120.0, r.v1, 0.0)
    }
    @Test fun weightKg() = assertEquals(70.0, BleParse.weight(b(0, 0xB0, 0x36))!!.v1, 0.0)
    @Test fun weightPounds() = assertEquals(68.0, BleParse.weight(b(1, 0x94, 0x3A))!!.v1, 0.1) // 150.0 lb
    @Test fun temperature() = assertEquals(36.6, BleParse.temperature(b(0, 0x6E, 0x01, 0x00, 0xFF))!!.v1, 0.001)
    @Test fun temperatureFahrenheit() = assertEquals(37.0, BleParse.temperature(b(1, 0xDA, 0x03, 0x00, 0xFF))!!.v1, 0.05) // 98.6 F
    @Test fun oximeter() {
        val r = BleParse.oximeter(b(0, 0x61, 0, 0x41, 0))!!
        assertEquals(97.0, r.v1, 0.0); assertEquals(65, r.pulse)
    }
    @Test fun oximeterNoValue() = assertNull(BleParse.oximeter(b(0, 0xFF, 0x07, 0x41, 0)))
    @Test fun glucoseKgPerL() = assertEquals(100.0, BleParse.glucose(b(0x02, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0x01, 0xD0, 0x11))!!.v1, 0.0)
    @Test fun glucoseMolPerL() = assertEquals(99.0, BleParse.glucose(b(0x06, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0x37, 0xC0, 0x11))!!.v1, 0.0)
    @Test fun glucoseWithoutValue() = assertNull(BleParse.glucose(b(0x00, 1, 0, 0, 0, 0, 0, 0, 0, 0)))
}
