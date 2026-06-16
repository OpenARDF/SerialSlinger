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

- Desktop install and run verified on macOS, Windows, and Linux
- Android is supported for tablet/mobile workflows

## Project Status

The project currently includes:

- a UI for loading, editing, and submitting settings
- automatic serial-port discovery and SignalSlinger probing
- shared protocol, settings, and session logic in a Kotlin Multiplatform core

## More Information

- [SignalSlinger Partner Project](https://github.com/OpenARDF/SignalSlinger)
