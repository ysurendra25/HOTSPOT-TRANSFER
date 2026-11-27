package com.pocketbridge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Hotspot band parsing and the Connect hint that depends on it. */
public class HotspotBandTest {

    @Test public void frequencies() {
        assertEquals(HotspotBand.GHZ_2_4, HotspotBand.fromFrequency(2437));
        assertEquals(HotspotBand.GHZ_5, HotspotBand.fromFrequency(5180));
        assertEquals(HotspotBand.GHZ_6, HotspotBand.fromFrequency(5955));
        assertEquals(HotspotBand.UNKNOWN, HotspotBand.fromFrequency(0));
    }

    @Test public void softApMasksAndLegacyBands() {
        assertEquals(HotspotBand.GHZ_2_4, HotspotBand.fromSoftApBandMask(1));
        assertEquals(HotspotBand.GHZ_5, HotspotBand.fromSoftApBandMask(2));
        assertEquals(HotspotBand.GHZ_5, HotspotBand.fromSoftApBandMask(2 | 4));
        assertEquals(HotspotBand.GHZ_6, HotspotBand.fromSoftApBandMask(4));
        assertEquals(HotspotBand.UNKNOWN, HotspotBand.fromSoftApBandMask(1 | 2));
        assertEquals(HotspotBand.GHZ_2_4, HotspotBand.fromLegacyApBand(0));
        assertEquals(HotspotBand.GHZ_5, HotspotBand.fromLegacyApBand(1));
        assertEquals(HotspotBand.UNKNOWN, HotspotBand.fromLegacyApBand(-1));
        assertEquals(HotspotBand.UNKNOWN, HotspotBand.fromSoftApConfiguration(new Object()));
    }

    @Test public void hintOnlyInHotspotModeOnA5GhzPhone() {
        assertEquals(HotspotBand.Hint.SWITCH_TO_5, HotspotBand.hint(HotspotBand.GHZ_2_4, true, true));
        assertEquals(HotspotBand.Hint.CHECK_BAND, HotspotBand.hint(HotspotBand.UNKNOWN, true, true));
        assertEquals(HotspotBand.Hint.NONE, HotspotBand.hint(HotspotBand.GHZ_5, true, true));
        assertEquals(HotspotBand.Hint.NONE, HotspotBand.hint(HotspotBand.GHZ_2_4, false, true));
        assertEquals(HotspotBand.Hint.NONE, HotspotBand.hint(HotspotBand.GHZ_2_4, true, false));
        assertTrue(HotspotBand.message(HotspotBand.Hint.SWITCH_TO_5).contains("2.4 GHz"));
        assertEquals("", HotspotBand.message(HotspotBand.Hint.NONE));
    }
}
