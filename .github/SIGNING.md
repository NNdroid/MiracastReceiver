# GitHub Actions APK Signing

MiracastReceiver can build a signed release APK from GitHub Actions without storing the JKS file or passwords in the repository.

## Required repository secrets

Configure these secrets under **Settings → Secrets and variables → Actions → Repository secrets**:

| Secret | Description |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded JKS/keystore file |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Signing key alias |
| `ANDROID_KEY_PASSWORD` | Password for the signing key |

All four secrets must be present. If any secret is missing, CI continues to build and upload the debug APK but skips the signed release build.

## Encode the JKS file

Linux:

```bash
base64 -w 0 release.jks
```

macOS:

```bash
base64 < release.jks | tr -d '\n'
```

Copy the resulting single-line value into `ANDROID_KEYSTORE_BASE64`.

## When signing runs

Signed release builds run only for trusted non-pull-request events:

- pushes to `main`
- manual `workflow_dispatch` runs

Pull requests never receive the release signing secrets and continue to build only the debug APK.

## What the workflow does

When all signing secrets are configured, Android CI:

1. decodes the JKS into `$RUNNER_TEMP`;
2. restricts the temporary file permissions;
3. validates the keystore password and alias with `keytool`;
4. exposes only the temporary keystore path and signing values to Gradle through environment variables;
5. builds `:app:assembleRelease`;
6. validates every generated release APK with `apksigner verify`;
7. uploads the signed release APKs as `MiracastReceiver-release-signed-<commit-sha>` for 30 days.

The keystore itself and its passwords are never committed to the repository.

## Local signed release build

The Gradle configuration uses the same environment variables locally:

```bash
export ANDROID_SIGNING_STORE_FILE=/absolute/path/to/release.jks
export ANDROID_SIGNING_STORE_PASSWORD='your-keystore-password'
export ANDROID_SIGNING_KEY_ALIAS='your-key-alias'
export ANDROID_SIGNING_KEY_PASSWORD='your-key-password'

cd MiracastReceiver
./gradlew :app:assembleRelease
```

If these four environment variables are absent, the release signing config is not attached.
