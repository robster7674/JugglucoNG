package tk.glucodata.drivers.anytime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnytimeFramesTests {

    @Test
    fun parseCheckResponseUsesOfficialBigEndianCurrentFieldWhenDiagnosticLooksImplausible() {
        val status = AnytimeFrames.parseCheckResponse(
            byteArrayOf(
                0x05,
                0x12,
                0x05,
                0x64,
                0x00,
                0x01,
                0xC0.toByte(),
                0x48,
                0x62,
                0x04,
                0x1B,
                0x06,
                0xD1.toByte(),
                0x06,
                0xD2.toByte(),
                0x05,
                0x18,
                0x00,
                0x00,
                0xD6.toByte(),
            ),
            AnytimeConstants.BATTERY_LOW_VOLTS_CT3_A,
        )

        assertTrue(status.isHealthy)
        assertEquals(1380, status.sensorAgeReadings)
        assertEquals(13.8f, status.workingElectrodeCurrentNa, 0.001f)
        assertEquals(4.27f, status.batteryVolts, 0.001f)
    }

    @Test
    fun parseWideRawRecordsMatchesOfficialCt4ByteOrder() {
        val records = AnytimeFrames.parseRawRecords(
            byteArrayOf(
                0x07,
                0x93.toByte(),
                0x00,
                0x01,
                0xB7.toByte(),
                0x05,
                0x5E,
                0x49,
                0x0A,
                0x04,
                0x1B,
                0x27,
            ),
            wideRecords = true,
        )

        assertEquals(1, records.size)
        val rec = records.single()
        assertEquals(147, rec.glucoseId)
        assertEquals(4.39f, rec.ibNa, 0.001f)
        assertEquals(13.74f, rec.iwNa, 0.001f)
        assertEquals(33.10f, rec.temperatureC, 0.001f)
    }

    @Test
    fun ct5EncodeDecodeRoundTripMatchesOfficialInversePair() {
        val plain = byteArrayOf(0x01, 0x09, 0x01, 0x00, 0x03, 0x53, 0x55, 0x00, 0x31, 0x32, 0x33, 0x34)
        val key = 0x5A

        val encrypted = AnytimeFrames.ct5Decode(plain, key)

        assertTrue(encrypted.toList() != plain.toList())
        assertEquals(plain.toList(), AnytimeFrames.ct5Encode(encrypted, key).toList())
    }

    @Test
    fun parseCt5CurrentRecordDecryptsOfficialLayout() {
        val key = 0x5A
        val plainPayload = byteArrayOf(
            0x01,
            0xB7.toByte(), // Ib 4.39 nA
            0x05,
            0x5E, // Iw 13.74 nA
            0x49,
            0x0A, // 33.10 C
            0x50, // trend 5, glucose high nibble 0
            0x7B, // 123 mg/dL
            0x00, // error
            0x00,
            0x00,
        )
        val encrypted = AnytimeFrames.ct5Decode(plainPayload, key)
        val frame = ByteArray(15)
        frame[0] = AnytimeConstants.RX_CT5_PUSH_GLUCOSE
        frame[1] = 0x93.toByte()
        frame[2] = 0x00
        encrypted.copyInto(frame, destinationOffset = 3)
        frame[14] = AnytimeFrames.sum(frame, 0, 13)

        val rec = AnytimeFrames.parseCt5CurrentRecord(frame, key)

        assertNotNull(rec)
        rec!!
        assertEquals(147, rec.glucoseId)
        assertEquals(4.39f, rec.ibNa, 0.001f)
        assertEquals(13.74f, rec.iwNa, 0.001f)
        assertEquals(33.10f, rec.temperatureC, 0.001f)
        assertEquals(123, rec.gluMgdl)
        assertEquals(5, rec.trend)
        assertEquals(0, rec.errorCode)
    }
}
