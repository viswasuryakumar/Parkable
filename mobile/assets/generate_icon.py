from PIL import Image, ImageDraw, ImageFont
import os

ACCENT = (37, 99, 235)  # theme.accent (#2563eb) from mobile/theme/colors.ts
WHITE = (255, 255, 255)

def make_icon(size, path, corner_radius_ratio=0.0):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    if corner_radius_ratio > 0:
        draw.rounded_rectangle(
            [0, 0, size - 1, size - 1],
            radius=int(size * corner_radius_ratio),
            fill=ACCENT,
        )
    else:
        draw.rectangle([0, 0, size - 1, size - 1], fill=ACCENT)

    # "P" for Parkable, bold, centered - no external font dependency needed,
    # PIL's default bitmap font scales poorly, so build a simple block "P"
    # out of rectangles instead of relying on a system font being present.
    pad = size * 0.28
    bar_w = size * 0.16
    x0 = pad
    y0 = pad
    y1 = size - pad
    # vertical stroke of the P
    draw.rectangle([x0, y0, x0 + bar_w, y1], fill=WHITE)
    # bowl of the P (rounded rectangle outline effect via two rects)
    bowl_h = (y1 - y0) * 0.55
    bowl_w = size - pad - x0 - bar_w * 0.2
    draw.rounded_rectangle(
        [x0, y0, x0 + bowl_w, y0 + bowl_h],
        radius=bowl_h * 0.45,
        fill=WHITE,
    )
    # punch the inner hole of the bowl
    inner_pad = bar_w * 0.9
    draw.rounded_rectangle(
        [x0 + bar_w + inner_pad * 0.4, y0 + inner_pad * 0.5,
         x0 + bowl_w - inner_pad * 0.3, y0 + bowl_h - inner_pad * 0.5],
        radius=bowl_h * 0.3,
        fill=ACCENT,
    )

    img.convert("RGB").save(path, "PNG")

out_dir = os.path.dirname(os.path.abspath(__file__))
make_icon(1024, os.path.join(out_dir, "icon.png"))
make_icon(1024, os.path.join(out_dir, "adaptive-icon.png"))
make_icon(48, os.path.join(out_dir, "favicon.png"))
make_icon(192, os.path.join(out_dir, "pwa-192.png"), corner_radius_ratio=0.18)
make_icon(512, os.path.join(out_dir, "pwa-512.png"), corner_radius_ratio=0.18)
print("done")
