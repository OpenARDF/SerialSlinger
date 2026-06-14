gradle := if os_family() == "windows" { "gradlew.bat" } else { "./gradlew" }

# Show available recipes.
default:
    @just --list

# Run Gradle tasks through the repo wrapper.
gradle +tasks:
    {{gradle}} {{tasks}}

# Compile the desktop target.
compile:
    {{gradle}} shared:compileKotlinDesktop

# Run desktop tests.
test:
    {{gradle}} shared:desktopTest

# Run the normal local validation gate.
check: compile test

# Launch the desktop UI from this checkout.
desktop-run:
    ./run-desktop-ui.sh

# Run the desktop smoke CLI. Example: just desktop-smoke "list"
desktop-smoke args="list":
    {{gradle}} desktopSmokeRun --args="{{args}}"

# Build the Android debug APK.
android-debug:
    {{gradle}} :androidApp:assembleDebug

# Install the Android debug APK. Optionally pass an adb serial.
android-install serial="":
    ./scripts/android-install-debug.sh {{serial}}

# Run Android debug automation. Example: just android-command "--serial ABC123 get-state"
android-command +args:
    ./scripts/android-debug-command.sh {{args}}

# Run the Android regression helper. Example: just android-regression "--serial ABC123"
android-regression *args:
    ./scripts/android-regression.sh {{args}}

# Prepare the desktop jDeploy bundle.
jdeploy-prepare:
    npm run jdeploy:prepare

# Build the local jDeploy package payload.
jdeploy-package:
    npm run jdeploy:package

# Install the local jDeploy package.
jdeploy-install-local:
    npm run jdeploy:install-local

# Verify the local jDeploy installation.
jdeploy-verify-install:
    npm run jdeploy:verify-install

# Run the app through the local jDeploy flow.
jdeploy-local:
    npm run jdeploy:local

# Preview the npm and jDeploy package payload.
jdeploy-pack-preview:
    npm run jdeploy:pack-preview

# Run the jDeploy release preflight gate.
jdeploy-preflight:
    npm run jdeploy:release-preflight

# Check a release checklist phase.
release-checklist file phase="pre-tag":
    npm run release:checklist -- --file "{{file}}" --phase "{{phase}}"

# Build the macOS app image.
macos-app-image:
    {{gradle}} verifyDesktopPackagingEnvironment desktopAppImage

# Build the macOS DMG.
macos-dmg:
    {{gradle}} verifyDesktopPackagingEnvironment desktopDmg
