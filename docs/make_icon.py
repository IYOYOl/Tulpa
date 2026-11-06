import os
from PIL import Image
import math

SRC = "assets/icon_1024.png"
RES = "app/src/main/res"

img = Image.open(SRC).convert("RGBA")
W, H = img.size
px = img.load()

# ---- 1) 找"白色圆环"的圆心（用近白像素的重心，排除红色小点） ----
sumx = sumy = n = 0
for y in range(H):
    for x in range(W):
        r, g, b, a = px[x, y]
        if a < 8:
            continue
        if r > 150 and g > 150 and b > 150:   # 近白
            sumx += x; sumy += y; n += 1
ring_cx = sumx / n if n else W / 2.0
ring_cy = sumy / n if n else H / 2.0

# ---- 2) 内容包围盒（圆环 + 红点），用于算需要多大缩放 ----
min_x, min_y, max_x, max_y = W, H, -1, -1
for y in range(H):
    for x in range(W):
        r, g, b, a = px[x, y]
        if a < 8:
            continue
        lum = (r * 30 + g * 59 + b * 11) // 100
        if lum < 25:
            continue
        if x < min_x: min_x = x
        if x > max_x: max_x = x
        if y < min_y: min_y = y
        if y > max_y: max_y = y

# 以"圆心"为基准，取所有内容像素到圆心的最远距离
max_dist = 0.0
for (cx_, cy_) in [(min_x, min_y), (max_x, min_y), (min_x, max_y), (max_x, max_y)]:
    d = math.hypot(cx_ - ring_cx, cy_ - ring_cy)
    if d > max_dist:
        max_dist = d
# 再扫一遍真实像素，避免包围盒角落不准确
step = 2
for y in range(min_y, max_y + 1, step):
    for x in range(min_x, max_x + 1, step):
        r, g, b, a = px[x, y]
        if a < 8:
            continue
        lum = (r * 30 + g * 59 + b * 11) // 100
        if lum < 25:
            continue
        d = math.hypot(x - ring_cx, y - ring_cy)
        if d > max_dist:
            max_dist = d

SAFE_R = 33.0 * 0.95           # 安全区半径 33dp，留 5% 余量
scale_dp = SAFE_R / max_dist   # dp / 原图像素

densities = {
    "mipmap-mdpi": 1.0,
    "mipmap-hdpi": 1.5,
    "mipmap-xhdpi": 2.0,
    "mipmap-xxhdpi": 3.0,
    "mipmap-xxxhdpi": 4.0,
}

for folder, d in densities.items():
    canvas = int(round(108 * d))
    out_dir = os.path.join(RES, folder)
    os.makedirs(out_dir, exist_ok=True)

    draw_scale = scale_dp * d
    draw_w = int(round(W * draw_scale))
    draw_h = int(round(H * draw_scale))
    # 让"圆环圆心"落在画布正中心
    off_x = int(round(canvas / 2.0 - ring_cx * draw_scale))
    off_y = int(round(canvas / 2.0 - ring_cy * draw_scale))

    scaled = img.resize((max(1, draw_w), max(1, draw_h)), Image.LANCZOS)

    bg = Image.new("RGB", (canvas, canvas), (0, 0, 0))
    bg.save(os.path.join(out_dir, "ic_launcher_background.png"))

    fg = Image.new("RGB", (canvas, canvas), (0, 0, 0))
    fg.paste(scaled, (off_x, off_y), scaled)
    fg.save(os.path.join(out_dir, "ic_launcher_foreground.png"))

    lum_img = scaled.convert("L")
    mono = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    white = Image.new("RGBA", scaled.size, (255, 255, 255, 0))
    white.putalpha(lum_img)
    mono.paste(white, (off_x, off_y), white)
    mono.save(os.path.join(out_dir, "ic_launcher_monochrome.png"))

print(f"ring_center=({ring_cx:.1f},{ring_cy:.1f}) bbox=({min_x},{min_y})-({max_x},{max_y})")
print(f"max_dist={max_dist:.1f}px scale_dp_per_px={scale_dp:.4f}")
