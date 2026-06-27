# Windy Weather Revived

Windy Weather Revived is a revival and rebuild of the old Samsung S3 secret live wallpaper service for modern smartphones. It's built with Open-Meteo compatibility and experimental Samsung weather integration. 
This project contains small fixes and improvements to the original, including a full port to Kotlin, all while keeping original features intact for those who want nothing changed.

## Improvements

* Full port to Open-Meteo weather
* Upscaled textures, can be toggled off
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
.\gradlew.bat clean assembleDebug
.\gradlew.bat lintDebug
```

The app module is `app`. The package/application ID remains `com.BalancedLight.WindyWeather` for installed-app and wallpaper-component compatibility.

## Installation
Download and install the APK, and open your phone wallpaper selector, then select Windy Weather.
(You may need to select "Live Wallpaper" first on certain phones)
