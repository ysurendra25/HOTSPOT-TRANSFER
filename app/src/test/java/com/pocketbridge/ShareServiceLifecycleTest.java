package com.pocketbridge;

import android.content.Intent;
import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@LooperMode(LooperMode.Mode.PAUSED)
public class ShareServiceLifecycleTest {
    @Test public void stoppingDuringHotspotLookupAllowsImmediateRestartAndClearsTheCode() throws Exception {
        ServiceController<ShareSessionService> controller = Robolectric.buildService(ShareSessionService.class).create();
        ShareSessionService service = controller.get();
        try {
            service.onStartCommand(new Intent(ShareSessionService.ACTION_START_HOTSPOT), 0, 1);
            assertServerAnswers();
            service.onStartCommand(new Intent(ShareSessionService.ACTION_STOP), 0, 2);
            assertFalse(lastStatus(service).getBooleanExtra(ShareSessionService.EXTRA_ACTIVE, true));
            assertNull(lastStatus(service).getStringExtra(ShareSessionService.EXTRA_CODE));

            service.onStartCommand(new Intent(ShareSessionService.ACTION_START_WIFI), 0, 3);
            shadowOf(Looper.getMainLooper()).idle();
            assertServerAnswers();
            service.onStartCommand(new Intent(ShareSessionService.ACTION_QUERY), 0, 4);
            Intent status = lastStatus(service);
            assertTrue(status.getBooleanExtra(ShareSessionService.EXTRA_ACTIVE, false));
            assertTrue(status.getStringExtra(ShareSessionService.EXTRA_CODE).matches("[0-9]{6}"));
        } finally {
            service.onStartCommand(new Intent(ShareSessionService.ACTION_STOP), 0, 5);
            controller.destroy();
        }
    }

    private static void assertServerAnswers() throws Exception {
        URL health = new URL("http://127.0.0.1:" + MediaHttpServer.PORT + "/health");
        HttpURLConnection connection = (HttpURLConnection) health.openConnection(Proxy.NO_PROXY);
        try {
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            assertEquals(200, connection.getResponseCode());
        } finally {
            connection.disconnect();
        }
    }

    private static Intent lastStatus(ShareSessionService service) {
        List<Intent> broadcasts = shadowOf(service.getApplication()).getBroadcastIntents();
        for (int i = broadcasts.size() - 1; i >= 0; i--) {
            if (ShareSessionService.ACTION_STATUS.equals(broadcasts.get(i).getAction())) return broadcasts.get(i);
        }
        throw new AssertionError("Missing share status broadcast");
    }
}
