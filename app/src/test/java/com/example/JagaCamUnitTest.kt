package com.example

import com.example.data.CameraFacing
import com.example.data.CctvRoom
import com.example.data.RoomStatus
import com.example.data.VideoResolution
import com.example.security.CalculatorEngine
import com.example.security.CalculatorState
import com.example.security.PinHasher
import com.example.util.QrCodeUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JagaCamUnitTest {

    @Test
    fun `qr payload parser extracts valid room code`() {
        assertEquals("JG4K8M2P", QrCodeUtils.parseRoomCodeFromQr("jagacam://pair/jg4k8m2p"))
        assertEquals("AB12CD34", QrCodeUtils.parseRoomCodeFromQr("  ab12cd34  "))
        assertNull(QrCodeUtils.parseRoomCodeFromQr("short"))
        assertNull(QrCodeUtils.parseRoomCodeFromQr("invalid space code"))
    }

    @Test
    fun `low battery threshold triggers strictly below 15 percent`() {
        assertTrue(CctvRoom(batteryPercent = 14L).isLowBattery)
        assertTrue(CctvRoom(batteryPercent = 0L).isLowBattery)
        assertFalse(CctvRoom(batteryPercent = 15L).isLowBattery)
        assertFalse(CctvRoom(batteryPercent = 85L).isLowBattery)
    }

    @Test
    fun `safe enum parsing falls back gracefully on unknown values`() {
        assertEquals(RoomStatus.WAITING, RoomStatus.safeParse("UNKNOWN_STATE"))
        assertEquals(CameraFacing.BACK, CameraFacing.safeParse(null))
        assertEquals(VideoResolution.SD_480P, VideoResolution.safeParse("1080p"))
        assertEquals(VideoResolution.HD_720P, VideoResolution.safeParse("720p"))
    }

    @Test
    fun `pin hasher validates format and verifies salted hash`() {
        assertFalse(PinHasher.isValidPin("123"))
        assertFalse(PinHasher.isValidPin("123456789"))
        assertFalse(PinHasher.isValidPin("12ab"))
        assertTrue(PinHasher.isValidPin("2580"))
        assertTrue(PinHasher.isValidPin("12345678"))

        val stored = PinHasher.hashPin("2580")
        assertTrue(PinHasher.verifyPin("2580", stored))
        assertFalse(PinHasher.verifyPin("2581", stored))
        assertFalse(PinHasher.verifyPin("2580", null))
    }

    @Test
    fun `calculator engine evaluates math and extracts pure pin candidates`() {
        var s = CalculatorState()
        s = CalculatorEngine.onDigit(s, '2')
        s = CalculatorEngine.onDigit(s, '5')
        s = CalculatorEngine.onDigit(s, '8')
        s = CalculatorEngine.onDigit(s, '0')
        assertEquals("2580", CalculatorEngine.extractPlainPinCandidate(s))

        // Arithmetic expression must NOT be treated as a plain PIN candidate
        s = CalculatorEngine.onClear()
        s = CalculatorEngine.onDigit(s, '1')
        s = CalculatorEngine.onDigit(s, '2')
        s = CalculatorEngine.onOperator(s, '×')
        s = CalculatorEngine.onDigit(s, '4')
        assertNull(CalculatorEngine.extractPlainPinCandidate(s))
        s = CalculatorEngine.onEquals(s)
        assertEquals("48", s.display)
    }
}
