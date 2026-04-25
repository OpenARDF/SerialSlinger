# SerialSlinger

SerialSlinger is a desktop app for configuring SignalSlinger devices through a graphical interface instead of raw serial commands.

Download the latest build: [Install SerialSlinger](https://www.jdeploy.com/gh/OpenARDF/SerialSlinger)

License: MIT. See [LICENSE](/Users/charlesscharlau/Documents/GitHub/SerialSlinger/LICENSE).

## What It Does

When a SignalSlinger is connected, SerialSlinger is intended to:

1. connect to the device
2. read the current settings
3. show those settings in a table
4. let you edit them in the app
5. write the requested changes back to the device

The goal is to make SignalSlinger setup practical without memorizing serial commands.

## Platform Status

- Verified on Windows, macOS, and Android
- Linux is a planned target, but has not yet been verified

## Project Status

The project currently includes:

- a desktop UI for loading, editing, and submitting settings
- automatic desktop-side serial-port discovery and SignalSlinger probing
- shared protocol, settings, and session logic in a Kotlin Multiplatform core
- an initial Android app scaffold with USB-device discovery and shared-core integration

## More Information

- [SignalSlinger Partner Project](https://github.com/OpenARDF/SignalSlinger)
