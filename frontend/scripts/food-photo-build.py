"""Build the generated food-photo sources into production WebP assets.

The source renders are kept under frontend/outputs for provenance. This script
normalizes every dish to the 3:2 crop used by the recipe cards and writes a
small manifest/contact sheet so the asset set can be checked without a
browser-specific image decoder.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
SOURCE_DIR = ROOT / "outputs" / "food-real-20260803" / "sources"
OUTPUT_DIR = ROOT / "public" / "food" / "real"
QA_DIR = ROOT / "outputs" / "food-real-20260803" / "qa"
TARGET_SIZE = (1200, 800)

SLUGS = [
    "authentic-mapo-tofu",
    "kung-pao-chicken",
    "dongpo-pork",
    "lotus-root-pork-rib-soup",
    "handmade-jiaozi",
    "scallion-oil-noodles",
    "garlic-broccoli",
    "tomato-scrambled-eggs",
    "sweet-sour-pork",
    "steamed-sea-bass",
    "soy-sauce-chicken",
    "winter-melon-soup",
    "tea-fragrant-ribs",
    "pan-fried-mushroom",
    "pumpkin-millet-porridge",
    "cucumber-shrimp",
    "scallion-pancake",
    "three-cup-chicken",
    "miso-salmon",
    "red-bean-rice-cake",
]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def crop_to_ratio(image: Image.Image, target_ratio: float) -> Image.Image:
    width, height = image.size
    source_ratio = width / height
    if source_ratio > target_ratio:
        crop_width = round(height * target_ratio)
        left = (width - crop_width) // 2
        return image.crop((left, 0, left + crop_width, height))
    crop_height = round(width / target_ratio)
    top = (height - crop_height) // 2
    return image.crop((0, top, width, top + crop_height))


def write_contact_sheet(paths: list[Path]) -> Path:
    QA_DIR.mkdir(parents=True, exist_ok=True)
    columns, thumb_width, thumb_height, label_height = 5, 240, 160, 28
    rows = (len(paths) + columns - 1) // columns
    sheet = Image.new("RGB", (columns * thumb_width, rows * (thumb_height + label_height)), "#f3efe8")
    draw = ImageDraw.Draw(sheet)
    for index, path in enumerate(paths):
        x = (index % columns) * thumb_width
        y = (index // columns) * (thumb_height + label_height)
        with Image.open(path) as image:
            thumbnail = image.convert("RGB")
            thumbnail.thumbnail((thumb_width - 12, thumb_height - 12), Image.Resampling.LANCZOS)
            paste_x = x + (thumb_width - thumbnail.width) // 2
            paste_y = y + (thumb_height - thumbnail.height) // 2
            sheet.paste(thumbnail, (paste_x, paste_y))
        draw.text((x + 8, y + thumb_height + 5), path.stem, fill="#3e332c")
    output = QA_DIR / "food-contact-sheet.jpg"
    sheet.save(output, "JPEG", quality=92, optimize=True)
    return output


def main() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    generated = []
    output_paths = []
    target_ratio = TARGET_SIZE[0] / TARGET_SIZE[1]

    for slug in SLUGS:
        source = SOURCE_DIR / f"{slug}.png"
        if not source.is_file():
            raise FileNotFoundError(f"missing source render: {source}")
        output = OUTPUT_DIR / f"{slug}.webp"
        with Image.open(source) as image:
            source_size = image.size
            normalized = crop_to_ratio(image.convert("RGB"), target_ratio)
            normalized = normalized.resize(TARGET_SIZE, Image.Resampling.LANCZOS)
            normalized.save(output, "WEBP", quality=88, method=6)
        with Image.open(output) as check:
            if check.size != TARGET_SIZE or check.mode != "RGB":
                raise RuntimeError(f"invalid output for {slug}: {check.size} {check.mode}")
        generated.append(
            {
                "slug": slug,
                "source": str(source.relative_to(ROOT.parent)).replace("\\", "/"),
                "sourceDimensions": list(source_size),
                "output": f"/food/real/{slug}.webp",
                "dimensions": list(TARGET_SIZE),
                "bytes": output.stat().st_size,
                "sha256": sha256(output),
            }
        )
        output_paths.append(output)

    manifest = {
        "format": "webp",
        "dimensions": list(TARGET_SIZE),
        "count": len(generated),
        "generatedBy": "OpenAI image generation, normalized with Pillow",
        "placeholder": False,
        "items": generated,
    }
    (OUTPUT_DIR / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    contact_sheet = write_contact_sheet(output_paths)
    total_bytes = sum(path.stat().st_size for path in output_paths)
    print(f"Built {len(generated)} food photos at {TARGET_SIZE[0]}x{TARGET_SIZE[1]}.")
    print(f"Total WebP size: {total_bytes / 1024 / 1024:.2f} MiB")
    print(f"Contact sheet: {contact_sheet}")


if __name__ == "__main__":
    main()
