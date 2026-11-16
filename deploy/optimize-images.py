"""Create small website derivatives from the existing, unchanged brand PNGs.

Run with Pillow installed: python deploy/optimize-images.py
This operates only on repository assets and never starts services or touches uploads.
"""
from pathlib import Path
import json
from PIL import Image, ImageOps

assets = Path(__file__).resolve().parents[1] / "apps/web-shared/public/assets"
results = []


def save(source, image, filename):
    target = assets / filename
    assert target != source and target.suffix == ".webp"
    image.save(target, "WEBP", quality=86, method=6)
    results.append({"source": source.name, "output": filename,
                    "dimensions": list(image.size), "bytes": target.stat().st_size})


with Image.open(assets / "hero-editorial.png") as hero:
    save(assets / "hero-editorial.png", hero, "hero-editorial.webp")
    # Same 68% horizontal focal point as the existing mobile cover crop.
    width = min(hero.width, round(hero.height * 9 / 16))
    portrait = ImageOps.fit(hero, (width, hero.height), centering=(0.68, 0.5))
    save(assets / "hero-editorial.png", portrait, "hero-editorial-mobile.webp")

for name in ("candle", "diffuser"):
    source = assets / (name + ".png")
    with Image.open(source) as image:
        for width in (480, 960):
            resized = image.copy()
            resized.thumbnail((width, 10000), Image.Resampling.LANCZOS)
            save(source, resized, f"{name}-{width}.webp")

print(json.dumps(results, indent=2))
