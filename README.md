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

## Installation
Download and install the APK, and open your phone wallpaper selector, then select Windy Weather.
(You may need to select "Live Wallpaper" first on certain phones)
