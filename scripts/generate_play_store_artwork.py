#!/usr/bin/env python3

from __future__ import annotations

import math
import struct
import zlib
from pathlib import Path

from generate_app_icon import (
    BG_BOTTOM,
    BG_TOP,
    CABLE,
    CONNECTOR,
    OUTLINE,
    PIN,
    SHADOW,
    Canvas,
    add,
    blend,
    clamp01,
    draw_circle,
    draw_connector_pins,
    draw_polyline,
    draw_segment,
    lerp_color,
    normalize,
    rgba,
    sample_cubic,
)


ROOT = Path(__file__).resolve().parents[1]
PLAY_STORE_DIR = ROOT / "androidApp" / "play-store"
PLAY_ICON_PATH = PLAY_STORE_DIR / "app-icon.png"
FEATURE_GRAPHIC_PATH = PLAY_STORE_DIR / "feature-graphic.png"


class RectCanvas:
    def __init__(self, width: int, height: int) -> None:
        self.width = width
        self.height = height
        self.size = max(width, height)
        self.pixels = [rgba(0, 0, 0, 0) for _ in range(width * height)]

    def _index(self, x: int, y: int) -> int:
        return y * self.width + x

    def blend_pixel(self, x: int, y: int, color: tuple[int, int, int, int]) -> None:
        if 0 <= x < self.width and 0 <= y < self.height and color[3] > 0:
            idx = self._index(x, y)
            self.pixels[idx] = blend(self.pixels[idx], color)


def encode_rect_png(canvas: RectCanvas) -> bytes:
    def chunk(tag: bytes, data: bytes) -> bytes:
        crc = zlib.crc32(tag + data) & 0xFFFFFFFF
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", crc)

    rows = bytearray()
    for y in range(canvas.height):
        rows.append(0)
        for x in range(canvas.width):
            rows.extend(canvas.pixels[canvas._index(x, y)])

    compressed = zlib.compress(bytes(rows), level=9)
    header = struct.pack(">IIBBBBB", canvas.width, canvas.height, 8, 6, 0, 0, 0)
    return b"".join(
        [
            b"\x89PNG\r\n\x1a\n",
            chunk(b"IHDR", header),
            chunk(b"IDAT", compressed),
            chunk(b"IEND", b""),
        ]
    )


def fill_rect_gradient(
    canvas: Canvas | RectCanvas,
    top_color: tuple[int, int, int, int],
    bottom_color: tuple[int, int, int, int],
) -> None:
    width = getattr(canvas, "width", canvas.size)
    height = getattr(canvas, "height", canvas.size)
    for y in range(height):
        row_color = lerp_color(top_color, bottom_color, y / max(1, height - 1))
        for x in range(width):
            canvas.blend_pixel(x, y, row_color)


def stroke_rect(canvas: RectCanvas, inset: float, width: float, color: tuple[int, int, int, int]) -> None:
    for y in range(canvas.height):
        for x in range(canvas.width):
            dist = min(x + 0.5 - inset, y + 0.5 - inset, canvas.width - inset - x - 0.5, canvas.height - inset - y - 0.5)
            if 0 <= dist <= width:
                canvas.blend_pixel(x, y, color)


def draw_play_icon(size: int) -> bytes:
    canvas = Canvas(size)
    fill_rect_gradient(canvas, BG_TOP, BG_BOTTOM)
    stroke_rect_icon(canvas, inset=size * 0.028, width=size * 0.012, color=rgba(*OUTLINE[:3], 60))
    draw_cable_mark(canvas, scale=size, origin=(0.0, 0.0), accent=True)
    return encode_square_png(canvas)


def stroke_rect_icon(canvas: Canvas, inset: float, width: float, color: tuple[int, int, int, int]) -> None:
    for y in range(canvas.size):
        for x in range(canvas.size):
            dist = min(x + 0.5 - inset, y + 0.5 - inset, canvas.size - inset - x - 0.5, canvas.size - inset - y - 0.5)
            if 0 <= dist <= width:
                canvas.blend_pixel(x, y, color)


def encode_square_png(canvas: Canvas) -> bytes:
    rect = RectCanvas(canvas.size, canvas.size)
    rect.pixels = canvas.pixels
    return encode_rect_png(rect)


def draw_cable_mark(
    canvas: Canvas | RectCanvas,
    scale: float,
    origin: tuple[float, float],
    accent: bool,
) -> None:
    ox, oy = origin
    p0 = (ox + scale * 0.29, oy + scale * 0.24)
    p1 = (ox + scale * 0.45, oy + scale * 0.50)
    p2 = (ox + scale * 0.72, oy + scale * 0.77)
    top_curve = sample_cubic(
        p0,
        (ox + scale * 0.61, oy + scale * 0.22),
        (ox + scale * 0.61, oy + scale * 0.44),
        p1,
        steps=90,
    )
    bottom_curve = sample_cubic(
        p1,
        (ox + scale * 0.26, oy + scale * 0.58),
        (ox + scale * 0.33, oy + scale * 0.81),
        p2,
        steps=90,
    )[1:]
    cable_points = top_curve + bottom_curve
    shadow_points = [(x + scale * 0.012, y + scale * 0.014) for x, y in cable_points]
    draw_polyline(canvas, shadow_points, radius=scale * 0.070, color=rgba(*SHADOW[:3], 48))

    start_tangent = normalize((top_curve[4][0] - p0[0], top_curve[4][1] - p0[1]))
    end_tangent = normalize((p2[0] - bottom_curve[-5][0], p2[1] - bottom_curve[-5][1]))
    draw_segment(canvas, add(p0, start_tangent, -scale * 0.17), add(p0, start_tangent, scale * 0.035), radius=scale * 0.074, color=CONNECTOR)
    draw_segment(canvas, add(p2, end_tangent, -scale * 0.035), add(p2, end_tangent, scale * 0.17), radius=scale * 0.074, color=CONNECTOR)
    draw_polyline(canvas, cable_points, radius=scale * 0.058, color=CABLE)
    draw_connector_pins(canvas, p0, start_tangent, spacing=scale * 0.028, pin_radius=scale * 0.010)
    draw_connector_pins(canvas, p2, end_tangent, spacing=scale * 0.028, pin_radius=scale * 0.010)

    if accent:
        accent_center = (ox + scale * 0.66, oy + scale * 0.32)
        draw_circle(canvas, accent_center[0], accent_center[1], scale * 0.043, rgba(45, 199, 178, 72))
        draw_circle(canvas, accent_center[0], accent_center[1], scale * 0.018, rgba(245, 180, 72, 245))


def draw_signal_bands(canvas: RectCanvas) -> None:
    center_x = canvas.width * 0.76
    center_y = canvas.height * 0.46
    for index, radius in enumerate((58, 104, 150, 196)):
        alpha = 48 - index * 7
        width = 5.5
        for y in range(math.floor(center_y - radius - width), math.ceil(center_y + radius + width)):
            for x in range(math.floor(center_x - radius - width), math.ceil(center_x + radius + width)):
                dx = x + 0.5 - center_x
                dy = y + 0.5 - center_y
                distance = math.hypot(dx, dy)
                if radius - width <= distance <= radius + width:
                    fade = 1.0 - abs(distance - radius) / width
                    canvas.blend_pixel(x, y, rgba(45, 199, 178, round(alpha * clamp01(fade))))


def draw_feature_graphic() -> bytes:
    canvas = RectCanvas(1024, 500)
    fill_rect_gradient(canvas, BG_TOP, BG_BOTTOM)
    stroke_rect(canvas, inset=12, width=3, color=rgba(*OUTLINE[:3], 42))
    draw_signal_bands(canvas)
    draw_cable_mark(canvas, scale=560, origin=(-28, -26), accent=True)

    for x in range(616, 986):
        for y in range(92, 408):
            t = (x - 616) / 370
            fade = clamp01(1.0 - abs(y - 250) / 168) * clamp01(t)
            if fade > 0:
                canvas.blend_pixel(x, y, rgba(245, 180, 72, round(22 * fade)))
    return encode_rect_png(canvas)


def main() -> None:
    PLAY_STORE_DIR.mkdir(parents=True, exist_ok=True)
    PLAY_ICON_PATH.write_bytes(draw_play_icon(512))
    FEATURE_GRAPHIC_PATH.write_bytes(draw_feature_graphic())
    print(f"Wrote {PLAY_ICON_PATH.relative_to(ROOT)}")
    print(f"Wrote {FEATURE_GRAPHIC_PATH.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
