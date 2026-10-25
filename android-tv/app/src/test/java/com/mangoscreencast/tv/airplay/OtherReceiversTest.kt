package com.mangoscreencast.tv.airplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class OtherReceiversTest {
    private val tv = InetAddress.getByName("192.168.1.69")
    private val local = listOf(InetAddress.getByName("127.0.0.1"), tv, InetAddress.getByName("fe80::1"))

    @Test
    fun aReceiverAtOneOfTheTvsAddressesIsOnThisTv() {
        assertTrue(OtherReceivers.isThisTv(InetAddress.getByName("192.168.1.69"), local))
    }

    @Test
    fun aReceiverAtLoopbackIsOnThisTv() {
        // Hisense's built-in AirPlay resolves to localhost.local.
        assertTrue(OtherReceivers.isThisTv(InetAddress.getByName("127.0.0.1"), local))
        assertTrue(OtherReceivers.isThisTv(InetAddress.getByName("::1"), local))
    }

    @Test
    fun aReceiverElsewhereIsNot() {
        assertFalse(OtherReceivers.isThisTv(InetAddress.getByName("192.168.1.224"), local))
        assertFalse(OtherReceivers.isThisTv(null, local))
    }

    @Test
    fun ourOwnServiceIsSkippedUnderEitherName() {
        val own = setOf("Mango Screen Cast For macOS/iOS")
        assertTrue(OtherReceivers.isOurs("Mango Screen Cast For macOS/iOS", own))
        // The responder's renamed copy of ours, before the settled name is known.
        assertTrue(OtherReceivers.isOurs("Mango Screen Cast For macOS/iOS (2)", own))
        assertFalse(OtherReceivers.isOurs("Master Bedroom TV", own))
        assertFalse(OtherReceivers.isOurs("Mango Screen Cast For macOS/iOS Bedroom", own))
    }
}
