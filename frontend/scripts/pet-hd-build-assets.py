"""Build native-HD Xinn row assets from approved imagegen chroma-key strips.

This script is deterministic: it removes no background itself and creates no visual
content. Inputs must already be alpha PNGs produced by imagegen plus the installed
remove_chroma_key.py helper. It splits equal pose slots, applies one shared crop and
scale per row, preserves relative vertical motion, and writes 384x416 cells.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
RUN = ROOT / "outputs" / "xinn-hd-final-20260802"
ALPHA = RUN / "decoded" / "alpha"
OUT = ROOT / "public" / "pets" / "xinn" / "hd"
QA = RUN / "qa"
FRAMES = QA / "frames"
PREVIEWS = QA / "previews"

CELL_W, CELL_H = 384, 416
INNER_W, INNER_H = 368, 400

ROWS = {
    "idle": 6,
    "running-right": 8,
    "waving": 4,
    "jumping": 5,
    "failed": 8,
    "waiting": 6,
    "running": 6,
    "review": 6,
    "look-row-9": 8,
    "look-row-10": 8,
    "idle-curious": 8,
    "idle-sleeve": 8,
    "idle-sway": 8,
    "chat-open": 8,
}

DURATIONS = {
    "idle": [280, 110, 110, 140, 140, 320],
    "running-right": [120, 120, 120, 120, 120, 120, 120, 220],
    "running-left": [120, 120, 120, 120, 120, 120, 120, 220],
    "waving": [140, 140, 140, 280],
    "jumping": [140, 140, 140, 140, 280],
    "failed": [140, 140, 140, 140, 140, 140, 140, 240],
    "waiting": [150, 150, 150, 150, 150, 260],
    "running": [120, 120, 120, 120, 120, 220],
    "review": [150, 150, 150, 150, 150, 280],
    "idle-curious": [180, 140, 140, 220, 180, 180, 240, 320],
    "idle-sleeve": [200, 180, 180, 220, 200, 180, 260, 360],
    "idle-sway": [160, 160, 180, 180, 180, 180, 220, 340],
    "chat-open": [90, 90, 110, 130, 150, 160, 180, 220],
    "look-row-9": [180] * 8,
    "look-row-10": [180] * 8,
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def clear_hidden_rgb(image: Image.Image) -> Image.Image:
    image = image.convert("RGBA")
    pixels = image.load()
    for y in range(image.height):
        for x in range(image.width):
            r, g, b, a = pixels[x, y]
            if a == 0 and (r or g or b):
                pixels[x, y] = (0, 0, 0, 0)
    return image


def find_frame_boundaries(image: Image.Image, frame_count: int) -> list[int]:
    """Find transparent gutters near the expected equal-width boundaries."""
    alpha = image.getchannel("A")
    projection = [
        sum(1 for value in alpha.crop((x, 0, x + 1, image.height)).get_flattened_data() if value)
        for x in range(image.width)
    ]
    nominal = image.width / frame_count
    boundaries = [0]
    for index in range(1, frame_count):
        expected = round(index * nominal)
        radius = max(8, round(nominal * 0.24))
        start = max(boundaries[-1] + 4, expected - radius)
        end = min(image.width - 4, expected + radius)
        # Up to two pixels in a full-height column is isolated matte residue, not a
        # connected silhouette. The selected separator is zeroed below.
        candidates = [x for x in range(start, end + 1) if projection[x] <= 2]
        if not candidates:
            minimum = min(projection[start : end + 1])
            if minimum > 2:
                raise RuntimeError(
                    f"no transparent gutter near frame boundary {index}: minimum occupancy={minimum}"
                )
        # Pick the centre of the transparent run nearest the expected boundary.
        runs: list[tuple[int, int]] = []
        run_start = candidates[0]
        previous = candidates[0]
        for x in candidates[1:]:
            if x != previous + 1:
                runs.append((run_start, previous))
                run_start = x
            previous = x
        runs.append((run_start, previous))
        centres = [round((left + right) / 2) for left, right in runs]
        boundaries.append(min(centres, key=lambda value: abs(value - expected)))
    boundaries.append(image.width)
    return boundaries


def build_row(row_id: str, frame_count: int) -> tuple[list[Image.Image], dict]:
    source = ALPHA / f"{row_id}-source-alpha.png"
    if not source.exists():
        raise FileNotFoundError(source)
    image = Image.open(source).convert("RGBA")
    # Remove only the near-transparent chroma-removal residue before gutter finding.
    alpha = image.getchannel("A").point(lambda value: 0 if value <= 32 else value)
    image.putalpha(alpha)
    slots: list[Image.Image] = []
    boxes = []
    boundaries = find_frame_boundaries(image, frame_count)
    alpha = image.getchannel("A")
    for boundary in boundaries[1:-1]:
        for y in range(image.height):
            alpha.putpixel((boundary, y), 0)
    image.putalpha(alpha)
    for index in range(frame_count):
        left = boundaries[index]
        right = boundaries[index + 1]
        slot = image.crop((left, 0, right, image.height))
        bbox = slot.getchannel("A").getbbox()
        if bbox is None:
            raise RuntimeError(f"{row_id} frame {index} is empty")
        slots.append(slot)
        boxes.append(bbox)

    # One shared normalized crop preserves scale, baseline, and intended vertical travel.
    x0f = min(box[0] / slot.width for slot, box in zip(slots, boxes))
    x1f = max(box[2] / slot.width for slot, box in zip(slots, boxes))
    y0 = min(box[1] for box in boxes)
    y1 = max(box[3] for box in boxes)
    xpad = 0.035
    ypad = max(4, round((y1 - y0) * 0.025))
    x0f = max(0.0, x0f - xpad)
    x1f = min(1.0, x1f + xpad)
    y0 = max(0, y0 - ypad)
    y1 = min(image.height, y1 + ypad)

    crop_width = max(round((x1f - x0f) * slot.width) for slot in slots)
    crop_height = y1 - y0
    scale = min(INNER_W / crop_width, INNER_H / crop_height)
    resized_w = max(1, round(crop_width * scale))
    resized_h = max(1, round(crop_height * scale))

    output_frames: list[Image.Image] = []
    min_source_pose_height = image.height
    for index, (slot, bbox) in enumerate(zip(slots, boxes)):
        sx0 = round(x0f * slot.width)
        sx1 = round(x1f * slot.width)
        crop = slot.crop((sx0, y0, sx1, y1))
        shared = Image.new("RGBA", (crop_width, crop_height), (0, 0, 0, 0))
        shared.alpha_composite(crop, ((crop_width - crop.width) // 2, 0))
        scaled = shared.resize((resized_w, resized_h), Image.Resampling.LANCZOS)
        cell = Image.new("RGBA", (CELL_W, CELL_H), (0, 0, 0, 0))
        cell.alpha_composite(scaled, ((CELL_W - resized_w) // 2, (CELL_H - resized_h) // 2))
        cell = clear_hidden_rgb(cell)
        final_bbox = cell.getchannel("A").getbbox()
        if final_bbox is None:
            raise RuntimeError(f"{row_id} frame {index} became empty")
        margins = (final_bbox[0], final_bbox[1], CELL_W - final_bbox[2], CELL_H - final_bbox[3])
        if min(margins) < 8:
            raise RuntimeError(f"{row_id} frame {index} final margin <8: {margins}")
        min_source_pose_height = min(min_source_pose_height, bbox[3] - bbox[1])
        output_frames.append(cell)

    row = Image.new("RGBA", (CELL_W * 8, CELL_H), (0, 0, 0, 0))
    for index, cell in enumerate(output_frames):
        row.alpha_composite(cell, (index * CELL_W, 0))
    OUT.mkdir(parents=True, exist_ok=True)
    row_path = OUT / f"{row_id}.webp"
    row.save(row_path, "WEBP", lossless=True, method=6)

    frame_dir = FRAMES / row_id
    frame_dir.mkdir(parents=True, exist_ok=True)
    for index, cell in enumerate(output_frames):
        cell.save(frame_dir / f"{index:02d}.png")

    return output_frames, {
        "id": row_id,
        "source": str(source.relative_to(ROOT)),
        "sourceSha256": sha256(source),
        "sourceDimensions": [image.width, image.height],
        "minimumSourcePoseHeight": min_source_pose_height,
        "frames": frame_count,
        "output": str(row_path.relative_to(ROOT)),
        "outputSha256": sha256(row_path),
        "derivation": "imagegen-native-strip -> chroma removal -> shared-crop single-resample",
    }


def derive_running_left(right_frames: list[Image.Image]) -> dict:
    left_frames = [frame.transpose(Image.Transpose.FLIP_LEFT_RIGHT) for frame in right_frames]
    row = Image.new("RGBA", (CELL_W * 8, CELL_H), (0, 0, 0, 0))
    frame_dir = FRAMES / "running-left"
    frame_dir.mkdir(parents=True, exist_ok=True)
    for index, cell in enumerate(left_frames):
        row.alpha_composite(cell, (index * CELL_W, 0))
        cell.save(frame_dir / f"{index:02d}.png")
    path = OUT / "running-left.webp"
    row.save(path, "WEBP", lossless=True, method=6)
    return {
        "id": "running-left",
        "source": "public/pets/xinn/hd/running-right.webp",
        "sourceSha256": sha256(OUT / "running-right.webp"),
        "sourceDimensions": [CELL_W * 8, CELL_H],
        "minimumSourcePoseHeight": None,
        "frames": 8,
        "output": str(path.relative_to(ROOT)),
        "outputSha256": sha256(path),
        "derivation": "approved running-right frames mirrored individually; temporal order preserved",
    }


def make_previews(all_frames: dict[str, list[Image.Image]]) -> None:
    PREVIEWS.mkdir(parents=True, exist_ok=True)
    for row_id, frames in all_frames.items():
        display = [frame.resize((192, 208), Image.Resampling.LANCZOS) for frame in frames]
        display[0].save(
            PREVIEWS / f"{row_id}.gif",
            save_all=True,
            append_images=display[1:],
            duration=DURATIONS[row_id],
            loop=0,
            disposal=2,
            transparency=0,
        )

    thumb_w, thumb_h = 192, 208
    row_order = list(all_frames)
    sheet = Image.new("RGBA", (thumb_w * 8 + 180, thumb_h * len(row_order)), (32, 34, 38, 255))
    draw = ImageDraw.Draw(sheet)
    for row_index, row_id in enumerate(row_order):
        draw.text((8, row_index * thumb_h + 8), row_id, fill=(255, 255, 255, 255))
        for col, frame in enumerate(all_frames[row_id]):
            sheet.alpha_composite(frame.resize((thumb_w, thumb_h), Image.Resampling.LANCZOS), (180 + col * thumb_w, row_index * thumb_h))
    sheet.save(QA / "hd-contact-sheet.png")

    legacy = Image.open(ROOT / "public" / "pets" / "xinn" / "spritesheet.webp").convert("RGBA").crop((0, 0, 192, 208))
    old = legacy.resize((307, round(307 * 208 / 192)), Image.Resampling.BICUBIC)
    new = all_frames["idle"][0].resize((307, round(307 * 208 / 192)), Image.Resampling.LANCZOS)
    compare = Image.new("RGBA", (307 * 2 + 48, old.height + 48), (28, 30, 34, 255))
    compare.alpha_composite(old, (16, 32))
    compare.alpha_composite(new, (32 + 307, 32))
    d = ImageDraw.Draw(compare)
    d.text((16, 8), "OLD 192->307", fill="white")
    d.text((32 + 307, 8), "NATIVE HD ->307", fill="white")
    compare.save(QA / "old-vs-new-307.png")


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    FRAMES.mkdir(parents=True, exist_ok=True)
    provenance = []
    all_frames: dict[str, list[Image.Image]] = {}
    for row_id, count in ROWS.items():
        frames, record = build_row(row_id, count)
        all_frames[row_id] = frames
        provenance.append(record)
        print(f"built {row_id}: {count} frames")
    provenance.insert(2, derive_running_left(all_frames["running-right"]))
    all_frames = {
        "idle": all_frames["idle"],
        "running-right": all_frames["running-right"],
        "running-left": [Image.open(FRAMES / "running-left" / f"{i:02d}.png").convert("RGBA") for i in range(8)],
        **{key: value for key, value in all_frames.items() if key not in {"idle", "running-right"}},
    }
    make_previews(all_frames)
    manifest = {
        "ok": True,
        "generatedBy": "built-in imagegen",
        "placeholder": False,
        "cell": [CELL_W, CELL_H],
        "rows": provenance,
    }
    (OUT / "provenance.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    (QA / "hd-build-provenance.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print("wrote", OUT / "provenance.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
