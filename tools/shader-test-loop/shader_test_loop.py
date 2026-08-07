"""Automated librashader shader test loop (cybernetics: actuator=setprop, sensors=screencap+logcat).
Black-box: no visual inspection needed; classification from pixel statistics + logs."""
import subprocess, time, json, csv, sys

BASE = "/data/user/0/app.gamenative/files/retroarch_presets"
PRESETS = [
    ("misc/invert.slangp", "invert (control+)"),
    ("misc/color-mangler.slangp", "color-mangler"),
    ("misc/chroma.slangp", "chroma"),
    ("misc/colorimetry.slangp", "colorimetry"),
    ("misc/ega.slangp", "ega"),
    ("misc/ascii.slangp", "ascii"),
    ("misc/cmyk-halftone-dot.slangp", "cmyk-halftone"),
    ("misc/bead.slangp", "bead"),
    ("misc/glass.slangp", "glass"),
    ("misc/edge-detect.slangp", "edge-detect"),
    ("misc/grade.slangp", "grade"),
    ("misc/deband.slangp", "deband"),
    ("film/technicolor.slangp", "technicolor"),
    ("cel/MMJ_Cel_Shader.slangp", "cel-shader"),
    ("hdr/hdr_inverse_tonemap.slangp", "hdr-inverse-tonemap"),
    ("ntsc/artifact-colors.slangp", "ntsc-artifact-colors"),
    ("ntsc/blargg.slangp", "ntsc-blargg"),
    ("nearest.slangp", "nearest (control-)"),
]

def adb(args, timeout=60):
    return subprocess.run(["adb"] + args, capture_output=True, text=True, timeout=timeout)

def screen_stats():
    r = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=60)
    if r.returncode != 0 or len(r.stdout) < 1000:
        return None
    import numpy as np
    from PIL import Image
    import io
    arr = np.array(Image.open(io.BytesIO(r.stdout)).convert('RGB'))
    h, w, _ = arr.shape
    mean = arr.mean(axis=(0, 1))
    std = arr.std(axis=(0, 1))
    grid = arr[::max(1,h//8), ::max(1,w//16)].reshape(-1, 3)
    nonblack = int((grid.max(axis=1) > 30).sum())
    bright = int((grid.mean(axis=1) > 120).sum())
    # colorfulness: mean channel spread
    colorfulness = float(np.abs(arr[:,:,0].astype(int)-arr[:,:,1]).mean() + np.abs(arr[:,:,1].astype(int)-arr[:,:,2]).mean() + np.abs(arr[:,:,0].astype(int)-arr[:,:,2]).mean()) / 3.0
    return {"mean": tuple(round(v,1) for v in mean), "std": tuple(round(v,1) for v in std),
            "nonblack": nonblack, "bright": bright, "colorfulness": round(colorfulness,1), "grid": len(grid)}

def classify(stats, chain_ok, app_alive):
    if not app_alive:
        return "CRASH"
    if not chain_ok:
        return "CHAIN_FAIL"
    if stats is None:
        return "NO_SCREEN"
    m = stats["mean"][0]
    if stats["bright"] >= 60:
        return "BRIGHT/VISIBLE"
    if stats["nonblack"] >= 10 and m > 10:
        return "VISIBLE"
    return "BLACK"

def run_once(rel, name, logcat_seen):
    # actuator: switch preset at runtime
    path = f"{BASE}/{rel}"
    adb(["shell", "setprop", "debug.gamenative.preset", path])
    time.sleep(6)
    # sensors
    stats = screen_stats()
    log = adb(["logcat", "-d"]).stdout
    # chain ok? (the latest 'chain active' after this setprop, or a create-failed)
    idx = log.rfind(f"runtime preset override -> {path}")
    tail = log[idx:] if idx >= 0 else ""
    chain_ok = ("preset chain active=1" in tail) or ("preset chain active" not in tail and idx < 0)
    chain_fail = "filter chain create failed" in tail
    if chain_fail:
        chain_ok = False
    app_alive = adb(["shell", "pidof", "app.gamenative"]).stdout.strip() != ""
    verdict = classify(stats, chain_ok, app_alive)
    print(f"{rel:45s} {verdict:14s} mean={stats['mean'] if stats else None} nblk={stats['nonblack'] if stats else '-'} br={stats['bright'] if stats else '-'} col={stats['colorfulness'] if stats else '-'}")
    return [rel, name, verdict, chain_ok, str(stats['mean'] if stats else ''), stats['nonblack'] if stats else '', stats['bright'] if stats else '', stats['colorfulness'] if stats else '']

def main():
    out = "/tmp/shader_results.csv"
    with open(out, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["preset", "name", "verdict", "chain_ok", "mean_rgb", "nonblack", "bright", "colorfulness"])
        for rel, name in PRESETS:
            try:
                row = run_once(rel, name, None)
            except Exception as e:
                row = [rel, name, f"ERROR:{e}", "", "", "", "", ""]
            w.writerow(row)
            f.flush()
    print(f"\nresults -> {out}")

if __name__ == "__main__":
    main()
