#!/usr/bin/env python3
"""Regenerate app/src/main/assets/profile-catalog.json from the seed profile repo.

Pattern of tools/shaders/sync_slang_shaders.py (deterministic, pinned source, nothing
ships dynamically): the APK ships ONLY the catalog metadata; the browser
(ProfileCatalogBrowser) reads this asset offline.

Source: a community profile Git repo, PINNED COMMIT (constant at the top — update by
hand when the upstream changes). v1 of the source: the fork keeps a seed folder
(tools/profiles/seed/) with example profiles exported from real GamepadProfile JSON;
the external community repo plugs in later WITHOUT a format change (same entry
schema).

Validation (spec 2026-08-16-E-profile-catalog-comunitario, §1.1) — per profile,
reject-with-warning (never abort the whole sync):
  - profile fields must be inside the GamepadProfile allowlist (unknown fields reject
    the ENTRY with a warning — a newer catalog must not leak keys into an older APK);
  - enums validated (faceStyle/gyroMode/deadzone modes/response curves/layer trigger
    modes/swipe directions — the F/D allowlists: iconKey/children do not exist in
    GamepadProfile, so that part of the F/D validation is vacuous here and enforced
    by the Kotlin parser on load);
  - LUTs sanitized like StickTransform.sanitizeLut (clamp 0..1, drop non-finite,
    drop below 2 points) and written back sanitized;
  - scalar floats must be finite; macro keyCodes must be ints (SWIPE_OPEN_RADIAL =
    -1000 is a VALID swipe binding value, spec D).
Output: { "generatedFrom": <commit>, "schemaVersion": 1, "profiles": [...] } sorted
by id, UTF-8, no timestamps -> deterministic (gate: run twice, diff must be empty).
Usage: python3 tools/profiles/sync_profile_repo.py [--out PATH]
"""

import argparse
import json
import math
import os
import sys

# ── Fonte pinada (constante no topo — atualizar à mão quando o upstream mudar) ──
SEED_COMMIT = "local-seed-v1"

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
SEED_DIR = os.path.join(SCRIPT_DIR, "seed")
DEFAULT_OUT = os.path.join(
    SCRIPT_DIR, "..", "..", "app", "src", "main", "assets", "profile-catalog.json",
)

# ── Allowlists espelho do Kotlin (GamepadProfile.kt / enums do repo) ──
PROFILE_FIELDS = {
    "faceStyle", "swapOkCancel", "leftStickDeadzone", "rightStickDeadzone",
    "leftTriggerDeadzone", "rightTriggerDeadzone", "layers", "gyroMode",
    "gyroSensitivity", "gyroDeadzone", "gyroActivateButton", "layerTriggers",
    "rumbleOnActivate", "rumbleOnBack", "touchpadDoubleTapRightClick",
    "touchpadSwipes", "leftStickDeadzoneMode", "rightStickDeadzoneMode",
    "leftStickCurve", "rightStickCurve", "leftStickLut", "rightStickLut",
    "flickStickEnabled", "flickStickActivationRadius", "flickStickSnapAngle",
    "gyroFusionEnabled", "gyroFusionKp", "gyroFusionKi", "schemaVersion",
    # G (spec 2026-08-16-G-gyro-v2): campos novos do gyro v2.
    "gyroSensitivityY", "gyroInvertX", "gyroInvertY", "gyroSmoothMinCutoff",
    "gyroSmoothBeta", "gyroStickMaxOutput", "gyroStickAntiDeadzone",
    "gyroActivateToggle", "gyroGripAngleDeg",
}
ENTRY_FIELDS = {
    "id", "game", "faceStyle", "controller", "name", "author", "description",
    "downloads", "profile",
}
FACE_STYLES = {"XBOX", "PLAYSTATION", "NINTENDO", "GENERIC"}
GYRO_MODES = {"OFF", "MOUSE", "CAMERA"}
DEADZONE_MODES = {"RADIAL", "AXIAL"}
RESPONSE_CURVES = {"LINEAR", "EXPONENTIAL", "SCURVE", "LUT"}
LAYER_TRIGGER_MODES = {"HOLD", "TOGGLE", "DOUBLE_TAP", "LONG_PRESS", "SEQUENCE"}
SWIPE_DIRECTIONS = {
    "UP", "UP_RIGHT", "RIGHT", "DOWN_RIGHT", "DOWN", "DOWN_LEFT", "LEFT", "UP_LEFT",
}
# Espelho de StickTransform.sanitizeLut (MIN_LUT_POINTS = 2).
MIN_LUT_POINTS = 2
# D (spec 2026-08-16-D): keyCode reservado do swipe → abrir radial (binding válido).
SWIPE_OPEN_RADIAL = -1000


def warn(msg: str) -> None:
    print(f"WARN: {msg}", file=sys.stderr)


def is_finite_number(v) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v)


def sanitize_lut(raw) -> list:
    """Espelho de StickTransform.sanitizeLut: clamp 0..1, drop não-finitos, min 2 pontos."""
    clean = [float(v) for v in raw if is_finite_number(v) and 0.0 <= float(v) <= 1.0]
    if len(clean) < MIN_LUT_POINTS:
        return []
    return clean


def binding_token_ok(token: str) -> bool:
    """Espelho do GamepadBindingCodec.decode (spec 2026-08-16-H-binding-modifiers-
    duckstation, §2.4): valida o FORMATO do token de binding — base `key:<int>` /
    `axis:<int>:<-1|1>` / `hat:<int>:<int>0` + sufixo opcional `:turbo` + bloco final
    `:m=<full,inv,s<%>,dz<%>>` (um ÚNICO campo `:`; vírgulas separam subcampos).
    Leniente como o Kotlin: campos desconhecidos dentro do bloco são ignorados e a
    base tolera partes extras depois dos parâmetros obrigatórios."""
    if not isinstance(token, str):
        return False
    parts = token.split(":")
    if parts and parts[-1].startswith("m="):
        block = parts[-1][2:]
        parts = parts[:-1]
        # Campos do bloco: full/inv/s<digitos>/dz<digitos>; o resto é ignorado
        # (política V1 — nunca quebrar perfil futuro). Vazio após isso = lixo que o
        # Kotlin também ignora, então não rejeita sozinho.
        for field in block.split(","):
            if field == "" or field in ("full", "inv"):
                continue
            if (field.startswith("s") and field[1:].isdigit()) or \
                    (field.startswith("dz") and field[2:].isdigit()):
                continue
            # campo desconhecido — ignorado (leniente, espelho do decode)
    turbo = bool(parts) and parts[-1] == "turbo"
    if turbo:
        parts = parts[:-1]
    if not parts:
        return False
    kind = parts[0]

    def int_ok(s: str) -> bool:
        try:
            int(s)
            return True
        except ValueError:
            return False

    if kind == "key":
        return len(parts) >= 2 and int_ok(parts[1])
    if kind == "axis":
        return len(parts) >= 3 and int_ok(parts[1]) and parts[2] in ("-1", "1")
    if kind == "hat":
        return (
            len(parts) >= 3
            and int_ok(parts[1])
            and int_ok(parts[2])
            and int(parts[2]) > 0
        )
    return False


def validate_profile(raw: dict, where: str) -> tuple:
    """Valida o objeto GamepadProfile. Retorna (ok, sanitized) ou (False, [])."""
    errors = []
    unknown = sorted(set(raw) - PROFILE_FIELDS)
    if unknown:
        errors.append("campos desconhecidos do perfil: %s" % ", ".join(unknown))

    def enum_ok(value, allowed, field):
        if value not in allowed:
            errors.append(f"{field} fora da allowlist: {value!r}")
            return False
        return True

    for field in ("faceStyle",):
        if field in raw and raw[field] is not None:
            enum_ok(raw[field], FACE_STYLES, field)
    for field in ("gyroMode",):
        if field in raw and raw[field] is not None:
            enum_ok(raw[field], GYRO_MODES, field)
    for field in ("leftStickDeadzoneMode", "rightStickDeadzoneMode"):
        if field in raw and raw[field] is not None:
            enum_ok(raw[field], DEADZONE_MODES, field)
    for field in ("leftStickCurve", "rightStickCurve"):
        if field in raw and raw[field] is not None:
            enum_ok(raw[field], RESPONSE_CURVES, field)

    for field in (
        "leftStickDeadzone", "rightStickDeadzone", "leftTriggerDeadzone",
        "rightTriggerDeadzone", "gyroSensitivity", "gyroDeadzone",
        "flickStickActivationRadius", "flickStickSnapAngle", "gyroFusionKp",
        "gyroFusionKi",
        # G (spec 2026-08-16-G-gyro-v2): campos novos do gyro v2 (floats).
        "gyroSensitivityY", "gyroSmoothMinCutoff", "gyroSmoothBeta",
        "gyroStickMaxOutput", "gyroStickAntiDeadzone", "gyroGripAngleDeg",
    ):
        if field in raw and raw[field] is not None and not is_finite_number(raw[field]):
            errors.append(f"{field} não é número finito")

    for field in ("swapOkCancel", "touchpadDoubleTapRightClick", "flickStickEnabled",
                  "gyroFusionEnabled",
                  # G: bools do gyro v2.
                  "gyroInvertX", "gyroInvertY", "gyroActivateToggle"):
        if field in raw and raw[field] is not None and not isinstance(raw[field], bool):
            errors.append(f"{field} deve ser bool")

    if "schemaVersion" in raw and not isinstance(raw["schemaVersion"], int):
        errors.append("schemaVersion deve ser int")

    layers = raw.get("layers")
    if layers is not None:
        if not isinstance(layers, dict) or not all(
            isinstance(l, str) and isinstance(bindings, dict)
            and all(isinstance(b, str) and isinstance(v, str) for b, v in bindings.items())
            for l, bindings in layers.items()
        ):
            errors.append("layers deve ser mapa camada → mapa botão → string")
        else:
            # H (spec 2026-08-16-H-binding-modifiers-duckstation, §2.4): o FORMATO dos
            # tokens é validado (espelho do GamepadBindingCodec) — o sufixo :m= é
            # ACEITO pelo validador; token fora da gramática descarta a entry.
            for layer_name, bindings in layers.items():
                for button, token in bindings.items():
                    if not binding_token_ok(token):
                        errors.append(
                            "layers[%s][%s] token de binding inválido: %r"
                            % (layer_name, button, token)
                        )

    triggers = raw.get("layerTriggers")
    if triggers is not None:
        if not isinstance(triggers, dict):
            errors.append("layerTriggers deve ser mapa")
        else:
            for name, spec in triggers.items():
                if not isinstance(spec, dict):
                    errors.append(f"layerTriggers[{name}] deve ser objeto")
                    continue
                if not isinstance(spec.get("button"), str):
                    errors.append(f"layerTriggers[{name}].button deve ser string")
                if "mode" in spec:
                    enum_ok(spec["mode"], LAYER_TRIGGER_MODES, f"layerTriggers[{name}].mode")
                if "doubleTapMs" in spec and not isinstance(spec["doubleTapMs"], int):
                    errors.append(f"layerTriggers[{name}].doubleTapMs deve ser int")
                if "isShift" in spec and not isinstance(spec["isShift"], bool):
                    errors.append(f"layerTriggers[{name}].isShift deve ser bool")
                # I (spec 2026-08-16-I-trigger-engine-keymapper, §2.5): campos novos
                # dos modos LONG_PRESS/SEQUENCE — ints e lista curta de strings.
                if "longPressMs" in spec and (
                    not isinstance(spec["longPressMs"], int) or isinstance(spec["longPressMs"], bool)
                ):
                    errors.append(f"layerTriggers[{name}].longPressMs deve ser int")
                if "seqTimeoutMs" in spec and (
                    not isinstance(spec["seqTimeoutMs"], int) or isinstance(spec["seqTimeoutMs"], bool)
                ):
                    errors.append(f"layerTriggers[{name}].seqTimeoutMs deve ser int")
                if "sequence" in spec:
                    seq = spec["sequence"]
                    if not isinstance(seq, list) or not all(
                        isinstance(s, str) and s for s in seq
                    ):
                        errors.append(
                            f"layerTriggers[{name}].sequence deve ser lista de strings curtas"
                        )
                    elif len(seq) > 2:
                        errors.append(
                            f"layerTriggers[{name}].sequence deve ter no máximo 2 passos (2–3 botões no total)"
                        )

    swipes = raw.get("touchpadSwipes")
    if swipes is not None:
        if not isinstance(swipes, dict):
            errors.append("touchpadSwipes deve ser mapa direção → macro")
        else:
            for direction, macro in swipes.items():
                if direction not in SWIPE_DIRECTIONS:
                    errors.append(f"direção de swipe fora do enum SwipeDir: {direction!r}")
                if not isinstance(macro, list) or not all(
                    isinstance(k, dict) and isinstance(k.get("keyCode"), int)
                    and not isinstance(k.get("keyCode"), bool)
                    for k in macro
                ):
                    errors.append(f"touchpadSwipes[{direction}] deve ser lista de macros com keyCode int")
                    continue
                for k in macro:
                    if "holdMs" in k and (not isinstance(k["holdMs"], int) or k["holdMs"] < 0):
                        errors.append(f"touchpadSwipes[{direction}].holdMs inválido")
                    if "gapMs" in k and (not isinstance(k["gapMs"], int) or k["gapMs"] < 0):
                        errors.append(f"touchpadSwipes[{direction}].gapMs inválido")

    if errors:
        return False, [f"{where}: {e}" for e in errors]

    # Sanitização aplicada à SAÍDA (espelho do withSanitizedLuts do load).
    out = dict(raw)
    for lut_field in ("leftStickLut", "rightStickLut"):
        if lut_field in out and out[lut_field] is not None:
            if not isinstance(out[lut_field], list):
                errors.append(f"{lut_field} deve ser lista de pontos 0..1")
                return False, [f"{where}: {lut_field} deve ser lista de pontos 0..1"]
            clean = sanitize_lut(out[lut_field])
            out[lut_field] = clean if clean else None
    return True, out


def validate_entry(raw: dict, where: str) -> tuple:
    """Valida a ENTRY do catálogo. Retorna (ok, sanitized_entry) ou (False, [])."""
    errors = []
    if not isinstance(raw, dict):
        return False, [f"{where}: entry não é objeto JSON"]
    unknown = sorted(set(raw) - ENTRY_FIELDS)
    if unknown:
        # Entry extras: aviso, mas não rejeita (o parser Kotlin usa ignoreUnknownKeys).
        warn(f"{where}: campos extras na entry (mantidos): {', '.join(unknown)}")
    for required in ("id", "name", "author", "profile"):
        if required not in raw:
            errors.append(f"{where}: campo obrigatório ausente: {required}")
    if not errors:
        if not isinstance(raw["id"], str) or not raw["id"].strip():
            errors.append(f"{where}: id deve ser string não vazia")
        for field in ("name", "author"):
            if not isinstance(raw[field], str):
                errors.append(f"{where}: {field} deve ser string")
        if "game" in raw and raw["game"] is not None and not isinstance(raw["game"], str):
            errors.append(f"{where}: game deve ser string ou null")
        if "faceStyle" in raw and raw["faceStyle"] is not None                 and raw["faceStyle"] not in FACE_STYLES:
            errors.append(f"{where}: faceStyle fora da allowlist")
        if "description" in raw and raw["description"] is not None                 and not isinstance(raw["description"], str):
            errors.append(f"{where}: description deve ser string")
        if "downloads" in raw and raw["downloads"] is not None                 and not isinstance(raw["downloads"], int):
            errors.append(f"{where}: downloads deve ser int")
    if errors:
        return False, errors
    ok, profile_or_errors = validate_profile(raw["profile"], f"{where}.profile")
    if not ok:
        return False, profile_or_errors
    out = dict(raw)
    out["profile"] = profile_or_errors
    return True, out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", default=DEFAULT_OUT, help="output JSON path")
    args = parser.parse_args()

    if not os.path.isdir(SEED_DIR):
        warn(f"seed dir ausente: {SEED_DIR}")
        return 1

    files = []
    for root, _dirs, names in os.walk(SEED_DIR):
        for name in names:
            if name.endswith(".json"):
                files.append(os.path.join(root, name))
    files.sort()

    profiles = []
    rejected = 0
    for path in files:
        where = os.path.relpath(path, SEED_DIR)
        try:
            with open(path, encoding="utf-8") as fh:
                raw = json.load(fh)
        except (json.JSONDecodeError, OSError) as exc:
            warn(f"{where}: JSON ilegível — entry descartada ({exc})")
            rejected += 1
            continue
        ok, result = validate_entry(raw, where)
        if not ok:
            for msg in result:
                warn(f"{where}: entry DESCARTADA — {msg}")
            rejected += 1
            continue
        profiles.append(result)

    profiles.sort(key=lambda e: e["id"])

    out = {
        "generatedFrom": SEED_COMMIT,
        "schemaVersion": 1,
        "profiles": profiles,
    }
    out_path = args.out
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    with open(out_path, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(out, fh, ensure_ascii=False, indent=2)
        fh.write("\n")

    print(
        f"profile-catalog.json: {len(profiles)} perfis validados, "
        f"{rejected} entries descartadas (seed commit {SEED_COMMIT})",
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
