"""Generate calm piano-style default tracks for the ambient music player.

The built-in fallback links (pixabay CDN) return 403 and the DB seed used
placeholder URLs, so the player had no playable source. These WAVs are
synthesized locally (deterministic, no network, no third-party assets) so the
feature always has something real to play. Replace them anytime via the admin
library with real audio files.

Notes: 44100 Hz / 16-bit / mono, ~36s each, soft piano-ish arpeggios with
exponential decay envelope and 3 harmonics per note. Files are served from
frontend/public/audio/ and cached with the existing runtime CacheFirst rule.
"""
from __future__ import annotations

import math
import struct
import wave
from pathlib import Path

RATE = 44100
AMPLITUDE = 0.32  # 柔和音量，避免突兀
OUT_DIR = Path(__file__).resolve().parents[1] / "public" / "audio"


def note_freq(semitone: int) -> float:
    """A4 = 440Hz (semitone 69); 返回 MIDI 音高对应的频率。"""
    return 440.0 * (2 ** ((semitone - 69) / 12))


def piano_tone(freq: float, duration: float, velocity: float = 1.0) -> list[float]:
    """钢琴近似：基频 + 3 个泛音，指数衰减包络，轻微起音。"""
    n = int(RATE * duration)
    decay = 1 / max(0.15, duration * 1.6)
    samples: list[float] = []
    for i in range(n):
        t = i / RATE
        env = math.exp(-decay * t) * min(1.0, t / 0.004)
        tone = (
            math.sin(2 * math.pi * freq * t)
            + 0.45 * math.sin(2 * math.pi * freq * 2 * t)
            + 0.18 * math.sin(2 * math.pi * freq * 3 * t)
            + 0.08 * math.sin(2 * math.pi * freq * 4 * t)
        )
        samples.append(tone * env * velocity * AMPLITUDE)
    return samples


def render(progression: list[list[int]], chord_dur: float, pattern: list[tuple[int, float]]) -> list[float]:
    """和弦进行 → 琶音旋律：progression 为 MIDI 音高列表，pattern 为 (音符序号, 时长比例)。"""
    out: list[float] = []
    for chord in progression:
        notes = [note_freq(n) for n in chord]
        for offset, ratio in pattern:
            note = notes[offset % len(notes)]
            out.extend(piano_tone(note, chord_dur * ratio))
    return out


def write_wav(name: str, samples: list[float]) -> None:
    # 淡入淡出防止爆音
    fade = min(2400, len(samples) // 8)
    for i in range(fade):
        samples[i] *= i / fade
        samples[-1 - i] *= i / fade
    data = b"".join(struct.pack("<h", max(-32767, min(32767, int(s * 32767)))) for s in samples)
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    path = OUT_DIR / name
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(data)
    print(f"{path.name}: {len(samples)/RATE:.1f}s, {path.stat().st_size} bytes")


def main() -> None:
    # 每首约 32 秒：4 和弦 × 2 轮，每和弦 4 个琶音音符（各 ~1s）
    # 1) 雨的印记风格：C - G - Am - F 琶音（分解和弦）
    c = [48, 52, 55, 60]   # C3 E3 G3 C4
    g = [43, 47, 50, 55]   # G2 B2 D3 G3
    am = [45, 48, 52, 57]  # A2 C3 E3 A3
    f = [41, 45, 48, 53]   # F2 A2 C3 F3
    write_wav("calm-piano-1.wav", render([c, g, am, f] * 2, 2.0, [(0, 0.5), (2, 0.5), (1, 0.5), (3, 0.5)]))

    # 2) 静谧森林风格：Dm - Bb - F - C 慢琶音
    dm = [50, 53, 57, 62]
    bb = [46, 50, 53, 58]
    f2 = [41, 45, 48, 53]
    c2 = [48, 52, 55, 60]
    write_wav("calm-piano-2.wav", render([dm, bb, f2, c2] * 2, 2.0, [(0, 0.5), (2, 0.5), (1, 0.5), (3, 0.5)]))

    # 3) 月光边境风格：Am - Em - F - G 低音 + 高音琶音
    am2 = [45, 48, 52, 57]
    em = [40, 47, 52, 55]
    f3 = [41, 45, 48, 53]
    g3 = [43, 47, 50, 55]
    write_wav("calm-piano-3.wav", render([am2, em, f3, g3] * 2, 2.0, [(0, 0.5), (2, 0.5), (1, 0.5), (3, 0.5)]))


if __name__ == "__main__":
    main()
