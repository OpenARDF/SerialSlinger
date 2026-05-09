# SignalSlinger Bootloader Update Regression

This note records the real-device regression pass for SignalSlinger firmware update support after the move to the 8 KiB bootloader layout.

## Tested Configuration

- SignalSlinger release assets: `v2.0.0`, `v2.0.1`, and `v2.0.2`
- Active bootloader layout: app start `0x2000`, bootloader `BL0.12`, protocol `proto=1`
- Flash page size: 512 bytes
- App baud: 9600
- Update baud: 115200
- Tested SerialSlinger branch: `Development_Android`

## Android HW 3.4

Test device: Android tablet connected to a physical HW 3.4 SignalSlinger.

Passed checks:

- Normal UI update path from `2.0.2` to `2.0.1`.
- Normal UI latest-version update path from `2.0.1` to `2.0.2`.
- Resident fallback path: forced GitHub download failure, accepted the resident `2.0.2` HW 3.4 package, and completed the update.
- Already-current latest-version path: `2.0.2` reported as already installed without setting an error state.
- Physical interruption recovery: unplugged during `Sending update`, replugged, SerialSlinger reacquired the USB device, restarted the update from page 0, completed the update, and reloaded the app.

Final confirmed state:

```text
sw=2.0.2 hw=3.4
status=SignalSlinger Data Read Successfully
statusIsError=false
```

## Desktop macOS HW 3.5

Test device: macOS desktop connected to a physical HW 3.5 SignalSlinger at `/dev/cu.usbserial-ABSCDL93`.

Passed checks:

- Normal update loops across `v2.0.0`, `v2.0.1`, and `v2.0.2` release packages.
- Wrong-hardware guard: HW 3.5 device refused the HW 3.4 package before writing.
- Physical interruption recovery: unplugged during `Sending update`, replugged, SerialSlinger reacquired the serial device, restarted the update from page 0, and completed the update.
- Clean restore from `2.0.1` to `2.0.2`.

Final confirmed identity:

```text
* INF product=SignalSlinger update=UPD
* INF sw=2.0.2 hw=3.5 app=0x2000 baud=115200
```

## Result

Bootloader update support is functionally good for the tested Android and desktop paths. Both physical devices ended on `2.0.2`, and both confirmed the `0x2000` app layout after update.
