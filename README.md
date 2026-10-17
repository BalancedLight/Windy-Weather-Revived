<p align="center">
  <img
    src="https://github.com/user-attachments/assets/62bb84cf-efbd-448a-9e92-760711bb211c"
    alt="Windy Weather Revived banner with two wind turbines against a bright blue sky with clouds and the sun." />
</p>
<p align="center">
Windy Weather Revived is a revival and rebuild of the old Samsung Galaxy S2/S3 secret live wallpaper service for modern smartphones. It's built with Open-Meteo compatibility and experimental Samsung weather integration. 
This project contains small fixes and improvements to the original, including a full port to Kotlin, all while keeping original features intact for those who want nothing changed.
</p>

> Windy Weather Revived is an independent, unofficial open-source project. It is not affiliated with, endorsed by, or sponsored by Samsung Electronics. Samsung and related product names are trademarks of their respective owners.

Weather data is provided by [Open-Meteo](https://open-meteo.com/) under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Windy Weather Revived is permanently free and ad-free and uses Open-Meteo only under its non-commercial terms. Optional location weather sends rounded coordinates to Open-Meteo and passes those same rounded coordinates to the device's Android system geocoding service to determine the displayed place name. The GitHub distribution may additionally send them to the United States Naval Observatory (USNO) for moon information; the Play distribution calculates moon phase on-device. See the [Privacy Policy](https://balancedlight.github.io/Windy-Weather-Revived/privacy-policy.html).

## Improvements

* Full port to Open-Meteo weather
* Upscaled textures, can be toggled off
* Dynamic sky engine — skies are live gradients that move continuously through night, sunrise, day and sunset, with stable daily colour variation and weather-aware foreground light (toggle it off for the original fixed skies)
* Force toggle day or night
* Force toggle weather
* Faster refreshes (10 minutes to 6 hours, or off)
* New weather events ("Mostly Clear", raindrops on thunder, frost overlay in freezing temperatures, etc..)
* Framerate adjustments from 15 to 60FPS
* Moon phases appear on-screen
* Parallax for launchers that support it
* Experimental Samsung weather compatibility (Samsung Only)

## Google Play
Want to be added to the Google Play testing branch? 
You can enroll here: https://play.google.com/apps/testing/com.BalancedLight.WindyWeather

## Build

The default Android Studio flavor is `github`: local/manual and GitHub APKs support Android
5.0 (API 21). Select `play` explicitly for Google Play (Android 7.0 / API 24). The Play
minimum is this project's distribution choice, not a universal Google requirement. `targetSdk`
remains 37 for both; minimum SDK and target SDK serve different purposes.

```powershell
.\gradlew.bat :app:assembleGithubDebug           # normal local APK, API 21
.\gradlew.bat :app:assembleGithubRelease         # GitHub release APK, API 21
.\gradlew.bat :app:bundlePlayRelease             # Google Play AAB, API 24
.\gradlew.bat :app:verifyDistributionArtifacts   # both release APKs + Play AAB, inspect packaged manifests
.\gradlew.bat :app:testGithubDebugUnitTest :app:testPlayDebugUnitTest
```

In Android Studio's Generate Signed Bundle / APK wizard, select `playRelease` for Play or
`githubRelease` for GitHub. Existing signing setup is unchanged. The Play distribution retains
`com.BalancedLight.WindyWeather`; GitHub is separately installable as
`com.BalancedLight.WindyWeather.github` and retains USNO moon lookup and AeroWeather synchronization.
An unqualified `assembleDebug` builds **both** flavors; use the explicit GitHub command for API 21.

Material 1.13.0 and Play services location 21.3.0 declare minSdk 21. The complete GitHub
dependency graph must pass the normal manifest merger and API checks: no `tools:overrideLibrary`
or manifest compatibility bypass is used. Do not upgrade dependencies past API 21 support
without revisiting this distribution contract.

### Version codes

Every Gradle invocation that schedules APK or AAB packaging reserves a fresh build number
**before** manifest processing. Repeated incremental builds, debug/release, Android Studio
signed builds, APKs and bundles all embed the new code in the current artifact. All phone
variants and outputs in one invocation share a code; Wear uses the next odd code because
it shares the phone's Play application ID. Building both modules still reserves only once.

`gradle/build-number.gradle` uses twice the UTC seconds since 2020 for phone codes (even),
plus one for Wear (odd). The next reservation also exceeds both saved codes, so repeated
builds within one second and a backward clock in the same checkout remain monotonic.
The first build migrates the old small codes to this larger range. Codes stay within Play's
2,100,000,000 limit until 2053; an explicit guard rejects exhaustion. Version names stay manual.

[`version.properties`](version.properties) records the last reserved codes. A lock serializes
reservations in this checkout, and `.gradle/build-number-state.properties` preserves the floor
across `clean` and restores of the tracked file. Sync, help, lint, tests, manifest-only tasks,
`clean` alone and `--dry-run` do not reserve. `clean assemble...` does. Failed packaging
invocations can consume a number: gaps are intentional and numbers must never be rolled back.
The obsolete `-PnoVersionBump` flag has no effect; all packaging builds increment.
Configuration cache is disabled because numbering inspects the real task graph each invocation;
explicitly enabling it fails with an explanation instead of silently reusing a code.

CI uses the same allocator. Phone and Wear jobs share a concurrency group and a cached
high-water file; workflow reruns reserve fresh codes (`run_attempt` makes each cache save unique).
CI artifacts include `version.properties` to carry the used floor back to local builds.
GitHub cache scope/retention still applies. Before moving releases between independent local
checkouts and CI, carry forward the latest `version.properties` and compare the artifact's code
with Play Console. Offline machines cannot guarantee global uniqueness without shared state:
serialize official release builds, keep clocks correct, and publish artifacts in increasing-code
order. The workflow does not commit, push or upload to Play automatically.

Focused integration regression (requires the installed JDK/Android SDK):

```powershell
powershell -File tools\test_build_numbering.ps1
```

It checks non-packaging tasks and dry-runs leave the counter unchanged, then verifies two
successive packaging runs (one after clean) increase the actual APK and AAB manifest codes.
`verifyDistributionArtifacts -PverificationDistribution=github` or `play` limits inspection to
one CI distribution; `-PverificationBuildType=debug` checks debug artifacts.

## Installation
Download and install the APK, and open your phone wallpaper selector, then select Windy Weather.
(You may need to select "Live Wallpaper" first on certain phones)

## Wear OS watch face

`wearWatchFace/` is a separate, resource-only [Watch Face Format](https://developer.android.com/training/wearables/wff) version 2 package that recreates the Windy scenes on a 450 × 450 round watch face. It shares the phone app's `applicationId` so it publishes to the same Play Console entry on a dedicated Wear OS track, and it contains no code — the whole face is `res/raw/watchface.xml` plus artwork derived from the phone app's drawables by `tools/wear_assets/generate.py`.

```powershell
python tools\wear_assets\generate.py          # rebuild the artwork from the phone drawables
python tools\wear_assets\build_watchface.py   # rebuild res/raw/watchface.xml from the derived layout
python tools\wear_assets\generate.py --check  # deterministic; also asserts the XML geometry matches
.\gradlew.bat :wearWatchFace:assembleDebug :wearWatchFace:bundleRelease
```

The face's geometry is derived, not hand-authored: `tools/wear_assets/framing.py` holds the mobile
renderer's projection, `generate.py` writes both the artwork and the `layout` block of
`wear/scene_mapping.json`, and `build_watchface.py` emits `watchface.xml` from that layout. `--check`
regenerates into a scratch area and fails if either the assets or the markup have drifted.

Wear builds use odd version codes and phone builds use even codes so their shared Play application ID never reuses a code within a coordinated build history.
Play requires watch-face screenshots to be square and at least 384 x 384; the two in
`play-assets/wear/` are 454 x 454 captures of the running face.

## Disclaimer
Some additions to this application were created with assistance from AI
