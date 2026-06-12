#!/bin/sh
# Fetch the Ghostty source at the commit our prebuilts are built from.
# Override GHOSTTY_SRC to choose the checkout location.
set -eu

GHOSTTY_COMMIT=5659cef41f4f2f7a478d0800a11836fa17e64d66
GHOSTTY_REPO=https://github.com/ghostty-org/ghostty

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
GHOSTTY_SRC=${GHOSTTY_SRC:-$REPO_ROOT/third_party/ghostty}

if [ ! -d "$GHOSTTY_SRC/.git" ]; then
    mkdir -p "$(dirname "$GHOSTTY_SRC")"
    git clone "$GHOSTTY_REPO" "$GHOSTTY_SRC"
fi

cd "$GHOSTTY_SRC"
git fetch origin "$GHOSTTY_COMMIT" 2>/dev/null || git fetch origin
git checkout -q "$GHOSTTY_COMMIT"
echo "Ghostty at $(git rev-parse HEAD) in $GHOSTTY_SRC"
