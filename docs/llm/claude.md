I have forked a repo called Seal and now I want to start making changes but before that, I want to set up a new github action that generates a full build, not a pre-release, on every push to the main branch 
here is what I have done so far 

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

The nice_try you see above is a placeholder and not the actual password but I think that shouldn't matter. 

I won't be building locally though. All builds will happen on github actions. 
Please remember to read the full dump from `dump.txt` in the project files. 
this code is public at github.com/kusl/seal/ but there might be changes I have not yet pushed to github yet. 
please remember to give me FULL files for any file that needs to change or for any new file for easy copy pasting 
always use engineering best practices 
explain every change thoroughly so it is educational as well as functional 
do not hallucinate. 
