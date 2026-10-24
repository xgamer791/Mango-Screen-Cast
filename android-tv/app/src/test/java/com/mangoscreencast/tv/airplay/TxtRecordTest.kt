package com.mangoscreencast.tv.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

class TxtRecordTest {
    private fun wire(vararg entries: String): ByteArray =
        entries.flatMap { listOf(it.length.toByte()) + it.toByteArray().toList() }.toByteArray()

    @Test
    fun parsesAirPlayRecord() {
        val record = wire(
            "deviceid=02:11:22:33:44:55",
            "features=0x527FFEE6,0x400",
            "pw=false",
            "model=AppleTV3,2",
        )
        assertEquals(
            listOf(
                "deviceid" to "02:11:22:33:44:55",
                "features" to "0x527FFEE6,0x400",
                "pw" to "false",
                "model" to "AppleTV3,2",
            ),
            TxtRecord.parse(record),
        )
    }

    @Test
    fun handlesFlagsEmptyEntriesAndTruncation() {
        val record = wire("flag", "", "key=") + byteArrayOf(20, 'x'.code.toByte())
        assertEquals(listOf("flag" to "", "key" to ""), TxtRecord.parse(record))
    }
}
