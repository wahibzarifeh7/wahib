# Renders the launcher icon (gold "WZ" on the app's ink background) at each
# Android density. Run once after changing the design; the PNGs are committed.
import os
import sys
from PIL import Image, ImageDraw, ImageFont

FONT = "/usr/share/fonts/opentype/inter/InterDisplay-Bold.otf"
INK = (11, 15, 20, 255)
GOLD = (214, 162, 76, 255)
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

res = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "res")
for density, px in SIZES.items():
    scale = 4  # draw large, downsample for smooth edges
    s = px * scale
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    pad = int(s * 0.04)
    d.rounded_rectangle([pad, pad, s - pad, s - pad], radius=int(s * 0.22), fill=INK)
    d.rounded_rectangle([pad, pad, s - pad, s - pad], radius=int(s * 0.22),
                        outline=GOLD, width=max(1, int(s * 0.025)))
    font = ImageFont.truetype(FONT, int(s * 0.42))
    d.text((s / 2, s / 2), "WZ", font=font, fill=GOLD, anchor="mm")
    out = os.path.join(res, "mipmap-" + density)
    os.makedirs(out, exist_ok=True)
    img.resize((px, px), Image.LANCZOS).save(os.path.join(out, "ic_launcher.png"))
    print("wrote", density, px, file=sys.stderr)
