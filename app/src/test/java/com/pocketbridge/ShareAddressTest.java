package com.pocketbridge;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ShareAddressTest {
    private static ShareSessionService.Addr address(String ip, String name) {
        return new ShareSessionService.Addr(ip, name);
    }

    @Test public void hotspotAndWifiUseTheirOwnNetworksEvenWithVpnAndMobileDataEnabled() {
        List<ShareSessionService.Addr> networks = Arrays.asList(
                address("192.0.0.4", "v4-rmnet0"), address("10.11.22.1", "tun0"),
                address("192.168.1.131", "wifi"), address("10.255.251.221", "ap_br_wlan2"));
        assertEquals("10.255.251.221", ShareSessionService.selectAddress(networks, true));
        assertEquals("192.168.1.131", ShareSessionService.selectAddress(networks, false));
        Collections.reverse(networks);
        assertEquals("10.255.251.221", ShareSessionService.selectAddress(networks, true));
        assertEquals("192.168.1.131", ShareSessionService.selectAddress(networks, false));
    }

    @Test public void hotspotWaitsForItsAddressIfOnlyAnUpstreamConnectionExists() {
        List<ShareSessionService.Addr> upstream = Arrays.asList(
                address("192.168.0.121", "wifi"), address("10.1.0.1", "rmnet0"),
                address("10.2.0.1", "apn0"), address("10.3.0.1", "vpn"),
                address("10.4.0.1", "eth0"), address("192.168.49.1", "wlan0-p2p0"),
                address("192.0.0.4", "v4-softap0"));
        assertNull(ShareSessionService.selectAddress(upstream, true));
        assertNull(ShareSessionService.selectAddress(Collections.emptyList(), true));
    }

    @Test public void recognisesSingleRadioAndDualRadioHotspotsWithoutAssumingAnIpSuffix() {
        ShareSessionService.Addr wlan0 = address("10.150.205.183", "wlan0");
        assertEquals(wlan0.ip, ShareSessionService.selectAddress(Collections.singletonList(wlan0), true));
        ShareSessionService.Addr wlan1 = address("172.21.48.37", "wlan1");
        assertEquals(wlan1.ip, ShareSessionService.selectAddress(Arrays.asList(wlan0, wlan1), true));
        ShareSessionService.Addr ap = address("192.168.55.123", "swlan0");
        assertEquals(ap.ip, ShareSessionService.selectAddress(Arrays.asList(wlan0, wlan1, ap), true));
    }

    @Test public void onlyAnUnambiguousExistingAccessPointSkipsPrivateHotspotCreation() {
        for (String name : Arrays.asList("ap0", "ap_br_wlan2", "softap0", "swlan0", "wlan_ap0", "br_wlan0")) {
            assertTrue(name, ShareSessionService.isAccessPointInterface(name));
        }
        for (String name : Arrays.asList("wifi", "wlan0", "wlan1", "apn0", "eth0", "rmnet0", "wlan0-p2p0")) {
            assertFalse(name, ShareSessionService.isAccessPointInterface(name));
        }
    }
}
