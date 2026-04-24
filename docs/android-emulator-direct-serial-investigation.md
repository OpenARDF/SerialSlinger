# Android Emulator Direct-Serial Investigation

This work is intentionally tabled for now so the findings do not get lost.

## Goal

Allow the Android app to talk to a real SignalSlinger through an Android emulator by exposing a guest serial device such as `/dev/ttyS0` or `/dev/ttyAMA0`.

## App-side work completed

- Added `AndroidConnectionTarget` with `Usb(deviceName)` and `DirectSerial(path)`.
- Added `AndroidDirectSerialTransport` for direct guest tty paths.
- Refactored Android transport resolution so the session controller can target either USB or direct serial.
- Added a manual emulator load path in Android Tools.
- Tightened emulator probing so `20 TX / 0 RX` no longer counts as success.

Relevant files:

- `androidApp/src/main/kotlin/com/SerialSlinger/openardf/AndroidConnectionTarget.kt`
- `androidApp/src/main/kotlin/com/SerialSlinger/openardf/AndroidSessionController.kt`
- `androidApp/src/main/kotlin/com/SerialSlinger/openardf/AndroidDebugCommandReceiver.kt`
- `androidApp/src/main/kotlin/com/SerialSlinger/openardf/MainActivity.kt`
- `androidApp/src/main/AndroidManifest.xml`
- `shared/src/androidMain/kotlin/com/openardf/serialslinger/transport/AndroidDirectSerialTransport.kt`

## Emulator images tested

- Existing Play-backed `Small_Phone` AVD
- Rootable `Small_Phone_Rootable` AVD built from:
  - `system-images;android-37.0;google_apis_ps16k;arm64-v8a`

## Key findings

### Play-backed image

- `/dev/ttyS0` existed.
- The app was blocked by SELinux as `untrusted_app_34` against `u:object_r:serial_device:s0`.
- This was enough to prove the direct transport path was structurally correct, but not usable on that AVD.

### Rootable image

- `adb root` worked.
- `setenforce 0` removed the SELinux blocker for testing.
- `/dev/ttyS0` still failed with `EIO`.
- `/dev/ttyAMA0` and `/dev/hvc0` were present, but no SignalSlinger replies came back through them.

### Triple-serial experiment

The emulator was relaunched with placeholder serial ports to try to expose a third guest UART:

```bash
~/Library/Android/sdk/emulator/emulator \
  -avd Small_Phone_Rootable \
  -port 5560 \
  -no-snapshot-load \
  -qemu \
  -serial null \
  -serial null \
  -serial unix:/tmp/serialslinger-qemu.sock,server,nowait
```

Result on this ARM `google_apis_ps16k` image:

- Android still only exposed `ttyS0` and `ttyAMA0`.
- No `ttyS2` or `ttyAMA2` appeared.
- `/proc/cmdline` still showed `8250.nr_uarts=1`.

Conclusion: on this image, skipping the first two QEMU serial ports did not make a third Android guest UART available.

## Host bridge findings

A logging bridge connected the emulator socket to the real host serial device `/dev/tty.usbserial-0001`.

Observed behavior:

- The bridge successfully connected to the emulator socket.
- Real bytes arrived from the SignalSlinger device toward the emulator socket.
- Guest writes from tested nodes such as `/dev/ttyAMA0` and `/dev/hvc0` never produced matching outbound bridge traffic.
- Blocking guest reads on tested nodes stayed empty even while the bridge saw inbound device bytes.

Conclusion: the host-side bridge was alive, but the attached emulator serial channel was not mapped to any usable Android guest tty node that was found during testing.

## Current app-level result

The Android emulator load path now reports honest failures instead of false success:

```text
No emulator serial path responded successfully.
Emulator serial /dev/ttyS0: write failed: EIO (I/O error)
Emulator serial /dev/ttyAMA0: no response lines were received.
Emulator serial /dev/hvc0: no response lines were received.
```

## Practical conclusion

The remaining blocker is below the Android app layer.

- The app-side direct-serial transport and target plumbing are in place.
- The tested emulator launch paths did not expose a guest tty that Android user space could actually use for SignalSlinger traffic.
- Further progress likely requires a different emulator image or architecture, or a different emulator/device-forwarding approach.

## Suggested next step if this work resumes

Start by testing a different emulator image or architecture that is known to surface an extra guest UART in Android user space before changing more app code.
