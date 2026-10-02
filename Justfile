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

# Run Android host/unit tests and build the debug application.
android-check:
    {{gradle}} :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest

# Install and run the complete Android instrumentation suite on every connected device.
android-instrumentation:
    {{gradle}} :androidApp:connectedDebugAndroidTest
    node scripts/check-android-instrumentation-results.mjs

# Treat new Android lint findings as failures and verify the release bundle.
android-release-check:
    {{gradle}} :androidApp:lintRelease :androidApp:bundleRelease
    just android-native-compat-check

# Verify the AAB's 16 KB packaging request and every packaged 64-bit ELF load alignment.
android-native-compat-check bundle="androidApp/build/outputs/bundle/release/androidApp-release.aab":
    node --test scripts/check-android-native-compatibility.test.mjs
    node scripts/check-android-native-compatibility.mjs {{quote(bundle)}}

# Build a signed release AAB and verify it against the configured Play upload certificate.
android-signing-check:
    ./scripts/build-signed-bundle.sh

# Run repository-owned Node workflow and packaging tests.
scripts-test:
    node --test \
        scripts/check-android-native-compatibility.test.mjs \
        scripts/check-android-instrumentation-results.test.mjs \
        scripts/check-release-checklist.test.mjs \
        scripts/check-release-tag.test.mjs \
        scripts/jdeploy-local-smoke.test.mjs \
        scripts/prepare-jdeploy-github-release.test.mjs \
        scripts/publish-jdeploy-github-release.test.mjs

# Validate workflows, portable shell, shell formatting, and Git whitespace.
static-check:
    ./scripts/static-check.sh

# Reject known vulnerabilities anywhere in the npm dependency tree.
dependency-audit:
    npm audit --audit-level=low

# Run the normal local validation gate across desktop, Android, release scripts, and static analysis.
check: compile test android-check android-release-check scripts-test static-check

# Increment the local test-build suffix and align package metadata.
local-version-bump:
    node ./scripts/bump-local-version-suffix.mjs

# Launch the desktop UI from this checkout.
desktop-run: local-version-bump
    ./run-desktop-ui.sh

# Run the desktop smoke CLI. Example: just desktop-smoke "list"
desktop-smoke args="list":
    {{gradle}} desktopSmokeRun --args="{{args}}"

# Build the Android debug APK.
android-debug: local-version-bump
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
jdeploy-prepare: local-version-bump
    npm run jdeploy:prepare

# Build the local jDeploy package payload.
jdeploy-package: local-version-bump
    npm run jdeploy:package

# Install the local jDeploy package.
jdeploy-install-local: local-version-bump
    npm run jdeploy:install-local

# Verify the local jDeploy installation.
jdeploy-verify-install:
    npm run jdeploy:verify-install

# Run the app through the local jDeploy flow.
jdeploy-local: local-version-bump
    npm run jdeploy:local

# Install and probe the genuine local jDeploy application without serial hardware.
jdeploy-local-smoke:
    npm run jdeploy:local-smoke

# Install and probe the genuine local jDeploy application on macOS.
macos-installed-smoke:
    @test "$(uname -s)" = "Darwin" || { echo "macos-installed-smoke requires macOS." >&2; exit 1; }
    npm run jdeploy:local-smoke

# Preview the npm and jDeploy package payload.
jdeploy-pack-preview: local-version-bump
    npm run jdeploy:pack-preview

# Run the jDeploy release preflight gate.
jdeploy-preflight:
    npm run jdeploy:release-preflight

# Run every automatable release gate; hardware checks remain controlled by the checklist.
release-check: check dependency-audit jdeploy-preflight

# Scan tracked history and the current worktree before a sensitive push or release.
secret-check:
    gitleaks detect --source . --no-banner

# Check a release checklist phase.
release-checklist file phase="pre-tag":
    npm run release:checklist -- --file "{{file}}" --phase "{{phase}}"

# Mark one release-checklist item done with concrete evidence.
release-checklist-done file item evidence:
    npm run release:checklist:update -- --file "{{file}}" --id "{{item}}" --done-evidence "{{evidence}}"

# Mark one release-checklist item skipped with the user's explicit waiver.
release-checklist-skip file item requested_by reason:
    npm run release:checklist:update -- --file "{{file}}" --id "{{item}}" --skip-requested-by "{{requested_by}}" --skip-reason "{{reason}}"

# Validate the checked release-notes file named by a release checklist.
release-notes-check checklist:
    npm run release:notes -- --checklist "{{checklist}}"

# Build the macOS app image.
macos-app-image: local-version-bump
    {{gradle}} verifyDesktopPackagingEnvironment desktopAppImage

# Build the macOS DMG.
macos-dmg: local-version-bump
    {{gradle}} verifyDesktopPackagingEnvironment desktopDmg
