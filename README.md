<p align="center">
  <img
    src="https://github.com/user-attachments/assets/62bb84cf-efbd-448a-9e92-760711bb211c"
    alt="Windy Weather Revived banner with two wind turbines against a bright blue sky with clouds and the sun." />
</p>
<p align="center">
Windy Weather Revived is a revival and rebuild of the old Samsung S3 secret live wallpaper service for modern smartphones. It's built with Open-Meteo compatibility and experimental Samsung weather integration. 
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

## Build

```powershell
.\gradlew.bat testPlayDebugUnitTest lintPlayRelease bundlePlayRelease
.\gradlew.bat testGithubDebugUnitTest lintGithubRelease assembleGithubRelease
```

The Play distribution retains `com.BalancedLight.WindyWeather`. The GitHub distribution is separately installable as `com.BalancedLight.WindyWeather.github` and retains the optional USNO moon lookup and AeroWeather synchronization.

### Version codes

Play version codes live in [`version.properties`](version.properties) at the repository root, one
key per module. The root `build.gradle` applies them through the Android Gradle plugin's variant
API and increments the key for whichever module you just built, immediately after a release bundle
succeeds — so the bundle you upload carries the current number and the next build is already clear
of it.

```powershell
.\gradlew.bat :app:bundlePlayRelease          # bumps app.versionCode
.\gradlew.bat :wearWatchFace:bundleRelease    # bumps wearWatchFace.versionCode
.\gradlew.bat :app:bundlePlayRelease -PnoVersionBump   # bumps nothing
```

Only `bundle*Release` tasks bump; debug builds, IDE syncs and `assembleGithubRelease` (the GitHub
APK never goes through Play) leave the file alone, and one invocation produces one bump however
many variants it builds. CI passes `-PnoVersionBump`.

`app/build.gradle` still declares a `versionCode`. It is overridden at build time and is no longer
what ships — `version.properties` is authoritative.

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

Wear releases use version codes from 100000 upward so they never collide with the phone track.
Play requires watch-face screenshots to be square and at least 384 x 384; the two in
`play-assets/wear/` are 454 x 454 captures of the running face.
