#!/bin/sh
# Package a private Android 4.2 CarPlay test without changing the app code.
# The caller must supply an identity they are authorized to use.
set -eu

if [ "$#" -ne 2 ]; then
    echo "Usage: $0 /private/offline-mfi /private/output.apk" >&2
    exit 2
fi

identity_dir=$1
output_apk=$2
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
base_apk="$project_dir/car-market-kit/APKs/diplay-legacy-android42-sogou-map-formal-20261025.apk"
android_sdk=${ANDROID_HOME:-"$HOME/Library/Android/sdk"}
build_tools="$android_sdk/build-tools/34.0.0"
debug_keystore="$HOME/.android/debug.keystore"

for file in "$identity_dir/identity.pk8" "$identity_dir/certificate.p7b" "$base_apk" "$debug_keystore"; do
    if [ ! -s "$file" ]; then
        echo "Required input is missing or empty: $file" >&2
        exit 1
    fi
done
if [ -e "$output_apk" ]; then
    echo "Refusing to overwrite existing APK: $output_apk" >&2
    exit 1
fi

umask 077
work_dir=$(mktemp -d /private/tmp/diplay-private-mfi.XXXXXX)
cleanup() {
    case "$work_dir" in
        /private/tmp/diplay-private-mfi.*) rm -rf -- "$work_dir" ;;
    esac
}
trap cleanup EXIT HUP INT TERM

mkdir -p "$work_dir/assets/offline-mfi"
cp "$identity_dir/identity.pk8" "$work_dir/assets/offline-mfi/identity.pk8"
cp "$identity_dir/certificate.p7b" "$work_dir/assets/offline-mfi/certificate.p7b"

# Validate the certificate structure and match public keys without displaying secrets.
openssl pkcs7 -inform DER -in "$identity_dir/certificate.p7b" \
    -print_certs -out "$work_dir/certificates.pem"
count=$(grep -c -- 'BEGIN CERTIFICATE' "$work_dir/certificates.pem")
if [ "$count" -ne 1 ]; then
    echo "Expected exactly one MFi accessory certificate; found $count" >&2
    exit 1
fi
openssl pkey -inform DER -in "$identity_dir/identity.pk8" \
    -pubout -outform DER -out "$work_dir/key-public.der"
openssl x509 -in "$work_dir/certificates.pem" -pubkey -noout |
    openssl pkey -pubin -outform DER -out "$work_dir/cert-public.der"
if ! cmp -s "$work_dir/key-public.der" "$work_dir/cert-public.der"; then
    echo "MFi certificate/private key mismatch" >&2
    exit 1
fi

# Preserve the API 17 manifest, resources, DEX, and package identity.
# Remove only the previous APK v1 signatures, then add the two assets and resign.
cp "$base_apk" "$work_dir/unsigned.apk"
zip -q -d "$work_dir/unsigned.apk" 'META-INF/*.SF' 'META-INF/*.RSA' 'META-INF/MANIFEST.MF'
(cd "$work_dir" && zip -q -X "$work_dir/unsigned.apk" \
    assets/offline-mfi/identity.pk8 assets/offline-mfi/certificate.p7b)
"$build_tools/zipalign" -f 4 "$work_dir/unsigned.apk" "$work_dir/aligned.apk"
"$build_tools/apksigner" sign \
    --ks "$debug_keystore" --ks-key-alias androiddebugkey \
    --ks-pass pass:android --key-pass pass:android \
    --v1-signing-enabled true --v2-signing-enabled false --v3-signing-enabled false \
    --out "$work_dir/signed.apk" "$work_dir/aligned.apk"
"$build_tools/apksigner" verify --min-sdk-version 17 "$work_dir/signed.apk"
"$build_tools/aapt2" dump badging "$work_dir/signed.apk" | grep -q "package: name='sogou.map'"
"$build_tools/aapt2" dump badging "$work_dir/signed.apk" | grep -q "sdkVersion:'17'"

base_cert=$("$build_tools/apksigner" verify --print-certs "$base_apk" |
    sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
test_cert=$("$build_tools/apksigner" verify --print-certs "$work_dir/signed.apk" |
    sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
if [ -z "$base_cert" ] || [ "$base_cert" != "$test_cert" ]; then
    echo "Android APK signing certificate changed" >&2
    exit 1
fi
for dex in classes.dex classes2.dex classes3.dex; do
    base_sha=$(unzip -p "$base_apk" "$dex" | shasum -a 256)
    test_sha=$(unzip -p "$work_dir/signed.apk" "$dex" | shasum -a 256)
    if [ "$base_sha" != "$test_sha" ]; then
        echo "Unexpected changes to $dex" >&2
        exit 1
    fi
done
unzip -t "$work_dir/signed.apk" > /dev/null
install -m 600 "$work_dir/signed.apk" "$output_apk"
echo "MFi certificate and private key match; DEX/signing/API17 checks passed."
echo "Private test APK: $output_apk"
