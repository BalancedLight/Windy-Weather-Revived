package com.BalancedLight.WindyWeather

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.AdapterView.OnItemSelectedListener
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SecretWallpaperSetting : Activity() {
    private var locationStatus: TextView? = null
    private var weatherDebugText: TextView? = null
    private lateinit var prefs: SharedPreferences

    protected override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wallpaper_settings)
        this.prefs = getSharedPreferences(
            com.BalancedLight.WindyWeather.SecretWallpaperSetting.Companion.PREF_NAME,
            MODE_PRIVATE
        )

        this.locationStatus = findViewById(R.id.location_status)
        val permissionButton: Button = findViewById(R.id.btn_permission)
        val locationButton: Button = findViewById(R.id.btn_location_settings)
        val refreshButton: Button = findViewById(R.id.btn_refresh)
        val closeButton: Button = findViewById(R.id.btn_close)
        val weatherRefreshSpinner: Spinner = findViewById(R.id.spinner_weather_refresh_interval)
        val targetFpsValueText: TextView = findViewById(R.id.text_target_fps_value)
        val targetFpsSeekBar: SeekBar = findViewById(R.id.seek_target_fps)
        val powerSaveFpsValueText: TextView = findViewById(R.id.text_power_save_fps_value)
        val powerSaveFpsSeekBar: SeekBar = findViewById(R.id.seek_power_save_fps)
        val frameRateDependentAnimationSwitch: Switch =
            findViewById(R.id.switch_frame_rate_dependent_animation)
        val oldNightEffectSwitch: Switch = findViewById(R.id.switch_old_night_effect)
        val hideThunderRaindropsLegacySwitch: Switch =
            findViewById(R.id.switch_hide_thunder_raindrops_legacy)
        val legacyBelowFreezingFrostSwitch: Switch =
            findViewById(R.id.switch_legacy_below_freezing_frost)
        val legacyHumidityWaterdropSwitch: Switch =
            findViewById(R.id.switch_legacy_humidity_waterdrop)
        val legacyDelaySnowGroundSwitch: Switch = findViewById(R.id.switch_legacy_delay_snow_ground)
        val legacyClassicWatermarkSwitch: Switch =
            findViewById(R.id.switch_legacy_classic_watermark)
        val showCitySwitch: Switch = findViewById(R.id.switch_show_city_name)
        val showLogoSwitch: Switch = findViewById(R.id.switch_show_legacy_logo)
        val groundParallaxSwitch: Switch = findViewById(R.id.switch_ground_parallax)
        val samsungWeatherSwitch: Switch = findViewById(R.id.switch_use_samsung_weather)
        val syncAeroWeatherRefreshSwitch: Switch =
            findViewById(R.id.switch_sync_aeroweather_refresh)
        val showWeatherDebugTextSwitch: Switch = findViewById(R.id.switch_show_weather_debug_text)
        val texturePackGroup: RadioGroup = findViewById(R.id.radio_texture_pack)
        val dayNightGroup: RadioGroup = findViewById(R.id.radio_day_night_mode)
        val autoSceneButton: Button = findViewById(R.id.btn_force_auto)
        val clearSceneButton: Button = findViewById(R.id.btn_force_clear)
        val cloudySceneButton: Button = findViewById(R.id.btn_force_cloudy)
        val mostlyClearSceneButton: Button = findViewById(R.id.btn_force_mostly_clear)
        val drearySceneButton: Button = findViewById(R.id.btn_force_dreary)
        val fogSceneButton: Button = findViewById(R.id.btn_force_fog)
        val freezingFogSceneButton: Button = findViewById(R.id.btn_force_freezing_fog)
        val rainSceneButton: Button = findViewById(R.id.btn_force_rain)
        val thunderSceneButton: Button = findViewById(R.id.btn_force_thunder)
        val snowSceneButton: Button = findViewById(R.id.btn_force_snow)
        val sleetSceneButton: Button = findViewById(R.id.btn_force_sleet)
        this.weatherDebugText = findViewById(R.id.text_weather_debug_info)

        permissionButton.setOnClickListener({ v -> requestLocationPermissionIfNeeded() })
        locationButton.setOnClickListener({ v -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) })
        refreshButton.setOnClickListener({ v ->
            Toast.makeText(this, R.string.settings_weather_refresh_started, Toast.LENGTH_SHORT)
                .show()
            val selectedSourceMode: String? = if (samsungWeatherSwitch.isChecked)
                SecretWallpaperService.WEATHER_SOURCE_SAMSUNG_DEVICE
            else
                SecretWallpaperService.WEATHER_SOURCE_OPEN_METEO
            WeatherDataCoordinator.refreshAsync(
                this,
                selectedSourceMode,
                { snapshot ->
                    if (snapshot == null || snapshot.weatherCode === WeatherSnapshot.UNKNOWN_WEATHER_CODE) {
                        Toast.makeText(
                            this,
                            R.string.settings_weather_refresh_failed,
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        val refreshIntent: Intent =
                            Intent(SecretWallpaperService.ACTION_FORCE_WEATHER_REFRESH)
                        refreshIntent.setPackage(packageName)
                        sendBroadcast(refreshIntent)
                        val toastRes: Int =
                            if (samsungWeatherSwitch.isChecked && isOpenMeteoFallbackSource(
                                    snapshot
                                )
                            )
                                R.string.settings_weather_source_fallback
                            else
                                R.string.settings_weather_refreshed
                        Toast.makeText(this, toastRes, Toast.LENGTH_SHORT).show()
                    }
                    refreshStatus()
                }
            )
        })
        closeButton.setOnClickListener({ v -> finish() })

        val configuredWeatherSource = normalizeWeatherSourceMode(
            this.prefs.getString(
                SecretWallpaperService.PREF_KEY_WEATHER_SOURCE_MODE,
                SecretWallpaperService.WEATHER_SOURCE_OPEN_METEO
            )
        )
        samsungWeatherSwitch.setChecked(
            SecretWallpaperService.WEATHER_SOURCE_SAMSUNG_DEVICE.equals(
                configuredWeatherSource
            )
        )
        samsungWeatherSwitch.setOnCheckedChangeListener({ buttonView, isChecked ->
            val sourceMode: String? = if (isChecked)
                SecretWallpaperService.WEATHER_SOURCE_SAMSUNG_DEVICE
            else
                SecretWallpaperService.WEATHER_SOURCE_OPEN_METEO
            val intent: Intent = Intent(SecretWallpaperService.ACTION_SET_WEATHER_SOURCE_MODE)
            intent.setPackage(packageName)
            intent.putExtra(SecretWallpaperService.EXTRA_WEATHER_SOURCE_MODE, sourceMode)
            sendBroadcast(intent)

            val toastRes: Int
            if (!isChecked) {
                toastRes = R.string.settings_weather_source_open_meteo_on
            } else if (!hasSamsungWeatherPermission()) {
                requestSamsungWeatherPermissionIfNeeded()
                toastRes = R.string.settings_weather_permission_requesting
            } else if (!WeatherDataCoordinator.isSamsungLikelyAvailable(this)) {
                toastRes = R.string.settings_weather_source_fallback
            } else {
                toastRes = R.string.settings_weather_source_samsung_on
            }
            Toast.makeText(this, toastRes, Toast.LENGTH_SHORT).show()
            refreshStatus()
        })

        val syncAeroWeatherRefresh: Boolean = this.prefs.getBoolean(
            SecretWallpaperService.PREF_KEY_SYNC_WITH_AEROWEATHER_REFRESH,
            SecretWallpaperService.AEROWEATHER_REFRESH_SYNC_DEFAULT
        )
        syncAeroWeatherRefreshSwitch.setChecked(syncAeroWeatherRefresh)
        syncAeroWeatherRefreshSwitch.setOnCheckedChangeListener({ buttonView, isChecked ->
            val intent: Intent = Intent(SecretWallpaperService.ACTION_SET_AEROWEATHER_REFRESH_SYNC)
            intent.setPackage(packageName)
            intent.putExtra(
                SecretWallpaperService.EXTRA_AEROWEATHER_REFRESH_SYNC_ENABLED,
                isChecked
            )
            sendBroadcast(intent)

            val toastRes: Int
            if (isChecked && !hasSamsungWeatherPermission()) {
                requestSamsungWeatherPermissionIfNeeded()
                toastRes = R.string.settings_weather_permission_requesting
            } else {
                toastRes = if (isChecked)
                    R.string.settings_sync_aeroweather_refresh_on
                else
                    R.string.settings_sync_aeroweather_refresh_off
            }
            Toast.makeText(this, toastRes, Toast.LENGTH_SHORT).show()
            refreshStatus()
        })

        val showWeatherDebugText: Boolean = this.prefs.getBoolean(
            com.BalancedLight.WindyWeather.SecretWallpaperSetting.Companion.PREF_KEY_SHOW_WEATHER_DEBUG_TEXT,
            false
        )
        showWeatherDebugTextSwitch.setChecked(showWeatherDebugText)
        setWeatherDebugVisibility(showWeatherDebugText)
        showWeatherDebugTextSwitch.setOnCheckedChangeListener({ buttonView, isChecked ->
            this.prefs.edit().putBoolean(
                com.BalancedLight.WindyWeather.SecretWallpaperSetting.Companion.PREF_KEY_SHOW_WEATHER_DEBUG_TEXT,
                isChecked
            ).apply()
            setWeatherDebugVisibility(isChecked)
            if (isChecked) {
                updateWeatherDebugText()
            }
        })

        val refreshIntervalAdapter: ArrayAdapter<CharSequence?> = ArrayAdapter.createFromResource(
            this,
            R.array.settings_weather_refresh_interval_labels,
            android.R.layout.simple_spinner_item
        )
        refreshIntervalAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        weatherRefreshSpinner.setAdapter(refreshIntervalAdapter)
        val currentRefreshMinutes: Int = this.prefs.getInt(
            SecretWallpaperService.PREF_KEY_WEATHER_REFRESH_INTERVAL_MINUTES,
            SecretWallpaperService.WEATHER_REFRESH_DEFAULT_MINUTES
        )
        val normalizedRefreshMinutes = normalizeRefreshInterval(currentRefreshMinutes)
        val selectedRefreshMinutes = intArrayOf(normalizedRefreshMinutes)
        val selectedRefreshIndex = refreshIntervalIndexForMinutes(normalizedRefreshMinutes)
        weatherRefreshSpinner.setSelection(selectedRefreshIndex, false)
        weatherRefreshSpinner.setOnItemSelectedListener(object : OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                val selectedMinutes: Int =
                    com.BalancedLight.WindyWeather.SecretWallpaperSetting.Companion.WEATHER_REFRESH_INTERVAL_MINUTES[position]
                if (selectedMinutes == selectedRefreshMinutes[0]) {
                    return
                }
                val intent: Intent =
                    Intent(SecretWallpaperService.ACTION_SET_WEATHER_REFRESH_INTERVAL)
                intent.setPackage(packageName)
                intent.putExtra(
                    SecretWallpaperService.EXTRA_WEATHER_REFRESH_INTERVAL_MINUTES,
                    selectedMinutes
                )
                sendBroadcast(intent)
                selectedRefreshMinutes[0] = selectedMinutes
                Toast.makeText(
                    this@SecretWallpaperSetting,
                    R.string.settings_weather_refresh_interval_updated,
                    Toast.LENGTH_SHORT
                ).show()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        })

        val fpsRange: Int =
            SecretWallpaperService.TARGET_FPS_MAX - SecretWallpaperService.TARGET_FPS_MIN
        targetFpsSeekBar.setMax(fpsRange)
        powerSaveFpsSeekBar.setMax(fpsRange)
        val currentTargetFps = normalizeFrameRate(
            this.prefs.getInt(
                SecretWallpaperService.PREF_KEY_TARGET_FPS,
                SecretWallpaperService.TARGET_FPS_DEFAULT
            )
        )
        var currentPowerSaveFps = normalizeFrameRate(
            this.prefs.getInt(
                SecretWallpaperService.PREF_KEY_POWER_SAVE_TARGET_FPS,
                SecretWallpaperService.TARGET_FPS_POWER_SAVE_DEFAULT
            )
        )
        if (currentPowerSaveFps > currentTargetFps) {
            currentPowerSaveFps = currentTargetFps
        }
        targetFpsSeekBar.setProgress(currentTargetFps - SecretWallpaperService.TARGET_FPS_MIN)
        powerSaveFpsSeekBar.setProgress(currentPowerSaveFps - SecretWallpaperService.TARGET_FPS_MIN)
        targetFpsValueText.setText(
            String.format(
                Locale.getDefault(),
                getString(R.string.settings_target_fps_value),
                currentTargetFps
            )
        )
        powerSaveFpsValueText.setText(
            String.format(
                Locale.getDefault(),
                getString(R.string.settings_power_save_fps_value),
                currentPowerSaveFps
            )
        )

        val selectedTargetFps = intArrayOf(currentTargetFps)
        val selectedPowerSaveFps = intArrayOf(currentPowerSaveFps)

        targetFpsSeekBar.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val fps: Int = SecretWallpaperService.TARGET_FPS_MIN + progress
                if (fps < selectedPowerSaveFps[0]) {
                    selectedPowerSaveFps[0] = fps
                    powerSaveFpsSeekBar.setProgress(fps - SecretWallpaperService.TARGET_FPS_MIN)
                    powerSaveFpsValueText.setText(
                        String.format(
                            Locale.getDefault(),
                            getString(R.string.settings_power_save_fps_value),
                            fps
                        )
                    )
                }
                selectedTargetFps[0] = fps
                targetFpsValueText.setText(
                    String.format(
                        Locale.getDefault(),
                        getString(R.string.settings_target_fps_value),
                        fps
                    )
                )
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val intent: Intent = Intent(SecretWallpaperService.ACTION_SET_TARGET_FPS)
                intent.setPackage(packageName)
                intent.putExtra(SecretWallpaperService.EXTRA_TARGET_FPS, selectedTargetFps[0])
                sendBroadcast(intent)
                val powerSaveIntent: Intent =
                    Intent(SecretWallpaperService.ACTION_SET_POWER_SAVE_TARGET_FPS)
                powerSaveIntent.setPackage(packageName)
                powerSaveIntent.putExtra(
                    SecretWallpaperService.EXTRA_POWER_SAVE_TARGET_FPS,
                    selectedPowerSaveFps[0]
                )
                sendBroadcast(powerSaveIntent)
                Toast.makeText(
                    this@SecretWallpaperSetting,
                    R.string.settings_target_fps_updated,
                    Toast.LENGTH_SHORT
                ).show()
            }
        })

        powerSaveFpsSeekBar.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                var fps: Int = SecretWallpaperService.TARGET_FPS_MIN + progress
                if (fps > selectedTargetFps[0]) {
                    fps = selectedTargetFps[0]
                    seekBar.setProgress(fps - SecretWallpaperService.TARGET_FPS_MIN)
                }
                selectedPowerSaveFps[0] = fps
                powerSaveFpsValueText.setText(
                    String.format(
                        Locale.getDefault(),
                        getString(R.string.settings_power_save_fps_value),
                        fps
                    )
                )
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val intent: Intent = Intent(SecretWallpaperService.ACTION_SET_POWER_SAVE_TARGET_FPS)
                intent.setPackage(packageName)
                intent.putExtra(
                    SecretWallpaperService.EXTRA_POWER_SAVE_TARGET_FPS,
                    selectedPowerSaveFps[0]
                )
                sendBroadcast(intent)
                Toast.makeText(
                    this@SecretWallpaperSetting,
                    R.string.settings_power_save_fps_updated,
                    Toast.LENGTH_SHORT
                ).show()
            }
        })

        val frameRateDependentAnimation: Boolean = this.prefs.getBoolean(
            SecretWallpaperService.PREF_KEY_FRAME_RATE_DEPENDENT_ANIMATION,
            SecretWallpaperService.FRAME_RATE_DEPENDENT_ANIMATION_DEFAULT
        )
        frameRateDependentAnimationSwitch.setChecked(frameRateDependentAnimation)
        frameRateDependentAnimationSwitch.setOnCheckedChangeListener({ buttonView, isChecked ->
            val intent: Intent =
                Intent(SecretWallpaperService.ACTION_SET_FRAME_RATE_DEPENDENT_ANIMATION)
            intent.setPackage(packageName)
            intent.putExtra(
                SecretWallpaperService.EXTRA_FRAME_RATE_DEPENDENT_ANIMATION_ENABLED,
                isChecked
            )
            sendBroadcast(intent)
            Toast.makeText(
                this,
                if (isChecked)
                    R.string.settings_frame_rate_dependent_animation_on
                else
                    R.string.settings_frame_rate_dependent_animation_off,
                Toast.LENGTH_SHORT
            ).show()
        })

        val oldNightEffectEnabled: Boolean =
            this.prefs.getBoolean(SecretWallpaperService.PREF_KEY_OLD_NIGHT_EFFECT, false)
        oldNightEffectSwitch.setChecked(oldNightEffectEnabled)
        oldNightEffectSwitch.setOnCheckedChangeListener({ buttonView, isChecked ->
            val intent: Intent = Intent(SecretWallpaperService.ACTION_DEBUG_SET_OLD_NIGHT_EFFECT)
            intent.setPackage(packageName)
            intent.putExtra(SecretWallpaperService.EXTRA_OLD_NIGHT_EFFECT_ENABLED, isChecked)
            sendBroadcast(intent)
            Toast.makeText(
                this,
                if (isChecked) R.string.settings_old_night_effect_on else R.string.settings_old_night_effect_off,
                Toast.LENGTH_SHORT
            ).show()
        })

        val hideThunderRaindropsLegacy: Boolean = this.prefs.getBoolean(
            SecretWallpaperService.PREF_KEY_HIDE_THUNDER_RAINDROPS_LEGACY,
            false
        )
        hideThunderRaindropsLegacySwitch.setChecked(hideThunderRaindropsLegacy)
        hideThunderRaindropsLegacySwitch.setOnCheckedChangeListener({ buttonView, isChecked ->
            val intent: Intent =
                Intent(SecretWallpaperService.ACTION_DEBUG_SET_HIDE_THUNDER_RAINDROPS_LEGACY)
            intent.setPackage(packageName)
            intent.putExtra(SecretWallpaperService.EXTRA_HIDE_THUNDER_RAINDROPS_LEGACY, isChecked)
            sendBroadcast(intent)
            Toast.makeText(
                this,
                if (isChecked) R.string.settings_hide_thunder_raindrops_legacy_on else R.string.settings_hide_thunder_raindrops_legacy_off,
                Toast.LENGTH_SHORT
            ).show()
        })

        val legacyBelowFreezingFrost: Boolean =
            this.prefs.getBoolean(SecretWallpaperService.PREF_KEY_LEGACY_FREEZING_FROST, true)
        legacyBelowFreezingFrostSwitch.setChecked(!legacyBelowFreezingFrost)
        legacyBelowFreezingFrostSwitch.setOnCheckedChangeListener({ buttonView, isChecked ->
            val intent: Intent = Intent(SecretWallpaperService.ACTION_SET_LEGACY_FREEZING_FROST)
            intent.setPackage(packageName)
            intent.putExtra(SecretWallpaperService.EXTRA_LEGACY_FREEZING_FROST_ENABLED, !isChecked)
            sendBroadcast(intent)
            Toast.makeText(
                this,
                if (isChecked) R.string.settings_legacy_below_freezing_frost_on else R.string.settings_legacy_below_freezing_frost_off,
                Toast.LENGTH_SHORT
            ).show()
        })

        val legacyHumidityWaterdrop: Boolean =
            this.prefs.getBoolean(SecretWallpaperService.PREF_KEY_LEGACY_HUMIDITY_WATERDROP, true)
        legacyHumidityWaterdropSwitch.setChecked(!legacyHumidityWaterdrop)
        legacyHumidityWaterdropSwitch.setOnCheckedChangeListener({ buttonView, isChecked ->
            val intent: Intent = Intent(SecretWallpaperService.ACTION_SET_LEGACY_HUMIDITY_WATERDROP)
            intent.setPackage(packageName)
            intent.putExtra(
                SecretWallpaperService.EXTRA_LEGACY_HUMIDITY_WATERDROP_ENABLED,
                !isChecked
            )
            sendBroadcast(intent)
            Toast.makeText(
                this,
                if (isChecked) R.string.settings_legacy_humidity_waterdrop_on else R.string.settings_legacy_humidity_waterdrop_off,
                Toast.LENGTH_SHORT
            ).show()
        })

        val legacyDelaySnowGround: Boolean =
            this.prefs.getBoolean(SecretWallpaperService.PREF_KEY_LEGACY_DELAY_SNOW_GROUND, true)
        legacyDelaySnowGroundSwitch.setChecked(!legacyDelaySnowGround)
        legacyDelaySnowGroundSwitch.setOnCheckedChangeListener({ buttonView, isChecked ->
            val intent: Intent = Intent(SecretWallpaperService.ACTION_SET_LEGACY_DELAY_SNOW_GROUND)
            intent.setPackage(packageName)
            intent.putExtra(
                SecretWallpaperService.EXTRA_LEGACY_DELAY_SNOW_GROUND_ENABLED,
                !isChecked
            )
            sendBroadcast(intent)
            Toast.makeText(
                this,
                if (isChecked) R.string.settings_legacy_delay_snow_ground_on else R.string.settings_legacy_delay_snow_ground_off,
                Toast.LENGTH_SHORT
            ).show()
        })

        val legacyClassicWatermark: Boolean =
            this.prefs.getBoolean(SecretWallpaperService.PREF_KEY_LEGACY_CLASSIC_WATERMARK, false)
        legacyClassicWatermarkSwitch.setChecked(legacyClassicWatermark)
        legacyClassicWatermarkSwitch.setOnCheckedChangeListener({ buttonView, isChecked ->
            val intent: Intent = Intent(SecretWallpaperService.ACTION_SET_LEGACY_CLASSIC_WATERMARK)
            intent.setPackage(packageName)
            intent.putExtra(
                SecretWallpaperService.EXTRA_LEGACY_CLASSIC_WATERMARK_ENABLED,
                isChecked
            )
            sendBroadcast(intent)
            Toast.makeText(
                this,
                if (isChecked) R.string.settings_legacy_classic_watermark_on else R.string.settings_legacy_classic_watermark_off,
                Toast.LENGTH_SHORT
            ).show()
        })

        val showCity: Boolean =
            this.prefs.getBoolean(SecretWallpaperService.PREF_KEY_SHOW_CITY_NAME, true)
        showCitySwitch.setChecked(showCity)
        showCitySwitch.setOnCheckedChangeListener({ buttonView, isChecked ->
            val intent: Intent = Intent(SecretWallpaperService.ACTION_DEBUG_SET_CITY_NAME_VISIBLE)
            intent.setPackage(packageName)
            intent.putExtra(SecretWallpaperService.EXTRA_CITY_NAME_VISIBLE, isChecked)
            sendBroadcast(intent)
            Toast.makeText(
                this,
                if (isChecked) R.string.settings_debug_city_name_on else R.string.settings_debug_city_name_off,
                Toast.LENGTH_SHORT
            ).show()
        })

        val showLogo: Boolean =
            this.prefs.getBoolean(SecretWallpaperService.PREF_KEY_SHOW_LEGACY_LOGO, false)
        showLogoSwitch.setChecked(showLogo)
        showLogoSwitch.setOnCheckedChangeListener({ buttonView, isChecked ->
            val intent: Intent = Intent(SecretWallpaperService.ACTION_DEBUG_SET_LEGACY_LOGO_VISIBLE)
            intent.setPackage(packageName)
            intent.putExtra(SecretWallpaperService.EXTRA_LEGACY_LOGO_VISIBLE, isChecked)
            sendBroadcast(intent)
            Toast.makeText(
                this,
                if (isChecked) R.string.settings_debug_logo_on else R.string.settings_debug_logo_off,
                Toast.LENGTH_SHORT
            ).show()
        })

        val groundParallaxEnabled: Boolean = this.prefs.getBoolean(
            SecretWallpaperService.PREF_KEY_GROUND_PARALLAX,
            SecretWallpaperService.GROUND_PARALLAX_DEFAULT
        )
        groundParallaxSwitch.setChecked(groundParallaxEnabled)
        groundParallaxSwitch.setOnCheckedChangeListener({ buttonView, isChecked ->
            val intent: Intent = Intent(SecretWallpaperService.ACTION_SET_GROUND_PARALLAX)
            intent.setPackage(packageName)
            intent.putExtra(SecretWallpaperService.EXTRA_GROUND_PARALLAX_ENABLED, isChecked)
            sendBroadcast(intent)
            Toast.makeText(
                this,
                if (isChecked) R.string.settings_ground_parallax_on else R.string.settings_ground_parallax_off,
                Toast.LENGTH_SHORT
            ).show()
        })

        val texturePack: String? = this.prefs.getString(
            SecretWallpaperService.PREF_KEY_TEXTURE_PACK,
            SecretWallpaperService.TEXTURE_PACK_HQ
        )
        if (SecretWallpaperService.TEXTURE_PACK_LEGACY.equals(texturePack)) {
            texturePackGroup.check(R.id.rb_texture_pack_legacy)
        } else {
            texturePackGroup.check(R.id.rb_texture_pack_hq)
        }
        texturePackGroup.setOnCheckedChangeListener({ group, checkedId ->
            val pack: String? = if (checkedId === R.id.rb_texture_pack_legacy)
                SecretWallpaperService.TEXTURE_PACK_LEGACY
            else
                SecretWallpaperService.TEXTURE_PACK_HQ
            val intent: Intent = Intent(SecretWallpaperService.ACTION_SET_TEXTURE_PACK)
            intent.setPackage(packageName)
            intent.putExtra(SecretWallpaperService.EXTRA_TEXTURE_PACK, pack)
            sendBroadcast(intent)
            Toast.makeText(
                this,
                if (SecretWallpaperService.TEXTURE_PACK_LEGACY.equals(pack))
                    R.string.settings_texture_pack_applied_legacy
                else
                    R.string.settings_texture_pack_applied_hq,
                Toast.LENGTH_SHORT
            ).show()
        })

        val dayNightMode: Int = this.prefs.getInt(
            SecretWallpaperService.PREF_KEY_DEBUG_DAY_NIGHT_MODE,
            SecretWallpaperService.DAY_NIGHT_MODE_AUTO
        )
        when (dayNightMode) {
            SecretWallpaperService.DAY_NIGHT_MODE_FORCE_DAY -> dayNightGroup.check(R.id.rb_day_night_day)
            SecretWallpaperService.DAY_NIGHT_MODE_FORCE_NIGHT -> dayNightGroup.check(R.id.rb_day_night_night)
            else -> dayNightGroup.check(R.id.rb_day_night_auto)
        }
        dayNightGroup.setOnCheckedChangeListener({ group, checkedId ->
            var mode: Int = SecretWallpaperService.DAY_NIGHT_MODE_AUTO
            if (checkedId === R.id.rb_day_night_day) {
                mode = SecretWallpaperService.DAY_NIGHT_MODE_FORCE_DAY
            } else if (checkedId === R.id.rb_day_night_night) {
                mode = SecretWallpaperService.DAY_NIGHT_MODE_FORCE_NIGHT
            }
            val intent: Intent = Intent(SecretWallpaperService.ACTION_DEBUG_SET_DAY_NIGHT_MODE)
            intent.setPackage(packageName)
            intent.putExtra(SecretWallpaperService.EXTRA_DAY_NIGHT_MODE, mode)
            sendBroadcast(intent)
            Toast.makeText(this, R.string.settings_debug_day_night_updated, Toast.LENGTH_SHORT)
                .show()
        })

        autoSceneButton.setOnClickListener({ v ->
            sendForcedWeatherCode(WeatherSnapshot.UNKNOWN_WEATHER_CODE)
            sendForcedScene(-1)
            val refreshIntent: Intent = Intent(SecretWallpaperService.ACTION_FORCE_WEATHER_REFRESH)
            refreshIntent.setPackage(packageName)
            sendBroadcast(refreshIntent)
            Toast.makeText(this, R.string.settings_debug_scene_auto, Toast.LENGTH_SHORT).show()
        })
        clearSceneButton.setOnClickListener({ v ->
            forceScenePreset(
                SecretWallpaperService.WeatherConditions.D1_CLEAR.ordinal,
                WeatherSnapshot.UNKNOWN_WEATHER_CODE
            )
        })
        cloudySceneButton.setOnClickListener({ v ->
            forceScenePreset(
                SecretWallpaperService.WeatherConditions.D2_CLOUDY.ordinal,
                WeatherSnapshot.UNKNOWN_WEATHER_CODE
            )
        })
        mostlyClearSceneButton.setOnClickListener({ v ->
            forceScenePreset(
                SecretWallpaperService.WeatherConditions.D10_MOSTLY_CLEAR.ordinal,
                WeatherSnapshot.UNKNOWN_WEATHER_CODE
            )
        })
        drearySceneButton.setOnClickListener({ v ->
            forceScenePreset(
                SecretWallpaperService.WeatherConditions.D3_DREARY.ordinal,
                WeatherSnapshot.UNKNOWN_WEATHER_CODE
            )
        })
        fogSceneButton.setOnClickListener({ v ->
            forceScenePreset(
                SecretWallpaperService.WeatherConditions.D4_FOG.ordinal,
                WeatherSnapshot.UNKNOWN_WEATHER_CODE
            )
        })
        // Freezing fog visual preset: fog scene + Open-Meteo code 48.
        freezingFogSceneButton.setOnClickListener({ v ->
            forceScenePreset(
                SecretWallpaperService.WeatherConditions.D4_FOG.ordinal,
                48
            )
        })
        rainSceneButton.setOnClickListener({ v ->
            forceScenePreset(
                SecretWallpaperService.WeatherConditions.D5_RAIN_SHOWERS.ordinal,
                WeatherSnapshot.UNKNOWN_WEATHER_CODE
            )
        })
        thunderSceneButton.setOnClickListener({ v ->
            forceScenePreset(
                SecretWallpaperService.WeatherConditions.D6_THUNDERSTORMS.ordinal,
                WeatherSnapshot.UNKNOWN_WEATHER_CODE
            )
        })
        snowSceneButton.setOnClickListener({ v ->
            forceScenePreset(
                SecretWallpaperService.WeatherConditions.D7_FLURRIES_SNOW.ordinal,
                WeatherSnapshot.UNKNOWN_WEATHER_CODE
            )
        })
        sleetSceneButton.setOnClickListener({ v ->
            forceScenePreset(
                SecretWallpaperService.WeatherConditions.D9_SLEET.ordinal,
                WeatherSnapshot.UNKNOWN_WEATHER_CODE
            )
        })

        refreshStatus()
    }

    private fun sendForcedScene(sceneOrdinal: Int) {
        val intent: Intent = Intent(SecretWallpaperService.ACTION_DEBUG_SET_FORCED_SCENE)
        intent.setPackage(packageName)
        intent.putExtra(SecretWallpaperService.EXTRA_DEBUG_FORCED_SCENE, sceneOrdinal)
        sendBroadcast(intent)
        if (sceneOrdinal >= 0) {
            Toast.makeText(this, R.string.settings_debug_scene_applied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendForcedWeatherCode(weatherCode: Int) {
        val intent: Intent = Intent(SecretWallpaperService.ACTION_DEBUG_SET_FORCED_WEATHER_CODE)
        intent.setPackage(packageName)
        intent.putExtra(SecretWallpaperService.EXTRA_DEBUG_FORCED_WEATHER_CODE, weatherCode)
        sendBroadcast(intent)
    }

    private fun forceScenePreset(sceneOrdinal: Int, weatherCodeOverride: Int) {
        sendForcedWeatherCode(weatherCodeOverride)
        sendForcedScene(sceneOrdinal)
    }

    private fun normalizeRefreshInterval(minutes: Int): Int {
        if (minutes == SecretWallpaperService.WEATHER_REFRESH_OFF_MINUTES) {
            return SecretWallpaperService.WEATHER_REFRESH_OFF_MINUTES
        }
        if (minutes < SecretWallpaperService.WEATHER_REFRESH_MIN_MINUTES) {
            return SecretWallpaperService.WEATHER_REFRESH_MIN_MINUTES
        }
        return Math.min(minutes, SecretWallpaperService.WEATHER_REFRESH_MAX_MINUTES)
    }

    private fun normalizeFrameRate(fps: Int): Int {
        if (fps < SecretWallpaperService.TARGET_FPS_MIN) {
            return SecretWallpaperService.TARGET_FPS_MIN
        }
        return Math.min(fps, SecretWallpaperService.TARGET_FPS_MAX)
    }

    private fun refreshIntervalIndexForMinutes(minutes: Int): Int {
        for (i in com.BalancedLight.WindyWeather.SecretWallpaperSetting.Companion.WEATHER_REFRESH_INTERVAL_MINUTES.indices) {
            if (com.BalancedLight.WindyWeather.SecretWallpaperSetting.Companion.WEATHER_REFRESH_INTERVAL_MINUTES[i] == minutes) {
                return i
            }
        }
        return 1
    }

    private fun normalizeWeatherSourceMode(sourceMode: String?): String {
        return if (SecretWallpaperService.WEATHER_SOURCE_SAMSUNG_DEVICE.equals(sourceMode))
            SecretWallpaperService.WEATHER_SOURCE_SAMSUNG_DEVICE
        else
            SecretWallpaperService.WEATHER_SOURCE_OPEN_METEO
    }

    private fun isOpenMeteoFallbackSource(snapshot: WeatherSnapshot?): Boolean {
        if (snapshot == null || snapshot.codeSource == null) {
            return false
        }
        return WeatherSnapshot.SOURCE_OPEN_METEO_FALLBACK.equals(snapshot.codeSource)
    }

    private fun hasSamsungWeatherPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            SamsungWeatherRepository.PERMISSION_READ_DANGEROUS_PROVIDER
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestSamsungWeatherPermissionIfNeeded() {
        if (hasSamsungWeatherPermission()) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(
                arrayOf<String?>(SamsungWeatherRepository.PERMISSION_READ_DANGEROUS_PROVIDER),
                com.BalancedLight.WindyWeather.SecretWallpaperSetting.Companion.REQUEST_SAMSUNG_WEATHER_PERMISSION
            )
        }
    }

    private fun requestLocationPermissionIfNeeded() {
        if (hasLocationPermission()) {
            Toast.makeText(this, R.string.settings_permission_already_granted, Toast.LENGTH_SHORT)
                .show()
            refreshStatus()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(
                arrayOf<String?>(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ),
                com.BalancedLight.WindyWeather.SecretWallpaperSetting.Companion.REQUEST_LOCATION_PERMISSION
            )
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun refreshStatus() {
        if (this.locationStatus == null) {
            return
        }
        val locationText: String? = getString(
            if (hasLocationPermission())
                R.string.settings_status_location_granted
            else
                R.string.settings_status_location_missing
        )
        val weatherText: String? = getString(
            if (hasSamsungWeatherPermission())
                R.string.settings_status_weather_granted
            else
                R.string.settings_status_weather_missing
        )
        this.locationStatus?.text = locationText.toString() + "\n" + weatherText
        updateWeatherDebugText()
    }

    private fun setWeatherDebugVisibility(visible: Boolean) {
        if (this.weatherDebugText == null) {
            return
        }
        this.weatherDebugText?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun updateWeatherDebugText() {
        if (this.weatherDebugText == null) {
            return
        }
        val showDebug: Boolean = this.prefs.getBoolean(
            com.BalancedLight.WindyWeather.SecretWallpaperSetting.Companion.PREF_KEY_SHOW_WEATHER_DEBUG_TEXT,
            false
        )
        if (!showDebug) {
            this.weatherDebugText?.text = ""
            return
        }

        val snapshot: WeatherSnapshot? = WeatherDataCoordinator.readFromCache(this)
        val configuredSourceMode = normalizeWeatherSourceMode(
            this.prefs.getString(
                SecretWallpaperService.PREF_KEY_WEATHER_SOURCE_MODE,
                SecretWallpaperService.WEATHER_SOURCE_OPEN_METEO
            )
        )
        val codeSource: String? = if (snapshot != null && snapshot.codeSource != null)
            snapshot.codeSource
        else
            WeatherSnapshot.SOURCE_UNKNOWN

        val samsungSuccess = WeatherSnapshot.SOURCE_SAMSUNG.equals(codeSource)
                || WeatherSnapshot.SOURCE_SAMSUNG_WITH_OPEN_METEO_FALLBACK.equals(codeSource)
        val openMeteoSuccess = WeatherSnapshot.SOURCE_CURRENT.equals(codeSource)
                || WeatherSnapshot.SOURCE_DAILY.equals(codeSource)
                || WeatherSnapshot.SOURCE_OPEN_METEO_FALLBACK.equals(codeSource)
                || WeatherSnapshot.SOURCE_SAMSUNG_WITH_OPEN_METEO_FALLBACK.equals(codeSource)

        val weatherCode: Int =
            if (snapshot != null) snapshot.weatherCode else WeatherSnapshot.UNKNOWN_WEATHER_CODE
        val weatherName = describeWeatherCode(weatherCode)
        val lastUpdated = formatTimestamp(if (snapshot != null) snapshot.lastUpdatedMs else 0L)
        val forcedCode: Int = this.prefs.getInt(
            SecretWallpaperService.PREF_KEY_DEBUG_FORCED_WEATHER_CODE,
            WeatherSnapshot.UNKNOWN_WEATHER_CODE
        )
        val forcedScene: Int =
            this.prefs.getInt(SecretWallpaperService.PREF_KEY_DEBUG_FORCED_SCENE, -1)

        val debug: StringBuilder = StringBuilder(512)
        debug.append("Source mode: ").append(configuredSourceMode).append('\n')
        debug.append("AeroWeather sync: ")
            .append(
                this.prefs.getBoolean(
                    SecretWallpaperService.PREF_KEY_SYNC_WITH_AEROWEATHER_REFRESH,
                    SecretWallpaperService.AEROWEATHER_REFRESH_SYNC_DEFAULT
                )
            )
            .append('\n')
        debug.append("Code source: ").append(codeSource).append('\n')
        debug.append("Samsung API success: ").append(samsungSuccess).append('\n')
        debug.append("Open-Meteo success: ").append(openMeteoSuccess).append('\n')
        debug.append("Weather: ").append(weatherName).append(" (id=").append(weatherCode)
            .append(")\n")
        if (snapshot != null) {
            debug.append("Temp C (cur/high/low): ")
                .append(snapshot.currentTempC).append('/')
                .append(snapshot.highTempC).append('/')
                .append(snapshot.lowTempC).append('\n')
            debug.append("Humidity: ").append(snapshot.humidityPercent).append("%\n")
            debug.append("Wind: ").append(String.format(Locale.US, "%.1f", snapshot.windSpeedKmh))
                .append(" km/h\n")
            debug.append("Sunrise/Sunset: ")
                .append(formatHourMinute(snapshot.sunriseTime))
                .append(" / ")
                .append(formatHourMinute(snapshot.sunsetTime))
                .append('\n')
            debug.append("Moon phase index: ").append(snapshot.moonPhase).append('\n')
            debug.append("City: ").append(if (snapshot.cityName == null) "" else snapshot.cityName)
                .append('\n')
            debug.append("Last update: ").append(lastUpdated).append('\n')
        } else {
            debug.append("No cached weather snapshot\n")
        }
        debug.append("Samsung permission: ").append(hasSamsungWeatherPermission()).append('\n')
        debug.append("Samsung provider available: ")
            .append(WeatherDataCoordinator.isSamsungLikelyAvailable(this)).append('\n')
        debug.append("Forced weather code: ").append(forcedCode).append('\n')
        debug.append("Forced scene ordinal: ").append(forcedScene).append('\n')
        debug.append("Cache stale (6h): ")
            .append(WeatherDataCoordinator.isCacheStale(this, 6L * 60L * 60L * 1000L))
        this.weatherDebugText?.text = debug.toString()
    }

    private fun describeWeatherCode(weatherCode: Int): String {
        if (weatherCode == WeatherSnapshot.UNKNOWN_WEATHER_CODE) {
            return "Unknown"
        }
        if (weatherCode == 1) {
            return "Mostly Clear"
        }
        when (weatherCode) {
            0 -> return "Clear"
            2 -> return "Cloudy"
            3 -> return "Dreary"
            45, 48 -> return "Fog"
            51, 53, 55, 61, 63, 65, 80, 81, 82 -> return "Rain Showers"
            56, 57, 66, 67 -> return "Sleet"
            71, 73, 75, 77, 85, 86 -> return "Snow"
            95, 96, 99 -> return "Thunderstorms"
            else -> return "Weather Code " + weatherCode
        }
    }

    private fun formatHourMinute(hhmm: Int): String? {
        val hour = hhmm / 100
        val minute = hhmm % 100
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            return "--:--"
        }
        return String.format(Locale.US, "%02d:%02d", hour, minute)
    }

    private fun formatTimestamp(timestampMs: Long): String? {
        if (timestampMs <= 0L) {
            return "never"
        }
        return SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.getDefault()
        ).format(Date(timestampMs))
    }

    protected override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == com.BalancedLight.WindyWeather.SecretWallpaperSetting.Companion.REQUEST_LOCATION_PERMISSION) {
            refreshStatus()
        } else if (requestCode == com.BalancedLight.WindyWeather.SecretWallpaperSetting.Companion.REQUEST_SAMSUNG_WEATHER_PERMISSION) {
            val granted = hasSamsungWeatherPermission()
            if (granted) {
                broadcastAeroWeatherSyncPreference()
            }
            refreshStatus()
            Toast.makeText(
                this,
                if (granted)
                    R.string.settings_weather_permission_granted
                else
                    R.string.settings_weather_permission_denied,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun broadcastAeroWeatherSyncPreference() {
        val enabled: Boolean = this.prefs.getBoolean(
            SecretWallpaperService.PREF_KEY_SYNC_WITH_AEROWEATHER_REFRESH,
            SecretWallpaperService.AEROWEATHER_REFRESH_SYNC_DEFAULT
        )
        val syncIntent: Intent = Intent(SecretWallpaperService.ACTION_SET_AEROWEATHER_REFRESH_SYNC)
        syncIntent.setPackage(packageName)
        syncIntent.putExtra(SecretWallpaperService.EXTRA_AEROWEATHER_REFRESH_SYNC_ENABLED, enabled)
        sendBroadcast(syncIntent)
    }

    companion object {
        private const val REQUEST_LOCATION_PERMISSION = 1001
        private const val REQUEST_SAMSUNG_WEATHER_PERMISSION = 1002
        private val PREF_NAME = "com.BalancedLight.WindyWeather"
        private val PREF_KEY_SHOW_WEATHER_DEBUG_TEXT = "pref_show_weather_debug_text"
        private val WEATHER_REFRESH_INTERVAL_MINUTES = intArrayOf(10, 15, 30, 60, 180, 360, 0)
    }
}
