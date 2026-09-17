package ai.argvid.gen0.gimbal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class F32cBleContractTest {
    @Test
    fun acceptsOnlyF32cAdvertisedNames() {
        assertTrue(F32cBleContract.isAdvertisedNameAccepted("F32C-Gimbal"))
        assertTrue(F32cBleContract.isAdvertisedNameAccepted("F32C-Gimbal-1"))
        assertFalse(F32cBleContract.isAdvertisedNameAccepted("Other-Gimbal"))
        assertFalse(F32cBleContract.isAdvertisedNameAccepted(null))
    }

    @Test
    fun commandLimitCountsUtf8Bytes() {
        val payload = "{\"msg\":\"${"中".repeat(70)}\"}"
        assertEquals(payload.length, 8 + 70 + 2)
        assertTrue(payload.toByteArray().size <= F32cBleContract.maxCommandBytes)
        assertArrayEquals(
            "{\"cmd\":\"query\"}".toByteArray(),
            F32cBleContract.commandJson("{\"cmd\":\"query\"}"),
        )
    }

    @Test
    fun acceptsCommandAtTheFirmwareBoundary() {
        // The firmware drops any FF03 JSON whose length reaches its 240-byte buffer,
        // so exactly maxCommandBytes must pass and one byte more must not.
        val largest = "x".repeat(F32cBleContract.maxCommandBytes)
        assertEquals(F32cBleContract.maxCommandBytes, F32cBleContract.commandJson(largest).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCommandLargerThanWireLimit() {
        F32cBleContract.commandJson("x".repeat(F32cBleContract.maxCommandBytes + 1))
    }
}
