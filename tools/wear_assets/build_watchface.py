import json
import os

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
XML = os.path.join(REPO, "wearWatchFace/src/main/res/raw/watchface.xml")
L = json.load(open(os.path.join(REPO, "wear/scene_mapping.json"), encoding="utf-8"))["layout"]

NIGHT = ("(([WEATHER.IS_AVAILABLE] &amp;&amp; !([WEATHER.IS_DAY])) || "
         "(!([WEATHER.IS_AVAILABLE]) &amp;&amp; ([HOUR_0_23] &lt; 7 || [HOUR_0_23] &gt; 18)))")
DAY = "!" + NIGHT
OVERCAST = ("[WEATHER.CONDITION] == 13 || [WEATHER.CONDITION] == 4 || [WEATHER.CONDITION] == 6 || "
            "[WEATHER.CONDITION] == 12 || [WEATHER.CONDITION] == 9 || [WEATHER.CONDITION] == 5 || "
            "[WEATHER.CONDITION] == 7 || [WEATHER.CONDITION] == 11 || [WEATHER.CONDITION] == 10")
OVERCAST_DAY = "[WEATHER.CONDITION] == 3 || " + OVERCAST
CLOUDY_FAMILY = ("[WEATHER.CONDITION] == 2 || [WEATHER.CONDITION] == 15 || " + OVERCAST.replace(
    "[WEATHER.CONDITION] == 13 || ", ""))
CLEAR_FAMILY = ("[WEATHER.CONDITION] == 0 || [WEATHER.CONDITION] == 1 || "
                "[WEATHER.CONDITION] == 8 || [WEATHER.CONDITION] == 14")
SNOW_FAMILY = ("[WEATHER.CONDITION] == 5 || [WEATHER.CONDITION] == 7 || "
               "[WEATHER.CONDITION] == 11 || [WEATHER.CONDITION] == 10")
GREEN_DAY = ("[WEATHER.CONDITION] == 0 || [WEATHER.CONDITION] == 1 || [WEATHER.CONDITION] == 8 || "
             "[WEATHER.CONDITION] == 14 || [WEATHER.CONDITION] == 4 || [WEATHER.CONDITION] == 6 || "
             "[WEATHER.CONDITION] == 12")
GREY_CLOUD = ("[WEATHER.CONDITION] == 13 || [WEATHER.CONDITION] == 3 || "
              "([WEATHER.CONDITION] == 14 &amp;&amp; " + NIGHT + ")")
RATE = "([WEATHER.CONDITION] == 15 ? 48 : 24)"
STAR_ON = "((" + NIGHT + ") &amp;&amp; (" + CLEAR_FAMILY + "))"
SUN_ON = ("((" + DAY + ") &amp;&amp; (" + CLEAR_FAMILY +
          " || [WEATHER.CONDITION] == 2 || [WEATHER.CONDITION] == 15))")
FLARE_ON = "((" + DAY + ") &amp;&amp; (" + CLEAR_FAMILY + "))"
TWINKLE_PHASES = [0.0, 0.28, 0.55, 0.81, 0.14, 0.42, 0.68]

out = []
w = out.append


def rect(r, extra=""):
    return 'x="%d" y="%d" width="%d" height="%d"%s' % (r["x"], r["y"], r["width"], r["height"],
                                                       extra)


def part_image(name, r, resource, indent, alpha=None, transforms=(), extra=""):
    pad = " " * indent
    a = '' if alpha is None else ' alpha="%d"' % alpha
    lines = ['%s<PartImage name="%s" %s%s%s>' % (pad, name, rect(r), extra, a)]
    for target, value in transforms:
        lines.append('%s    <Transform target="%s" value="%s" />' % (pad, target, value))
    lines.append('%s    <Image resource="%s" />' % (pad, resource))
    lines.append('%s</PartImage>' % pad)
    return "\n".join(lines)


def condition(indent, expressions, branches, default):
    pad = " " * indent
    lines = ['%s<Condition>' % pad, '%s    <Expressions>' % pad]
    for name, expr in expressions:
        lines.append('%s        <Expression name="%s">%s</Expression>' % (pad, name, expr))
    lines.append('%s    </Expressions>' % pad)
    for name, body in branches:
        lines.append('%s    <Compare expression="%s">' % (pad, name))
        lines.append(body)
        lines.append('%s    </Compare>' % pad)
    lines.append('%s    <Default>' % pad)
    lines.append(default)
    lines.append('%s    </Default>' % pad)
    lines.append('%s</Condition>' % pad)
    return "\n".join(lines)


w('<WatchFace width="450" height="450" clipShape="CIRCLE">')
w('    <Metadata key="CLOCK_TYPE" value="DIGITAL" />')
w('    <Metadata key="PREVIEW_TIME" value="10:10:30" />')
w('')
w('    <UserConfigurations>')
w('        <BooleanConfiguration id="showTemperature" displayName="@string/cfg_temperature" screenReaderText="@string/sr_temperature" defaultValue="TRUE" />')
w('        <BooleanConfiguration id="showCondition" displayName="@string/cfg_condition" screenReaderText="@string/sr_condition" defaultValue="FALSE" />')
w('        <BooleanConfiguration id="showBranding" displayName="@string/cfg_branding" screenReaderText="@string/sr_branding" defaultValue="FALSE" />')
w('        <ListConfiguration id="clockPosition" displayName="@string/cfg_clock_position" defaultValue="%s">'
  % L["default_clock_position"])
for name in ("top", "center", "bottom", "left", "right"):
    w('            <ListOption id="%s" displayName="@string/cfg_clock_%s" '
      'icon="cfg_clock_%s" screenReaderText="@string/sr_clock_%s" />' % (name, name, name, name))
w('        </ListConfiguration>')
w('        <ColorConfiguration id="clockColour" displayName="@string/cfg_clock_colour" defaultValue="light">')
w('            <ColorOption id="light" displayName="@string/cfg_clock_light" colors="#FFFFFFFF #FFDCE4F0 #99000000" />')
w('            <ColorOption id="dark" displayName="@string/cfg_clock_dark" colors="#FF141A24 #FF2C3644 #66FFFFFF" />')
w('        </ColorConfiguration>')
w('        <Flavors defaultValue="classic">')
for fid, temp, cond, brand, pos in (("classic", "TRUE", "FALSE", "FALSE", "top"),
                                    ("informative", "TRUE", "TRUE", "TRUE", "top"),
                                    ("minimal", "FALSE", "FALSE", "FALSE", "center")):
    w('            <Flavor id="%s" displayName="@string/flavor_%s">' % (fid, fid))
    w('                <Configuration id="showTemperature" optionId="%s" />' % temp)
    w('                <Configuration id="showCondition" optionId="%s" />' % cond)
    w('                <Configuration id="showBranding" optionId="%s" />' % brand)
    w('                <Configuration id="clockPosition" optionId="%s" />' % pos)
    w('                <Configuration id="clockColour" optionId="light" />')
    w('            </Flavor>')
w('        </Flavors>')
w('    </UserConfigurations>')
w('')
w('    <Scene backgroundColor="#ff000000">')
w('')
w('        <Group name="interactive" x="0" y="0" width="450" height="450" alpha="255">')
w('            <Variant mode="AMBIENT" target="alpha" value="0" />')
w('')

w('            <Group name="skyLayer" x="0" y="0" width="450" height="450" alpha="255">')
w('')
w(condition(16,
            [("skyOvercastNight", NIGHT + " &amp;&amp; (" + OVERCAST + ")"),
             ("skyNight", NIGHT),
             ("skyOvercastDay", OVERCAST_DAY)],
            [("skyOvercastNight", part_image("skyOvercastNight", L["sky"], "bg_sky_overcast_night", 24)),
             ("skyNight", part_image("skyClearNight", L["sky"], "bg_sky_clear_night", 24)),
             ("skyOvercastDay", part_image("skyOvercastDay", L["sky"], "bg_sky_overcast_day", 24))],
            part_image("skyClearDay", L["sky"], "bg_sky_clear_day", 24)))
w('')
w(part_image("starField", L["starfield"], "overlay_starfield", 16, alpha=0,
             transforms=[("alpha", "(" + STAR_ON + " ? 255 : 0)")]))
for i, r in enumerate(L["star_twinkles"]):
    phase = TWINKLE_PHASES[i]
    tail = "" if phase == 0.0 else " + %s" % phase
    expr = ("(" + STAR_ON + " ? (40 + 150 * abs(fract([SECOND_MILLISECOND] / 3.75%s) * 2 - 1)) : 0)"
            % tail)
    w(part_image("starTwinkle%d" % i, r, "star_twinkle", 16, alpha=0,
                 transforms=[("alpha", expr)]))
w('')
nightcover_on = ("((" + NIGHT + ") &amp;&amp; ([WEATHER.CONDITION] == 13 || "
                 "[WEATHER.CONDITION] == 3 || [WEATHER.CONDITION] == 4 || "
                 "[WEATHER.CONDITION] == 6 || [WEATHER.CONDITION] == 12 || "
                 "[WEATHER.CONDITION] == 9 || [WEATHER.CONDITION] == 10))")
w(part_image("nightCover", L["nightcover"], "overlay_nightcover", 16, alpha=0,
             transforms=[("alpha", "(" + nightcover_on + " ? 255 : 0)")]))
w('')
w(part_image("sunFlare", L["sun_flare"], "sun_flare", 16, alpha=0,
             transforms=[("alpha", "(" + FLARE_ON + " ? 86 : 0)")]))
w(part_image("sunRays", L["sun_rays"], "sun_rays", 16, alpha=0,
             extra=' pivotX="0.5" pivotY="0.5" angle="0"',
             transforms=[("angle", "[SECOND_MILLISECOND] * 6"),
                         ("alpha", "(" + SUN_ON + " ? 140 : 0)")]))
w(part_image("sunCore", L["sun_core"], "sun_core", 16, alpha=0,
             transforms=[("alpha", "(" + SUN_ON + " ? 107 : 0)")]))
w('')
w('                <Group name="moonGroup" x="0" y="0" width="450" height="450" alpha="0">')
w('                    <Transform target="alpha" value="(%s &amp;&amp; [MOON_PHASE_TYPE] &gt; 0 ? 255 : 0)" />'
  % STAR_ON)
moon_names = {1: "moonEveningCrescent", 2: "moonFirstQuarter", 3: "moonWaxingGibbous",
              5: "moonWaningGibbous", 6: "moonLastQuarter", 7: "moonMorningCrescent"}
w(condition(20,
            [("moon%d" % p, "[MOON_PHASE_TYPE] == %d" % p) for p in sorted(moon_names)],
            [("moon%d" % p, part_image(moon_names[p], L["moon"], "moon_p%d" % p, 28))
             for p in sorted(moon_names)],
            part_image("moonFull", L["moon"], "moon_p4", 28)))
w('                </Group>')
w('            </Group>')
w('')

w('            <Group name="cloudLayer" x="0" y="0" width="450" height="450" alpha="255">')
w('')
for slot in ("a", "b"):
    up = slot.upper()
    over = L["clouds"]["%s_overcast" % slot]
    light = L["clouds"]["%s_light" % slot]
    windy_over = "([WEATHER.CONDITION] == 15 ? %d : %d)" % (over["drift"] // 2, over["drift"])
    drift_over = ("0 - %d + fract([SECONDS_IN_DAY] / %s) * %d"
                  % (over["width"], windy_over, 450 + over["width"]))
    drift_over_fixed = ("0 - %d + fract([SECONDS_IN_DAY] / %d) * %d"
                        % (over["width"], over["drift"], 450 + over["width"]))
    drift_light = ("0 - %d + fract([SECONDS_IN_DAY] / %d) * %d"
                   % (light["width"], light["drift"], 450 + light["width"]))
    grey_alpha = "((%s) ? %d : %d)" % (NIGHT, 115 if slot == "a" else 153,
                                       230 if slot == "a" else 128)
    day_alpha = ("(([WEATHER.CONDITION] == 0 || [WEATHER.CONDITION] == 1 || "
                 "[WEATHER.CONDITION] == 8) ? 0 : %d)" % (230 if slot == "a" else 128))
    w(condition(16,
                [("cloud%sNight" % up, NIGHT + " &amp;&amp; (" + CLOUDY_FAMILY + ")"),
                 ("cloud%sGrey" % up, GREY_CLOUD),
                 ("cloud%sLight" % up, "[WEATHER.CONDITION] == 14")],
                [("cloud%sNight" % up,
                  part_image("cloud%sNight" % up, over, "cloud_%s_night" % slot, 24, alpha=64,
                             transforms=[("x", drift_over)])),
                 ("cloud%sGrey" % up,
                  part_image("cloud%sGrey" % up, over, "cloud_%s_grey" % slot, 24, alpha=128,
                             transforms=[("x", drift_over_fixed), ("alpha", grey_alpha)])),
                 ("cloud%sLight" % up,
                  part_image("cloud%sLight" % up, light, "cloud_%s_light" % slot, 24,
                             alpha=77 if slot == "a" else 115,
                             transforms=[("x", drift_light)]))],
                part_image("cloud%sDay" % up, over, "cloud_%s_day" % slot, 24, alpha=0,
                           transforms=[("x", drift_over), ("alpha", day_alpha)])))
    w('')
w('            </Group>')
w('')

by_distance = {0: [], 1: [], 2: []}
for mill in L["windmills"]:
    by_distance[mill["distance"]].append(mill)


def mill_parts(mill, variant, indent):
    pad = " " * indent
    suffix = "Night" if variant == "night" else "Day"
    alpha = None if mill["alpha"] == 255 else mill["alpha"]
    blocks = [part_image("mill%dTower%s" % (mill["index"], suffix), mill["tower"],
                         "%s_%s" % (mill["tower_asset"], variant), indent, alpha=alpha),
              part_image("mill%dRotor%s" % (mill["index"], suffix), mill["rotor"],
                         "%s_%s" % (mill["rotor_asset"], variant), indent, alpha=alpha,
                         extra=' pivotX="0.5" pivotY="0.5" angle="0"',
                         transforms=[("angle", "%d + [SECOND_MILLISECOND] * %s"
                                      % (int(mill["angle_offset"]), RATE))])]
    if "hub" in mill:
        blocks.append(part_image("mill%dHub%s" % (mill["index"], suffix), mill["hub"],
                                 "wm_hub_%s" % variant, indent, alpha=alpha))
    return "\n".join(blocks)


def mill_condition(group, mills, indent):
    pad = " " * indent
    def group_body(variant):
        cap = variant.capitalize()
        return ('%s        <Group name="%s%s" x="0" y="0" width="450" height="450" alpha="255">\n'
                '%s\n%s        </Group>'
                % (pad, group, cap, mill_parts_all(mills, variant, indent + 12), pad))

    def mill_parts_all(ms, variant, ind):
        return "\n".join(mill_parts(m, variant, ind) for m in ms)

    return condition(indent, [("%sNight" % group, NIGHT)],
                     [("%sNight" % group, group_body("night"))], group_body("day"))


w('            <Group name="landscapeFarMills" x="0" y="0" width="450" height="450" alpha="255">')
w('                <Gyro x="(%s/90) * clamp([ACCELEROMETER_ANGLE_X], -90, 90)" />'
  % by_distance[2][0]["gyro"])
w('')
w(mill_condition("farMills", by_distance[2], 16))
w('            </Group>')
w('')
w('            <Group name="landscapeMidMills" x="0" y="0" width="450" height="450" alpha="255">')
w('                <Gyro x="(%s/90) * clamp([ACCELEROMETER_ANGLE_X], -90, 90)" />'
  % by_distance[1][0]["gyro"])
w('')
far_ground = [("SnowNight", NIGHT + " &amp;&amp; (" + SNOW_FAMILY + ")", "snow_night"),
              ("Night", NIGHT, "night"),
              ("SnowDay", SNOW_FAMILY, "snow_day")]
w(condition(16,
            [("far%s" % n, e) for n, e, _ in far_ground],
            [("far%s" % n, part_image("landFar%s" % n, L["land_far"], "land_far_%s" % a, 24))
             for n, e, a in far_ground],
            part_image("landFarDay", L["land_far"], "land_far_day", 24)))
w('')
w(mill_condition("midMills", by_distance[1], 16))
w('            </Group>')
w('')
w('            <Group name="landscapeLayer" x="0" y="0" width="450" height="450" alpha="255">')
w('                <Gyro x="(%s/90) * clamp([ACCELEROMETER_ANGLE_X], -90, 90)" />'
  % by_distance[0][0]["gyro"])
w('')
ground = [("SnowNight", NIGHT + " &amp;&amp; (" + SNOW_FAMILY + ")", "snow_night"),
          ("Night", NIGHT, "night"),
          ("SnowDay", SNOW_FAMILY, "snow_day"),
          ("GreenDay", GREEN_DAY, "green_day")]
w(condition(16,
            [("near%s" % n, e) for n, e, _ in ground],
            [("near%s" % n, part_image("landNear%s" % n, L["land_near"], "land_near_%s" % a, 24))
             for n, e, a in ground],
            part_image("landNearOvercastDay", L["land_near"], "land_near_overcast_day", 24)))
w('')
w(mill_condition("heroMills", by_distance[0], 16))
w('')
w(condition(16,
            [("lawn%s" % n, e) for n, e, _ in ground],
            [("lawn%s" % n, part_image("lawn%s" % n, L["lawn"], "lawn_%s" % a, 24))
             for n, e, a in ground],
            part_image("lawnOvercastDay", L["lawn"], "lawn_overcast_day", 24)))
w('            </Group>')
w('')

w('            <Group name="weatherEffects" x="0" y="0" width="450" height="450" alpha="255">')
w('')
fog_drift = "0 - 450 + fract([SECONDS_IN_DAY] / 96) * 450"
w(condition(16, [("fogNight", NIGHT)],
            [("fogNight", part_image("fogNight", L["fog"], "fx_fog_night", 24, alpha=0,
                                     transforms=[("x", fog_drift),
                                                 ("alpha", "([WEATHER.CONDITION] == 3 ? 230 : ([WEATHER.CONDITION] == 13 ? 115 : 0))")]))],
            part_image("fogDay", L["fog"], "fx_fog_day", 24, alpha=0,
                       transforms=[("x", fog_drift),
                                   ("alpha", "([WEATHER.CONDITION] == 3 ? 102 : ([WEATHER.CONDITION] == 13 ? 51 : 0))")])))
w('')
rain = L["rain"]
rain_far = {"x": rain["x"] - 40, "y": rain["y"] - 40,
            "width": rain["width"] + 80, "height": rain["height"] + 80}
for name, r, order, alpha_expr in (
        ("rainFar", rain_far, [0, 1, 2],
         "(([WEATHER.CONDITION] == 4 || [WEATHER.CONDITION] == 9) ? 150 : ([WEATHER.CONDITION] == 6 ? 100 : ([WEATHER.CONDITION] == 12 ? 82 : ([WEATHER.CONDITION] == 10 ? 100 : 0))))"),
        ("rainNear", rain, [2, 0, 1],
         "(([WEATHER.CONDITION] == 4 || [WEATHER.CONDITION] == 9) ? 235 : ([WEATHER.CONDITION] == 6 ? 155 : 0))")):
    w('                <PartAnimatedImage name="%s" %s alpha="0">' % (name, rect(r)))
    w('                    <Transform target="alpha" value="%s" />' % alpha_expr)
    w('                    <AnimationController play="ON_VISIBLE" repeat="TRUE" loopCount="0" resumePlayBack="TRUE" />')
    w('                    <SequenceImages frameRate="12" loopCount="0">')
    for i in order:
        w('                        <Image resource="fx_rain_f%d" />' % i)
    w('                    </SequenceImages>')
    w('                </PartAnimatedImage>')
    w('')
snow = L["snow"]
w(part_image("snowFar", snow, "fx_snow_far", 16, alpha=0,
             transforms=[("y", "0 - 450 + fract([SECOND_MILLISECOND] / 10) * 450"),
                         ("alpha", "([WEATHER.CONDITION] == 5 ? 255 : ([WEATHER.CONDITION] == 7 ? 215 : ([WEATHER.CONDITION] == 11 ? 150 : ([WEATHER.CONDITION] == 10 ? 160 : 0))))")]))
w(part_image("snowNear", snow, "fx_snow_near", 16, alpha=0,
             transforms=[("y", "0 - 450 + fract([SECOND_MILLISECOND] / 6) * 450"),
                         ("alpha", "([WEATHER.CONDITION] == 5 ? 255 : ([WEATHER.CONDITION] == 7 ? 205 : ([WEATHER.CONDITION] == 10 ? 150 : 0)))")]))
w('')
w(part_image("lensWater", L["waterdrop"], "overlay_waterdrop", 16, alpha=0,
             transforms=[("alpha", "(([WEATHER.CONDITION] == 4 || [WEATHER.CONDITION] == 6 || [WEATHER.CONDITION] == 9 || [WEATHER.CONDITION] == 10) ? 255 : 0)")]))
frost_expr = ("((" + SNOW_FAMILY + ") ? 210 : ([WEATHER.IS_AVAILABLE] ? "
              "([WEATHER.TEMPERATURE_UNIT] == 2 ? ([WEATHER.TEMPERATURE] &lt; 18 ? 235 : "
              "([WEATHER.TEMPERATURE] &lt; 32 ? 150 : 0)) : ([WEATHER.TEMPERATURE] &lt; -8 ? 235 : "
              "([WEATHER.TEMPERATURE] &lt; 0 ? 150 : 0))) : 0))")
w(part_image("frostFringe", L["frost"], "overlay_frost", 16, alpha=0,
             transforms=[("alpha", frost_expr)]))
w('')
w(part_image("stormGlow", L["storm_glow"], "storm_cloud_glow", 16, alpha=0,
             transforms=[("alpha", "([WEATHER.CONDITION] == 9 ? clamp(200 - fract(([SECOND_MILLISECOND] + 0.8) / 4) * 2000, 0, 200) : 0)")]))
for i, bolt in enumerate(L["storm_bolts"]):
    offset = ["", " + 1.6", " + 2.9"][i]
    k = [2400, 2600, 2600][i]
    expr = ("([WEATHER.CONDITION] == 9 ? clamp(255 - fract(([SECOND_MILLISECOND]%s) / 4) * %d, 0, 255) : 0)"
            % (offset, k))
    w(part_image(bolt["element"], bolt, bolt["key"], 16, alpha=0, transforms=[("alpha", expr)]))
w(part_image("stormFlash", L["storm_flash"], "storm_flash", 16, alpha=0,
             transforms=[("alpha", "([WEATHER.CONDITION] == 9 ? clamp(255 - fract([SECOND_MILLISECOND] / 4) * 3000, 0, 255) : 0)")]))
w('            </Group>')
w('')

w('            <ListConfiguration id="clockPosition">')
for name in ("top", "center", "bottom", "left", "right"):
    spec = L["clock_positions"][name]
    cap = name.capitalize()
    fonts = spec["fonts"]
    w('                <ListOption id="%s">' % name)
    w('                    <Group name="clock%s" x="0" y="0" width="450" height="450" alpha="255">' % cap)
    if spec["scrim"] is not None:
        sc = spec["scrim"]
        w('                        <PartImage name="scrim%s" %s pivotX="0.5" pivotY="0.5" angle="%d" alpha="255">'
          % (cap, rect(sc), sc["angle"]))
        w('                            <Image resource="overlay_top_scrim" />')
        w('                        </PartImage>')
    d = spec["date"]
    shadow = {"x": d["x"] + 2, "y": d["y"] + 2, "width": d["width"], "height": d["height"]}
    for elem, r, colour in (("dateShadow" + cap, shadow, 2), ("date" + cap, d, 1)):
        w('                        <PartText name="%s" %s alpha="255">' % (elem, rect(r)))
        w('                            <Text align="CENTER" ellipsis="TRUE" maxLines="1">')
        w('                                <Font family="SYNC_TO_DEVICE" size="%d" weight="MEDIUM" color="[CONFIGURATION.clockColour.%d]">'
          % (fonts["date"], colour))
        w('                                    <Template>%s %s %s<Parameter expression="[DAY_OF_WEEK_S]" /><Parameter expression="[DAY_Z]" /><Parameter expression="[MONTH_S]" /></Template>')
        w('                                </Font>')
        w('                            </Text>')
        w('                        </PartText>')
    t = spec["time"]
    for dx, colour in ((3, 2), (0, 0)):
        w('                        <DigitalClock x="%d" y="%d" width="%d" height="%d" alpha="255">'
          % (t["x"] + dx, t["y"] + dx, t["width"], t["height"]))
        w('                            <TimeText align="CENTER" x="0" y="0" width="%d" height="%d" format="hh:mm" hourFormat="SYNC_TO_DEVICE">'
          % (t["width"], t["height"]))
        w('                                <Font family="SYNC_TO_DEVICE" size="%d" weight="MEDIUM" color="[CONFIGURATION.clockColour.%d]" />'
          % (fonts["time"], colour))
        w('                            </TimeText>')
        w('                        </DigitalClock>')
    tp = spec["temperature"]
    tps = {"x": tp["x"] + 2, "y": tp["y"] + 2, "width": tp["width"], "height": tp["height"]}
    w('                        <Condition>')
    w('                            <Expressions>')
    w('                                <Expression name="temperatureKnown%s">[WEATHER.IS_AVAILABLE]</Expression>' % cap)
    w('                            </Expressions>')
    w('                            <Compare expression="temperatureKnown%s">' % cap)
    w('                                <Group name="temperatureGroup%s" x="0" y="0" width="450" height="450" alpha="255">' % cap)
    for elem, r, colour in (("temperatureShadow" + cap, tps, 2), ("temperature" + cap, tp, 0)):
        w('                                    <PartText name="%s" %s alpha="255">' % (elem, rect(r)))
        w('                                        <Transform target="alpha" value="([CONFIGURATION.showTemperature] ? 255 : 0)" />')
        w('                                        <Text align="CENTER" ellipsis="TRUE" maxLines="1">')
        w('                                            <Font family="SYNC_TO_DEVICE" size="%d" weight="MEDIUM" color="[CONFIGURATION.clockColour.%d]">'
          % (fonts["temperature"], colour))
        w('                                                <Template>%d\u00b0<Parameter expression="[WEATHER.TEMPERATURE]" /></Template>')
        w('                                            </Font>')
        w('                                        </Text>')
        w('                                    </PartText>')
    w('                                </Group>')
    w('                            </Compare>')
    w('                            <Default>')
    w('                                <PartText name="temperatureUnknown%s" %s alpha="215">' % (cap, rect(tp)))
    w('                                    <Transform target="alpha" value="([CONFIGURATION.showTemperature] ? 215 : 0)" />')
    w('                                    <Text align="CENTER" ellipsis="TRUE" maxLines="1">')
    w('                                        <Font family="SYNC_TO_DEVICE" size="%d" weight="MEDIUM" color="[CONFIGURATION.clockColour.1]">'
      % fonts["temperature"])
    w('                                            <Shadow color="[CONFIGURATION.clockColour.2]" offsetX="1.5" offsetY="1.5" radius="2" />--\u00b0</Font>')
    w('                                    </Text>')
    w('                                </PartText>')
    w('                            </Default>')
    w('                        </Condition>')
    cn = spec["condition"]
    w('                        <PartText name="condition%s" %s alpha="0">' % (cap, rect(cn)))
    w('                            <Transform target="alpha" value="([CONFIGURATION.showCondition] ? 235 : 0)" />')
    w('                            <Text align="CENTER" ellipsis="TRUE" maxLines="1">')
    w('                                <Font family="SYNC_TO_DEVICE" size="%d" weight="NORMAL" color="[CONFIGURATION.clockColour.1]">'
      % fonts["condition"])
    w('                                    <Shadow color="[CONFIGURATION.clockColour.2]" offsetX="1.5" offsetY="1.5" radius="2" />')
    w('                                    <Template>%s<Parameter expression="[WEATHER.CONDITION_NAME]" /></Template>')
    w('                                </Font>')
    w('                            </Text>')
    w('                        </PartText>')
    w('                    </Group>')
    w('                </ListOption>')
w('            </ListConfiguration>')
w('')

w('            <Group name="clockExtras" x="0" y="0" width="450" height="450" alpha="255">')
w(part_image("brandMark", L["logo"], "logo_mark", 16, alpha=0,
             transforms=[("alpha", "([CONFIGURATION.showBranding] ? 150 : 0)")]))
w(part_image("weatherErrorDot", L["weather_error_dot"], "weather_error_dot", 16, alpha=0,
             transforms=[("alpha", "((!([WEATHER.IS_AVAILABLE]) || [WEATHER.IS_ERROR]) ? 210 : 0)")]))
w('            </Group>')
w('        </Group>')
w('')

for side, slot_id, key, label in (("left", 1, "complication_left", "sr_complication_left"),
                                  ("right", 2, "complication_right", "sr_complication_right")):
    r = L[key]
    w('        <ComplicationSlot name="%sSlot" slotId="%d" %s' % (side, slot_id, rect(r)))
    w('                          supportedTypes="SHORT_TEXT RANGED_VALUE EMPTY"')
    w('                          displayName="@string/%s" isCustomizable="TRUE" alpha="255">' % label)
    w('            <Variant mode="AMBIENT" target="alpha" value="0" />')
    w('            <BoundingOval %s />' % rect(r))
    for ctype, cname in (("SHORT_TEXT", "%sShortText" % side), ("RANGED_VALUE", "%sRanged" % side)):
        w('            <Complication type="%s">' % ctype)
        w('                <PartText name="%s" x="0" y="12" width="%d" height="28" alpha="255">'
          % (cname, r["width"]))
        w('                    <Text align="CENTER" ellipsis="TRUE" maxLines="1">')
        w('                        <Font family="SYNC_TO_DEVICE" size="20" weight="MEDIUM" color="#FFFFFFFF">')
        w('                            <Shadow color="#99000000" offsetX="1.5" offsetY="1.5" radius="2" />')
        w('                            <Template>%s<Parameter expression="[COMPLICATION.TEXT]" /></Template>')
        w('                        </Font>')
        w('                    </Text>')
        w('                </PartText>')
        w('            </Complication>')
    w('        </ComplicationSlot>')
    w('')

amb = L["clock_positions"][L["default_clock_position"]]
w('        <Group name="ambient" x="0" y="0" width="450" height="450" alpha="0">')
w('            <Variant mode="AMBIENT" target="alpha" value="255" />')
w('')
w(part_image("ambientTower", L["ambient_tower"], "wm_tower_ambient", 12, alpha=150))
w(part_image("ambientRotor", L["ambient_rotor"], "wm_rotor_ambient", 12, alpha=150))
w('            <PartText name="ambientDate" %s alpha="255">' % rect(amb["date"]))
w('                <Text align="CENTER" ellipsis="TRUE" maxLines="1">')
w('                    <Font family="SYNC_TO_DEVICE" size="%d" weight="NORMAL" color="#FF6E7889">'
  % amb["fonts"]["date"])
w('                        <Template>%s %s %s<Parameter expression="[DAY_OF_WEEK_S]" /><Parameter expression="[DAY_Z]" /><Parameter expression="[MONTH_S]" /></Template>')
w('                    </Font>')
w('                </Text>')
w('            </PartText>')
w('            <DigitalClock %s alpha="255">' % rect(amb["time"]))
w('                <TimeText align="CENTER" x="0" y="0" width="%d" height="%d" format="hh:mm" hourFormat="SYNC_TO_DEVICE">'
  % (amb["time"]["width"], amb["time"]["height"]))
w('                    <Font family="SYNC_TO_DEVICE" size="%d" weight="LIGHT" color="#FFB6C0D4" />'
  % amb["fonts"]["time"])
w('                </TimeText>')
w('            </DigitalClock>')
w('        </Group>')
w('')
w('    </Scene>')
w('</WatchFace>')

open(XML, "w", encoding="utf-8", newline="\n").write("\n".join(out) + "\n")
print("watchface.xml rebuilt:", len(out), "lines")
