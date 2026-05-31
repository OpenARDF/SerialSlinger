#!/bin/zsh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

if [[ -x /usr/libexec/java_home ]]; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
else
    echo "ERROR: /usr/libexec/java_home is unavailable. Set JAVA_HOME to a full JDK 17 installation." >&2
    exit 1
fi

export PATH="$JAVA_HOME/bin:$PATH"

cd "$REPO_ROOT"

./gradlew prepareDesktopJdeployBundle verifyDesktopJdeployBundle
