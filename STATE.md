# PocketBridge 0.7 — working state

Branch: `pocketbridge-0.7` (from `codex/pocketbridge-next-fix` @ c1542fb, v0.6.0.12).
Never touch `main`, tags or releases from this branch.

## Version numbers
- Branch base: versionName 0.6.0.12, versionCode 36
- Google Drive build (drive-0700.apk, aapt2 dump badging): versionName 0.7.0.0, versionCode 45
- This branch: versionName **0.7.0.1**, versionCode **46**

## Phase 1 (done, commits dcf32e3, 1b1abd8 and the follow-up)
- Screen latency fixes (ScreenEncoderPlan, HardwareEncoders), phone power relay
  (LaptopRelay, LaptopPowerClient, Go phone_relay.go), airplane-mode reset
  (AirplaneController + accessibility service + quick tile).
- phone_relay.go backs off 60 s on HTTP 429 (phone code lockout) instead of retrying.
- Unit tests: ScreenEncoderPlanTest, LaptopRelayTest, LaptopPowerClientTest, Go phase07_test.go.

Phase 2 status: see below (updated at the end of Phase 2).
