# Android Authenticator

A private, offline-first Android authenticator for TOTP accounts. The app stores shared secrets in encrypted Android storage and never sends them to GitHub.

## Features

- Add TOTP accounts manually or by scanning an `otpauth://` QR code
- Generate 6- or 8-digit SHA-1/SHA-256/SHA-512 TOTP codes
- Copy a code with one tap
- Store accounts using AndroidX encrypted storage

## Build an APK

Install Android Studio (which includes JDK 17), then run `./gradlew.bat assembleDebug`.
The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

Every push to `main` runs **Build Android APK** on GitHub Actions. Download and extract the `android-authenticator-debug` artifact, then copy `app-debug.apk` to your Android phone.

## Security

Never commit TOTP secrets, backups, or signing keystores. Keep the original authenticator active until every account has been tested. Chrome Sync data cannot be accessed by an Android app; secure backup and cloud synchronization are later milestones.
