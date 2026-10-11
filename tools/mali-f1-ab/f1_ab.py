#!/usr/bin/env python3
"""Executor do A/B do F1 (WRAPPER_BYPASS_SWAPCHAIN_PLACED) — docs/plano-mali-mediatek.md.

Por que existe: o primeiro A/B manual (2026-10-10, Tiny and Big no SM-A146M) saiu
**inconclusivo por confusão de protocolo**, não por mérito do F1:

  1. as duas pernas tiveram durações diferentes (789 vs 401 amostras);
  2. a perna A incluía o warm-up "Installing Mono..." (p95 máx 1356 ms vs 216 ms);
  3. o caminho de present escolhido pelo wrapper não foi registrado, então um
     resultado nulo ficou ambíguo (o bypass engatou ou não?);
  4. foi usada a métrica errada: o painel é 60/90 Hz e o jogo rodava a ~62 fps,
     isto é, **limitado por vsync**. Sob vsync, um ganho de WSI/blit NÃO aparece
     como fps — aparece como GPU% e frame-time menores.

Este script padroniza as duas pernas para que a comparação seja válida:
duração idêntica, mesma cena (use um jogo com benchmark embutido), log do wrapper
ligado em ambas, e as métricas de GPU%/frame-time no relatório.

Uso:
    # valida a lógica sem tocar no device
    python3 tools/mali-f1-ab/f1_ab.py --container STEAM_205910 --dry-run

    # executa o A/B de verdade (device conectado, jogo JÁ instalado)
    python3 tools/mali-f1-ab/f1_ab.py --container STEAM_205910 --seconds 120

Requer `adb` no PATH. Não instala nada e não apaga nada: só escreve no
`graphicsDriverConfig`/`envVars` do container indicado, a partir de um backup
que é restaurado no fim (`--keep-config` desliga a restauração).
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time

LEG_OFF = "off"
LEG_ON = "on"
# Diretório que o coletor sempre-ativo já usa e que comprovadamente é gravável pelo
# uid do app (ele escreve metrics-*.jsonl ali).
WRAPPER_LOG_DIR = "/sdcard/Android/data/app.gamenative/files/powercontrol"
METRICS_DIR = WRAPPER_LOG_DIR


def adb(serial: str | None, *args: str, stdin: bytes | None = None,
        check: bool = True) -> subprocess.CompletedProcess:
    cmd = ["adb"] + (["-s", serial] if serial else []) + list(args)
    res = subprocess.run(cmd, input=stdin, stdout=subprocess.PIPE,
                         stderr=subprocess.STDOUT)
    if check and res.returncode != 0:
        raise RuntimeError(f"{' '.join(cmd)} falhou:\n{res.stdout.decode(errors='replace')}")
    return res


def shell(serial: str | None, cmd: str, stdin: bytes | None = None,
          check: bool = True) -> str:
    return adb(serial, "shell", cmd, stdin=stdin, check=check).stdout.decode(errors="replace")


def container_path(container_id: str) -> str:
    return f"files/imagefs/home/xuser-{container_id}/.container"


def read_container(serial: str | None, container_id: str) -> dict:
    raw = shell(serial, f"run-as app.gamenative cat {container_path(container_id)}")
    if not raw.strip():
        raise RuntimeError(
            f"não consegui ler o .container de {container_id}. "
            "Ele existe? O app é um build debug (run-as exige isso)?"
        )
    return json.loads(raw)


def patch_config(cfg: dict, bypass: bool, log_path: str) -> dict:
    """Aplica a alavanca do F1 e liga o log do wrapper, preservando o resto."""
    out = json.loads(json.dumps(cfg))  # cópia
    gdc = out.get("graphicsDriverConfig", "")
    gdc = ",".join(p for p in gdc.split(",") if not p.startswith("bypassSwapchainPlaced="))
    if bypass:
        gdc = f"{gdc},bypassSwapchainPlaced=1"
    out["graphicsDriverConfig"] = gdc

    env = out.get("envVars", "")
    drop = ("WRAPPER_LOG_LEVEL=", "WRAPPER_LOG_FILE=")
    env = " ".join(t for t in env.split(" ") if not t.startswith(drop))
    env = f"{env} WRAPPER_LOG_LEVEL=info WRAPPER_LOG_FILE={log_path}"
    out["envVars"] = env
    return out


def write_container(serial: str | None, container_id: str, cfg: dict) -> None:
    payload = json.dumps(cfg, ensure_ascii=False).encode("utf-8")
    shell(serial, f"run-as app.gamenative sh -c 'cat > {container_path(container_id)}'",
          stdin=payload)


def app_id_of(container_id: str) -> int:
    m = re.search(r"(\d+)$", container_id)
    if not m:
        raise SystemExit(f"não consegui extrair o app id de {container_id!r} "
                         "(esperado algo como STEAM_205910)")
    return int(m.group(1))


def run_leg(serial: str | None, container_id: str, app_id: int, leg: str,
            seconds: int, out_dir: str) -> None:
    bypass = leg == LEG_ON
    log_path = f"{WRAPPER_LOG_DIR}/wrapper-{leg}.log"

    print(f"[{leg}] parando o app e aplicando a config (bypass={int(bypass)})...")
    shell(serial, "am force-stop app.gamenative", check=False)
    time.sleep(3)
    base = read_container(serial, container_id)
    write_container(serial, container_id, patch_config(base, bypass, log_path))
    time.sleep(1)

    shell(serial, "run-as app.gamenative rm -f " + log_path, check=False)
    adb(serial, "logcat", "-c", check=False)
    print(f"[{leg}] lançando o jogo por {seconds}s (cena DEVE ser a mesma das duas pernas)...")
    shell(serial, f"am start -a app.gamenative.LAUNCH_GAME --ei app_id {app_id} "
                  "--es game_source STEAM", check=False)
    time.sleep(seconds)
    shell(serial, "am force-stop app.gamenative", check=False)
    time.sleep(3)

    # env var realmente entregue ao wrapper (prova do plumbing)
    env_line = shell(serial, "logcat -d -s ProcessHelper", check=False)
    m = re.search(r"WRAPPER_BYPASS_SWAPCHAIN_PLACED=(\d)", env_line)
    print(f"[{leg}] env var entregue: WRAPPER_BYPASS_SWAPCHAIN_PLACED="
          f"{m.group(1) if m else '??'} (esperado {int(bypass)})")

    os.makedirs(out_dir, exist_ok=True)
    newest = shell(serial, f"ls -t {METRICS_DIR}/metrics-*.jsonl 2>/dev/null | head -1").strip()
    if newest:
        data = adb(serial, "exec-out", f"cat {newest}").stdout
        dest = os.path.join(out_dir, f"leg-{leg}.jsonl")
        with open(dest, "wb") as fh:
            fh.write(data)
        print(f"[{leg}] métricas -> {dest} ({len(data.splitlines())} amostras)")

    # mecanismo: o caminho de WSI realmente escolhido pelo wrapper
    wl = shell(serial, f"cat {log_path}", check=False)
    if wl.strip():
        wdest = os.path.join(out_dir, f"wrapper-{leg}.log")
        with open(wdest, "w", encoding="utf-8") as fh:
            fh.write(wl)
        bypassing = wl.count("Bypassing EXT_map_memory_placed emulation for swapchain image")
        emulating = wl.count("Emulating vkAllocateMemory")
        print(f"[{leg}] wrapper: 'Bypassing...swapchain'={bypassing}  "
              f"'Emulating vkAllocateMemory'={emulating}  -> {wdest}")
    else:
        print(f"[{leg}] AVISO: log do wrapper vazio em {log_path} "
              "(o wrapper não escreveu; verifique WRAPPER_LOG_FILE/permissão)")


def main() -> int:
    ap = argparse.ArgumentParser(description="A/B do F1 no Mali (plano §11.5)")
    ap.add_argument("--container", required=True, help="ex.: STEAM_205910")
    ap.add_argument("--seconds", type=int, default=120,
                    help="duração de CADA perna (igual nas duas; default 120)")
    ap.add_argument("--serial", default=None, help="serial do adb (se houver >1 device)")
    ap.add_argument("--out", default="reference/_f1ab", help="diretório de saída")
    ap.add_argument("--dry-run", action="store_true",
                    help="valida a lógica e o device sem escrever/lançar nada")
    ap.add_argument("--keep-config", action="store_true",
                    help="não restaura o .container original no fim")
    args = ap.parse_args()

    app_id = app_id_of(args.container)
    cpath = container_path(args.container)

    if args.dry_run:
        sample = {"id": args.container, "graphicsDriverConfig": "vulkanVersion=1.3,version=System",
                  "envVars": "WINEESYNC=1 WRAPPER_LOG_LEVEL=info"}
        off = patch_config(sample, False, f"{WRAPPER_LOG_DIR}/wrapper-off.log")
        on = patch_config(sample, True, f"{WRAPPER_LOG_DIR}/wrapper-on.log")
        assert "bypassSwapchainPlaced=1" in on["graphicsDriverConfig"]
        assert "bypassSwapchainPlaced" not in off["graphicsDriverConfig"]
        assert off["envVars"].count("WRAPPER_LOG_LEVEL=") == 1, "duplicou WRAPPER_LOG_LEVEL"
        assert on["envVars"].count("WRAPPER_LOG_FILE=") == 1, "duplicou WRAPPER_LOG_FILE"
        print("dry-run OK")
        print("  app id.......:", app_id)
        print("  .container...:", cpath)
        print("  leg OFF cfg..:", off["graphicsDriverConfig"][-40:])
        print("  leg ON  cfg..:", on["graphicsDriverConfig"][-40:])
        print("  log wrapper..:", f"{WRAPPER_LOG_DIR}/wrapper-{{off,on}}.log")
        devices = adb(args.serial, "devices", check=False).stdout.decode(errors="replace")
        print("  adb devices..:\n" + "\n".join("    " + l for l in devices.splitlines()[1:] if l.strip()))
        return 0

    if not shutil.which("adb"):
        raise SystemExit("adb não encontrado no PATH")
    original = read_container(args.serial, args.container)
    backup = os.path.join(args.out, f"container-{args.container}.orig.json")
    os.makedirs(args.out, exist_ok=True)
    with open(backup, "w", encoding="utf-8") as fh:
        json.dump(original, fh, ensure_ascii=False)
    print(f"backup do .container -> {backup}")

    try:
        for leg in (LEG_OFF, LEG_ON):
            run_leg(args.serial, args.container, app_id, leg, args.seconds, args.out)
    finally:
        if not args.keep_config:
            write_container(args.serial, args.container, original)
            print("config original restaurada")

    print("\nCompare as duas pernas (fps é secundário sob vsync; olhe GPU% e p95):")
    print(f"  python3 tools/mali-metrics-report/mali_metrics_report.py "
          f"{args.out}/leg-{LEG_OFF}.jsonl {args.out}/leg-{LEG_ON}.jsonl")
    return 0


if __name__ == "__main__":
    sys.exit(main())
