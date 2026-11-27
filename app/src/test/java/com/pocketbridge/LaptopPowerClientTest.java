package com.pocketbridge;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LaptopPowerClientTest {
    private FakeHttpServer helper;
    private HelperLink link;
    private volatile String lastJson = "{\"ok\":true,\"last\":{\"action\":\"sleep\",\"ok\":true,\"pending\":false}}";

    @Before public void start() throws Exception {
        helper = new FakeHttpServer();
        helper.route("/ping", r -> new FakeHttpServer.Reply(200, "{\"ok\":true,\"app\":\"PocketBridge Windows Control\"}"));
        helper.route("/power", r -> "GET".equals(r.method)
                ? new FakeHttpServer.Reply(200, lastJson)
                : r.body.contains("\"lock\"") ? new FakeHttpServer.Reply(200, "{\"ok\":true,\"message\":\"Laptop locked.\"}")
                : new FakeHttpServer.Reply(202, "{\"ok\":true,\"scheduled\":true,\"message\":\"The laptop will sleep in a moment.\"}"));
        link = HelperLink.parse("http://127.0.0.1:" + helper.port() + "/?t=tok");
        LaptopPowerClient.followUpDelayMs = 10;
        LaptopPowerClient.relayTimeoutMs = 3000;
    }

    @After public void stop() throws Exception { helper.close(); }

    @Test public void lockGoesDirectWhenTheLaptopIsReachable() throws Exception {
        LaptopPowerClient.Outcome outcome = LaptopPowerClient.run(link, "lock", false, new LaptopRelay(System::currentTimeMillis), null);
        assertTrue(outcome.ok);
        assertEquals("direct", outcome.route);
        assertEquals("Laptop locked.", outcome.message);
    }

    @Test public void aDeferredActionShowsTheErrorWindowsReported() throws Exception {
        lastJson = "{\"ok\":true,\"last\":{\"action\":\"sleep\",\"ok\":false,\"pending\":false,\"error\":\"privilege not held\"}}";
        LaptopPowerClient.Outcome outcome = LaptopPowerClient.run(link, "sleep", true, null, null);
        assertFalse(outcome.ok);
        assertTrue(outcome.message, outcome.message.contains("privilege not held"));
    }

    @Test public void aDeferredActionThatWorkedReportsSuccess() throws Exception {
        LaptopPowerClient.Outcome outcome = LaptopPowerClient.run(link, "sleep", true, null, null);
        assertTrue(outcome.message, outcome.ok);
    }

    @Test public void usesTheHelpersConnectionWhenTheLaptopCannotBeReached() throws Exception {
        LaptopRelay relay = new LaptopRelay(System::currentTimeMillis);
        Thread laptop = new Thread(() -> {
            try {
                LaptopRelay.Command first = relay.poll("0.7.0.1", 0); // marks the helper connected
                LaptopRelay.Command command = first != null ? first : relay.poll("0.7.0.1", 3000);
                relay.complete(command.id, true, "The laptop is restarting.");
            } catch (Exception ignored) { }
        });
        laptop.start();
        Thread.sleep(50);
        HelperLink unreachable = HelperLink.parse("http://127.0.0.1:1/?t=tok");
        LaptopPowerClient.Outcome outcome = LaptopPowerClient.run(unreachable, "restart", true, relay, null);
        laptop.join(3000);
        assertTrue(outcome.message, outcome.ok);
        assertEquals("relay", outcome.route);
        assertEquals("The laptop is restarting.", outcome.message);
    }

    @Test public void explainsWhatToDoWhenNoLaptopIsConnected() throws Exception {
        LaptopPowerClient.Outcome outcome = LaptopPowerClient.run(null, "lock", false, new LaptopRelay(System::currentTimeMillis), null);
        assertFalse(outcome.ok);
        assertTrue(outcome.message.contains("No laptop is connected"));
    }
}
