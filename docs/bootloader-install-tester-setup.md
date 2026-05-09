# Bootloader Install Tester Setup

This note describes what testers should expect before using **Install Bootloader on SignalSlinger**.

## What SerialSlinger Does

SerialSlinger does not silently install system tools. Bootloader installation uses programmer software, USB access, and fuse writes, so the app checks the computer and stops with guidance when something is missing.

Before writing anything, the desktop app:

1. downloads or opens a complete SignalSlinger setup package
2. verifies package files, sizes, and SHA-256 hashes
3. finds PowerShell
4. runs the package prerequisite check without touching the device
5. probes the connected programmer without writing flash or fuses
6. asks the user to confirm fuse writes with a `Continue` / `Cancel` prompt

## Required Hardware

- SignalSlinger connected to normal USB serial
- Atmel-ICE or compatible CMSIS-DAP programmer connected to the computer
- Programmer connected to the SignalSlinger UPDI header
- SignalSlinger powered during programming

The setup script's programmer check uses a no-write probe. With pymcuprog it runs a programmer ping, then reads fuses. That confirms the programmer, USB access, UPDI connection, target power, and AVR device response before SerialSlinger asks for fuse-write confirmation.

## Required Tools

macOS:

- PowerShell 7: `brew install --cask powershell`
- Python 3
- pymcuprog and USB support:

```sh
python -m pip install pymcuprog pyusb
```

If pymcuprog was installed with pipx:

```sh
pipx install pymcuprog
pipx inject pymcuprog pyusb
```

Windows:

- PowerShell 7 with pymcuprog, or Windows PowerShell with Microchip Studio
- For Microchip Studio path: install Microchip Studio 7 so `atprogram.exe` is available
- For pymcuprog path: install Python 3, pymcuprog, and pyusb

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
- programmer cannot be opened: tell the user to unplug/replug the programmer and check USB access.
- stale setup package: tell the user to choose `Download Latest`.

Full script output still belongs in the session log for diagnostics.

