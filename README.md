# HeartMonitor

Android app that connects to a Bluetooth LE heart-rate sensor (built for the
**iGPSPORT HR50** chest strap), shows the live heart rate and records sessions
to CSV.

## Features

- BLE scan for the Heart Rate service (`0x180D`), connect, subscribe to the
  Heart Rate Measurement characteristic (`0x2A37`)
- Large live BPM read-out with sensor-contact indicator
- Live min / max / average BPM for the running (and last completed) session
- Automatic reconnect with back-off after an unexpected drop — a running
  recording keeps buffering across the gap instead of stopping
- Recording to CSV with timestamps — saved to app storage and, on Android 10+,
  to `Downloads/HeartMonitor/` via MediaStore
- Foreground service keeps a recording running while the app is backgrounded /
  the screen is off
- In-app list of past recordings with share and delete (delete also removes the
  public `Downloads/HeartMonitor/` copy)
- No location permission required on Android 12+ (`neverForLocation`)

## Tech

Kotlin · Jetpack Compose · MVVM · Coroutines/Flow · min SDK 26 / target 34

## Module layout

| Package | Responsibility |
|---|---|
| `ble/` | `HeartRateBleManager` — scan, GATT, auto-reconnect, exposes `Flow`s; `HeartRateMeasurementParser` — pure 0x2A37 decoder |
| `data/` | `CsvStorageManager` — file + MediaStore I/O, list/delete; `HeartRateCsv` — pure CSV formatting |
| `recording/` | `HeartRateRecorder` (app-scoped buffer) + `RecordingService` (foreground); `HeartRateStats` — pure min/max/avg |
| `ui/` | `HeartRateViewModel`, `HeartRateScreen`, `RecordingsScreen` |

## Build

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew :app:assembleDebug        # debug APK
./gradlew :app:testDebugUnitTest    # unit tests (parser, CSV, stats)
./gradlew :app:assembleRelease      # R8 + resource shrinking; unsigned unless keystore.properties exists
```

Create `local.properties` with `sdk.dir=/path/to/android-sdk` (or open in
Android Studio).

### Release signing (optional)

Create `keystore.properties` in the project root (git-ignored):

```properties
storeFile=/absolute/path/to/heartmonitor.jks
storePassword=…
keyAlias=…
keyPassword=…
```

`assembleRelease` then produces a signed APK; without the file it produces
`app-release-unsigned.apk`.

## CSV format

```
timestamp_iso,timestamp_epoch_ms,elapsed_s,bpm,sensor_contact,rr_ms
```

`rr_ms` holds space-separated RR intervals in milliseconds when the sensor
reports them.
