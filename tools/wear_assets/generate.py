import argparse
import hashlib
import json
import math
import os
import random
import shutil
import sys
import tempfile
import xml.etree.ElementTree as ElementTree

from PIL import Image, ImageDraw, ImageFilter, ImageFont

import framing

Image.MAX_IMAGE_PIXELS = None

REPO_ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
SOURCE_DIR = os.path.join(REPO_ROOT, "app", "res", "drawable-nodpi")
OUTPUT_DIR = os.path.join(REPO_ROOT, "wearWatchFace", "src", "main", "res", "drawable-nodpi")
PREVIEW_DIR = os.path.join(REPO_ROOT, "wear", "generated-preview")
MANIFEST_PATH = os.path.join(REPO_ROOT, "wear", "wear_asset_manifest.json")
SCENE_MAPPING_PATH = os.path.join(REPO_ROOT, "wear", "scene_mapping.json")
ASSET_SOURCES_PATH = os.path.join(REPO_ROOT, "wear", "asset_sources.json")
WATCHFACE_PATH = os.path.join(REPO_ROOT, "wearWatchFace", "src", "main", "res", "raw",
                              "watchface.xml")

FACE = framing.FACE
SEED = 20260901
NIGHT_GAIN = 0.24
NIGHT_LIFT = 0.06
HERO_INDEX = 2
FOREGROUND_MILLS = (1, 2)
MID_MILLS = (5, 6, 7)
FAR_MILLS = (11, 12)
SCENE_MILLS = FAR_MILLS + MID_MILLS + FOREGROUND_MILLS

CLOCK_LAYOUT = {
    "logo": {"x": 170, "y": 420, "width": 110, "height": 26},
    "weather_error_dot": {"x": 218, "y": 398, "width": 14, "height": 14},
    "complication_left": {"x": 98, "y": 374, "width": 52, "height": 52},
    "complication_right": {"x": 300, "y": 374, "width": 52, "height": 52},
    "ambient_rotor": {"x": 150, "y": 225, "width": 150, "height": 150},
    "ambient_tower": {"x": 213, "y": 298, "width": 13, "height": 143},
}

SCRIM_SIZE = (450, 232)

CLOCK_POSITIONS = {
    "top": {
        "scrim": {"x": 0, "y": 0, "width": 450, "height": 232, "angle": 0},
        "time": {"x": 25, "y": 62, "width": 400, "height": 88},
        "date": {"x": 75, "y": 152, "width": 300, "height": 26},
        "temperature": {"x": 125, "y": 182, "width": 200, "height": 32},
        "condition": {"x": 75, "y": 216, "width": 300, "height": 22},
        "fonts": {"time": 66, "date": 20, "temperature": 26, "condition": 17},
    },
    "center": {
        "scrim": None,
        "condition": {"x": 75, "y": 152, "width": 300, "height": 22},
        "date": {"x": 75, "y": 178, "width": 300, "height": 26},
        "time": {"x": 25, "y": 207, "width": 400, "height": 88},
        "temperature": {"x": 125, "y": 299, "width": 200, "height": 32},
        "fonts": {"time": 66, "date": 20, "temperature": 26, "condition": 17},
    },
    "bottom": {
        "scrim": {"x": 0, "y": 218, "width": 450, "height": 232, "angle": 180},
        "condition": {"x": 75, "y": 188, "width": 300, "height": 22},
        "date": {"x": 75, "y": 214, "width": 300, "height": 26},
        "time": {"x": 25, "y": 243, "width": 400, "height": 88},
        "temperature": {"x": 125, "y": 335, "width": 200, "height": 32},
        "fonts": {"time": 66, "date": 20, "temperature": 26, "condition": 17},
    },
    "left": {
        "scrim": {"x": -109, "y": 109, "width": 450, "height": 232, "angle": 270},
        "date": {"x": 10, "y": 152, "width": 220, "height": 24},
        "time": {"x": 10, "y": 181, "width": 220, "height": 72},
        "temperature": {"x": 10, "y": 255, "width": 220, "height": 28},
        "condition": {"x": 10, "y": 283, "width": 220, "height": 20},
        "fonts": {"time": 54, "date": 18, "temperature": 22, "condition": 15},
    },
    "right": {
        "scrim": {"x": 109, "y": 109, "width": 450, "height": 232, "angle": 90},
        "date": {"x": 220, "y": 152, "width": 220, "height": 24},
        "time": {"x": 220, "y": 181, "width": 220, "height": 72},
        "temperature": {"x": 220, "y": 255, "width": 220, "height": 28},
        "condition": {"x": 220, "y": 283, "width": 220, "height": 20},
        "fonts": {"time": 54, "date": 18, "temperature": 22, "condition": 15},
    },
}

DEFAULT_CLOCK_POSITION = "top"

BOLT_PLACEMENTS = [
    {"key": "storm_bolt_a", "source": "g_lightning_01.png", "x": 1.0, "y": 3.0, "scale": 0.62},
    {"key": "storm_bolt_b", "source": "g_lightning_02.png", "x": -2.6, "y": 3.4, "scale": 0.5},
    {"key": "storm_bolt_c", "source": "g_lightning_03.png", "x": 2.8, "y": 3.8, "scale": 0.44},
]


class Builder:
    def __init__(self):
        self.mapping = read_json(SCENE_MAPPING_PATH)
        self.sources = read_json(ASSET_SOURCES_PATH)
        self.records = {}
        self.images = {}
        self.geometry = {}
        self._source_cache = {}
        self._source_hash = {}

    def source(self, name):
        if name not in self._source_cache:
            path = os.path.join(SOURCE_DIR, name)
            with open(path, "rb") as handle:
                data = handle.read()
            self._source_hash[name] = hashlib.sha256(data).hexdigest()
            image = Image.open(path)
            self._source_cache[name] = image.convert("RGBA")
        return self._source_cache[name].copy()

    def source_hash(self, name):
        if name not in self._source_hash:
            self.source(name)
        return self._source_hash[name]

    def place(self, key, rect):
        self.geometry[key] = rect
        return rect

    def emit(self, key, image, sources, crop, scale, scene_usage, lossless=True, frames=1, fps=0):
        image = image.copy()
        opaque = is_opaque(image)
        if opaque:
            image = image.convert("RGB")
        filename = key + ".webp"
        path = os.path.join(OUTPUT_DIR, filename)
        params = {"method": 6}
        if opaque:
            params["lossless"] = False
            params["quality"] = 92
        else:
            params["lossless"] = True
            params["quality"] = 100
        image.save(path, format="WEBP", **params)
        with open(path, "rb") as handle:
            digest = hashlib.sha256(handle.read()).hexdigest()
        self.records[key] = {
            "output": "wearWatchFace/src/main/res/drawable-nodpi/" + filename,
            "sources": [{"resource": name, "sha256": self.source_hash(name)} for name in sources],
            "output_sha256": digest,
            "output_bytes": os.path.getsize(path),
            "width": image.size[0],
            "height": image.size[1],
            "crop": crop,
            "scale": scale,
            "format": "WEBP_LOSSLESS" if params["lossless"] else "WEBP_LOSSY_Q92",
            "pixel_format": "RGB" if opaque else "RGBA",
            "decoded_bytes": image.size[0] * image.size[1] * (2 if opaque else 4),
            "animation_frames": frames,
            "animation_fps": fps,
            "scene_usage": scene_usage,
        }
        self.images[key] = self.source_out(path)
        return self.images[key]

    def source_out(self, path):
        image = Image.open(path)
        return image.convert("RGBA")


def read_json(path):
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def is_opaque(image):
    if image.mode != "RGBA":
        return True
    return image.getchannel("A").getextrema()[0] == 255


def resize(image, size):
    return image.resize((max(1, int(size[0])), max(1, int(size[1]))), Image.LANCZOS)


def content_box(image):
    box = image.getchannel("A").getbbox()
    if box is None:
        return (0, 0, image.size[0], image.size[1])
    return box


def hub_box(image):
    width, height = image.size
    cx = width / 2.0
    cy = height / 2.0
    left, top, right, bottom = content_box(image)
    reach = int(math.ceil(max(cx - left, right - cx, cy - top, bottom - cy)))
    return (int(cx) - reach, int(cy) - reach, int(cx) + reach, int(cy) + reach)


def crop_padded(image, box):
    canvas = Image.new("RGBA", (box[2] - box[0], box[3] - box[1]), (0, 0, 0, 0))
    source_box = (max(0, box[0]), max(0, box[1]),
                  min(image.size[0], box[2]), min(image.size[1], box[3]))
    canvas.paste(image.crop(source_box), (source_box[0] - box[0], source_box[1] - box[1]))
    return canvas


def tint(image, gain, lift=0.0):
    pixels = image.load()
    width, height = image.size
    out = Image.new("RGBA", (width, height))
    target = out.load()
    for y in range(height):
        for x in range(width):
            r, g, b, a = pixels[x, y]
            target[x, y] = (
                clamp255(r * gain + 255.0 * lift * (a / 255.0)),
                clamp255(g * gain + 255.0 * lift * (a / 255.0)),
                clamp255(b * gain + 255.0 * lift * (a / 255.0) * 1.15),
                a,
            )
    return out


def clamp255(value):
    return max(0, min(255, int(round(value))))


def scale_alpha(image, factor):
    alpha = image.getchannel("A").point(lambda v: clamp255(v * factor))
    out = image.copy()
    out.putalpha(alpha)
    return out


def radial_alpha(image, inner, outer):
    width, height = image.size
    mask = Image.new("L", (width, height))
    draw = mask.load()
    cx = (width - 1) / 2.0
    cy = (height - 1) / 2.0
    radius = min(cx, cy)
    for y in range(height):
        dy = (y - cy) / radius
        for x in range(width):
            dx = (x - cx) / radius
            dist = math.sqrt(dx * dx + dy * dy)
            t = max(0.0, min(1.0, (dist - inner) / max(1e-6, outer - inner)))
            draw[x, y] = clamp255(t * t * (3.0 - 2.0 * t) * 255.0)
    out = image.copy()
    existing = out.getchannel("A")
    combined = Image.new("L", (width, height))
    src = existing.load()
    msk = mask.load()
    dst = combined.load()
    for y in range(height):
        for x in range(width):
            dst[x, y] = src[x, y] * msk[x, y] // 255
    out.putalpha(combined)
    return out


def vertical_fade(image, start, end):
    width, height = image.size
    alpha = image.getchannel("A")
    src = alpha.load()
    out_alpha = Image.new("L", (width, height))
    dst = out_alpha.load()
    for y in range(height):
        t = max(0.0, min(1.0, (y / float(max(1, height - 1)) - start) / max(1e-6, end - start)))
        factor = 1.0 - (t * t * (3.0 - 2.0 * t))
        for x in range(width):
            dst[x, y] = clamp255(src[x, y] * factor)
    out = image.copy()
    out.putalpha(out_alpha)
    return out


def mirror_tile_h(image):
    width, height = image.size
    canvas = Image.new("RGBA", (width * 2, height), (0, 0, 0, 0))
    canvas.paste(image, (0, 0))
    canvas.paste(image.transpose(Image.FLIP_LEFT_RIGHT), (width, 0))
    return canvas


def stack_v(image):
    width, height = image.size
    canvas = Image.new("RGBA", (width, height * 2), (0, 0, 0, 0))
    canvas.paste(image, (0, 0))
    canvas.paste(image, (0, height))
    return canvas


def scatter_field(sprites, size, count, rng, pixel_range, alpha_range, rotate=False):
    canvas = Image.new("RGBA", size, (0, 0, 0, 0))
    width, height = size
    for _ in range(count):
        sprite = sprites[rng.randrange(len(sprites))]
        target = max(2, int(round(rng.uniform(*pixel_range))))
        piece = resize(sprite, (target, target))
        if rotate:
            piece = piece.rotate(rng.uniform(0.0, 360.0), resample=Image.BICUBIC, expand=True)
        piece = scale_alpha(piece, rng.uniform(*alpha_range))
        x = rng.randrange(width)
        y = rng.randrange(height)
        for ox in (0, -width, width):
            for oy in (0, -height, height):
                px = x + ox - piece.size[0] // 2
                py = y + oy - piece.size[1] // 2
                if px > width or py > height or px + piece.size[0] < 0 or py + piece.size[1] < 0:
                    continue
                canvas.alpha_composite(piece, (px, py))
    return canvas


def trim_square(image):
    box = content_box(image)
    cropped = image.crop(box)
    side = max(cropped.size)
    square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    square.alpha_composite(cropped, ((side - cropped.size[0]) // 2, (side - cropped.size[1]) // 2))
    return square


def outline_only(image, thickness):
    alpha = image.getchannel("A")
    eroded = alpha.filter(ImageFilter.MinFilter(1 + 2 * thickness))
    edge = Image.new("L", alpha.size)
    src = alpha.load()
    ero = eroded.load()
    dst = edge.load()
    for y in range(alpha.size[1]):
        for x in range(alpha.size[0]):
            dst[x, y] = max(0, src[x, y] - ero[x, y])
    out = image.copy()
    out.putalpha(edge)
    return out


def window_asset(builder, key, quad, name, scenes, pad_x=0, pad_y=0, transform=None,
                 lossless=True):
    source = builder.source(name)
    window = framing.visible_window(quad, source.size, pad_x, pad_y)
    if window is None:
        return None
    rect = window["rect"]
    out = resize(source.crop(window["box"]), (rect["width"], rect["height"]))
    if transform is not None:
        out = transform(out)
    scale = round(rect["width"] / float(window["box"][2] - window["box"][0]), 5)
    builder.emit(key, out, [name], list(window["box"]), scale, scenes, lossless=lossless)
    return rect


def build_backgrounds(builder):
    quad = framing.sky_quad()
    plan = [
        ("bg_sky_clear_day", "sky_01.jpg",
         ["D1_CLEAR", "D10_MOSTLY_CLEAR", "D2_CLOUDY", "D8_ICE_COLD"]),
        ("bg_sky_clear_night", "sky_02.jpg",
         ["D1_CLEAR", "D10_MOSTLY_CLEAR", "D2_CLOUDY", "D4_FOG", "D8_ICE_COLD"]),
        ("bg_sky_overcast_day", "sky_03.png",
         ["D3_DREARY", "D4_FOG", "D5_RAIN_SHOWERS", "D6_THUNDERSTORMS", "D7_FLURRIES_SNOW",
          "D9_SLEET"]),
        ("bg_sky_overcast_night", "sky_04.png",
         ["D3_DREARY", "D5_RAIN_SHOWERS", "D6_THUNDERSTORMS", "D7_FLURRIES_SNOW", "D9_SLEET"]),
    ]
    rect = None
    for key, name, scenes in plan:
        rect = window_asset(builder, key, quad, name, scenes)
    builder.place("sky", rect)


def build_overlays(builder):
    builder.place("nightcover", window_asset(
        builder, "overlay_nightcover", framing.sky_quad(), "nightcover_01.png",
        ["D3_DREARY", "D4_FOG", "D5_RAIN_SHOWERS", "D6_THUNDERSTORMS", "D9_SLEET"]))

    def whiten(image):
        thick = image.getchannel("A").filter(ImageFilter.MaxFilter(3))
        merged = Image.merge("RGBA", (
            Image.new("L", image.size, 226),
            Image.new("L", image.size, 234),
            Image.new("L", image.size, 250),
            thick,
        ))
        return vertical_fade(scale_alpha(merged, 1.5), 0.62, 1.0)

    builder.place("starfield", window_asset(
        builder, "overlay_starfield", framing.star_band_quad(), "d_sky_stars.png",
        ["D1_CLEAR", "D10_MOSTLY_CLEAR", "D8_ICE_COLD"], transform=whiten))

    star = builder.source("d_star.png")
    box = hub_box(star)
    largest = framing.snap(framing.project_content(framing.star_quad(0), box, star.size))
    builder.emit("star_twinkle",
                 resize(crop_padded(star, box), (largest["width"], largest["height"])),
                 ["d_star.png"], list(box),
                 round(largest["width"] / float(box[2] - box[0]), 5),
                 ["D1_CLEAR", "D10_MOSTLY_CLEAR", "D8_ICE_COLD"])
    twinkles = []
    for index in range(len(framing.STAR_X)):
        rect = framing.snap(framing.project_content(framing.star_quad(index), box, star.size))
        if rect["x"] + rect["width"] < 8 or rect["x"] > FACE - 8:
            continue
        if rect["y"] + rect["height"] < 8 or rect["y"] > FACE - 8:
            continue
        twinkles.append(rect)
    builder.place("star_twinkles", twinkles)

    builder.place("frost", window_asset(
        builder, "overlay_frost", framing.frost_quad(), "e_frost.png",
        ["D7_FLURRIES_SNOW", "D8_ICE_COLD", "D9_SLEET", "D4_FOG"],
        transform=lambda im: scale_alpha(radial_alpha(im, 0.36, 1.02), 2.4)))

    builder.place("waterdrop", window_asset(
        builder, "overlay_waterdrop", framing.waterdrop_quad(), "c_waterdrop.png",
        ["D5_RAIN_SHOWERS", "D6_THUNDERSTORMS", "D9_SLEET"],
        transform=lambda im: scale_alpha(radial_alpha(im, 0.24, 1.06), 1.5)))

    scrim = Image.new("RGBA", SCRIM_SIZE, (0, 0, 0, 0))
    pixels = scrim.load()
    for y in range(scrim.size[1]):
        t = y / float(scrim.size[1] - 1)
        alpha = clamp255(92 * (1.0 - t) * (1.0 - t))
        for x in range(scrim.size[0]):
            pixels[x, y] = (4, 8, 16, alpha)
    builder.emit("overlay_top_scrim", scrim, ["sky_01.jpg"],
                 [0, 0, SCRIM_SIZE[0], SCRIM_SIZE[1]], 1.0, ["all"])
    builder.place("clock_positions",
                  {name: {k: v for k, v in spec.items()} for name, spec in CLOCK_POSITIONS.items()})
    builder.place("default_clock_position", DEFAULT_CLOCK_POSITION)


def build_ground(builder):
    pad = framing.GROUND_PAD
    near_quad = framing.land_near_quad()
    lawn_quad = framing.lawn_quad()

    far_plan = [
        ("land_far_day", "a_land_02.png",
         ["D1_CLEAR", "D10_MOSTLY_CLEAR", "D5_RAIN_SHOWERS", "D2_CLOUDY", "D3_DREARY", "D4_FOG",
          "D6_THUNDERSTORMS", "D8_ICE_COLD"]),
        ("land_far_snow_day", "a_land_07.png", ["D7_FLURRIES_SNOW", "D9_SLEET"]),
        ("land_far_night", "a_land_04.png",
         ["D1_CLEAR", "D2_CLOUDY", "D3_DREARY", "D4_FOG", "D5_RAIN_SHOWERS", "D6_THUNDERSTORMS",
          "D8_ICE_COLD", "D10_MOSTLY_CLEAR"]),
        ("land_far_snow_night", "a_land_09.png", ["D7_FLURRIES_SNOW", "D9_SLEET"]),
    ]
    far_quad = framing.land_far_quad()
    rect = None
    for key, name, scenes in far_plan:
        rect = window_asset(builder, key, far_quad, name, scenes, pad_x=pad)
    builder.place("land_far", rect)

    near_plan = [
        ("land_near_green_day", "a_land_01.png",
         ["D1_CLEAR", "D10_MOSTLY_CLEAR", "D5_RAIN_SHOWERS"]),
        ("land_near_overcast_day", "a_land_05.png",
         ["D2_CLOUDY", "D3_DREARY", "D4_FOG", "D6_THUNDERSTORMS", "D8_ICE_COLD"]),
        ("land_near_snow_day", "a_land_06.png", ["D7_FLURRIES_SNOW", "D9_SLEET"]),
        ("land_near_night", "a_land_03.png",
         ["D1_CLEAR", "D2_CLOUDY", "D3_DREARY", "D4_FOG", "D5_RAIN_SHOWERS", "D6_THUNDERSTORMS",
          "D8_ICE_COLD", "D10_MOSTLY_CLEAR"]),
        ("land_near_snow_night", "a_land_08.png", ["D7_FLURRIES_SNOW", "D9_SLEET"]),
    ]
    for key, name, scenes in near_plan:
        rect = window_asset(builder, key, near_quad, name, scenes, pad_x=pad)
    builder.place("land_near", rect)

    lawn_plan = [
        ("lawn_green_day", "a_lawn_01.png", ["D1_CLEAR", "D10_MOSTLY_CLEAR", "D5_RAIN_SHOWERS"]),
        ("lawn_overcast_day", "a_lawn_03.png",
         ["D2_CLOUDY", "D3_DREARY", "D4_FOG", "D6_THUNDERSTORMS", "D8_ICE_COLD"]),
        ("lawn_snow_day", "a_lawn_04.png", ["D7_FLURRIES_SNOW", "D9_SLEET"]),
        ("lawn_night", "a_lawn_02.png",
         ["D1_CLEAR", "D2_CLOUDY", "D3_DREARY", "D4_FOG", "D5_RAIN_SHOWERS", "D6_THUNDERSTORMS",
          "D8_ICE_COLD", "D10_MOSTLY_CLEAR"]),
        ("lawn_snow_night", "a_lawn_05.png", ["D7_FLURRIES_SNOW", "D9_SLEET"]),
    ]
    for key, name, scenes in lawn_plan:
        rect = window_asset(builder, key, lawn_quad, name, scenes, pad_x=pad)
    builder.place("lawn", rect)


def max_size(current, rect):
    return (max(current[0], rect["width"]), max(current[1], rect["height"]))


def build_windmill(builder):
    sharp_src = builder.source("a_windmill_wing.png")
    sharp_box = hub_box(sharp_src)
    blur_src = builder.source("a_windmill_wing_blur2.png")
    blur_box = hub_box(blur_src)
    hub_src = builder.source("a_windmill_center_01.png")
    hub_cap_box = hub_box(hub_src)
    near_tower_src = builder.source("a_windmill_pillar_01.png")
    near_tower_box = content_box(near_tower_src)
    far_tower_src = builder.source("a_windmill_pillar_blur2_02.png")
    far_tower_box = content_box(far_tower_src)

    mills = []
    sizes = {"rotor": (0, 0), "rotor_blur": (0, 0), "tower": (0, 0), "tower_far": (0, 0),
             "hub": (0, 0)}
    for index in SCENE_MILLS:
        parts = framing.mill(index)
        foreground = index in FOREGROUND_MILLS
        rotor_box = sharp_box if foreground else blur_box
        rotor_src = sharp_src if foreground else blur_src
        tower_box = near_tower_box if foreground else far_tower_box
        tower_src = near_tower_src if foreground else far_tower_src
        entry = {
            "index": index,
            "distance": parts["distance"],
            "gyro": framing.gyro_px(parts["distance"]),
            "alpha": int(round(parts["alpha"] * 255)),
            "angle_offset": parts["angle_offset"],
            "rotor_asset": "wm_rotor" if foreground else "wm_rotor_blur",
            "tower_asset": "wm_tower" if foreground else "wm_tower_far",
            "rotor": framing.snap(framing.project_content(parts["wing"], rotor_box,
                                                          rotor_src.size)),
            "tower": framing.snap(framing.project_content(parts["pillar"], tower_box,
                                                          tower_src.size)),
        }
        sizes["rotor" if foreground else "rotor_blur"] = max_size(
            sizes["rotor" if foreground else "rotor_blur"], entry["rotor"])
        sizes["tower" if foreground else "tower_far"] = max_size(
            sizes["tower" if foreground else "tower_far"], entry["tower"])
        if foreground:
            entry["hub"] = framing.snap(framing.project_content(parts["center"], hub_cap_box,
                                                                hub_src.size))
            sizes["hub"] = max_size(sizes["hub"], entry["hub"])
        mills.append(entry)
    builder.place("windmills", mills)

    rotor_day = resize(crop_padded(sharp_src, sharp_box), sizes["rotor"])
    rotor_scale = round(sizes["rotor"][0] / float(sharp_box[2] - sharp_box[0]), 5)
    builder.emit("wm_rotor_day", rotor_day, ["a_windmill_wing.png"], list(sharp_box),
                 rotor_scale, ["all"])
    builder.emit("wm_rotor_night", tint(rotor_day, NIGHT_GAIN, NIGHT_LIFT),
                 ["a_windmill_wing.png"], list(sharp_box), rotor_scale, ["all"])

    blur_day = resize(crop_padded(blur_src, blur_box), sizes["rotor_blur"])
    blur_scale = round(sizes["rotor_blur"][0] / float(blur_box[2] - blur_box[0]), 5)
    builder.emit("wm_rotor_blur_day", blur_day, ["a_windmill_wing_blur2.png"], list(blur_box),
                 blur_scale, ["all"])
    builder.emit("wm_rotor_blur_night", tint(blur_day, NIGHT_GAIN, NIGHT_LIFT),
                 ["a_windmill_wing_blur2.png"], list(blur_box), blur_scale, ["all"])

    ambient_rotor_size = (CLOCK_LAYOUT["ambient_rotor"]["width"],
                          CLOCK_LAYOUT["ambient_rotor"]["height"])
    ambient_rotor = resize(crop_padded(sharp_src, sharp_box), ambient_rotor_size)
    silhouette = Image.merge("RGBA", (
        Image.new("L", ambient_rotor.size, 190),
        Image.new("L", ambient_rotor.size, 198),
        Image.new("L", ambient_rotor.size, 214),
        ambient_rotor.getchannel("A").point(lambda v: 255 if v > 150 else 0),
    ))
    builder.emit("wm_rotor_ambient", outline_only(silhouette, 1), ["a_windmill_wing.png"],
                 list(sharp_box),
                 round(ambient_rotor_size[0] / float(sharp_box[2] - sharp_box[0]), 5), ["ambient"])

    hub_day = resize(crop_padded(hub_src, hub_cap_box), sizes["hub"])
    hub_scale = round(sizes["hub"][0] / float(hub_cap_box[2] - hub_cap_box[0]), 5)
    builder.emit("wm_hub_day", hub_day, ["a_windmill_center_01.png"], list(hub_cap_box),
                 hub_scale, ["all"])
    builder.emit("wm_hub_night", tint(hub_day, NIGHT_GAIN, NIGHT_LIFT),
                 ["a_windmill_center_01.png"], list(hub_cap_box), hub_scale, ["all"])

    towers = [("wm_tower", "a_windmill_pillar_01.png", near_tower_src, near_tower_box,
               sizes["tower"], True),
              ("wm_tower_far", "a_windmill_pillar_blur2_02.png", far_tower_src, far_tower_box,
               sizes["tower_far"], False)]
    for prefix, name, source, box, size, make_ambient in towers:
        day = resize(source.crop(box), size)
        scale = round(size[0] / float(box[2] - box[0]), 5)
        builder.emit(prefix + "_day", day, [name], list(box), scale, ["all"])
        builder.emit(prefix + "_night", tint(day, NIGHT_GAIN, NIGHT_LIFT), [name], list(box),
                     scale, ["all"])
        if make_ambient:
            ambient_size = (CLOCK_LAYOUT["ambient_tower"]["width"],
                            CLOCK_LAYOUT["ambient_tower"]["height"])
            ambient_tower = resize(source.crop(box), ambient_size)
            tower_silhouette = Image.merge("RGBA", (
                Image.new("L", ambient_tower.size, 190),
                Image.new("L", ambient_tower.size, 198),
                Image.new("L", ambient_tower.size, 214),
                ambient_tower.getchannel("A").point(lambda v: 255 if v > 150 else 0),
            ))
            builder.emit("wm_tower_ambient", outline_only(tower_silhouette, 1), [name],
                         list(box),
                         round(ambient_size[0] / float(box[2] - box[0]), 5), ["ambient"])


def build_celestial(builder):
    rays = builder.source("a_sun_01.png")
    rays.alpha_composite(builder.source("a_sun_03.png"))
    rays_box = hub_box(rays)
    rays_rect = framing.snap(framing.project_content(framing.sun_quad(), rays_box, rays.size))
    builder.emit("sun_rays",
                 resize(crop_padded(rays, rays_box), (rays_rect["width"], rays_rect["height"])),
                 ["a_sun_01.png", "a_sun_03.png"], list(rays_box),
                 round(rays_rect["width"] / float(rays_box[2] - rays_box[0]), 5),
                 ["D1_CLEAR", "D10_MOSTLY_CLEAR", "D2_CLOUDY", "D8_ICE_COLD"])
    builder.place("sun_rays", rays_rect)

    builder.place("sun_core", window_asset(
        builder, "sun_core", framing.sun_quad(), "a_sun_02.png",
        ["D1_CLEAR", "D10_MOSTLY_CLEAR", "D2_CLOUDY", "D8_ICE_COLD"], lossless=False))

    builder.place("sun_flare", window_asset(
        builder, "sun_flare", framing.sun_flare_quad(), "a_sun_04.png",
        ["D1_CLEAR", "D10_MOSTLY_CLEAR"],
        transform=lambda im: scale_alpha(im, 0.85), lossless=False))

    moon_rect = None
    for phase in range(1, 8):
        name = "moon_0%d.png" % phase
        image = builder.source(name)
        box = hub_box(image)
        moon_rect = framing.snap(framing.project_content(framing.moon_quad(), box, image.size))
        builder.emit("moon_p%d" % phase,
                     resize(crop_padded(image, box), (moon_rect["width"], moon_rect["height"])),
                     [name], list(box), round(moon_rect["width"] / float(box[2] - box[0]), 5),
                     ["D1_CLEAR", "D10_MOSTLY_CLEAR", "D8_ICE_COLD"])
    builder.place("moon", moon_rect)


def build_clouds(builder):
    overcast = {p["slot"]: p for p in framing.CLOUD_PASSES_OVERCAST}
    light = {p["slot"]: p for p in framing.CLOUD_PASSES_LIGHT}
    plan = [
        ("cloud_a_light", "cloud_a_01.png", "a", light, ["D10_MOSTLY_CLEAR"]),
        ("cloud_a_day", "cloud_a_02.png", "a", overcast,
         ["D2_CLOUDY", "D5_RAIN_SHOWERS", "D6_THUNDERSTORMS", "D7_FLURRIES_SNOW", "D8_ICE_COLD",
          "D9_SLEET"]),
        ("cloud_a_grey", "cloud_a_03.png", "a", overcast,
         ["D3_DREARY", "D4_FOG", "D10_MOSTLY_CLEAR"]),
        ("cloud_a_night", "cloud_a_04.png", "a", overcast,
         ["D2_CLOUDY", "D5_RAIN_SHOWERS", "D6_THUNDERSTORMS", "D7_FLURRIES_SNOW", "D9_SLEET"]),
        ("cloud_b_light", "cloud_b_01.png", "b", light, ["D10_MOSTLY_CLEAR"]),
        ("cloud_b_day", "cloud_b_02.png", "b", overcast,
         ["D2_CLOUDY", "D5_RAIN_SHOWERS", "D6_THUNDERSTORMS", "D7_FLURRIES_SNOW", "D8_ICE_COLD",
          "D9_SLEET"]),
        ("cloud_b_grey", "cloud_b_03.png", "b", overcast,
         ["D3_DREARY", "D4_FOG", "D10_MOSTLY_CLEAR"]),
        ("cloud_b_night", "cloud_b_04.png", "b", overcast,
         ["D2_CLOUDY", "D5_RAIN_SHOWERS", "D6_THUNDERSTORMS", "D7_FLURRIES_SNOW", "D9_SLEET"]),
    ]
    rects = {}
    for key, name, slot, table, scenes in plan:
        spec = table[slot]
        source = builder.source(name)
        box = content_box(source)
        rect = framing.snap(framing.project_content(framing.cloud_quad(spec), box, source.size))
        builder.emit(key, resize(source.crop(box), (rect["width"], rect["height"])),
                     [name], list(box), round(rect["width"] / float(box[2] - box[0]), 5), scenes)
        rects[key] = rect
    builder.place("clouds", {
        "a_overcast": dict(rects["cloud_a_day"], drift=overcast["a"]["drift"]),
        "b_overcast": dict(rects["cloud_b_day"], drift=overcast["b"]["drift"]),
        "a_light": dict(rects["cloud_a_light"], drift=light["a"]["drift"]),
        "b_light": dict(rects["cloud_b_light"], drift=light["b"]["drift"]),
    })


def build_precipitation(builder):
    rng = random.Random(SEED)
    rain_quad = framing.rain_quad()
    rain_rect = None
    for index, name in enumerate(["c_rain_01.png", "c_rain_02.png", "c_rain_03.png"]):
        key = "fx_rain_f%d" % index
        rain_rect = window_asset(builder, key, rain_quad, name,
                                 ["D5_RAIN_SHOWERS", "D6_THUNDERSTORMS", "D9_SLEET"],
                                 transform=lambda im: scale_alpha(im, 6.0), lossless=False)
        builder.records[key]["animation_frames"] = 3
        builder.records[key]["animation_fps"] = 12
    builder.place("rain", rain_rect)

    flake_big = trim_square(builder.source("e_snow_01.png"))
    flake_mid = trim_square(builder.source("e_snow_02.png"))
    flake_small = trim_square(builder.source("e_snow_03.png"))
    clump = trim_square(builder.source("e_snow_04.png"))

    flake_gain = 0.5
    big_px = framing.snow_flake_px(0.1, 1.0) * flake_gain
    mid_px = framing.snow_flake_px(0.02, 1.0) * flake_gain
    small_px = framing.snow_flake_px(0.01, 1.0) * flake_gain
    clump_px = framing.snow_flake_px(0.35, 1.0) * 0.22 * flake_gain

    near = scatter_field([flake_big], (FACE, FACE), 6, rng,
                         (big_px * 0.45, big_px), (0.72, 0.95), rotate=True)
    near.alpha_composite(scatter_field([flake_mid], (FACE, FACE), 30, rng,
                                       (mid_px * 0.5, mid_px), (0.6, 0.92)))
    near.alpha_composite(scatter_field([clump], (FACE, FACE), 6, rng,
                                       (clump_px * 0.6, clump_px), (0.3, 0.5)))
    builder.emit("fx_snow_near", stack_v(near),
                 ["e_snow_01.png", "e_snow_02.png", "e_snow_04.png"], [0, 0, FACE, FACE], 1.0,
                 ["D7_FLURRIES_SNOW", "D9_SLEET"], lossless=False)

    far = scatter_field([flake_small], (FACE, FACE), 70, rng,
                        (small_px * 0.5, small_px), (0.4, 0.75))
    far.alpha_composite(scatter_field([flake_mid], (FACE, FACE), 24, rng,
                                      (mid_px * 0.35, mid_px * 0.7), (0.32, 0.6)))
    builder.emit("fx_snow_far", stack_v(far), ["e_snow_02.png", "e_snow_03.png"],
                 [0, 0, FACE, FACE], 1.0, ["D7_FLURRIES_SNOW", "D9_SLEET"], lossless=False)
    builder.place("snow", {"x": 0, "y": 0, "width": FACE, "height": FACE * 2})

    fog_quad = framing.fog_quad()
    for key, name, scenes in [("fx_fog_day", "fog_01.jpg", ["D3_DREARY", "D4_FOG"]),
                              ("fx_fog_night", "fog_02.png", ["D3_DREARY", "D4_FOG"])]:
        source = builder.source(name)
        window = framing.visible_window(fog_quad, source.size)
        tile = resize(source.crop(window["box"]), (FACE, FACE))
        if name.endswith(".jpg"):
            grey = tile.convert("L")
            tile = Image.merge("RGBA", (
                Image.new("L", tile.size, 236),
                Image.new("L", tile.size, 240),
                Image.new("L", tile.size, 244),
                grey.point(lambda v: clamp255(v * 0.85)),
            ))
        builder.emit(key, mirror_tile_h(tile), [name], list(window["box"]),
                     round(FACE / float(window["box"][2] - window["box"][0]), 5), scenes,
                     lossless=False)
    builder.place("fog", {"x": 0, "y": 0, "width": FACE * 2, "height": FACE})


def build_storm(builder):
    builder.place("storm_flash", window_asset(
        builder, "storm_flash", framing.sky_flash_quad(), "g_sky_flash.png",
        ["D6_THUNDERSTORMS"], transform=lambda im: scale_alpha(im, 3.2), lossless=False))

    glow_sources = ["cloud_a_light_01.png", "cloud_a_light_02.png", "cloud_a_light_03.png",
                    "cloud_b_light_01.png", "cloud_b_light_02.png", "cloud_b_light_03.png"]
    reference = builder.source(glow_sources[0])
    glow_box = content_box(reference)
    projected = framing.snap(framing.project_content(
        framing.cloud_quad(framing.CLOUD_PASSES_OVERCAST[0]), glow_box, reference.size))
    glow_rect = {
        "width": max(1, projected["width"] // 2),
        "height": max(1, projected["height"] // 2),
    }
    glow_rect["x"] = int(round((FACE - glow_rect["width"]) / 2.0)) - 20
    glow_rect["y"] = 26
    merged = None
    for name in glow_sources:
        image = builder.source(name)
        piece = resize(image.crop(content_box(image)), (glow_rect["width"], glow_rect["height"]))
        merged = piece if merged is None else Image.blend(merged, piece, 0.5)
    builder.emit("storm_cloud_glow", merged, glow_sources, list(glow_box),
                 round(glow_rect["width"] / float(glow_box[2] - glow_box[0]), 5),
                 ["D6_THUNDERSTORMS"])
    builder.place("storm_glow", glow_rect)

    bolts = []
    for spec in BOLT_PLACEMENTS:
        source = builder.source(spec["source"])
        box = content_box(source)
        rect = framing.snap(framing.project_content(
            framing.lightning_quad(spec["x"], spec["y"], spec["scale"]), box, source.size))
        builder.emit(spec["key"], resize(source.crop(box), (rect["width"], rect["height"])),
                     [spec["source"]], list(box),
                     round(rect["width"] / float(box[2] - box[0]), 5), ["D6_THUNDERSTORMS"])
        bolts.append(dict(rect, key=spec["key"],
                          element="stormBolt" + spec["key"][-1].upper()))
    builder.place("storm_bolts", bolts)


def build_marks(builder):
    logo = builder.source("logo.png")
    box = content_box(logo)
    size = (CLOCK_LAYOUT["logo"]["width"], CLOCK_LAYOUT["logo"]["height"])
    builder.emit("logo_mark", resize(logo.crop(box), size), ["logo.png"], list(box),
                 round(size[0] / float(box[2] - box[0]), 5), ["all"])

    dot = Image.new("RGBA", (28, 28), (0, 0, 0, 0))
    draw = ImageDraw.Draw(dot)
    draw.ellipse((4, 4, 23, 23), fill=(255, 196, 84, 235))
    draw.ellipse((9, 9, 18, 18), fill=(38, 26, 8, 255))
    builder.emit("weather_error_dot", resize(dot, (14, 14)), ["logo.png"], [0, 0, 28, 28], 0.5,
                 ["all"])
    for key in CLOCK_LAYOUT:
        builder.place(key, dict(CLOCK_LAYOUT[key]))


def scene_font(size):
    return ImageFont.load_default(size=size)


def paste(canvas, image, rect, alpha=1.0):
    piece = resize(image, (rect["width"], rect["height"]))
    if alpha < 1.0:
        piece = scale_alpha(piece, alpha)
    canvas.alpha_composite(piece, (rect["x"], rect["y"]))


def paste_rotated(canvas, image, rect):
    piece = resize(image, (rect["width"], rect["height"]))
    angle = rect.get("angle", 0)
    if angle:
        piece = piece.rotate(-angle, resample=Image.BICUBIC, expand=True)
    cx = rect["x"] + rect["width"] / 2.0
    cy = rect["y"] + rect["height"] / 2.0
    canvas.alpha_composite(piece, (int(round(cx - piece.size[0] / 2.0)),
                                   int(round(cy - piece.size[1] / 2.0))))


def compose_scene(builder, scene_key, night, phase=0.37, position_name=None):
    mapping = builder.mapping
    scene = mapping["scenes"][scene_key]
    geo = builder.geometry
    mode = "night" if night else "day"
    canvas = Image.new("RGBA", (FACE, FACE), (0, 0, 0, 255))
    paste(canvas, builder.images[scene["sky"][mode]].convert("RGBA"), geo["sky"])

    if scene["starfield"] == "night" and night:
        paste(canvas, builder.images["overlay_starfield"], geo["starfield"])
        for index, rect in enumerate(geo["star_twinkles"]):
            paste(canvas, builder.images["star_twinkle"], rect, 0.45 + 0.4 * ((index % 3) / 2.0))
    if scene["nightcover"] and night:
        paste(canvas, builder.images["overlay_nightcover"], geo["nightcover"])

    celestial = scene["celestial"][mode]
    if celestial == "sun":
        if scene_key in ("D1_CLEAR", "D10_MOSTLY_CLEAR"):
            paste(canvas, builder.images["sun_flare"], geo["sun_flare"], 0.34)
        rays = resize(builder.images["sun_rays"],
                      (geo["sun_rays"]["width"], geo["sun_rays"]["height"]))
        rays = rays.rotate(phase * 120.0, resample=Image.BICUBIC)
        canvas.alpha_composite(scale_alpha(rays, 0.55),
                               (geo["sun_rays"]["x"], geo["sun_rays"]["y"]))
        paste(canvas, builder.images["sun_core"], geo["sun_core"], 0.42)
    elif celestial == "moon":
        paste(canvas, builder.images["moon_p4"], geo["moon"])

    for index, cloud in enumerate(scene["clouds"]):
        key = cloud[mode]
        slot = "a" if key.startswith("cloud_a") else "b"
        variant = "light" if key.endswith("light") else "overcast"
        rect = geo["clouds"]["%s_%s" % (slot, variant)]
        sprite = resize(builder.images[key], (rect["width"], rect["height"]))
        sprite = scale_alpha(sprite, cloud["alpha_night"] if night else cloud["alpha_day"])
        offset = int(((phase * 0.7 + index * 0.35) % 1.0) * (FACE + rect["width"])) - rect["width"]
        canvas.alpha_composite(sprite, (offset, rect["y"]))

    ground = mapping["ground_sets"][scene["ground_set"][mode]]
    draw_windmills(builder, canvas, night, phase, (2,))
    paste(canvas, builder.images[ground["far"]], geo["land_far"])
    draw_windmills(builder, canvas, night, phase, (1,))
    paste(canvas, builder.images[ground["near"]], geo["land_near"])
    draw_windmills(builder, canvas, night, phase, (0,))
    paste(canvas, builder.images[ground["lawn"]], geo["lawn"])

    for effect in scene["precipitation"]:
        canvas.alpha_composite(precipitation_layer(builder, effect, phase))

    for overlay in scene["overlays"]:
        if overlay == "fog":
            canvas.alpha_composite(fog_layer(builder, night, phase, 0.9 if night else 0.4))
        elif overlay == "fog_half":
            canvas.alpha_composite(fog_layer(builder, night, phase, 0.45 if night else 0.2))
        elif overlay == "frost":
            paste(canvas, builder.images["overlay_frost"], geo["frost"])
        elif overlay == "waterdrop":
            paste(canvas, builder.images["overlay_waterdrop"], geo["waterdrop"])
        elif overlay == "storm":
            paste(canvas, builder.images["storm_flash"], geo["storm_flash"], 0.45)
            paste(canvas, builder.images["storm_cloud_glow"], geo["storm_glow"], 0.8)
            bolt = geo["storm_bolts"][0]
            paste(canvas, builder.images[bolt["key"]], bolt, 0.9)

    position = geo["clock_positions"][position_name or geo["default_clock_position"]]
    if position["scrim"] is not None:
        paste_rotated(canvas, builder.images["overlay_top_scrim"], position["scrim"])
    draw_clock(builder, canvas, scene_key, position)
    return apply_round_clip(canvas)


def precipitation_layer(builder, effect, phase):
    geo = builder.geometry
    layer = Image.new("RGBA", (FACE, FACE), (0, 0, 0, 0))
    if effect in ("rain_near", "rain_far"):
        frame = builder.images["fx_rain_f%d" % (int(phase * 3) % 3)]
        rect = dict(geo["rain"])
        if effect == "rain_far":
            rect = {"x": rect["x"] - 40, "y": rect["y"] - 40,
                    "width": rect["width"] + 80, "height": rect["height"] + 80}
        piece = scale_alpha(resize(frame, (rect["width"], rect["height"])),
                            0.38 if effect == "rain_far" else 0.62)
        layer.alpha_composite(piece, (rect["x"], rect["y"]))
    elif effect in ("snow_near", "snow_far"):
        strip = builder.images["fx_snow_near" if effect == "snow_near" else "fx_snow_far"]
        speed = 1.0 if effect == "snow_near" else 0.55
        layer.alpha_composite(strip, (0, int(((phase * speed) % 1.0) * FACE) - FACE))
    return layer


def fog_layer(builder, night, phase, alpha):
    strip = builder.images["fx_fog_night" if night else "fx_fog_day"]
    layer = Image.new("RGBA", (FACE, FACE), (0, 0, 0, 0))
    layer.alpha_composite(strip, (int((phase % 1.0) * FACE) - FACE, 0))
    return scale_alpha(layer, alpha)


def draw_windmills(builder, canvas, night, phase, distances):
    suffix = "night" if night else "day"
    for mill in builder.geometry["windmills"]:
        if mill["distance"] not in distances:
            continue
        alpha = mill["alpha"] / 255.0
        paste(canvas, builder.images[mill["tower_asset"] + "_" + suffix], mill["tower"], alpha)
        rotor = resize(builder.images[mill["rotor_asset"] + "_" + suffix],
                       (mill["rotor"]["width"], mill["rotor"]["height"]))
        rotor = rotor.rotate(-(phase * 360.0) - mill["angle_offset"], resample=Image.BICUBIC)
        canvas.alpha_composite(scale_alpha(rotor, alpha),
                               (mill["rotor"]["x"], mill["rotor"]["y"]))
        if "hub" in mill:
            paste(canvas, builder.images["wm_hub_" + suffix], mill["hub"], alpha)


def draw_clock(builder, canvas, scene_key, position):
    draw = ImageDraw.Draw(canvas)
    ink = (255, 255, 255, 255)
    shade = (0, 0, 0, 150)
    fonts = position["fonts"]
    centered(draw, position["time"], "10:10", scene_font(fonts["time"] + 4), ink, shade)
    centered(draw, position["date"], "TUE 09 JUN", scene_font(fonts["date"]), ink, shade)
    centered(draw, position["temperature"], "18°", scene_font(fonts["temperature"]), ink, shade)
    centered(draw, position["condition"], scene_key.split("_", 1)[1].replace("_", " ").title(),
             scene_font(fonts["condition"]), ink, shade)
    paste(canvas, builder.images["weather_error_dot"], builder.geometry["weather_error_dot"])


def centered(draw, slot, text, font, ink, shade):
    box = draw.textbbox((0, 0), text, font=font)
    x = slot["x"] + (slot["width"] - (box[2] - box[0])) // 2 - box[0]
    y = slot["y"] + (slot["height"] - (box[3] - box[1])) // 2 - box[1]
    draw.text((x + 2, y + 2), text, font=font, fill=shade)
    draw.text((x, y), text, font=font, fill=ink)


def apply_round_clip(canvas):
    mask = Image.new("L", (FACE, FACE), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, FACE - 1, FACE - 1), fill=255)
    out = Image.new("RGBA", (FACE, FACE), (0, 0, 0, 255))
    out.paste(canvas, (0, 0), mask)
    return out


def compose_ambient(builder):
    canvas = Image.new("RGBA", (FACE, FACE), (0, 0, 0, 255))
    geo = builder.geometry
    paste(canvas, builder.images["wm_tower_ambient"], geo["ambient_tower"], 0.55)
    paste(canvas, builder.images["wm_rotor_ambient"], geo["ambient_rotor"], 0.55)
    position = geo["clock_positions"][geo["default_clock_position"]]
    draw = ImageDraw.Draw(canvas)
    centered(draw, position["time"], "10:10", scene_font(position["fonts"]["time"] + 4),
             (196, 204, 222, 255), (0, 0, 0, 0))
    centered(draw, position["date"], "TUE 09 JUN", scene_font(position["fonts"]["date"]),
             (120, 128, 145, 255), (0, 0, 0, 0))
    return apply_round_clip(canvas)


def write_previews(builder):
    order = ["D1_CLEAR", "D10_MOSTLY_CLEAR", "D2_CLOUDY", "D3_DREARY", "D4_FOG",
             "D5_RAIN_SHOWERS", "D6_THUNDERSTORMS", "D7_FLURRIES_SNOW", "D8_ICE_COLD",
             "D9_SLEET"]
    produced = []
    labelled = []
    for scene_key in order:
        for night in (False, True):
            image = compose_scene(builder, scene_key, night)
            name = "scene_%s_%s.png" % (scene_key.lower(), "night" if night else "day")
            image.convert("RGB").save(os.path.join(PREVIEW_DIR, name), format="PNG",
                                      optimize=True)
            produced.append((scene_key, night, name))
    compose_ambient(builder).convert("RGB").save(
        os.path.join(PREVIEW_DIR, "scene_ambient.png"), format="PNG", optimize=True)
    produced.append(("AMBIENT", False, "scene_ambient.png"))

    position_names = []
    for name in ("top", "center", "bottom", "left", "right"):
        image = compose_scene(builder, "D10_MOSTLY_CLEAR", False, position_name=name)
        filename = "clock_%s.png" % name
        image.convert("RGB").save(os.path.join(PREVIEW_DIR, filename), format="PNG",
                                  optimize=True)
        position_names.append((name, filename))

    for night in (False, True):
        tiles = [(key.split("_", 1)[1].replace("_", " ").title(), name)
                 for key, is_night, name in produced if is_night == night and key != "AMBIENT"]
        write_contact_sheet(tiles, os.path.join(
            PREVIEW_DIR, "contact_sheet_%s.png" % ("night" if night else "day")))

    everything = []
    for key, is_night, name in produced:
        if key == "AMBIENT":
            continue
        everything.append(("%s %s" % (key.split("_", 1)[1].replace("_", " ").title(),
                                      "night" if is_night else "day"), name))
    everything.append(("Ambient", "scene_ambient.png"))
    for name, filename in position_names:
        everything.append(("Clock %s" % name, filename))
    write_contact_sheet(everything, os.path.join(PREVIEW_DIR, "contact_sheet_all.png"),
                        columns=6)

    return ([name for _, _, name in produced] + [f for _, f in position_names]
            + ["contact_sheet_day.png", "contact_sheet_night.png", "contact_sheet_all.png"])


def write_contact_sheet(tiles, path, columns=5):
    cell = FACE // 2
    label = 18
    rows = int(math.ceil(len(tiles) / float(columns)))
    sheet = Image.new("RGB", (columns * cell, rows * (cell + label)), (18, 18, 20))
    draw = ImageDraw.Draw(sheet)
    font = scene_font(13)
    for index, entry in enumerate(tiles):
        caption, name = entry
        tile = Image.open(os.path.join(PREVIEW_DIR, name)).convert("RGB")
        x = (index % columns) * cell
        y = (index // columns) * (cell + label)
        sheet.paste(tile.resize((cell, cell), Image.LANCZOS), (x, y))
        draw.text((x + 5, y + cell + 3), caption, font=font, fill=(226, 226, 232))
    sheet.save(path, format="PNG", optimize=True)


CONFIG_ICON = 96


def build_config_icons(builder):
    for name in builder.geometry["clock_positions"]:
        scene = compose_scene(builder, "D10_MOSTLY_CLEAR", False, position_name=name)
        icon = resize(scene, (CONFIG_ICON, CONFIG_ICON))
        mask = Image.new("L", (CONFIG_ICON, CONFIG_ICON), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, CONFIG_ICON - 1, CONFIG_ICON - 1), fill=255)
        icon.putalpha(mask)
        builder.emit("cfg_clock_" + name, icon, ["sky_01.jpg"],
                     [0, 0, FACE, FACE], round(CONFIG_ICON / float(FACE), 5), ["all"])


def write_preview_drawable(builder):
    image = compose_scene(builder, "D10_MOSTLY_CLEAR", False)
    path = os.path.join(OUTPUT_DIR, "preview_windy.webp")
    image.convert("RGB").save(path, format="WEBP", method=6, lossless=False, quality=92)
    with open(path, "rb") as handle:
        digest = hashlib.sha256(handle.read()).hexdigest()
    builder.records["preview_windy"] = {
        "output": "wearWatchFace/src/main/res/drawable-nodpi/preview_windy.webp",
        "sources": [{"resource": "composite", "sha256": "derived"}],
        "output_sha256": digest,
        "output_bytes": os.path.getsize(path),
        "width": FACE,
        "height": FACE,
        "crop": [0, 0, FACE, FACE],
        "scale": 1.0,
        "format": "WEBP_LOSSY_Q92",
        "pixel_format": "RGB",
        "decoded_bytes": FACE * FACE * 2,
        "animation_frames": 1,
        "animation_fps": 0,
        "scene_usage": ["watch_face_info preview"],
    }


def prune(expected_files, directory, keep_suffixes):
    removed = []
    for name in sorted(os.listdir(directory)):
        if not name.lower().endswith(keep_suffixes):
            continue
        if name not in expected_files:
            os.remove(os.path.join(directory, name))
            removed.append(name)
    return removed


def write_scene_mapping(builder):
    mapping = read_json(SCENE_MAPPING_PATH)
    mapping["canvas"] = {
        "width": FACE,
        "height": FACE,
        "clip_shape": "CIRCLE",
        "reference_aspect": framing.ASPECT,
        "ground_parallax_offset": framing.GROUND_OFFSET,
        "horizon_y": int(round(framing.horizon_y())),
    }
    mapping["layout"] = builder.geometry
    with open(SCENE_MAPPING_PATH, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(mapping, handle, indent=2, ensure_ascii=False)
        handle.write("\n")


GEOMETRY_ASSERTIONS = [
    ("skyOvercastNight", "sky"), ("skyClearNight", "sky"), ("skyOvercastDay", "sky"),
    ("skyClearDay", "sky"), ("nightCover", "nightcover"), ("starField", "starfield"),
    ("sunRays", "sun_rays"), ("sunCore", "sun_core"), ("sunFlare", "sun_flare"),
    ("landNearSnowNight", "land_near"), ("landNearNight", "land_near"),
    ("landNearSnowDay", "land_near"), ("landNearGreenDay", "land_near"),
    ("landNearOvercastDay", "land_near"),
    ("lawnSnowNight", "lawn"), ("lawnNight", "lawn"), ("lawnSnowDay", "lawn"),
    ("lawnGreenDay", "lawn"), ("lawnOvercastDay", "lawn"),
    ("landFarDay", "land_far"), ("landFarNight", "land_far"),
    ("landFarSnowDay", "land_far"), ("landFarSnowNight", "land_far"),
    ("lensWater", "waterdrop"), ("frostFringe", "frost"), ("stormFlash", "storm_flash"),
    ("brandMark", "logo"),
    ("weatherErrorDot", "weather_error_dot"),
    ("ambientRotor", "ambient_rotor"), ("ambientTower", "ambient_tower"),
]


def verify_watchface_geometry(layout):
    if not os.path.exists(WATCHFACE_PATH):
        return ["watchface.xml is missing"]
    root = ElementTree.parse(WATCHFACE_PATH).getroot()
    found = {}
    for element in root.iter():
        name = element.get("name")
        if name is None or element.get("x") is None:
            continue
        found[name] = {
            "x": int(float(element.get("x"))),
            "y": int(float(element.get("y"))),
            "width": int(float(element.get("width"))),
            "height": int(float(element.get("height"))),
        }
    failures = []

    def compare(element_name, expected):
        actual = found.get(element_name)
        if actual is None:
            failures.append("watchface.xml is missing element %s" % element_name)
            return
        for axis in ("x", "y", "width", "height"):
            if actual[axis] != expected[axis]:
                failures.append("watchface.xml %s.%s is %d, layout says %d"
                                % (element_name, axis, actual[axis], expected[axis]))

    for element_name, layout_key in GEOMETRY_ASSERTIONS:
        expected = layout.get(layout_key)
        if expected is not None:
            compare(element_name, expected)
    for mill in layout.get("windmills", []):
        for part, suffix in (("rotor", "Rotor"), ("tower", "Tower"), ("hub", "Hub")):
            if part not in mill:
                continue
            for variant in ("Day", "Night"):
                compare("mill%d%s%s" % (mill["index"], suffix, variant), mill[part])
    for index, rect in enumerate(layout.get("star_twinkles", [])):
        compare("starTwinkle%d" % index, rect)
    for name, spec in layout.get("clock_positions", {}).items():
        suffix = name.capitalize()
        if spec.get("scrim") is not None:
            compare("scrim" + suffix, spec["scrim"])
        for part in ("date", "temperature", "condition"):
            compare(part + suffix, spec[part])
    for bolt in layout.get("storm_bolts", []):
        compare(bolt["element"], bolt)
    return failures


def build_all():
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    os.makedirs(PREVIEW_DIR, exist_ok=True)
    builder = Builder()
    build_backgrounds(builder)
    build_overlays(builder)
    build_ground(builder)
    build_windmill(builder)
    build_celestial(builder)
    build_clouds(builder)
    build_precipitation(builder)
    build_storm(builder)
    build_marks(builder)
    build_config_icons(builder)
    write_preview_drawable(builder)
    previews = write_previews(builder)
    write_scene_mapping(builder)

    expected = set(os.path.basename(record["output"]) for record in builder.records.values())
    removed = prune(expected, OUTPUT_DIR, (".webp", ".png"))
    removed += prune(set(previews), PREVIEW_DIR, (".png",))

    total_decoded = sum(record["decoded_bytes"] for key, record in builder.records.items()
                        if key != "preview_windy")
    manifest = {
        "_comment": "Generated by tools/wear_assets/generate.py. Do not hand-edit; rerun the "
                    "generator instead.",
        "schema_version": 2,
        "generator": "tools/wear_assets/generate.py",
        "pillow_version": Image.__version__,
        "seed": SEED,
        "canvas": {"width": FACE, "height": FACE, "reference_aspect": framing.ASPECT},
        "totals": {
            "assets": len(builder.records),
            "packaged_bytes": sum(record["output_bytes"] for record in builder.records.values()),
            "decoded_bytes_all_resources": total_decoded,
        },
        "assets": {key: builder.records[key] for key in sorted(builder.records)},
    }
    with open(MANIFEST_PATH, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(manifest, handle, indent=2, sort_keys=False)
        handle.write("\n")
    return manifest, removed, builder.geometry


def restore_tree(backup, target):
    os.makedirs(target, exist_ok=True)
    keep = set(os.listdir(backup))
    for name in os.listdir(target):
        if name not in keep:
            path = os.path.join(target, name)
            if os.path.isfile(path):
                os.remove(path)
    shutil.copytree(backup, target, dirs_exist_ok=True)


def check():
    if not os.path.exists(MANIFEST_PATH):
        print("wear_asset_manifest.json is missing; run generate.py first")
        return 1
    committed = read_json(MANIFEST_PATH)
    staging = tempfile.mkdtemp(prefix="wear_assets_check_")
    backup_out = os.path.join(staging, "out")
    backup_prev = os.path.join(staging, "prev")
    backup_manifest = os.path.join(staging, "manifest.json")
    backup_mapping = os.path.join(staging, "scene_mapping.json")
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    os.makedirs(PREVIEW_DIR, exist_ok=True)
    shutil.copytree(OUTPUT_DIR, backup_out)
    shutil.copytree(PREVIEW_DIR, backup_prev)
    shutil.copy2(MANIFEST_PATH, backup_manifest)
    shutil.copy2(SCENE_MAPPING_PATH, backup_mapping)
    try:
        regenerated, _, layout = build_all()
        failures = []
        if committed.get("pillow_version") != regenerated.get("pillow_version"):
            failures.append("pillow version drift: manifest %s, current %s"
                            % (committed.get("pillow_version"),
                               regenerated.get("pillow_version")))
        old = committed.get("assets", {})
        new = regenerated.get("assets", {})
        for key in sorted(set(old) | set(new)):
            if key not in old:
                failures.append("new asset not committed: %s" % key)
            elif key not in new:
                failures.append("stale asset still committed: %s" % key)
            else:
                if old[key]["output_sha256"] != new[key]["output_sha256"]:
                    failures.append("output changed: %s" % key)
                old_sources = {entry["resource"]: entry["sha256"] for entry in old[key]["sources"]}
                new_sources = {entry["resource"]: entry["sha256"] for entry in new[key]["sources"]}
                if old_sources != new_sources:
                    failures.append("source changed: %s" % key)
        failures.extend(verify_watchface_geometry(layout))
        if failures:
            for line in failures:
                print("FAIL " + line)
            return 1
        print("OK %d generated assets match their sources, and watchface.xml geometry matches "
              "the generated layout" % len(new))
        return 0
    finally:
        restore_tree(backup_out, OUTPUT_DIR)
        restore_tree(backup_prev, PREVIEW_DIR)
        shutil.copy2(backup_manifest, MANIFEST_PATH)
        shutil.copy2(backup_mapping, SCENE_MAPPING_PATH)
        shutil.rmtree(staging, ignore_errors=True)


def main():
    parser = argparse.ArgumentParser(
        description="Generate the Wear OS watch face resources from the Windy Weather mobile "
                    "artwork, framed as a 1:1 centre crop of the phone view.")
    parser.add_argument("--check", action="store_true",
                        help="regenerate into a scratch area and fail if the committed assets "
                             "or the watchface.xml geometry have drifted")
    parser.add_argument("--layout", action="store_true",
                        help="print the derived geometry and exit without writing anything")
    args = parser.parse_args()
    if args.layout:
        builder = Builder()
        build_backgrounds(builder)
        build_overlays(builder)
        build_ground(builder)
        build_windmill(builder)
        build_celestial(builder)
        build_clouds(builder)
        build_precipitation(builder)
        build_storm(builder)
        build_marks(builder)
        print(json.dumps(builder.geometry, indent=2))
        return 0
    if args.check:
        return check()
    manifest, removed, _ = build_all()
    print("generated %d assets, %d bytes packaged, %d bytes decoded"
          % (manifest["totals"]["assets"], manifest["totals"]["packaged_bytes"],
             manifest["totals"]["decoded_bytes_all_resources"]))
    for name in removed:
        print("removed obsolete " + name)
    return 0


if __name__ == "__main__":
    sys.exit(main())
