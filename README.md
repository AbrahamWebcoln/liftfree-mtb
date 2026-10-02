# LiftFree MTB

A Garmin fenix 6 activity app and an Android companion for a single continuous bike-park ride with lift ascent excluded from the FIT ascent total sent to Strava.

**This is an installable test build, not a field-verified release.** Build results are in `BUILD-STATUS.json` and the included logs. Compilation and simulator tests do not prove Bluetooth operation on a physical watch or Strava's treatment of a real upload.

## Files to install

- `LiftFree-fenix6.prg`: standard fenix 6, not 6S or 6X.
- `LiftFree-fenix6pro.prg`: fenix 6 Pro. Use only the file matching the watch.
- `LiftFree-Android.apk`: Android 11 or newer, including modern Samsung phones.

Do not install both watch binaries. The source ZIP and synthetic FIT fixtures are for development; they are not activity profiles and must not be uploaded as rides.

### Watch: one-time installation

Connect the watch to a computer with its USB cable. In its storage, open `GARMIN/APPS`. Copy the matching `.prg` there and rename the copied file `LiftFree.prg`. Safely eject the watch. Look for **LiftFree MTB** in the START menu; use Add if it is not in your favorites.

This is a Connect IQ activity application, not a renamed copy of the built-in MTB profile. No Garmin developer tools are needed to install a compiled PRG.

### Phone: one-time installation

Open `LiftFree-Android.apk` on the phone and install it. Android may require allowing installation from the browser or file manager that opened it. Keep Garmin Connect installed, signed in and paired with the watch.

Open LiftFree MTB, tap **Start phone companion**, and grant Nearby devices and notification permissions. Use **Choose Garmin watch** when more than one device is paired. In Android battery settings, allow background use for LiftFree and Garmin Connect while riding.

## Strava: one-time account setup

**Disable the normal Garmin-to-Strava activity sync before using automatic uploads.** The watch also saves an ordinary Garmin backup, whose standard ascent is not corrected. Leaving the normal sync enabled can send the uncorrected backup as a duplicate.

This personal build does not contain a shared Strava developer account or client secret. To enable uploads:

1. On your own Strava account, open https://www.strava.com/settings/api and register a personal application. Use **LiftFree MTB** for its name and **localhost** for its Authorization Callback Domain. A website URL can point to this repository. Supply any other required profile fields in Strava's form.
2. In the phone app choose **Connect Strava / account setup**. Enter your application's client ID and client secret **on the phone only**, then authorize it. Leave both requested activity permissions checked so the app can upload and verify Only You activities. Do not paste the secret, access tokens or authorization codes into chat or GitHub.
3. Confirm **Garmin -> Strava auto-sync is off** in LiftFree. Automatic corrected uploads are optional and off by default. A saved ride can also be uploaded manually from the app.

Credentials are encrypted in Android Keystore-backed storage. No server is required. Android backup of this application's private data is disabled. Uninstalling the app removes its local ride files and account settings; export any files you need first.

Uploads follow your Strava account's default activity visibility. Set it to **Only You** before the first test. The application does not silently change your privacy settings.

## Each outing

Start the phone companion and keep the phone with you. Open LiftFree MTB on the watch. Wait for GPS and **PHONE CONNECTED**, then press START once.

**First trip on a new lift:** press BACK when boarding and BACK again at the top. This excludes that trip and teaches the lift corridor. This version remembers one learned lift route. Teaching a different route replaces the previous one. Learning requires enough GPS points, at least 100 m of route length and at least 8 m of height gain; shorter lifts can still be marked manually.

Later trips on the learned route can be detected automatically. The detector requires forward travel along that corridor; it does not classify every uphill section as a lift. Available cadence data protects pedaled climbs. Without cadence, a pedaled climb on the same corridor can be ambiguous. Press DOWN to force riding, or hold MENU before starting to turn automatic detection off and mark lifts manually.

At the end, press START, select **Finish and send**, and keep the phone nearby until **SAVED ON PHONE** appears. This creates one ride, not an activity for every downhill run. No computer processing is required after rides.

## What is changed

The companion reconstructs the watch's sample stream, applies lift markers, filters small altitude fluctuations with a two-metre reversal threshold, and writes the remaining ascent into the standard FIT session and lap ascent fields. Genuine climbing outside lift spans is retained subject to that noise filter.

The recorded GPS positions, sample times, altitude profile, available heart rate and cadence are retained within FIT and source sampling precision. Lift time and distance remain included. The elevation graph will therefore still show lifts. The phone FIT is reconstructed from the application's stream, not a byte-for-byte copy of Garmin's native file; Garmin-specific training metrics, power and temperature are not carried over by this version.

This applies only to rides recorded with LiftFree on the watch. It does not alter recordings made in the Strava phone app or change existing Strava activities.

## Check the first upload

Record a short test with a marked lift and a real riding climb. After uploading, open that ride in the phone app. It fetches Strava's reported elevation and compares it with the corrected FIT total.

- **verified** means Strava's reported ascent matches the file within 1 metre.
- **ELEVATION MISMATCH** means Strava did not retain that total. The expected and actual values are shown; do not treat the result as solved.
- **processing** means Strava has not finished importing it.
- **uncertain** means a network failure prevented confirming the upload response. Check Strava before retrying to avoid duplicates.

Do not use Strava's Correct Elevation feature for this test: it requests a different map-based calculation. Verification checks the uploaded number, not whether every lift was classified correctly. Review the lift count and use the manual override when needed.

## Disconnection and backup behavior

Samples are acknowledged only after the phone has saved them to disk. Short Bluetooth interruptions are retried without duplicating records. The watch buffer holds at most 180 samples, approximately three minutes at 1 Hz. Keep the phone companion running with the phone nearby.

If the buffer overflows, the watch shows **BACKUP ONLY** and refuses to upload a partial corrected ride. The ordinary Garmin recording is preserved, but its elevation is not corrected. An interrupted or incomplete ride is never silently labeled complete. A normally finished transfer can resume after reopening the watch application.

## Build and testing

GitHub Actions compiles both watch targets with Garmin's compiler, runs the Monkey C unit tests in Garmin's simulator, runs the Java ascent/FIT tests, independently decodes the synthetic FIT using Garmin's official Python SDK, builds the Android APK and checks its signature. Logs and SHA-256 checksums accompany the artifacts.

The current builds use test signing keys. Future builds may require reinstalling rather than updating the phone APK in place. Export saved rides before uninstalling. No signing keys are included in this repository or download package.

To prepare the project locally, run `python3 ci/build.py --prepare`. The full script expects Linux with Docker, Java 17, Python and the Android SDK. The compiler container and Gradle dependencies are downloaded only in the build environment.

## Privacy

No personal ride data is bundled or published. Learned routes stay on the watch; recordings stay in private phone storage and go to Strava only after the user authorizes an upload. The public repository contains source and synthetic tests only. The app cannot delete existing Strava activities.

## Technical references

- Garmin recording API: https://developer.garmin.com/connect-iq/api-docs/Toybox/ActivityRecording/Session.html
- Garmin Android companion SDK: https://github.com/garmin/connectiq-android-sdk
- Garmin FIT SDK: https://github.com/garmin/fit-python-sdk
- Strava upload and barometric elevation handling: https://developers.strava.com/docs/uploads/
- Strava authorization: https://developers.strava.com/docs/authentication/
