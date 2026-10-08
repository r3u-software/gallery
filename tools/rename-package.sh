#!/usr/bin/env bash
# Changes the placeholder package id (com.example.gallery) everywhere.
#   bash tools/rename-package.sh com.yourcompany.gallery
# Do this BEFORE your first release: the applicationId identifies the app on every phone forever.
set -euo pipefail

OLD="com.example.gallery"
NEW="${1:-}"
if ! [[ "$NEW" =~ ^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$ ]]; then
  echo "Usage: $0 <new.package.id>   (lowercase letters/digits/underscores, at least two segments)"
  exit 1
fi
[ "$NEW" = "$OLD" ] && { echo "Already $OLD"; exit 0; }

cd "$(dirname "$0")/.."
OLD_RE="${OLD//./\\.}"

# 1) text replacement in Gradle, manifest, resources and sources
files=$(grep -rlE "$OLD_RE" app/build.gradle.kts app/src || true)
for f in $files; do sed -i "s/$OLD_RE/$NEW/g" "$f"; done

# 2) move the source folders to match the new package
for set in main test; do
  base="app/src/$set/kotlin"
  [ -d "$base/com/example/gallery" ] || continue
  tmp="$base/.rename_tmp"
  mv "$base/com/example/gallery" "$tmp"
  find "$base/com" -type d -empty -delete 2>/dev/null || true
  dest="$base/${NEW//./\/}"
  mkdir -p "$(dirname "$dest")"
  mv "$tmp" "$dest"
done

echo "Renamed $OLD -> $NEW. Review with: git status && git diff --stat"
