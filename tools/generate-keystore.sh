#!/usr/bin/env bash
# Generates the release signing keystore (run this in a Codespace, where keytool is installed)
# and prints how to add it to GitHub as repository secrets.
#
#   bash tools/generate-keystore.sh            # create + print instructions
#   bash tools/generate-keystore.sh --set-secrets   # also run `gh secret set` for you (needs a token with secrets access)
#
# The keystore file is gitignored (gallery-release.*) and must NEVER be committed.
set -euo pipefail

OUT="gallery-release.jks"
ALIAS="gallery"
SET_SECRETS=0
[ "${1:-}" = "--set-secrets" ] && SET_SECRETS=1

command -v keytool >/dev/null || { echo "keytool not found. Run this inside the Codespace (JDK 17)."; exit 1; }
if [ -e "$OUT" ]; then
  echo "Refusing to overwrite existing $OUT. Move it away first (and make sure it's backed up!)."
  exit 1
fi

# One password for store and key (PKCS12 keystores use a single password anyway).
PASS="$(head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 28)"

keytool -genkeypair -v -storetype PKCS12 \
  -keystore "$OUT" -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 36500 \
  -storepass "$PASS" -keypass "$PASS" \
  -dname "CN=Gallery, O=Gallery, C=US" >/dev/null

B64="$(base64 -w0 "$OUT")"
echo "$B64" > "$OUT.b64"

cat <<EOF

============================================================
 Keystore created: $OUT  (and $OUT.b64). Both are gitignored.
============================================================

 BACK THESE UP NOW in a password manager (1Password, Bitwarden, ...):

   Keystore file : $OUT        (attach the file, or store the .b64 text)
   Key alias     : $ALIAS
   Password      : $PASS

 If you lose the keystore or the password you can NEVER publish an update that installs
 over the existing app: users would have to uninstall and lose their settings.

 Add the repository secrets (Settings > Secrets and variables > Actions > New repository secret):

   ANDROID_KEYSTORE_BASE64    = contents of $OUT.b64
   ANDROID_KEYSTORE_PASSWORD  = $PASS
   ANDROID_KEY_ALIAS          = $ALIAS
   ANDROID_KEY_PASSWORD       = $PASS

 Or with the GitHub CLI, from this folder:

   gh secret set ANDROID_KEYSTORE_BASE64   < $OUT.b64
   gh secret set ANDROID_KEYSTORE_PASSWORD --body '$PASS'
   gh secret set ANDROID_KEY_ALIAS         --body '$ALIAS'
   gh secret set ANDROID_KEY_PASSWORD      --body '$PASS'

 Then publish a release:   git tag v1.0.0 && git push origin v1.0.0

 After you have saved everything, delete the local copies:   shred -u $OUT $OUT.b64
EOF

if [ "$SET_SECRETS" = "1" ]; then
  echo
  echo ">> Setting repository secrets with gh ..."
  gh secret set ANDROID_KEYSTORE_BASE64   < "$OUT.b64"
  gh secret set ANDROID_KEYSTORE_PASSWORD --body "$PASS"
  gh secret set ANDROID_KEY_ALIAS         --body "$ALIAS"
  gh secret set ANDROID_KEY_PASSWORD      --body "$PASS"
  echo ">> Done. Still back up the keystore and password before deleting anything."
fi
