# Android Release Signing Configuration

This document explains how to configure release signing for the KeeNotes Android app.

## 1. Generate Release Keystore (One-time setup)

If you don't have a release keystore yet, generate one:

```bash
keytool -genkey -v \
  -keystore keenotes-release.jks \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -alias keenotes \
  -storetype JKS
```

**Important:** Save the following information securely:
- Keystore password
- Key password
- Key alias (e.g., "keenotes")
- Keystore file location

**⚠️ CRITICAL:** 
- Keep the keystore file safe and backed up
- Never commit it to Git
- If you lose it, you cannot update your app on Google Play

## 2. Local Development Signing

For local release builds, create a `gradle.properties` file in your home directory:

**Location:** `~/.gradle/gradle.properties`

```properties
# Android Release Signing
KEYSTORE_FILE=/path/to/your/keenotes-release.jks
KEYSTORE_PASSWORD=your_keystore_password
KEY_ALIAS=keenotes
KEY_PASSWORD=your_key_password
```

**⚠️ Security:** This file should NOT be committed to Git. It's in your home directory, not the project.

## 3. GitHub Actions Configuration

For CI/CD builds, configure GitHub Secrets:

### Step 1: Convert keystore to base64

```bash
# On macOS/Linux
base64 -i keenotes-release.jks -o keystore.base64

# Or copy directly to clipboard (macOS)
base64 -i keenotes-release.jks | pbcopy

# On Windows (PowerShell)
[Convert]::ToBase64String([IO.File]::ReadAllBytes("keenotes-release.jks")) | Set-Clipboard
```

### Step 2: Add GitHub Secrets

Go to: Repository → Settings → Secrets and variables → Actions → New repository secret

Add these secrets:
- `ANDROID_KEYSTORE_BASE64`: The base64 encoded keystore content
- `ANDROID_KEYSTORE_PASSWORD`: Your keystore password
- `ANDROID_KEY_ALIAS`: Your key alias (e.g., "keenotes")
- `ANDROID_KEY_PASSWORD`: Your key password

### Step 3: Verify Configuration

The GitHub Actions workflow (`.github/workflows/release.yml`) is already configured to:
1. Decode the keystore from the secret
2. Build signed APK and AAB
3. Clean up the keystore after build
4. Upload artifacts to the release

## 4. Build Commands

### Local Release Build (APK)
```bash
cd keenotes-android
gradle assembleRelease
```

### Local Release Build (AAB for Google Play)
```bash
cd keenotes-android
gradle bundleRelease
```

### Output Locations
- APK: `app/build/outputs/apk/release/keenotes-android-*.apk`
- AAB: `app/build/outputs/bundle/release/keenotes-android-*.aab`

## 5. Verify Signing

To verify your APK is properly signed:

```bash
# Check APK signature
jarsigner -verify -verbose -certs app/build/outputs/apk/release/keenotes-android-*.apk

# View APK signing certificate
keytool -printcert -jarfile app/build/outputs/apk/release/keenotes-android-*.apk
```

## 6. Security Best Practices

✅ **DO:**
- Keep keystore file in a secure location
- Back up keystore to multiple secure locations
- Use strong passwords (16+ characters)
- Store passwords in a password manager
- Limit access to GitHub Secrets

❌ **DON'T:**
- Commit keystore to Git (even private repos)
- Share keystore via email or chat
- Use the same keystore for multiple apps
- Print passwords in logs or console

## 7. Troubleshooting

### "Keystore not found" error
- Check the `KEYSTORE_FILE` path is correct
- Ensure the file exists and is readable

### "Wrong password" error
- Verify `KEYSTORE_PASSWORD` and `KEY_PASSWORD` are correct
- Check for extra spaces or special characters

### GitHub Actions fails with signing error
- Verify all 4 secrets are configured correctly
- Check the base64 encoding is complete (no truncation)
- Ensure the keystore file is valid

### Fallback to debug signing
If release signing is not configured, the build will automatically fall back to debug signing with a warning. This is fine for testing but NOT for production releases.

## 8. Google Play Upload

For Google Play Store:
1. Use the **AAB** file (not APK)
2. Upload to Google Play Console
3. Google Play will generate optimized APKs for different devices

For direct distribution:
1. Use the **APK** file
2. Users can install directly (requires "Unknown sources" enabled)
