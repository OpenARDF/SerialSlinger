#!/usr/bin/env python3

from __future__ import annotations

import math
import struct
import shutil
import subprocess
import zlib
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RESOURCE_DIR = ROOT / "shared" / "src" / "desktopMain" / "resources" / "icons"
PACKAGING_DIR = ROOT / "shared" / "packaging" / "icons"
MAC_ICONSET_DIR = PACKAGING_DIR / "SerialSlinger.iconset"
PNG_RUNTIME_PATH = RESOURCE_DIR / "serialslinger-icon-256.png"
ICO_PATH = PACKAGING_DIR / "SerialSlinger.ico"
ICNS_PATH = PACKAGING_DIR / "SerialSlinger.icns"
LINUX_PNG_PATH = PACKAGING_DIR / "SerialSlinger.png"
MASTER_PNG_PATH = PACKAGING_DIR / "SerialSlinger-master-1024.png"


def rgba(r: int, g: int, b: int, a: int = 255) -> tuple[int, int, int, int]:
    return (r, g, b, a)


BG_TOP = rgba(14, 31, 45)
BG_BOTTOM = rgba(16, 62, 76)
OUTLINE = rgba(96, 184, 177, 38)
SHADOW = rgba(3, 9, 15, 70)
CABLE = rgba(246, 244, 237)
CONNECTOR = rgba(43, 199, 178)
PIN = rgba(245, 180, 72)


def clamp01(value: float) -> float:
    return max(0.0, min(1.0, value))


def lerp_color(a: tuple[int, int, int, int], b: tuple[int, int, int, int], t: float) -> tuple[int, int, int, int]:
    t = clamp01(t)
    return tuple(round(a[index] + (b[index] - a[index]) * t) for index in range(4))  # type: ignore[return-value]


def blend(dst: tuple[int, int, int, int], src: tuple[int, int, int, int]) -> tuple[int, int, int, int]:
    src_a = src[3] / 255.0
    dst_a = dst[3] / 255.0
    out_a = src_a + dst_a * (1.0 - src_a)
    if out_a <= 0.0:
        return (0, 0, 0, 0)

    def channel(index: int) -> int:
        src_c = src[index] / 255.0
        dst_c = dst[index] / 255.0
        out_c = (src_c * src_a + dst_c * dst_a * (1.0 - src_a)) / out_a
        return round(out_c * 255.0)

    return (channel(0), channel(1), channel(2), round(out_a * 255.0))


class Canvas:
    def __init__(self, size: int) -> None:
        self.size = size
        self.pixels = [rgba(0, 0, 0, 0) for _ in range(size * size)]

    def _index(self, x: int, y: int) -> int:
        return y * self.size + x

    def blend_pixel(self, x: int, y: int, color: tuple[int, int, int, int]) -> None:
        if 0 <= x < self.size and 0 <= y < self.size and color[3] > 0:
            idx = self._index(x, y)
            self.pixels[idx] = blend(self.pixels[idx], color)

    def downsample(self, factor: int) -> "Canvas":
        target = Canvas(self.size // factor)
        area = factor * factor
        for y in range(target.size):
            for x in range(target.size):
                acc = [0, 0, 0, 0]
                for yy in range(factor):
                    for xx in range(factor):
                        px = self.pixels[self._index(x * factor + xx, y * factor + yy)]
                        for index in range(4):
                            acc[index] += px[index]
                averaged = tuple(round(value / area) for value in acc)
                target.pixels[target._index(x, y)] = averaged  # type: ignore[assignment]
        return target


def point_in_rounded_rect(x: float, y: float, left: float, top: float, right: float, bottom: float, radius: float) -> bool:
    nearest_x = min(max(x, left + radius), right - radius)
    nearest_y = min(max(y, top + radius), bottom - radius)
    dx = x - nearest_x
    dy = y - nearest_y
    return dx * dx + dy * dy <= radius * radius


def fill_rounded_rect(
    canvas: Canvas,
    left: float,
    top: float,
    right: float,
    bottom: float,
    radius: float,
    top_color: tuple[int, int, int, int],
    bottom_color: tuple[int, int, int, int],
) -> None:
    start_x = max(0, math.floor(left - 1))
    end_x = min(canvas.size, math.ceil(right + 1))
    start_y = max(0, math.floor(top - 1))
    end_y = min(canvas.size, math.ceil(bottom + 1))
    for y in range(start_y, end_y):
        t = (y - top) / max(1.0, bottom - top)
        row_color = lerp_color(top_color, bottom_color, t)
        cy = y + 0.5
        for x in range(start_x, end_x):
            cx = x + 0.5
            if point_in_rounded_rect(cx, cy, left, top, right, bottom, radius):
                canvas.blend_pixel(x, y, row_color)


def stroke_rounded_rect(canvas: Canvas, inset: float, radius: float, width: float, color: tuple[int, int, int, int]) -> None:
    outer_left = inset
    outer_top = inset
    outer_right = canvas.size - inset
    outer_bottom = canvas.size - inset
    inner_left = outer_left + width
    inner_top = outer_top + width
    inner_right = outer_right - width
    inner_bottom = outer_bottom - width
    inner_radius = max(0.0, radius - width)
    start_x = max(0, math.floor(outer_left - 1))
    end_x = min(canvas.size, math.ceil(outer_right + 1))
    start_y = max(0, math.floor(outer_top - 1))
    end_y = min(canvas.size, math.ceil(outer_bottom + 1))
    for y in range(start_y, end_y):
        cy = y + 0.5
        for x in range(start_x, end_x):
            cx = x + 0.5
            in_outer = point_in_rounded_rect(cx, cy, outer_left, outer_top, outer_right, outer_bottom, radius)
            in_inner = point_in_rounded_rect(cx, cy, inner_left, inner_top, inner_right, inner_bottom, inner_radius)
            if in_outer and not in_inner:
                canvas.blend_pixel(x, y, color)


def draw_circle(canvas: Canvas, center_x: float, center_y: float, radius: float, color: tuple[int, int, int, int]) -> None:
    start_x = max(0, math.floor(center_x - radius - 1))
    end_x = min(canvas.size, math.ceil(center_x + radius + 1))
    start_y = max(0, math.floor(center_y - radius - 1))
    end_y = min(canvas.size, math.ceil(center_y + radius + 1))
    radius_sq = radius * radius
    for y in range(start_y, end_y):
        cy = y + 0.5
        for x in range(start_x, end_x):
            cx = x + 0.5
            dx = cx - center_x
            dy = cy - center_y
            if dx * dx + dy * dy <= radius_sq:
                canvas.blend_pixel(x, y, color)


def draw_segment(
    canvas: Canvas,
    start: tuple[float, float],
    end: tuple[float, float],
    radius: float,
    color: tuple[int, int, int, int],
) -> None:
    x0, y0 = start
    x1, y1 = end
    min_x = max(0, math.floor(min(x0, x1) - radius - 1))
    max_x = min(canvas.size, math.ceil(max(x0, x1) + radius + 1))
    min_y = max(0, math.floor(min(y0, y1) - radius - 1))
    max_y = min(canvas.size, math.ceil(max(y0, y1) + radius + 1))
    dx = x1 - x0
    dy = y1 - y0
    length_sq = dx * dx + dy * dy
    if length_sq == 0:
        draw_circle(canvas, x0, y0, radius, color)
        return

    radius_sq = radius * radius
    for y in range(min_y, max_y):
        cy = y + 0.5
        for x in range(min_x, max_x):
            cx = x + 0.5
            t = ((cx - x0) * dx + (cy - y0) * dy) / length_sq
            t = clamp01(t)
            nearest_x = x0 + dx * t
            nearest_y = y0 + dy * t
            dist_x = cx - nearest_x
            dist_y = cy - nearest_y
            if dist_x * dist_x + dist_y * dist_y <= radius_sq:
                canvas.blend_pixel(x, y, color)


def cubic_point(
    p0: tuple[float, float],
    p1: tuple[float, float],
    p2: tuple[float, float],
    p3: tuple[float, float],
    t: float,
) -> tuple[float, float]:
    inv = 1.0 - t
    a = inv * inv * inv
    b = 3.0 * inv * inv * t
    c = 3.0 * inv * t * t
    d = t * t * t
    return (
        p0[0] * a + p1[0] * b + p2[0] * c + p3[0] * d,
        p0[1] * a + p1[1] * b + p2[1] * c + p3[1] * d,
    )


def sample_cubic(
    p0: tuple[float, float],
    p1: tuple[float, float],
    p2: tuple[float, float],
    p3: tuple[float, float],
    steps: int,
) -> list[tuple[float, float]]:
    return [cubic_point(p0, p1, p2, p3, index / steps) for index in range(steps + 1)]


def normalize(vector: tuple[float, float]) -> tuple[float, float]:
    x, y = vector
    length = math.hypot(x, y)
    if length == 0:
        return (1.0, 0.0)
    return (x / length, y / length)


def add(point: tuple[float, float], vector: tuple[float, float], scale: float) -> tuple[float, float]:
    return (point[0] + vector[0] * scale, point[1] + vector[1] * scale)


def draw_polyline(canvas: Canvas, points: list[tuple[float, float]], radius: float, color: tuple[int, int, int, int]) -> None:
    for start, end in zip(points, points[1:]):
        draw_segment(canvas, start, end, radius, color)


def draw_connector_pins(
    canvas: Canvas,
    anchor: tuple[float, float],
    tangent: tuple[float, float],
    spacing: float,
    pin_radius: float,
) -> None:
    tx, ty = normalize(tangent)
    nx, ny = -ty, tx
    face = add(anchor, (tx, ty), -spacing * 0.6)
    for offset in (-spacing, 0.0, spacing):
        center = add(face, (nx, ny), offset)
        draw_circle(canvas, center[0], center[1], pin_radius, PIN)


def render_icon(size: int) -> bytes:
    supersample = 2 if size <= 256 else 1
    work_size = size * supersample
    canvas = Canvas(work_size)

    left = work_size * 0.06
    top = work_size * 0.06
    right = work_size * 0.94
    bottom = work_size * 0.94
    radius = work_size * 0.205

    fill_rounded_rect(
        canvas,
        left,
        top + work_size * 0.03,
        right,
        bottom + work_size * 0.03,
        radius,
        rgba(*SHADOW[:3], 58),
        rgba(*SHADOW[:3], 0),
    )
    fill_rounded_rect(canvas, left, top, right, bottom, radius, BG_TOP, BG_BOTTOM)
    stroke_rounded_rect(canvas, inset=work_size * 0.06, radius=radius, width=work_size * 0.012, color=OUTLINE)

    p0 = (work_size * 0.29, work_size * 0.24)
    p1 = (work_size * 0.45, work_size * 0.50)
    p2 = (work_size * 0.72, work_size * 0.77)
    top_curve = sample_cubic(
        p0,
        (work_size * 0.61, work_size * 0.22),
        (work_size * 0.61, work_size * 0.44),
        p1,
        steps=90,
    )
    bottom_curve = sample_cubic(
        p1,
        (work_size * 0.26, work_size * 0.58),
        (work_size * 0.33, work_size * 0.81),
        p2,
        steps=90,
    )[1:]
    cable_points = top_curve + bottom_curve

    shadow_points = [(x + work_size * 0.012, y + work_size * 0.014) for x, y in cable_points]
    draw_polyline(canvas, shadow_points, radius=work_size * 0.070, color=rgba(*SHADOW[:3], 48))

    start_tangent = normalize((top_curve[4][0] - p0[0], top_curve[4][1] - p0[1]))
    end_tangent = normalize((p2[0] - bottom_curve[-5][0], p2[1] - bottom_curve[-5][1]))

    draw_segment(
        canvas,
        add(p0, start_tangent, -work_size * 0.17),
        add(p0, start_tangent, work_size * 0.035),
        radius=work_size * 0.074,
        color=CONNECTOR,
    )
    draw_segment(
        canvas,
        add(p2, end_tangent, -work_size * 0.035),
        add(p2, end_tangent, work_size * 0.17),
        radius=work_size * 0.074,
        color=CONNECTOR,
    )

    draw_polyline(canvas, cable_points, radius=work_size * 0.058, color=CABLE)
    draw_connector_pins(canvas, p0, start_tangent, spacing=work_size * 0.028, pin_radius=work_size * 0.010)
    draw_connector_pins(canvas, p2, end_tangent, spacing=work_size * 0.028, pin_radius=work_size * 0.010)

    accent_center = (work_size * 0.66, work_size * 0.32)
    draw_circle(canvas, accent_center[0], accent_center[1], work_size * 0.043, rgba(45, 199, 178, 72))
    draw_circle(canvas, accent_center[0], accent_center[1], work_size * 0.018, rgba(245, 180, 72, 245))

    return encode_png(canvas.downsample(supersample))


def encode_png(canvas: Canvas) -> bytes:
    def chunk(tag: bytes, data: bytes) -> bytes:
        crc = zlib.crc32(tag + data) & 0xFFFFFFFF
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", crc)

    rows = bytearray()
    for y in range(canvas.size):
        rows.append(0)
        for x in range(canvas.size):
            rows.extend(canvas.pixels[canvas._index(x, y)])

    compressed = zlib.compress(bytes(rows), level=9)
    header = struct.pack(">IIBBBBB", canvas.size, canvas.size, 8, 6, 0, 0, 0)
    return b"".join(
        [
            b"\x89PNG\r\n\x1a\n",
            chunk(b"IHDR", header),
            chunk(b"IDAT", compressed),
            chunk(b"IEND", b""),
        ]
    )


def write_png(path: Path, size: int) -> bytes:
    png_bytes = render_icon(size)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png_bytes)
    return png_bytes


def resize_png(source: Path, target: Path, size: int) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        ["/usr/bin/sips", "-z", str(size), str(size), str(source), "--out", str(target)],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


def write_ico(path: Path, pngs: list[tuple[int, bytes]]) -> None:
    header = struct.pack("<HHH", 0, 1, len(pngs))
    directory = bytearray()
    offset = 6 + 16 * len(pngs)
    image_bytes = bytearray()

    for size, png_data in pngs:
        width = 0 if size >= 256 else size
        height = 0 if size >= 256 else size
        directory.extend(
            struct.pack(
                "<BBBBHHII",
                width,
                height,
                0,
                0,
                1,
                32,
                len(png_data),
                offset,
            )
        )
        image_bytes.extend(png_data)
        offset += len(png_data)

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(header + directory + image_bytes)


def write_icns(path: Path, chunks: list[tuple[str, bytes]]) -> None:
    body = bytearray()
    for icon_type, png_data in chunks:
        chunk_size = 8 + len(png_data)
        body.extend(icon_type.encode("ascii"))
        body.extend(struct.pack(">I", chunk_size))
        body.extend(png_data)

    total_size = 8 + len(body)
    path.write_bytes(b"icns" + struct.pack(">I", total_size) + body)


def build_icns() -> None:
    if MAC_ICONSET_DIR.exists():
        shutil.rmtree(MAC_ICONSET_DIR)
    MAC_ICONSET_DIR.mkdir(parents=True, exist_ok=True)

    iconset_size_map = {
        "icon_16x16.png": 16,
        "icon_16x16@2x.png": 32,
        "icon_32x32.png": 32,
        "icon_32x32@2x.png": 64,
        "icon_128x128.png": 128,
        "icon_128x128@2x.png": 256,
        "icon_256x256.png": 256,
        "icon_256x256@2x.png": 512,
        "icon_512x512.png": 512,
        "icon_512x512@2x.png": 1024,
    }
    for filename, size in iconset_size_map.items():
        resize_png(MASTER_PNG_PATH, MAC_ICONSET_DIR / filename, size)

    icns_chunks = [
        ("icp4", (MAC_ICONSET_DIR / "icon_16x16.png").read_bytes()),
        ("icp5", (MAC_ICONSET_DIR / "icon_32x32.png").read_bytes()),
        ("icp6", (MAC_ICONSET_DIR / "icon_32x32@2x.png").read_bytes()),
        ("ic07", (MAC_ICONSET_DIR / "icon_128x128.png").read_bytes()),
        ("ic08", (MAC_ICONSET_DIR / "icon_256x256.png").read_bytes()),
        ("ic09", (MAC_ICONSET_DIR / "icon_512x512.png").read_bytes()),
        ("ic10", (MAC_ICONSET_DIR / "icon_512x512@2x.png").read_bytes()),
    ]
    write_icns(ICNS_PATH, icns_chunks)


def main() -> None:
    master_png = write_png(MASTER_PNG_PATH, 1024)
    resize_png(MASTER_PNG_PATH, PNG_RUNTIME_PATH, 256)
    resize_png(MASTER_PNG_PATH, LINUX_PNG_PATH, 512)

    ico_pngs = []
    for size in (16, 32, 48, 64, 128, 256):
        resized_path = PACKAGING_DIR / f".ico-{size}.png"
        resize_png(MASTER_PNG_PATH, resized_path, size)
        ico_pngs.append((size, resized_path.read_bytes()))
    write_ico(ICO_PATH, ico_pngs)
    build_icns()
    for size, _ in ico_pngs:
        (PACKAGING_DIR / f".ico-{size}.png").unlink(missing_ok=True)
    MASTER_PNG_PATH.unlink(missing_ok=True)
    print(f"Wrote {PNG_RUNTIME_PATH.relative_to(ROOT)}")
    print(f"Wrote {LINUX_PNG_PATH.relative_to(ROOT)}")
    print(f"Wrote {ICO_PATH.relative_to(ROOT)}")
    print(f"Wrote {ICNS_PATH.relative_to(ROOT)}")
    print(f"Master PNG bytes: {len(master_png)}")


if __name__ == "__main__":
    main()
