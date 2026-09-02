import math

FACE = 450
ASPECT = 0.5625
GROUND_OFFSET = 1.0
SKY_SHIFT = 2.5
FOV_HALF_TAN = math.tan(math.radians(22.5))

SQUARE = (8.0, 8.0)
RECT_1_2 = (2.0, 1.0)
RECT_1_4 = (4.0, 1.0)
RECT_1_16 = (16.0, 1.0)

GROUND_PAD = 26
LAND_FAR_LIFT = 0.3
GROUND_WEIGHTS = (1.2, 0.5, 0.2)
GROUND_GYRO_NEAR = 18.0
SUN_WORLD_Y = 3.6
MOON_WORLD_Y = 3.9
STAR_DROP = 2.0

WINDMILL_POS_X = [-6.5, -3.5, -0.8, 8.5, 10.4, -7.9, -4.4, -0.2, 11.5, 12.0, -11.5, -6.0, -3.0]
WINDMILL_POS_Y = [-2.8, -1.3, 2.2, 0.3, -1.1, -2.7, -2.75, -2.75, -2.5, -2.8, -3.5, -3.3, -3.2]
WINDMILL_POS_Z = [-23.0, -23.0, -23.0, -23.0, -23.0, -24.05, -24.05, -24.05, -23.95, -23.95,
                  -25.0, -25.0, -25.0]
WINDMILL_SCALE = [0.2, 0.35, 0.75, 0.5, 0.3, 0.15, 0.12, 0.12, 0.15, 0.09, 0.08, 0.08, 0.08]
WINDMILL_DISTANCE = [0, 0, 0, 0, 0, 1, 1, 1, 0, 0, 2, 2, 2]
WINDMILL_FLIP = [False, False, False, True, True, False, False, False, True, True,
                 False, False, False]
WINDMILL_ALPHA = [0.9, 0.9, 1.0, 1.0, 0.9, 1.0, 1.0, 1.0, 0.8, 0.8, 0.9, 0.9, 0.9]
WINDMILL_PILLAR_OFFSET_X = [-0.05, -0.1, -0.15, 0.1, 0.05, -0.02, -0.02, -0.05, 0.02, 0.02,
                            -0.05, -0.05, -0.05]
WINDMILL_PILLAR_OFFSET_Y = [-1.55, -2.7, -5.9, -3.9, -2.32, -1.18, -0.9, -0.9, -1.15, -0.7,
                            -0.6, -0.6, -0.6]
WINDMILL_ROTOR_OFFSET_X = [0.0, -0.04, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]
WINDMILL_ROTOR_OFFSET_Y = [0.0, 0.03, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]
WINDMILL_WING_OFFSET = [0.0, 30.0, 60.0, 90.0, 120.0, 30.0, 60.0, 90.0, 120.0, 150.0,
                        60.0, 90.0, 120.0]

STAR_X = [1.0, -1.7, 1.2, -1.5, -4.5, -6.1, -7.5]
STAR_Y = [5.4, 4.5, 3.2, 3.0, 4.7, 5.2, 4.8]
STAR_SIZE = [0.1, 0.1, 0.08, 0.1, 0.08, 0.08, 0.1]


def half_height(z_abs):
    return FOV_HALF_TAN * z_abs


def half_width(z_abs):
    return half_height(z_abs) * ASPECT


def crop_half(z_abs):
    return half_width(z_abs)


def px_per_world(z_abs):
    return FACE / (2.0 * crop_half(z_abs))


def ground_shift(distance):
    return (1.5 - (GROUND_OFFSET * GROUND_WEIGHTS[distance])) * 5.0


def quad(mesh, center_x, center_y, z_abs, scale_x, scale_y):
    return {
        "cx": center_x,
        "cy": center_y,
        "hx": mesh[0] * scale_x,
        "hy": mesh[1] * scale_y,
        "z": z_abs,
    }


def project(q):
    side = crop_half(q["z"])
    scale = px_per_world(q["z"])
    left = (q["cx"] - q["hx"] + side) * scale
    right = (q["cx"] + q["hx"] + side) * scale
    top = (side - (q["cy"] + q["hy"])) * scale
    bottom = (side - (q["cy"] - q["hy"])) * scale
    return (left, top, right - left, bottom - top)


def sub_quad(q, box, size):
    width, height = size
    u0 = box[0] / float(width)
    u1 = box[2] / float(width)
    v0 = box[1] / float(height)
    v1 = box[3] / float(height)
    left = q["cx"] - q["hx"] + (2.0 * q["hx"] * u0)
    right = q["cx"] - q["hx"] + (2.0 * q["hx"] * u1)
    top = q["cy"] + q["hy"] - (2.0 * q["hy"] * v0)
    bottom = q["cy"] + q["hy"] - (2.0 * q["hy"] * v1)
    return {
        "cx": (left + right) / 2.0,
        "cy": (top + bottom) / 2.0,
        "hx": (right - left) / 2.0,
        "hy": (top - bottom) / 2.0,
        "z": q["z"],
    }


def project_content(q, box, size):
    return project(sub_quad(q, box, size))


def visible_window(q, size, pad_x=0, pad_y=0, pad_top=None, pad_bottom=None):
    rect = project(q)
    if rect[2] <= 0 or rect[3] <= 0:
        return None
    top_pad = pad_y if pad_top is None else pad_top
    bottom_pad = pad_y if pad_bottom is None else pad_bottom
    u0 = _clamp01((-pad_x - rect[0]) / rect[2])
    u1 = _clamp01(((FACE + pad_x) - rect[0]) / rect[2])
    v0 = _clamp01((-top_pad - rect[1]) / rect[3])
    v1 = _clamp01(((FACE + bottom_pad) - rect[1]) / rect[3])
    if u1 - u0 <= 0.0 or v1 - v0 <= 0.0:
        return None
    width, height = size
    box = (
        int(math.floor(u0 * width)),
        int(math.floor(v0 * height)),
        int(math.ceil(u1 * width)),
        int(math.ceil(v1 * height)),
    )
    box = (
        max(0, min(width - 1, box[0])),
        max(0, min(height - 1, box[1])),
        max(1, min(width, box[2])),
        max(1, min(height, box[3])),
    )
    drawn = (
        rect[0] + (box[0] / float(width)) * rect[2],
        rect[1] + (box[1] / float(height)) * rect[3],
        ((box[2] - box[0]) / float(width)) * rect[2],
        ((box[3] - box[1]) / float(height)) * rect[3],
    )
    return {"box": box, "rect": snap(drawn)}


def _clamp01(value):
    return max(0.0, min(1.0, value))


def snap(rect):
    left = int(round(rect[0]))
    top = int(round(rect[1]))
    width = max(1, int(round(rect[2])))
    height = max(1, int(round(rect[3])))
    return {"x": left, "y": top, "width": width, "height": height}


def mill(index):
    distance = WINDMILL_DISTANCE[index]
    shift = ground_shift(distance)
    scale = WINDMILL_SCALE[index]
    base_x = WINDMILL_POS_X[index] - 1.5 + shift
    base_y = WINDMILL_POS_Y[index]
    z = abs(WINDMILL_POS_Z[index])
    return {
        "index": index,
        "distance": distance,
        "scale": scale,
        "flip": WINDMILL_FLIP[index],
        "alpha": WINDMILL_ALPHA[index],
        "angle_offset": WINDMILL_WING_OFFSET[index],
        "wing": quad(SQUARE,
                     base_x + WINDMILL_ROTOR_OFFSET_X[index],
                     base_y + WINDMILL_ROTOR_OFFSET_Y[index],
                     z, scale, scale),
        "pillar": quad(SQUARE,
                       base_x + WINDMILL_PILLAR_OFFSET_X[index],
                       base_y + WINDMILL_PILLAR_OFFSET_Y[index],
                       z - 0.1, scale * 0.08, scale),
        "center": quad(SQUARE,
                       base_x + WINDMILL_ROTOR_OFFSET_X[index],
                       base_y + WINDMILL_ROTOR_OFFSET_Y[index],
                       z + 0.1, scale * 0.04, scale * 0.04),
    }


def mills_in_frame(margin=0.0):
    visible = []
    for index in range(13):
        parts = mill(index)
        rect = project(parts["wing"])
        if rect[0] + rect[2] < -margin or rect[0] > FACE + margin:
            continue
        visible.append(parts)
    return visible


def sky_quad():
    return quad(SQUARE, -1.5 + SKY_SHIFT, -2.3, 30.0, 2.0, 2.0)


def star_band_quad():
    return quad(SQUARE, 1.3 + SKY_SHIFT, 7.0, 29.9, 1.8, 0.45)


def star_quad(index):
    return quad(SQUARE, STAR_X[index] + SKY_SHIFT, STAR_Y[index] - STAR_DROP, 28.0,
                STAR_SIZE[index], STAR_SIZE[index])


def sun_quad():
    return quad(SQUARE, (SKY_SHIFT * 0.2) + 3.0, SUN_WORLD_Y, 28.0, 1.0, 1.0)


def sun_flare_quad():
    ratio = 20.5 / 28.0
    center = sun_quad()
    return quad(SQUARE, center["cx"] * ratio, center["cy"] * ratio, 20.5, 1.2, 1.2)


def moon_quad():
    z = 28.5
    limit = half_width(z) - 2.4 - 0.25
    x = min(3.2 + (SKY_SHIFT * 0.2), limit)
    return quad(SQUARE, x, MOON_WORLD_Y, z, 0.3, 0.3)


CLOUD_PASSES_OVERCAST = [
    {"slot": "a", "y": 2.0, "z": 27.5, "sx": 2.8, "sy": 2.8, "day": 230, "night": 64,
     "drift": 216},
    {"slot": "b", "y": 3.2, "z": 27.4, "sx": 2.8, "sy": 3.2, "day": 128, "night": 64,
     "drift": 144},
]

CLOUD_PASSES_LIGHT = [
    {"slot": "a", "y": 4.5, "z": 27.0, "sx": 2.8, "sy": 2.8, "day": 77, "night": 115,
     "drift": 216},
    {"slot": "b", "y": -3.2, "z": 26.0, "sx": 2.8, "sy": 2.8, "day": 115, "night": 153,
     "drift": 144},
]


def cloud_quad(pass_spec):
    return quad(RECT_1_2, 0.0, pass_spec["y"], pass_spec["z"], pass_spec["sx"], pass_spec["sy"])


def land_far_quad():
    return quad(RECT_1_4, -1.5 + ((1.5 - (GROUND_OFFSET * 0.5)) * 5.0),
                -5.2 + LAND_FAR_LIFT, 24.0, 3.6, 1.8)


def land_near_quad():
    return quad(RECT_1_4, (1.5 - (GROUND_OFFSET * 1.2)) * 5.0, -6.4, 23.0, 3.5, 3.2)


def lawn_quad():
    return quad(RECT_1_4, (1.5 - (GROUND_OFFSET * 1.2)) * 5.0, -4.3, 23.0, 3.5, 1.0)


def fog_quad():
    return quad(SQUARE, 0.0, 0.0, 20.0, 0.7, 1.05)


def rain_quad():
    return quad(SQUARE, 0.0, 0.0, 20.5, 0.75, 1.9)


def waterdrop_quad():
    return full_frame_quad(20.0)


def full_frame_quad(z, gain=1.0):
    half = (crop_half(z) / 8.0) * gain
    return quad(SQUARE, 0.0, 0.0, z, half, half)


def frost_quad():
    return full_frame_quad(20.3)


def sky_flash_quad():
    return quad(SQUARE, 7.0, 0.0, 19.0, 1.7, 1.2)


def lightning_quad(x, y, scale):
    return quad(SQUARE, x + SKY_SHIFT, y, 26.0, scale, scale)


def snow_flake_px(tier_scale, flake_scale):
    z = 20.0
    return (SQUARE[0] * tier_scale * flake_scale * 2.0) * px_per_world(z)


def logo_quad():
    z = 14.0
    left_edge = -half_width(z) + ((16.0 / FACE) * (half_width(z) * 2.0))
    top_edge = half_height(z) - ((28.0 / FACE) * (half_height(z) * 2.0))
    return quad(RECT_1_4, left_edge + 2.0, top_edge - 0.5, z, 0.5, 0.5)


def horizon_y():
    return project(land_near_quad())[1]


def gyro_px(distance):
    return round(GROUND_GYRO_NEAR * (GROUND_WEIGHTS[distance] / GROUND_WEIGHTS[0]), 1)
