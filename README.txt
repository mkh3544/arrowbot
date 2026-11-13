Arrows Bot Android project

Build:
- Open in Android Studio and choose Build > Build APK, or push to GitHub.
- GitHub Actions workflow creates app-debug.apk as the ArrowsBot-apk artifact.

Use:
1. Install the APK (Android 11 / API 30 or newer).
2. Open Arrows Bot and enable its Accessibility service.
3. Return to the app, press Start, then open the puzzle within 5 seconds.
4. Press Stop from the app to stop automation.

Notes:
- This is an experimental visual solver. It may mis-detect some board themes or arrow layouts.
- Keep the app in the foreground until the service is enabled; the bot runs as an Accessibility service.
- Config values in Solver.kt control board bounds, color threshold, and continue-button location.