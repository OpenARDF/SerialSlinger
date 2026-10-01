# Android native compatibility

SerialSlinger verifies Android 16 KB page-size compatibility in two layers:

- `just android-native-compat-check` inspects the generated release AAB and requires its bundle
  configuration to request 16 KB native-library packaging alignment. If the bundle contains
  64-bit native libraries, every loadable ELF segment must also be aligned to at least 16 KB.
- The `Android native compatibility` GitHub Actions workflow builds the artifacts and installs the
  debug APK on an Android 15 emulator whose kernel uses 16 KB pages. It requires a successful
  activity launch, a surviving app process, and an empty SerialSlinger crash buffer.

The workflow runs when Android-relevant files change, on manual dispatch, and monthly to detect
hosted image or emulator drift. It is a hardware-free startup check; it does not replace the real
USB-device regression required by the release checklist.

The pinned Google 16 KB emulator image and emulator runner can emit SDK XML-version, userdata
`.ini`, client-configuration, or Netsim shutdown housekeeping messages. Those messages are
narrowly waived only when the image reports a 16384-byte page size and the install, launch,
process-survival, and crash-buffer checks all pass. Recheck this waiver whenever the image or
runner revision changes.
