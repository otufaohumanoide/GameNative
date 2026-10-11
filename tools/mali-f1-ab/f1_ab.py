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


def patch_config(cfg: dict, bypass: bool, log_path: str, driver: str | None = None) -> dict:
    """Aplica a alavanca do F1 e liga o log do wrapper, preservando o resto."""
    out = json.loads(json.dumps(cfg))  # cópia
    if driver:
        # O lever só existe no blob do `wrapper-gamenative`. Verificado por `strings`:
        # wrapper.tzst (Wrapper / Wrapper-v2 / Wrapper-legacy, ago/2025) NÃO tem
        # WRAPPER_BYPASS_SWAPCHAIN_PLACED nem WRAPPER_BCN_ASTC — testar com ele seria
        # medir uma variável que o driver ignora.
        out["graphicsDriver"] = driver

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


def run_leg(serial: str | None, container_id: str, app_id: int, leg: str, phase: str,
            seconds: int, out_dir: str, driver: str | None = None) -> str:
    """Roda UMA perna e devolve o caminho do jsonl. `phase` rotula o arquivo (ex.: '1-off')."""
    bypass = leg == LEG_ON
    log_path = f"{WRAPPER_LOG_DIR}/wrapper-{phase}.log"

    print(f"[{phase}] parando o app e aplicando a config (bypass={int(bypass)})...")
    shell(serial, "am force-stop app.gamenative", check=False)
    time.sleep(3)
    base = read_container(serial, container_id)
    write_container(serial, container_id, patch_config(base, bypass, log_path, driver))
    time.sleep(1)

    shell(serial, "run-as app.gamenative rm -f " + log_path, check=False)
    adb(serial, "logcat", "-c", check=False)
    print(f"[{phase}] lançando o jogo por {seconds}s...")
    shell(serial, f"am start -a app.gamenative.LAUNCH_GAME --ei app_id {app_id} "
                  "--es game_source STEAM", check=False)
    time.sleep(seconds)
    shell(serial, "am force-stop app.gamenative", check=False)
    time.sleep(3)

    # env var realmente entregue ao wrapper (prova do plumbing)
    env_line = shell(serial, "logcat -d -s ProcessHelper", check=False)
    m = re.search(r"WRAPPER_BYPASS_SWAPCHAIN_PLACED=(\d)", env_line)
    print(f"[{phase}] env var entregue: WRAPPER_BYPASS_SWAPCHAIN_PLACED="
          f"{m.group(1) if m else '??'} (esperado {int(bypass)})")

    os.makedirs(out_dir, exist_ok=True)
    newest = shell(serial, f"ls -t {METRICS_DIR}/metrics-*.jsonl 2>/dev/null | head -1").strip()
    dest = os.path.join(out_dir, f"leg-{phase}.jsonl")
    if newest:
        data = adb(serial, "exec-out", f"cat {newest}").stdout
        with open(dest, "wb") as fh:
            fh.write(data)
        print(f"[{phase}] métricas -> {dest} ({len(data.splitlines())} amostras)")
    else:
        print(f"[{phase}] ERRO: nenhum metrics-*.jsonl no device — o jogo não rodou.")

    # mecanismo: o caminho de WSI realmente escolhido pelo wrapper
    # NB: sem o 2>/dev/null um `cat` de arquivo ausente devolve a PRÓPRIA mensagem de erro como
    # "conteúdo", e o script reportava sucesso com o log inexistente. Não repetir isso.
    wl = shell(serial, f"cat {log_path} 2>/dev/null", check=False)
    if not wl.strip() or "No such file" in wl:
        print(f"[{phase}] AVISO: o wrapper NÃO criou {log_path}. O mecanismo (qual caminho de WSI "
              "foi escolhido) ficou SEM PROVA — um 'sem diferença' nesta perna é ambíguo.")
    else:
        wdest = os.path.join(out_dir, f"wrapper-{phase}.log")
        with open(wdest, "w", encoding="utf-8") as fh:
            fh.write(wl)
        bypassing = wl.count("Bypassing EXT_map_memory_placed emulation for swapchain image")
        emulating = wl.count("Emulating vkAllocateMemory")
        print(f"[{phase}] wrapper: 'Bypassing...swapchain'={bypassing}  "
              f"'Emulating vkAllocateMemory'={emulating}  -> {wdest}")
        if bypassing == 0 and emulating == 0:
            print(f"[{phase}] AVISO: o log existe mas não traz nenhuma linha do caminho de "
                  "memória/WSI — o mecanismo continua sem prova.")
    return dest


def segments(path: str, label: str, window: int = 30) -> None:
    """fps/GPU% em início/meio/fim de uma perna.

    Motivo (plano §11.7): num jogo que mistura menu (GPU ~20 %) e gameplay (GPU ~88 %), a média
    da perna mede a MISTURA, não o F1. Foi assim que um Δ de −15.8 fps apareceu com as duas
    pernas idênticas nos primeiros 30 s.
    """
    rows = []
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                r = json.loads(line)
            except json.JSONDecodeError:
                continue
            if (r.get("fps") or 0) > 0:
                rows.append(r)
    if not rows:
        return
    print(f"  [{label}] {len(rows)} amostras jogando")
    for name, start in (("início", 0), ("meio", len(rows) // 2), ("fim", max(0, len(rows) - window))):
        seg = rows[start:start + window]
        if not seg:
            continue
        fps = sum(x["fps"] for x in seg) / len(seg)
        gpu = sum((x.get("gpuUsagePercent") or 0) for x in seg) / len(seg)
        cpu = sum((x.get("cpuUsagePercent") or 0) for x in seg) / len(seg)
        print(f"      {name:7} n={len(seg):3d}  fps={fps:5.1f}  gpu={gpu:4.1f}%  cpu={cpu:4.1f}%")


def scene_bands(path: str) -> dict[str, tuple[float, int]]:
    """fps médio por faixa de GPU% (proxy da cena: menu vs gameplay).

    Comparar pernas pela MESMA faixa é a única forma justa quando a mistura de cena difere.
    """
    bands: dict[str, list[float]] = {"baixa(<40%)": [], "média(40-70%)": [], "alta(>70%)": []}
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                r = json.loads(line)
            except json.JSONDecodeError:
                continue
            if (r.get("fps") or 0) <= 0:
                continue
            g = r.get("gpuUsagePercent") or 0
            key = "baixa(<40%)" if g < 40 else ("média(40-70%)" if g <= 70 else "alta(>70%)")
            bands[key].append(r["fps"])
    return {k: (sum(v) / len(v), len(v)) for k, v in bands.items() if v}



def playing_stats(path: str) -> tuple[int, int]:
    """(amostras totais, amostras 'jogando' = fps > 0)."""
    total = playing = 0
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            total += 1
            try:
                rec = json.loads(line)
            except json.JSONDecodeError:
                continue
            if (rec.get("fps") or 0) > 0:
                playing += 1
    return total, playing


def validate_phases(phases: list[tuple[str, str]], out_dir: str, min_playing: int) -> bool:
    """Recusa comparar pernas não comparáveis — agora com AGREGADO por condição.

    Motivo (plano §11.5/§11.6/§11.7): o script já imprimiu Δ em cima de 763 vs 343 amostras e de
    44 vs 13. Um Δ entre pernas inválidas é ruído com aparência de resultado.

    `phases` é [(phase, leg)] na ordem executada; com --abba a ordem é off,on,on,off, então a
    deriva térmica (que é monotônica) cancela ao somar as duas pernas de cada condição.
    """
    ok = True
    pooled: dict[str, list[int]] = {LEG_OFF: [], LEG_ON: []}
    for phase, leg in phases:
        p = os.path.join(out_dir, f"leg-{phase}.jsonl")
        if not os.path.exists(p):
            print(f"PERNA {phase} AUSENTE ({p})")
            ok = False
            continue
        total, playing = playing_stats(p)
        pooled[leg].append(playing)
        verdict = "ok" if playing >= min_playing else "INVÁLIDA"
        print(f"perna {phase}: {total} amostras, {playing} jogando (mínimo {min_playing}) -> {verdict}")
        if playing < min_playing:
            ok = False

    tot = {leg: sum(v) for leg, v in pooled.items()}
    if all(tot.values()):
        a, b = tot[LEG_OFF], tot[LEG_ON]
        if abs(a - b) / max(a, b) > 0.25:
            print(f"CONDIÇÕES ASSIMÉTRICAS: OFF={a} vs ON={b} amostras jogando (>25%) — as pernas "
                  "não cobriram a mesma quantidade de jogo; Δ não é atribuível ao F1.")
            ok = False
        else:
            print(f"agregado por condição: OFF={a} amostras jogando, ON={b} (diferença "
                  f"{abs(a - b) / max(a, b) * 100:.0f}%, dentro do aceitável)")
    return ok


def main() -> int:
    ap = argparse.ArgumentParser(description="A/B do F1 no Mali (plano §11.5)")
    ap.add_argument("--container", required=True, help="ex.: STEAM_205910")
    ap.add_argument("--seconds", type=int, default=120,
                    help="duração de CADA perna (igual nas duas; default 120)")
    ap.add_argument("--serial", default=None, help="serial do adb (se houver >1 device)")
    ap.add_argument("--driver", default=None,
                    help="força o graphicsDriver nas duas pernas (os levers do F1/H2 só existem "
                         "no blob do `wrapper-gamenative`; com `Wrapper` a variável é ignorada). "
                         "Restaurado no fim junto com o resto do .container")
    ap.add_argument("--prewarm-seconds", type=int, default=0,
                    help="roda uma vez FORA da medição antes das pernas, para absorver extração de "
                         "driver/primeiro boot que senão cairia inteiro na perna OFF")
    ap.add_argument("--min-playing", type=int, default=60,
                    help="mínimo de amostras com fps>0 em CADA perna para o Δ ser emitido "
                         "(default 60; abaixo disso a perna é marcada inválida e nada é comparado)")
    ap.add_argument("--abba", action="store_true",
                    help="ordem espelhada OFF,ON,ON,OFF. Necessário quando o aparelho esquenta "
                         "durante a sessão (deriva térmica monotônica): com ordem fixa a segunda "
                         "perna é sempre a mais quente, e o Δ mede a temperatura, não o F1. Custa "
                         "o dobro de tempo")
    ap.add_argument("--out", default="reference/_f1ab", help="diretório de saída")
    ap.add_argument("--dry-run", action="store_true",
                    help="valida a lógica e o device sem escrever/lançar nada")
    ap.add_argument("--keep-config", action="store_true",
                    help="não restaura o .container original no fim")
    args = ap.parse_args()

    # Com --abba a deriva térmica (monotônica) cancela: a segunda perna de cada condição é a
    # mais quente e a primeira a mais fria, então a média por condição remove o viés de ordem.
    phases = [LEG_OFF, LEG_ON, LEG_ON, LEG_OFF] if args.abba else [LEG_OFF, LEG_ON]

    app_id = app_id_of(args.container)
    cpath = container_path(args.container)

    if args.dry_run:
        sample = {"id": args.container, "graphicsDriver": "Wrapper",
                  "graphicsDriverConfig": "vulkanVersion=1.3,version=System",
                  "envVars": "WINEESYNC=1 WRAPPER_LOG_LEVEL=info"}
        off = patch_config(sample, False, f"{WRAPPER_LOG_DIR}/wrapper-off.log", args.driver)
        on = patch_config(sample, True, f"{WRAPPER_LOG_DIR}/wrapper-on.log", args.driver)
        assert "bypassSwapchainPlaced=1" in on["graphicsDriverConfig"]
        assert "bypassSwapchainPlaced" not in off["graphicsDriverConfig"]
        assert off["envVars"].count("WRAPPER_LOG_LEVEL=") == 1, "duplicou WRAPPER_LOG_LEVEL"
        assert on["envVars"].count("WRAPPER_LOG_FILE=") == 1, "duplicou WRAPPER_LOG_FILE"
        if args.driver:
            assert off["graphicsDriver"] == args.driver and on["graphicsDriver"] == args.driver
        print("dry-run OK")
        print("  app id.......:", app_id)
        print("  .container...:", cpath)
        print("  driver.......:", args.driver or "(mantém o do container) — ATENÇÃO: o lever só "
              "existe no blob do `wrapper-gamenative`")
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
        if args.prewarm_seconds > 0:
            # Absorve custos de UMA VEZ só (extração do driver novo, primeiro boot): sem isto
            # eles caem inteiros na perna OFF e enviesam o A/B — foi o que aconteceu com o
            # warm-up "Installing Mono..." no primeiro teste manual (§11.5).
            print(f"[prewarm] rodando {args.prewarm_seconds}s fora da medição "
                  "(extração de driver / primeiro boot)...")
            run_leg(args.serial, args.container, app_id, LEG_OFF, "prewarm",
                    args.prewarm_seconds, args.out, args.driver)
        for i, leg in enumerate(phases, start=1):
            run_leg(args.serial, args.container, app_id, leg, f"{i}-{leg}",
                    args.seconds, args.out, args.driver)
    finally:
        if not args.keep_config:
            write_container(args.serial, args.container, original)
            print("config original restaurada")

    print()
    print("Segmentos por perna (início/meio/fim) — se a mistura menu×gameplay difere entre as "
          "pernas, a MÉDIA mede a mistura, não o F1:")
    for i, leg in enumerate(phases, start=1):
        p = os.path.join(args.out, f"leg-{i}-{leg}.jsonl")
        if os.path.exists(p):
            segments(p, f"{i}-{leg}")
    print("\nfps por faixa de GPU% (proxy de cena — comparar na MESMA faixa é o justo):")
    for i, leg in enumerate(phases, start=1):
        p = os.path.join(args.out, f"leg-{i}-{leg}.jsonl")
        if not os.path.exists(p):
            continue
        print(f"  [{i}-{leg}] " + "  ".join(f"{k}: {v[0]:.1f} fps (n={v[1]})"
                                            for k, v in scene_bands(p).items()))

    print()
    phase_pairs = [(f"{i}-{leg}", leg) for i, leg in enumerate(phases, start=1)]
    if validate_phases(phase_pairs, args.out, args.min_playing):
        print("\nAgregado por condição (o que vale, com a deriva térmica cancelada pela ordem "
              "espelhada):")
        for leg in (LEG_OFF, LEG_ON):
            files = [os.path.join(args.out, f"leg-{ph}.jsonl") for ph, l in phase_pairs if l == leg]
            print(f"  python3 tools/mali-metrics-report/mali_metrics_report.py "
                  f"{' '.join(files)}   # condição {leg}")
        return 0
    print("RESULTADO NÃO EMITIDO: as pernas não são comparáveis (ver acima). Ajuste a janela "
          "(--seconds), garanta a mesma cena e repita — qualquer Δ aqui seria ruído com "
          "aparência de resultado.")
    return 2


if __name__ == "__main__":
    sys.exit(main())
