#!/usr/bin/env bash
# ============================================================================
#  Creates the self-signed TLS certificate the backend needs to serve HTTPS.
#
#  Run this ONCE, from the backend/ folder:
#      ./make-keystore.sh
#
#  It writes src/main/resources/keystore.p12, which is gitignored — a private
#  key is a secret and never belongs in version control, not even a throwaway
#  development one.
# ============================================================================
set -euo pipefail

KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
OUT="src/main/resources/keystore.p12"
STOREPASS="changeit"

if [ -f "$OUT" ]; then
  echo "$OUT already exists. Delete it first if you want a fresh certificate."
  exit 0
fi

"$KEYTOOL" -genkeypair \
  -alias cookiedemo \
  -keyalg RSA \
  -keysize 2048 \
  -storetype PKCS12 \
  -keystore "$OUT" \
  -validity 365 \
  -storepass "$STOREPASS" \
  -dname "CN=localhost, OU=Dev, O=CookieShookie, L=., ST=., C=IN" \
  -ext "SAN=dns:localhost,ip:127.0.0.1"

echo
echo "Created $OUT  (password: $STOREPASS)"
echo "The backend will now start on https://localhost:8443"
