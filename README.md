# PhoneTunnel — Android App

SOCKS5 proxy server that shares your phone's internet (+ VPN) to your PC.

## Build with Android Studio

1. Open Android Studio
2. File → Open → select this folder (PhoneTunnelAndroid)
3. Wait for Gradle sync to finish
4. Build → Build Bundle(s)/APK(s) → Build APK(s)
5. APK saved to: app/build/outputs/apk/debug/app-debug.apk

## Build from command line

```bash
# Windows
gradlew.bat assembleDebug

# Mac / Linux
./gradlew assembleDebug
```

## Install on phone

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Requirements
- Android 5.0+ (API 21)
- USB Debugging enabled on phone

## How to use
1. Connect VPN on phone (optional — if you want PC to also use VPN)
2. Open PhoneTunnel app → tap Start
3. Connect USB cable to PC
4. Open PhoneTunnel Windows app → click Connect
5. All PC traffic now routes through your phone
