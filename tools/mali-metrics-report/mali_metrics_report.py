#!/usr/bin/env python3
"""Resumo/compare dos `metrics-*.jsonl` do GameNative para os A/B do plano Mali/MediaTek.

Uso:
    # analisa arquivos locais
    python3 mali_metrics_report.py sessaoA.jsonl [sessaoB.jsonl]

    # puxa do device (adb) e analisa os dois mais recentes
    python3 mali_metrics_report.py --pull [--serial <serial>] [--last 2]

Referencias: docs/plano-mali-mediatek.md (protocolos de A/B: F1/F2/G1/H2/I5) e
docs/plano-mali-mediatek-onda0-impl.md. O coletor sempre-ativo grava esses arquivos em
/sdcard/Android/data/app.gamenative/files/powercontrol/.

Só usa a biblioteca padrão. Faz a estatística apenas sobre amostras com fps > 0 (ignora o
aquecimento), e marca quantas amostras trazem temperatura/nível térmico.
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import statistics
import subprocess
import sys
import tempfile

DEVICE_DIR = "/sdcard/Android/data/app.gamenative/files/powercontrol"


def pull_metrics(serial: str | None, last: int) -> list[str]:
    dest = tempfile.mkdtemp(prefix="gamenative-metrics-")
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    cmd += ["pull", DEVICE_DIR, dest]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        sys.exit(f"adb pull falhou: {result.stderr.strip()}")
    files = sorted(glob.glob(os.path.join(dest, "powercontrol", "metrics-*.jsonl")))
    if not files:
        sys.exit(f"nenhum metrics-*.jsonl em {DEVICE_DIR}")
    return files[-last:]


def load(path: str) -> list[dict]:
    rows: list[dict] = []
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    return rows


def _values(rows: list[dict], key: str) -> list[float]:
    return [float(r[key]) for r in rows if r.get(key) is not None]


def summarize(path: str) -> dict:
    rows = load(path)
    gaming = [r for r in rows if r.get("fps", 0) > 0]
    fps = _values(gaming, "fps")
    p95 = _values(gaming, "frameTimeP95Ms")
    return {
        "path": path,
        "samples": len(rows),
        "gaming_samples": len(gaming),
        "mean_fps": statistics.fmean(fps) if fps else 0.0,
        "median_p95_ms": statistics.median(p95) if p95 else 0.0,
        "max_p95_ms": max(p95) if p95 else 0.0,
        "slow_frames": sum(int(r.get("slowFrameCount", 0)) for r in rows),
        "mean_gpu": statistics.fmean(_values(rows, "gpuUsagePercent")) if _values(rows, "gpuUsagePercent") else None,
        "mean_cpu": statistics.fmean(_values(rows, "cpuUsagePercent")) if _values(rows, "cpuUsagePercent") else None,
        "temp_samples": sum(1 for r in rows if r.get("cpuTempC") is not None or r.get("gpuTempC") is not None),
        "thermal_samples": sum(1 for r in rows if r.get("thermalStatus") is not None),
        "mean_headroom": statistics.fmean(_values(rows, "thermalHeadroom")) if _values(rows, "thermalHeadroom") else None,
        "max_headroom": max(_values(rows, "thermalHeadroom")) if _values(rows, "thermalHeadroom") else None,
    }


def fmt(value, unit: str = "", nd: int = 1) -> str:
    if value is None:
        return "-"
    return f"{value:.{nd}f}{unit}"


def print_row(s: dict) -> None:
    print(f"{os.path.basename(s['path'])}")
    print(f"  amostras: {s['samples']} (jogando: {s['gaming_samples']})")
    print(f"  fps médio: {fmt(s['mean_fps'])}  p95 mediano: {fmt(s['median_p95_ms'], ' ms')}  "
          f"p95 máx: {fmt(s['max_p95_ms'], ' ms')}")
    print(f"  slow frames: {s['slow_frames']}  gpu% médio: {fmt(s['mean_gpu'])}  "
          f"cpu% médio: {fmt(s['mean_cpu'])}")
    print(f"  amostras com temperatura: {s['temp_samples']}  com nível térmico: {s['thermal_samples']}")
    print(f"  headroom médio: {fmt(s['mean_headroom'], '', 2)}  headroom máx: {fmt(s['max_headroom'], '', 2)}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("files", nargs="*", help="metrics-*.jsonl locais")
    parser.add_argument("--pull", action="store_true", help="puxar do device via adb antes de analisar")
    parser.add_argument("--serial", default=None, help="serial do adb (se houver mais de um device)")
    parser.add_argument("--last", type=int, default=2, help="quantos arquivos usar com --pull (default 2)")
    args = parser.parse_args()

    files = args.files
    if args.pull:
        files = pull_metrics(args.serial, args.last)
    if not files:
        parser.error("informe arquivos ou use --pull")

    summaries = [summarize(path) for path in files]
    for summary in summaries:
        print_row(summary)
        print()

    if len(summaries) == 2:
        a, b = summaries
        print("A/B (B - A):")
        print(f"  fps médio: {b['mean_fps'] - a['mean_fps']:+.1f}  "
              f"p95 mediano: {b['median_p95_ms'] - a['median_p95_ms']:+.1f} ms  "
              f"slow frames: {b['slow_frames'] - a['slow_frames']:+d}")
        print("  (B melhor se fps↑ e p95/slow↓; registre o resultado no spec/plano antes de virar default)")


if __name__ == "__main__":
    main()
