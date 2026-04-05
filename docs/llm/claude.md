00
11

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

Now I have a thorough picture of the project. Let me synthesize everything and deliver the full files.

Here's a complete breakdown of what's needed and why, followed by the full files.

---

## Understanding the existing setup

From the dump, here's what the project already has:

**Existing signing logic in `app/build.gradle.kts`:** It reads from a `keystore.properties` file at the root of the project. If that file exists, it creates a `signingConfigs.githubPublish` config and applies it to the `release` build type. This is the hook we'll use.

**Existing workflow (`android.yml`):** Triggers only on `workflow_dispatch` (manual), uses a `SIGNING_KEY`/`ALIAS`/`KEY_STORE_PASSWORD`/`KEY_PASSWORD` secret scheme, and uses the `ilharp/sign-android-release` action to sign *after* the build. That's a different approach (post-build signing) with different secret names than what you set up (`ANDROID_KEYSTORE_BASE64` / `ANDROID_SIGNING_PASSWORD`).

**Product flavors:** There are three — `generic` (default), `githubPreview`, and `fdroid`. For a "full release" (not a preview), we want the `generic` flavor → Gradle task is `assembleGenericRelease`.

**Your keystore setup:** You used a single password for both `storepass` and `keypass`, and a single alias `myalias`. We'll read those from the two secrets you already created.

**What a GitHub Release requires:** The workflow needs to create a GitHub Release (not just an artifact) to count as a "real release" (not a pre-release). We'll use `softprops/action-gh-release` for that.

---

## Strategy

Instead of post-build signing (like the old `android.yml`), we'll use the **in-Gradle signing** path that `app/build.gradle.kts` already supports via `keystore.properties`. This is cleaner because:

1. Gradle signs the APK during the build step itself.
2. No need for a separate signing action.
3. The existing `build.gradle.kts` code already handles this — we just need to supply the file.

The workflow will:
1. Decode `ANDROID_KEYSTORE_BASE64` → write `android.keystore`
2. Write `keystore.properties` so Gradle picks it up automatically
3. Run `./gradlew assembleGenericRelease`
4. Create a GitHub Release tagged with the version name, uploading all APKs, marked **not** pre-release

---

## Files to create/modify

### 1. New file: `.github/workflows/release.yml` (new workflow)

This replaces the role of the old `android.yml` for your fork.### 2. New Gradle task: `printVersionName` in `app/build.gradle.kts`

The workflow above calls `./gradlew printVersionName` to get the version dynamically. We need to register that task. Here is the **full** `app/build.gradle.kts` with the task added at the bottom (and nothing else changed):### 3. Add `keystore.properties` to `.gitignore`

This file must **never** be committed. The root `.gitignore` needs one line added. Here is the relevant section to add (you already have `/build` in the existing file; just append this block):---

## Step-by-step explanation of every decision

**Why `assembleGenericRelease` and not something else?**
The project has three flavors: `generic`, `githubPreview`, and `fdroid`. `generic` is `isDefault = true` and has no `applicationIdSuffix` or modified `versionName` — it's the clean, vanilla release. `githubPreview` appends `.preview` to the app ID and relabels the app "Seal Preview", which is exactly what you don't want for a full release. `fdroid` appends `-(F-Droid)` to the version name. So `assembleGenericRelease` is the right target.

**Why write `keystore.properties` instead of passing env vars directly to Gradle?**
Because `app/build.gradle.kts` already has the `keystore.properties`-reading logic fully built. Using it means zero changes to the signing logic — we just feed it the file it expects. The alternative would be adding new `System.getenv()` calls to `build.gradle.kts`, which is more invasive.

**Why `RUNNER_TEMP` for the keystore file?**
`RUNNER_TEMP` is a per-job temporary directory provided by GitHub Actions that is outside the repo. Writing the keystore there means: (a) it can't accidentally be committed even if someone runs `git add .`, and (b) it's automatically cleaned up when the job ends.

**Why `softprops/action-gh-release@v2` with `prerelease: false`?**
This is the standard action for creating GitHub Releases. `prerelease: false` is explicit — without it, if the tag name contains `-alpha` or `-beta` (which it will, based on `currentVersion.name`), GitHub's UI might show it as a pre-release. By setting it explicitly, you override that heuristic.

**Why `generate_release_notes: true`?**
This automatically populates the release body with the commit messages since the last tag. Free changelogs with no extra work.

**Why the `printVersionName` task?**
The version lives in `buildSrc/src/main/kotlin/Version.kt` as a Kotlin expression. Parsing that in bash is fragile. Asking Gradle itself is the proper single-source-of-truth approach — the same value that ends up in `versionName` in the APK is what gets used for the release tag.

**Why `concurrency: cancel-in-progress: true`?**
If you push two commits in quick succession, you don't want two release jobs racing to tag the same version. The newer push cancels the in-progress job.

**The `permissions: contents: write` block** — GitHub Actions tokens by default have read-only permissions on public repos in some configurations. Explicitly requesting `contents: write` ensures the release creation step can always push the tag and create the release, regardless of org-level settings.

---

## What you need to do

1. **Confirm your secrets are set** — you said you already added `ANDROID_KEYSTORE_BASE64` and `ANDROID_SIGNING_PASSWORD`. Double-check the names match exactly (case-sensitive).

2. **Copy the three files** into your repo:
   - `.github/workflows/release.yml` (new file)
   - `app/build.gradle.kts` (replace existing)
   - Append the `.gitignore` lines

3. **Make sure `keystore.properties` is NOT in your repo** — run `git status` and confirm it's not tracked.

4. **Push to `main`** — the workflow triggers immediately.

One thing to be aware of: since `currentVersion` is currently `Version.Alpha(2, 0, 0, 5)`, the version name will be `2.0.0-alpha.5` and the release tag will be `v2.0.0-alpha.5`. GitHub won't auto-mark it as a pre-release because we explicitly set `prerelease: false`, but the name will still contain `-alpha`. That's correct behavior for your fork — you control what `currentVersion` is set to in `buildSrc/src/main/kotlin/Version.kt`. When you're ready to call something a stable release, change it to `Version.Stable(2, 0, 0)`.

05
12

