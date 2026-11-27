package com.pocketbridge;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LaptopRelayTest {
    @Test public void deliversACommandAndWaitsForTheHelpersAnswer() throws Exception {
        LaptopRelay relay = new LaptopRelay(System::currentTimeMillis);
        assertFalse(relay.helperConnected());
        AtomicReference<LaptopRelay.Command> picked = new AtomicReference<>();
        Thread helper = new Thread(() -> {
            try {
                LaptopRelay.Command command = relay.poll("0.7.0.1", 5000);
                picked.set(command);
                relay.complete(command.id, true, "Laptop locked.");
            } catch (InterruptedException ignored) { }
        });
        helper.start();
        Thread.sleep(100);
        assertTrue(relay.helperConnected());
        LaptopRelay.Command command = relay.enqueue("lock", false, null);
        assertTrue(relay.await(command, 5000));
        helper.join();
        assertEquals("lock", picked.get().action);
        assertTrue(command.ok);
        assertEquals("Laptop locked.", command.message);
        assertEquals("0.7.0.1", relay.helperVersion());
    }

    @Test public void anIdlePollReturnsNothing() throws Exception {
        LaptopRelay relay = new LaptopRelay(System::currentTimeMillis);
        long started = System.currentTimeMillis();
        assertNull(relay.poll("", 150));
        assertTrue(System.currentTimeMillis() - started >= 140);
        assertTrue(relay.helperConnected());
    }

    @Test public void anUnansweredCommandIsWithdrawn() throws Exception {
        LaptopRelay relay = new LaptopRelay(System::currentTimeMillis);
        LaptopRelay.Command command = relay.enqueue("sleep", true, null);
        assertFalse(relay.await(command, 100));
        // A helper that polls afterwards must not run the stale command.
        assertNull(relay.poll("", 50));
    }

    @Test public void helperCountsAsGoneAfterTheWindow() throws Exception {
        long[] now = {1_000_000};
        LaptopRelay relay = new LaptopRelay(() -> now[0]);
        relay.poll("", 0);
        assertTrue(relay.helperConnected());
        now[0] += LaptopRelay.CONNECTED_WINDOW_MS + 1;
        assertFalse(relay.helperConnected());
    }
}
