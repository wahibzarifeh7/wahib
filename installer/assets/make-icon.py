"""Regenerates the app's icon/logo assets from a source artwork file.

The source is a square logo tile with the icon glyph on top and "Glass Finder" text
below it (both on a black backdrop) - the crop boxes below were measured against that
specific artwork. If you get a new logo image, re-measure the boxes (open the source
in an image viewer and read off pixel coordinates) rather than assuming these numbers
still line up.

Usage: python installer/assets/make-icon.py path/to/source-logo.png
"""
import sys
from pathlib import Path
from PIL import Image

if len(sys.argv) != 2:
    print(__doc__)
    sys.exit(1)

root = Path(__file__).resolve().parent.parent.parent
src = Image.open(sys.argv[1]).convert('RGB')

# Icon-only crop (excludes the "Glass Finder" text beneath it), then upscaled to a clean master.
icon_master = src.crop((220, 60, 1010, 850)).resize((512, 512), Image.LANCZOS)
# Full lockup crop (icon + text), trimmed of the surrounding black margin.
full_master = src.crop((120, 100, 1160, 1040))

assets = root / 'installer' / 'assets'
public = root / 'public'
icon_master.save(assets / 'logo-icon-master.png')
full_master.save(assets / 'logo-full-master.png')

# Multi-resolution .ico for shortcuts, Add/Remove Programs, and the taskbar.
icon_master.save(assets / 'icon.ico', sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])

# Web assets shipped inside the app itself.
icon_master.resize((256, 256), Image.LANCZOS).save(public / 'logo-icon.png')
icon_master.resize((64, 64), Image.LANCZOS).save(public / 'favicon.png')
full_w = 640
full_h = round(full_master.height * full_w / full_master.width)
full_master.resize((full_w, full_h), Image.LANCZOS).save(public / 'logo-full.png')

print(f'Wrote icon.ico, logo-icon.png ({256}x{256}), favicon.png (64x64), logo-full.png ({full_w}x{full_h}).')
