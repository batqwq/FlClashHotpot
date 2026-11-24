package be.mygod.vpnhotspot.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiCountryCodeCommandsTest {
    @Test
    fun normalizeAcceptsValidIsoAlpha2Codes() {
        assertEquals("US", WifiCountryCodeCommands.normalizeCountryCode("us"))
        assertEquals("JP", WifiCountryCodeCommands.normalizeCountryCode("  jp "))
        assertEquals("SG", WifiCountryCodeCommands.normalizeCountryCode("SG"))
        assertEquals("HK", WifiCountryCodeCommands.normalizeCountryCode("hk"))
    }

    @Test
    fun normalizeRejectsInvalidAndPlaceholderCodes() {
        assertNull(WifiCountryCodeCommands.normalizeCountryCode(null))
        assertNull(WifiCountryCodeCommands.normalizeCountryCode(""))
        assertNull(WifiCountryCodeCommands.normalizeCountryCode("00"))
        assertNull(WifiCountryCodeCommands.normalizeCountryCode("XX"))
        assertNull(WifiCountryCodeCommands.normalizeCountryCode("ZZ"))
        assertNull(WifiCountryCodeCommands.normalizeCountryCode("T1"))
        assertNull(WifiCountryCodeCommands.normalizeCountryCode("USA"))
        assertNull(WifiCountryCodeCommands.normalizeCountryCode("U"))
        assertNull(WifiCountryCodeCommands.normalizeCountryCode("1A"))
    }

    @Test
    fun parseCountryCodeOutputHandlesAospFormats() {
        assertEquals("JP", WifiCountryCodeCommands.parseCountryCodeOutput("Wifi Country Code = JP\n"))
        assertEquals("US", WifiCountryCodeCommands.parseCountryCodeOutput("US\n"))
        assertEquals("DE", WifiCountryCodeCommands.parseCountryCodeOutput("Active country code: de"))
        assertNull(WifiCountryCodeCommands.parseCountryCodeOutput("Wifi Country Code = 00\n"))
        assertNull(WifiCountryCodeCommands.parseCountryCodeOutput(""))
    }
}
