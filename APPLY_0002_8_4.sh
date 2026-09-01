#!/data/data/com.termux/files/usr/bin/bash
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
PATCH="patches/0002.8.4-rear-hidden.patch"

if git apply --reverse --check "$PATCH" >/dev/null 2>&1; then
  echo "0002.8.4 code patch is already applied."
  exit 0
fi

echo "Checking 0002.8.4 patch..."
git apply --check "$PATCH"

echo "Applying 0002.8.4 patch..."
git apply "$PATCH"

echo "0002.8.4 applied successfully."
echo "Next: git diff --check && git status --short"
