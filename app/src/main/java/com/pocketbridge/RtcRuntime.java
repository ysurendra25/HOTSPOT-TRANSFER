package com.pocketbridge;

import android.content.Context;

import org.webrtc.PeerConnectionFactory;

/** Native WebRTC initialization is process-wide, including concurrent camera and screen sessions. */
final class RtcRuntime {
    private static boolean initialized;

    private RtcRuntime() { }

    static synchronized void initialize(Context context) {
        if (initialized) return;
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
                .builder(context.getApplicationContext()).createInitializationOptions());
        initialized = true;
    }
}
