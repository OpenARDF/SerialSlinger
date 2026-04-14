# SerialSlinger

SerialSlinger is a desktop app for configuring SignalSlinger devices through a graphical interface instead of raw serial commands.

Download the latest build: [Install SerialSlinger](https://www.jdeploy.com/gh/OpenARDF/SerialSlinger)

## Download

- [Install SerialSlinger](https://www.jdeploy.com/gh/OpenARDF/SerialSlinger)
- [GitHub releases](https://github.com/OpenARDF/SerialSlinger/releases)

## What It Does

When a SignalSlinger is connected, SerialSlinger is intended to:

1. connect to the device
2. read the current settings
3. show those settings in a table
4. let you edit them in the app
5. write the requested changes back to the device

The goal is to make SignalSlinger setup practical without memorizing serial commands.

## Platform Status

- macOS is the currently verified desktop path
- Windows and Linux remain target platforms, but should still be treated as early-stage
- Android is a planned target
- iOS is currently out of scope for direct USB or serial support

## Project Status

The project currently includes:

- a desktop UI for loading, editing, and submitting settings
- automatic desktop-side serial-port discovery and SignalSlinger probing
- shared protocol, settings, and session logic in a Kotlin Multiplatform core

## More Information

- [Developer Guide](/Users/charlesscharlau/Documents/GitHub/SerialSlinger/docs/developer-guide.md)
- [Packaging And Release](/Users/charlesscharlau/Documents/GitHub/SerialSlinger/docs/packaging-and-release.md)
- [Design Goals](/Users/charlesscharlau/Documents/GitHub/SerialSlinger/docs/design-goals.md)
- [High-Level Design](/Users/charlesscharlau/Documents/GitHub/SerialSlinger/docs/high-level-design.md)
- [Stack Decision](/Users/charlesscharlau/Documents/GitHub/SerialSlinger/docs/stack-decision.md)
- [Domain Model](/Users/charlesscharlau/Documents/GitHub/SerialSlinger/docs/domain-model.md)
