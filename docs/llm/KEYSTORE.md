# Android Keystore Setup

## Generate a keystore (one time)

```bash
keytool -genkey -v -keystore android.keystore \
  -alias myalias -keyalg RSA -keysize 2048 -validity 10000 \
  -storetype PKCS12 \
  -storepass NICE_TRY \
  -keypass NICE_TRY \
  -dname "CN=Seal, OU=Dev, O=kusl, L=Richmond, ST=VA, C=US"
```

## Add to GitHub Secrets

1. Base64 encode the keystore:
```bash
base64 -w 0 android.keystore > android.keystore.base64
```

2. In your GitHub repo, go to **Settings → Secrets and variables → Actions** and add:
   - `ANDROID_KEYSTORE_BASE64` — contents of `android.keystore.base64`
   - `ANDROID_SIGNING_PASSWORD` — the password you used above

## Local builds

For local Android builds without signing, the APK will be unsigned.
For signed local builds, place `android.keystore` in the repo root (it's gitignored).
