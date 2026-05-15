# Bootloader Install Tester Setup

This note describes what testers should expect before using **Install Bootloader on SignalSlinger**.

## What SerialSlinger Does

SerialSlinger does not silently install system tools. Bootloader installation uses programmer software, USB access, and fuse writes, so the app checks the computer and stops with guidance when something is missing.

For assembled devices with a serial connector, the desktop app:

1. downloads or opens a complete SignalSlinger setup package
2. verifies package files, sizes, and SHA-256 hashes
3. finds PowerShell
4. runs the package prerequisite check without touching the device
5. probes the connected programmer without writing flash or fuses
6. asks the user to confirm fuse writes with a `Continue` / `Cancel` prompt
7. programs and verifies through the programmer
8. verifies the installed SignalSlinger app over serial

For bare PCBs that do not yet have the serial connector installed, use **Install Bootloader on Bare PCB**. That flow keeps the same package checks, prerequisite checks, programmer probe, fuse confirmation, and programmer verification, but it skips serial-port selection and post-programming serial verification. The final message should say that serial communication was not checked.

## Required Hardware

- SignalSlinger connected to normal USB serial
- Atmel-ICE or compatible CMSIS-DAP programmer connected to the computer
- Programmer connected to the SignalSlinger UPDI header
- SignalSlinger powered during programming

The normal **Install Bootloader on SignalSlinger** flow requires the USB serial connection. The **Install Bootloader on Bare PCB** flow does not require a serial connector, but it cannot confirm `INF` afterward.

The setup script's programmer check uses a no-write probe. With pymcuprog it runs a programmer ping, then reads fuses. That confirms the programmer, USB access, UPDI connection, target power, and AVR device response before SerialSlinger asks for fuse-write confirmation.

Current complete release packages also report supported programmers in the release metadata and setup-script output. SerialSlinger expects stable setup lines such as `SS_SETUP_OK step=check_programmer tool=atmelice` and `SS_SETUP_ERROR code=no_programmer` so the UI can show friendly cross-platform messages without depending on fragile script wording.

## Required Tools

macOS:

- PowerShell 7: `brew install powershell`
- Python 3
- pymcuprog and USB support:

```sh
python -m pip install pymcuprog pyusb
```

Windows:

- PowerShell 7 with pymcuprog, or Windows PowerShell with Microchip Studio
- For Microchip Studio path: install Microchip Studio 7 so `atprogram.exe` is available
- For pymcuprog path: install Python 3, then run `python -m pip install pymcuprog pyusb`
- If SerialSlinger is already open when you install or expose new command-line tools, close and reopen the app before trying **Install Bootloader** again so it picks up the updated tool paths
- If `pymcuprog` is still not found, add your Python Scripts folder to `PATH`
- Common Windows script-folder locations:
  `%APPDATA%\Python\Python3xx\Scripts`
  `%LOCALAPPDATA%\Python\pythoncore-3.xx*\Scripts`
  `%LOCALAPPDATA%\Programs\Python\Python3xx\Scripts`

Linux:

- PowerShell 7 from Microsoft packages for the distribution
- Python 3
- pymcuprog and pyusb
- USB permissions that allow the user account to open the programmer

Linux should use the same Java desktop app flow as macOS, but it needs validation on real hardware before it should be described as fully verified.

## Friendly Failure Expectations

The app should avoid cryptic messages such as "exit code 1" by itself. It should translate common setup failures:

- PowerShell missing: tell the user to install PowerShell 7.
- pymcuprog missing: tell the user how to install pymcuprog and pyusb.
- pyusb missing: tell the user to inject or install pyusb.
- programmer missing: tell the user to connect an Atmel-ICE/CMSIS-DAP programmer.
- no supported programmer or target detected: tell the user to connect a supported programmer, connect UPDI, and power the board.
- programmer cannot be opened: tell the user to unplug/replug the programmer and check USB access.
- stale setup package: tell the user to choose `Download Latest`.

Full script output still belongs in the session log for diagnostics.
