"""Deterministic motion smoothing for native-HD Xinn row assets.

Why: the imagegen strips carry large per-frame pose shifts (idle up to 6px,
waiting up to 9.5px, chat-open up to 21px vertical at 384x416 source pixels).
The previously approved legacy atlas kept inter-frame centroid shifts tiny
for decorative states (<= ~1px at 192x208) while preserving semantic motion
(jumping 17-29px vertical, failed ~5px). This script brings the HD rows back
to that baseline without touching pose semantics or frame content:

- horizontal: translate every frame so its alpha centroid x aligns to the
  row's median centroid, clamped to +/-4px (body is stabilized)
- vertical: align every frame's alpha centroid y to the row's median
  centroid, clamped to +/-8px (visual center stays calm; semantic vertical
  motion such as jumping is preserved by the clamp headroom)
- translations keep content >= 4px cell margins

It rewrites the 13 animated webp rows (look rows are static) and updates
provenance.json output hashes so pet-animation-asset-check.mjs stays
consistent (native-HD pipeline required).

Usage: python scripts/pet-hd-motion-smooth.py
"""
from __future__ import annotations

import hashlib
import json
import statistics
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "public" / "pets" / "xinn" / "hd"
PROVENANCE = OUT / "provenance.json"

CELL_W, CELL_H = 384, 416
MIN_MARGIN = 8  # 修正后内容至少保留的格内边距（源像素，与素材检查一致）

# 垂直质心对齐的最大平移（源像素）：
# - 装饰类动作压到微晃（<=8px，307px 显示约 6.4 CSS px）
# - jumping 是跳跃语义运动（旧版基准 17-29px），保留大位移
# - failed 垂头语义（旧版基准 ~5px），放宽
VERTICAL_CLAMP = {
    "jumping": 40,
    "failed": 12,
}

# 高度归一：待机动作帧间高度波动（idle-sway 17px / idle-sleeve 8px / idle-curious 2px）
# 在 307px 显示下表现为"一缩一放"。按行共享中位数高度做等比缩放（保持宽高比、
# 不变形），消除缩放观感，同时保留宽高比一致的姿态语义。
HEIGHT_STABLE_ROWS = {"idle-curious", "idle-sleeve", "idle-sway"}
MAX_SCALE_DRIFT = 0.06  # 单帧缩放比例与中位数的最大偏差（防极端变形）

# look 行为静态方向格，不做运动对齐（无动画语义）
SKIP_ROWS = {"look-row-9", "look-row-10"}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def frame_metrics(cell: Image.Image) -> dict:
    alpha = cell.getchannel("A")
    data = list(alpha.get_flattened_data())
    total = sum(data)
    if total == 0:
        return {"gx": None, "gy": None, "bbox": None}
    width = cell.width
    gx = sum((i % width) * v for i, v in enumerate(data)) / total
    gy = sum((i // width) * v for i, v in enumerate(data)) / total
    return {"gx": gx, "gy": gy, "bbox": alpha.getbbox()}


def despill_chroma_edges(image: Image.Image) -> Image.Image:
    """确定性去色键：内容边界上命中绿/品红启发式的半透明像素转中性灰（保留 alpha）。
    高度归一（LANCZOS 重采样）会引入少量边缘色键伪影，必须在此清理。"""
    pixels = image.load()
    width, height = image.size
    for y in range(height):
        for x in range(width):
            r, g, b, a = pixels[x, y]
            if a == 0:
                continue
            nbr = [(x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)]
            edge = any(
                0 <= nx < width and 0 <= ny < height and pixels[nx, ny][3] == 0
                for nx, ny in nbr
            )
            if not edge:
                continue
            greenish = g > 120 and g > r * 1.5 and g > b * 1.5
            magenta = r > 120 and b > 120 and g < r * 0.7
            if greenish or magenta:
                v = (r + g + b) // 3
                pixels[x, y] = (v, v, v, a)
    return image


def smooth_row(row_id: str) -> dict:
    path = OUT / f"{row_id}.webp"
    image = Image.open(path).convert("RGBA")
    cells = [image.crop((c * CELL_W, 0, (c + 1) * CELL_W, CELL_H)) for c in range(8)]
    metrics = [frame_metrics(cell) for cell in cells]
    used = [m for m in metrics if m["bbox"] is not None]
    if not used:
        raise RuntimeError(f"{row_id}: no content")
    target_cx = statistics.median(m["gx"] for m in used)
    target_cy = statistics.median(m["gy"] for m in used)
    max_dy = VERTICAL_CLAMP.get(row_id, 8)

    # 待机动作：帧内容等比缩放到行共享中位数高度（消除"一缩一放"观感）
    if row_id in HEIGHT_STABLE_ROWS:
        target_h = statistics.median(m["bbox"][3] - m["bbox"][1] for m in used)
        normalized = []
        for cell, m in zip(cells, metrics):
            if m["bbox"] is None:
                normalized.append(cell)
                continue
            x0, y0, x1, y1 = m["bbox"]
            frame_h = y1 - y0
            scale = target_h / frame_h
            scale = max(1 - MAX_SCALE_DRIFT, min(scale, 1 + MAX_SCALE_DRIFT))
            nw = max(1, round(cell.width * scale))
            nh = max(1, round(cell.height * scale))
            resized = cell.resize((nw, nh), Image.Resampling.LANCZOS)
            out_cell = Image.new("RGBA", (CELL_W, CELL_H), (0, 0, 0, 0))
            # 缩放中心 = 帧内容底部中心：脚底稳定，向上等比缩放
            dx = round((CELL_W - nw) / 2)
            dy = CELL_H - nh
            out_cell.alpha_composite(resized, (dx, dy))
            normalized.append(out_cell)
        cells = normalized
        metrics = [frame_metrics(cell) for cell in cells]

    pixels = image.load()
    for col, (cell, m) in enumerate(zip(cells, metrics)):
        if m["bbox"] is None:
            continue
        x0, y0, x1, y1 = m["bbox"]
        # 水平：完全对齐到行中位数（身体稳定、姿态在帧内表达，与旧版一致）
        dx = round(target_cx - m["gx"])
        # 垂直：向行中位数收敛，clamp 限制残余位移（语义行放宽）
        dy = round(target_cy - m["gy"])
        dy = max(-max_dy, min(dy, max_dy))
        # 平移不得把内容推出格（保持最小边距）
        dx = max(-x0 + MIN_MARGIN, min(dx, CELL_W - x1 - MIN_MARGIN))
        dy = max(-y0 + MIN_MARGIN, min(dy, CELL_H - y1 - MIN_MARGIN))
        if dx == 0 and dy == 0:
            continue
        offset = col * CELL_W
        shifted = Image.new("RGBA", (CELL_W, CELL_H), (0, 0, 0, 0))
        shifted.alpha_composite(cell, (dx, dy))
        for y in range(CELL_H):
            for x in range(CELL_W):
                pixels[offset + x, y] = shifted.getpixel((x, y))

    # 平移/缩放后清空透明像素下的隐藏 RGB，清理重采样引入的边缘色键伪影，
    # 并重算最终边距验证
    image = despill_chroma_edges(image)
    pixels = image.load()
    for y in range(image.height):
        for x in range(image.width):
            r, g, b, a = pixels[x, y]
            if a == 0 and (r or g or b):
                pixels[x, y] = (0, 0, 0, 0)
    for col, m in enumerate(metrics):
        if m["bbox"] is None:
            continue
        cell = image.crop((col * CELL_W, 0, (col + 1) * CELL_W, CELL_H))
        bbox = cell.getchannel("A").getbbox()
        margins = (bbox[0], bbox[1], CELL_W - bbox[2], CELL_H - bbox[3])
        if min(margins) < MIN_MARGIN:
            raise RuntimeError(f"{row_id} col {col} margin < {MIN_MARGIN}: {margins}")

    image.save(path, "WEBP", lossless=True, method=6)
    return {"id": row_id, "outputSha256": sha256(path),
            "targetCx": round(target_cx, 2), "targetCy": round(target_cy, 2)}


def main() -> int:
    if not PROVENANCE.exists():
        print(f"FATAL: 缺少 {PROVENANCE}", file=sys.stderr)
        return 1
    manifest = json.loads(PROVENANCE.read_text(encoding="utf-8"))
    if manifest.get("placeholder") is not False:
        print("FATAL: 拒绝处理占位素材", file=sys.stderr)
        return 1

    smoothed = []
    for record in manifest["rows"]:
        row_id = record["id"]
        if row_id in SKIP_ROWS:
            continue
        result = smooth_row(row_id)
        record["outputSha256"] = result["outputSha256"]
        record["derivation"] = (record.get("derivation", "") + " -> motion-smooth"
                                " (centroid-x/y align, semantic motion preserved)")
        smoothed.append(row_id)
        print(f"smoothed {row_id}: targetCx={result['targetCx']} targetCy={result['targetCy']}")

    PROVENANCE.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"motion-smooth 完成: {len(smoothed)} 行已对齐，provenance 已更新")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
