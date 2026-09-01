# HeartMonitor

Android app that connects to a Bluetooth LE heart-rate sensor (built for the
**iGPSPORT HR50** chest strap), shows the live heart rate and records sessions
to CSV.

## Features

- BLE scan for the Heart Rate service (`0x180D`), connect, subscribe to the
  Heart Rate Measurement characteristic (`0x2A37`)
- Large live BPM read-out with sensor-contact indicator
- Recording to CSV with timestamps — saved to app storage and, on Android 10+,
  to `Downloads/HeartMonitor/` via MediaStore
- Foreground service keeps a recording running while the app is backgrounded /
  the screen is off
- In-app list of past recordings with share and delete
- No location permission required on Android 12+ (`neverForLocation`)

## Tech

Kotlin · Jetpack Compose · MVVM · Coroutines/Flow · min SDK 26 / target 34

## Module layout

| Package | Responsibility |
|---|---|
| `ble/` | `HeartRateBleManager` — scan, GATT, payload decoding, exposes `Flow`s |
| `data/` | `CsvStorageManager` — CSV serialisation, list/delete |
| `recording/` | `HeartRateRecorder` (app-scoped buffer) + `RecordingService` (foreground) |
| `ui/` | `HeartRateViewModel`, `HeartRateScreen`, `RecordingsScreen` |

## Build

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew :app:assembleDebug
```

Create `local.properties` with `sdk.dir=/path/to/android-sdk` (or open in
Android Studio).

## CSV format

```
timestamp_iso,timestamp_epoch_ms,elapsed_s,bpm,sensor_contact,rr_ms
```

`rr_ms` holds space-separated RR intervals in milliseconds when the sensor
reports them.
