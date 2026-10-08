#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/verify-skills.py
./scripts/gradle.sh :app:assembleDevDebug :app:testDevDebugUnitTest :app:lintDevDebug --console=plain
