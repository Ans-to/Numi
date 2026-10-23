#!/usr/bin/env bash
set -euo pipefail
umask 077
: "${1:?Usage: create-owner-key.sh /secure/path/outside/repository}"
root=$(cd "$(dirname "$0")/.." && pwd)
destination=$(realpath -m "$1")
case "$destination/" in "$root/"*) echo 'Signing material must be outside this source repository.' >&2; exit 1;; esac
mkdir -p "$destination"
chmod 700 "$destination"
if [ -e "$destination/owner.p12" ] || [ -e "$destination/owner.password" ]; then echo 'Refusing to overwrite existing signing material.' >&2; exit 1; fi
python3 -c 'import secrets,sys; open(sys.argv[1],"x").write(secrets.token_urlsafe(48))' "$destination/owner.password"
export CALCVAULT_GENERATED_PASSWORD="$(cat "$destination/owner.password")"
keytool -genkeypair -keystore "$destination/owner.p12" -storetype PKCS12 -alias owner \
  -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 10000 \
  -storepass:env CALCVAULT_GENERATED_PASSWORD -keypass:env CALCVAULT_GENERATED_PASSWORD \
  -dname 'CN=Calc Vault Owner, OU=Private Applications, O=Calc Vault' -noprompt
unset CALCVAULT_GENERATED_PASSWORD
chmod 600 "$destination/owner.p12" "$destination/owner.password"
printf 'Owner key generated outside source control: %s/owner.p12\nBack up the key securely and move owner.password to your password manager.\n' "$destination"
