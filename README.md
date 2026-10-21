# Wii Balance Board Scale

Two classes: `WiiBoard` (HID connection, protocol, calibration, weighing cycle) and `Main` (worker thread + window, or console when there is no display). Tests are in `test/Tests.java` (no JUnit needed).

```
./build.sh && ./test.sh && java -cp "out:lib/*" Main      # setup commands: commands.txt
```

## Three separate operations in `WiiBoard`

- **Calibration** (`readCalibration`): the board's factory raw values at 0/17/34 kg per sensor, read and cross-checked once per Bluetooth connection, reused for every weighing.
- **Tare** (`tare`): empty-board baseline in kg, taken when connected and the board is quiet and near zero; afterwards it only follows slow drift while empty. A board with a person on it is never zeroed ("Step off the board to zero it").
- **Measurement reset** (`resetMeasurement`): runs on step-off; clears the last result only.

Cycle: `ZEROING -> READY -> MEASURING -> DONE -> (step off) -> READY`. `FAILED` (never settled, or over capacity) also clears on step-off.

## Connection behavior

One thread calls `poll()`; it alone touches the HID device. Any report (even a non-sensor one) proves the link is alive; the board only sends sensor reports when the load changes, so quiet gaps are normal. The link is declared lost only after `LOST_MS` (10 s) with no report at all; then the device is closed and reopened with backoff 1, 2, 4 ... 30 s. A new connection re-reads calibration and re-zeros; a working connection never does.

The board powers itself off after about 20 minutes idle; press its power button (it must be paired and trusted in `bluetoothctl`). Software cannot wake it.

## Tuning

Constants at the top of `WiiBoard` (`ENTER_KG`, `EXIT_KG`, `STABLE_MS`, `STABLE_STD_KG`, ...). They are untuned starting values. Run with `-Dscale.debug=true` to see report rate, raw values and totals once per second.

## Hardware validation still needed

1. Empty board for 10 minutes with `-Dscale.debug=true`: drift of `total`, report rate, no `Connection lost` lines.
2. Known reference mass, 10 step-on/step-off trials without restarting: record error and spread. If error is a constant percentage, add a scale factor; if constant kg, it is an offset.
3. Same mass at the centre and each corner.
4. Switch the board off mid-weighing: expect "Connection lost", no result, recovery after it is switched back on.
