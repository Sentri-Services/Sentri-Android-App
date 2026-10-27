#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/verify-skills.py
python3 scripts/verify-language.py
python3 scripts/test-verify-language.py
if [[ ! -d tools/graphql-contract/node_modules/graphql ]]; then
  npm ci --prefix tools/graphql-contract --ignore-scripts --no-audit --no-fund
fi
node tools/graphql-contract/check.cjs
node tools/graphql-contract/test.cjs
./scripts/gradle.sh :app:assembleDevDebug :app:testDevDebugUnitTest :app:lintDevDebug --console=plain
