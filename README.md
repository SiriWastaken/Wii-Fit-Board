# Wii Balance Board Scale

Step on a Nintendo Wii Balance Board and the weight appears on a display. Java 17+, `hid4java` 0.8.0 + JNA, no other dependencies. The result is a calibrated estimate, not a medical-grade measurement.

## How it works

```
WiiBoard (HID, Bluetooth) -> Reports/Calibration -> median filter -> MeasurementTracker -> Display
        ^ ScaleEngine thread: connect, backoff, stale detection, publishing snapshots ^
```

| File | Job |
|---|---|
| `WiiBoard` | Find board, open, set report mode, read factory calibration, read reports. Implements `SensorSource`. |
| `Reports`, `Calibration` | Pure decoding and raw -> kg conversion (unit-tested). |
| `MeasurementTracker` | State machine: zero, step-on, stabilize, result, step-off. Pure, time passed in. |
| `ScaleEngine` | The only worker thread. Reconnect with exponential backoff, stale-data detection, 5-sample median filter, diagnostics. |
| `Display` -> `SwingDisplay`, `ConsoleDisplay` | Output. Swap in another display by implementing one method. |
| `SimulatedSource` | Fake board for `--simulate`. |
| `Config` + `scale.properties` | All thresholds and timings. |

States: `DISCONNECTED -> CONNECTING (zeroing) -> READY -> STABILIZING -> MEASUREMENT_COMPLETE -> (step off) -> READY`, plus `ERROR` (unstable load or over capacity; clears on step-off). The board is only called ready after it returned real sensor data and a quiet, near-zero empty-board baseline.

## Build, test, run

```
./build.sh          # -> dist/scale.jar, dist/lib, dist/scale.properties
./test.sh           # 50 hardware-free checks; exit code 1 on failure
java -jar dist/scale.jar --simulate               # UI, fake board
java -jar dist/scale.jar --simulate --headless    # console only
java -jar dist/scale.jar                          # real board, UI
java -jar dist/scale.jar --headless --diag        # real board, per-second raw/calibrated/total/Hz log
```

Options: `--config FILE` (default `./scale.properties` if present), `--headless` (automatic when there is no display), `--simulate`, `--diag`. Logs go to stderr (journald under systemd); the console display uses stdout.

## Linux: Bluetooth and HID access

```
sudo apt update && sudo apt install -y default-jre-headless bluez
sudo systemctl enable --now bluetooth && rfkill unblock bluetooth
sudo cp deploy/99-wiiboard.rules /etc/udev/rules.d/
sudo udevadm control --reload-rules
bluetoothctl            # then: power on, agent on, scan on
                        # press the RED sync button in the battery bay (not the power button)
                        # pair <MAC>, trust <MAC>, connect <MAC>   (if pair asks for a PIN, `connect` alone often works)
ls -l /dev/hidraw*      # the board's node must be crw-rw-rw-
```

If the node is still root-only, comment the first rule in `deploy/99-wiiboard.rules` and uncomment the broad one (`SUBSYSTEM=="hidraw", MODE="0666"`; this is the one confirmed on the Ubuntu test machine), reload, and reconnect the board. Pairing alone does not grant application access; the permission step is separate.

## Raspberry Pi deployment

Decide the Pi model first. **Recommended: Pi Zero 2 W** (64-bit ARM, Bluetooth built in). The original Pi Zero / Zero W is ARMv6; this project has not been run there and a JVM will be slow to start. `hid4java` 0.8.0 ships native libraries for `linux-aarch64`, `linux-arm` and `linux-armel`, but none were exercised on a Pi.

On the development machine: `./build.sh`, then `rsync -a dist/ <user>@<pi>:/tmp/wii-scale/`.

On the Pi (Raspberry Pi OS Lite or Desktop, Bookworm assumed):

```
sudo apt update && sudo apt install -y openjdk-17-jre-headless bluez     # java -version must print 17+
sudo useradd -r -m scale
sudo mkdir -p /opt/wii-scale && sudo cp -r /tmp/wii-scale/* /opt/wii-scale/ && sudo chown -R scale /opt/wii-scale
sudo cp deploy/99-wiiboard.rules /etc/udev/rules.d/ && sudo udevadm control --reload-rules
# pair the board with bluetoothctl as above, then:
sudo cp deploy/wii-scale.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now wii-scale
journalctl -u wii-scale -f          # logs
sudo systemctl stop wii-scale       # clean shutdown: releases HID, ends the worker thread
```

Update: rebuild, copy `dist/*` over `/opt/wii-scale`, `sudo systemctl restart wii-scale`. Keep your edited `scale.properties` (don't overwrite it).

**Display.** The service above is headless (console output / logs). The Swing UI needs a graphical session: on Raspberry Pi OS Desktop, copy `deploy/wii-scale.desktop` to `~/.config/autostart/` (set `fullscreen=true` in `scale.properties` for a small screen; the number scales with window height) and do not run the service at the same time (only one program can own the board). The `Display` interface is where an SPI/framebuffer display would plug in; none is chosen yet.

After a reboot, check: `bluetoothctl devices Paired` lists the board, `systemctl status wii-scale` is active. Whether the board reconnects by itself when you press its power button depends on BlueZ trusting it (`trust <MAC>`); this is unverified here. The app keeps retrying (1 s doubling to 30 s) until the board appears.

Troubleshooting: "Board not found" = not connected in `bluetoothctl`; "Could not open" = hidraw permissions; "No sensor data" repeatedly = board went to sleep (it powers off after ~20 min idle; press its power button); stuck on "Step off the board to zero it" = something over `maxBaselineKg` is on it.

## Tuning (`scale.properties`)

Defaults are untuned starting points. Tune with `--diag` on the real board.

- `enterKg` / `exitKg`: step-on and step-off thresholds above the empty baseline. Keep `exitKg` clearly lower than `enterKg` (hysteresis). Raise `enterKg` if a light object triggers it.
- `stableWindowMs` + `stableStdKg`: a result needs the last window to have std dev below the limit. Check empty-board std in `--diag`; set `zeroStdKg` ~3x that and `stableStdKg` a bit above the std of a person standing still. Longer window or lower std = steadier result, slower answer.
- `minStabilizeMs`: minimum time after step-on before a result. `stabilizeTimeoutMs`: gives up (ERROR) if the person never settles.
- `medianSamples`: spike rejection; larger adds lag. `baselineAlpha`: how fast the empty zero follows drift (0 disables).
- `staleMs`: how long without sensor reports before reconnecting.

## Hardware validation (not yet done)

1. Empty board, `--diag`, 10 min: note drift of `total` and its std dev; note Hz (expected ~100 with continuous mode, unverified).
2. Reference mass (e.g. 20 kg of known weights, then a person weighed on a trusted scale): 10 trials each, step on/off between trials. Record result, error vs. reference, spread. If error is a consistent percentage, add a scale factor; if constant, it is an offset.
3. Corner/edge placement: same mass at the centre and each corner; compare.
4. Disconnect test: switch the board off mid-measurement; expect DISCONNECTED, no result, recovery after reconnecting.
5. Record all numbers before changing defaults.

## Known limitations / unverified

- Nothing here has been run against a real board since the rewrite; the original WiiBoard code was working on Ubuntu.
- Changed vs. the working code: report mode now sets the *continuous* flag (`0x04`, per WiiBrew) so reports keep arriving when the load is steady; if the board rejects it, set the byte back to `0x00` in `WiiBoard.MODE_SENSORS_CONTINUOUS`.
- The Balance Board has the same VID/PID as a Wii Remote; it is told apart by its product name `RVL-WBC`.
- No temperature compensation. The zero is taken at startup and follows slow drift; a long-term accuracy claim needs the validation above.
- Over capacity is anything above `maxPlausibleKg` (200 kg; the board is rated about 150 kg).
- `out/` in the repo holds stale class files from an older package layout; it is not used.
