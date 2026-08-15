# SDL_GameControllerDB — notas do backend Android (F1.4)

Fonte: `reference/SDL_GameControllerDB` pinado em `42f28e22d20761e7004e8db91c4ad86402fdf600`
(2026-08-12). Asset do app: `app/src/main/assets/gamecontrollerdb.txt` (só entradas
`platform:Android`, 299 de 2258). Fontes SDL2 citadas: clone em `/tmp/sdlwork/sdl2`
(pin `a2e7c76bda17c853ba93c7d2c9fdddf8a9d621d1`).

## 1. Formatos de GUID Android (3 eras)

| Era | Formato | Exemplo | Vendor/product? |
|---|---|---|---|
| ≤ 2.0.5 | hex dos primeiros 16 chars do NOME (ASCII) | `38653964633230666463343334313533` | ❌ (só nome) |
| 2.0.16-ish | "bus-style": bus 05 + crc 0000 + vid/pid + máscaras de capacidade | `050000004c050000cc090000fffe3f00` | ✅ |
| 2.26.5+/hidapi | `SDL_CreateJoystickGUID(bus, vid, pid, version, fabricante, produto, 'h', 0)` — crc16 sobre "fabricante produto" | — | ✅ (crc ≠ 0) |

Nas 299 entradas Android do DB: 234 legado (hex-do-nome), 65 bus-style. **O parser do
fork indexa SÓ as bus-style (única chave estável = vendor+product).** As legadas não
carregam vid/pid — ignoradas (o Android já normaliza esses pads de qualquer forma).

## 2. Layout do GUID bus-style (16 bytes, impressos em ordem)

```
data[0]     = bus (Android SEMPRE 0x05 — SDL_HARDWARE_BUS_BLUETOOTH, mesmo p/ USB:
              SDL_sysjoystick.c Android_AddJoystick chama SDL_CreateJoystickGUID com
              SDL_HARDWARE_BUS_BLUETOOTH incondicionalmente)
data[1]     = 0
data[2..3]  = crc16 = 0x0000 (name=NULL ⇒ SDL_crc16 sobre nada)
data[4..5]  = vendor  (little-endian)
data[6..7]  = product (little-endian)
data[8..9]  = version = 0
data[10]    = assinatura do driver = 0
data[11]    = 0
data[12..13]= button_mask (LE) — bits = enum SDL_CONTROLLER_BUTTON (b0..b14)
data[14..15]= axis_mask  (LE) — 0x03/0x0F/0x3F conforme 2/4/6 eixos (SDLControllerManager.
              java getAxisMask: >=2 → 0x0003, >=4 → 0x000C, >=6 → 0x0030)
```

crc16 = CRC-16/ARC (polinômio 0xA001) — irrelevante aqui porque o campo é sempre 0
nas entradas Android (o "desc" não vira nome no GUID).

## 3. Semântica dos campos de mapping (backend Android)

`bN` = índice do **enum SDL_CONTROLLER_BUTTON** (SDL_sysjoystick.c `keycode_to_SDL`):

| bN | AKEYCODE Android |
|---|---|
| 0–3 | BUTTON_A/B/X/Y (96/97/99/100) |
| 4 | BUTTON_SELECT (109) — também AKEYCODE_BACK |
| 5 | BUTTON_MODE (110) |
| 6 | BUTTON_START (108) — também AKEYCODE_MENU |
| 7/8 | BUTTON_THUMBL/THUMBR (106/107) |
| 9/10 | BUTTON_L1/R1 (102/103) |
| 11–14 | DPAD_UP/DOWN/LEFT/RIGHT (19–22) |
| 15/16 | BUTTON_L2/R2 (104/105) |
| 17/18 | BUTTON_C/Z |
| 20–35 | BUTTON_1..16 (188–203) — `20 + (keycode − AKEYCODE_BUTTON_1)` |
| >35 | sem keycode Android (ignorar) |

`aN` = ordem de eixos do driver: os MotionRanges SOURCE_CLASS_JOYSTICK são ordenados
pelo RangeComparator (SDLControllerManager.java — com o swap GAS↔BRAKE e a reordenação
do Z) e numerados a partir de 0. Para o controle típico:

| aN | MotionEvent axis |
|---|---|
| 0–1 | AXIS_X, AXIS_Y (left stick) |
| 2–3 | AXIS_Z, AXIS_RZ (right stick) |
| 4–5 | AXIS_LTRIGGER, AXIS_RTRIGGER (17/18) — GAS/BRAKE são reordenados para cá |

`hN.M` = hat com máscara SDL (1=up, 2=right, 4=down, 8=left) — no Android, hats
AXIS_HAT_X/Y viram os botões DPAD (mesma máscara do MappingParser do fork).

Prefixos `+`/`-`/`~` em eixos: direção ±1 (xor entre prefixo e `~`).

## 4. O que isso significa para o fork

- As entradas bus-style **reproduzem o que a SDL viu no Android**: keycodes/axis
  NORMALIZADOS pelo framework (BUTTON_A…, DPAD_*, AXIS_X…). Elas diferem do
  `defaultAndroidMapping` do fork quando o pad tem layout não-padrão (trigger como
  botão, dpad em hat, eixo invertido, guide em tecla diferente, botões extras).
- Entradas de pads JÁ cobertos pelo `MappingDatabase` (DS4, DualSense, Xbox, Switch,
  8BitDo) repetem o default — inofensivas (o lookup do fork usa o SdlControllerDb só
  quando o MappingDatabase erra, e o fallback final continua sendo o
  DeviceClassifier/defaultAndroidMapping — byte-identical quando nada bate).
- HIDAPI (SDK 2.26.5+ na SDL): DS4/Xbox/Switch seriam reclamados pelo driver HIDAPI e
  ganhariam GUID com crc ≠ 0 — irrelevante para o fork, que NÃO usa SDL; a chave do
  fork é (vendor, product) direto do InputDevice.
