#!/usr/bin/env bash
# Enable repository git hooks from .githooks/ (pre-commit guards BuildInfo.java).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOOKS_DIR="${REPO_ROOT}/.githooks"

chmod +x "${HOOKS_DIR}/pre-commit"
git -C "$REPO_ROOT" config core.hooksPath .githooks

echo "Git hooks enabled: core.hooksPath=.githooks"
echo "  pre-commit -> blocks committing BuildInfo.java with VERSION != dev"
