package za.co.neroland.neroeconomy.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NeroEconomyNetworkTest {
    @Test void aStringWithinItsDeclaredLengthIsSentUnchanged() {
        assertEquals("minecraft:diamond", NeroEconomyNetwork.clip("minecraft:diamond", 128));
        assertEquals("a".repeat(128), NeroEconomyNetwork.clip("a".repeat(128), 128));
    }

    @Test void anOverLongStringIsCutToTheDeclaredLengthInsteadOfFailingTheEncode() {
        assertEquals("a".repeat(128), NeroEconomyNetwork.clip("a".repeat(129), 128));
        assertEquals("a".repeat(24), NeroEconomyNetwork.clip("a".repeat(5000), 24));
    }

    @Test void aMissingStringIsSentEmpty() {
        assertEquals("", NeroEconomyNetwork.clip(null, 64));
        assertEquals("", NeroEconomyNetwork.clip("abc", 0));
        assertEquals("", NeroEconomyNetwork.clip("abc", -3));
    }
}
