# Protocolo on-device consolidado v2 — Universal Input (sessões A/B/C, humano)

**Data:** 2026-08-16
**Objetivo:** fundir TODOS os "on-device pendente" (roadmap UX A–F, roadmap
input H/I/J1/J2 + F0, e fases K3/K4/K5) em TRÊS sessões executáveis, em ordem
de dependência, com evidência e roteiro de falha. Substitui caçar protocolos
spec a spec; cada spec mantém o seu §on-device como fonte, este doc é a agenda.
**Guia:** `docs/spec-2026-08-16-guia-universal-input-fechamento.md` (V1).

## 0. Regras da sessão

- **MIUI bloqueia `adb input`** — usar SEMPRE o harness:
  `adb shell setprop debug.gamenative.input "<verbo>"`.
  Verbos: `key:96` (A/Cross), `key:110` (PS/mode), `stick:x:y`, `hat:x:y`,
  `touch:0.5:0.5`/`touchdown`/`touchup`/`touchtap`, `gyro:x:y:z`,
  `rumble:0.6:0.6:300`, `pref:universal:1` (whitelist universal/touchpadmouse/
  rumble/layertick), `latency:report`.
- **Log tag:** `gncontrol` (evidência de log primária); screenshots para UI;
  vídeo curto para os testes de movimento.
- **Device primário:** Mi 11 (Adreno 650) + DS4 legítimo `054c:09cc` —
  preferir **BT** (a sessão de 2026-08-14 foi USB; USB não expõe vibrator).
  Secundários se disponíveis: generic DInput (DragonRise), 8BitDo, Switch Pro.
- **Bug encontrado → fix-commit** referenciando este doc + item; se o bug for de
  mapping/hub (cadeia K3/K4/K5, remap, camadas), o fix entra ANTES da fase K6 na
  fila (`retomada-fila.md` §2). Se for de UI/cosmético, segue paralelo.
- **Cada item:** registrar ✅/❌/⏳ na tabela §4 ao final da sessão.

## 1. Sessão A — Fundação de mapping (~40 min, BT)

Roda ANTES de qualquer fase nova (é a base de K6/K2/K1).

| # | Item | Passos (harness quando não físico) | Evidência | Se falhar |
|---|---|---|---|---|
| A1 | Rumble BT (fase A + retest do pendentes v1 §1.2) | BT + controle carregado: `rumble:0.6:0.6:300` + botão "Testar vibração" no card; `dumpsys vibrator_manager` | log `gncontrol rumble → true/false` + destino CONTROLLER/PHONE/NONE; USAGE_MEDIA no dumpsys; toggle OFF silencia | USB já documentado como limitação do stack; em BT falhando = probing de vibrators (backlog #6) |
| A2 | Remap visual (fase B) | QuickMenu → remap; mock renderiza FaceStyle; tap→captura→bind salva; flash 600 ms; escopo Este jogo/Todos os jogos; restaurar automático | screenshots + bind testado no jogo | captura não dispara = bus do dialog (padrão RadialMenuEditor) |
| A3 | Device card (fase C) | expandir card: viewer acende ao apertar; readouts gyro/touchpad variam; recentrar zera | screenshot viewer aceso | readout parado = listener do bus no card |
| A4 | K3 — badges de origem | DS4 → `Mapping: MODEL`; generic DInput 1-stick → `CAPABILITIES` sem binding fantasma (R3 não existe); remote BT se houver → `REMOTE` com BACK navegando; device desconhecido com entry no DB → `SDL_DB` | logcat `GamepadHub: added … shape=GAMEPAD mapping=MODEL` + screenshots do card | shape errado = `GamepadCapabilities` coletou mal no hotplug |
| A5 | K3 — hint/posicional | entry com `USE_BUTTON_LABELS` (ex.: 8BitDo se disponível) → FaceStyle NINTENDO no mock do remap | screenshot do mock | hint não parseou — ver `SdlControllerDb.kt:60-67` |
| A6 | K4 — quirk | DS4 BT: comportamento idêntico ao sem-quirk quando triggers em LTRIGGER/RTRIGGER; se capability indicar (triggers em RX/RY), log `gncontrol quirk <nome> aplicado` + badge `+QUIRK` | logcat | quirk ativando SEM necessidade = gate de capabilities errado (`DeviceQuirks.resolve`) |
| A7 | K5 — autoconfig | remap visual: trocar ✕↔○ → "Salvar perfil deste controle" → reconectar BT → mapping persiste SEM perfil lógico (badge `USER`); "Restaurar automático" volta (`MODEL`); validador: limpar bindings → diálogo `MISSING_CONFIRM`, nada salvo | log `gncontrol: autoconfig <key> salvo — tier USER ativo` + badge no card | badge não muda = `reResolveAutoconfig` não invalida (`GamepadHub.kt:428-440`) |
| A8 | K5/K4 interação | com quirk ativo: salvar → badge `USER+QUIRK` e quirk reaplicado por cima (save gravou o PRÉ-quirk) | badge | save gravou pós-quirk = `baseMappingCache` capturado no lugar errado |

## 2. Sessão B — Camadas, expressões e radial (~45 min)

Depende da A só para o hábito do fluxo; pode rodar em qualquer ordem interna.

| # | Item | Passos | Evidência | Se falhar |
|---|---|---|---|---|
| B1 | Radial v2 + turbo (fase F) | perfil v1 antigo carrega; submenu abre/volta; HOLD executa sem fechar (anti-repeat 120 ms); shift consome o botão sem abrir radial; turbo pulsa e solta limpo | screenshots + logcat `gncontrol` | v1 não carrega = schemaVersion 2 sem migração |
| B2 | Touchpad swipes (fase D) | `touch:0.5:0.5` + swipe rápido sintético vs arrasto lento: macro/radial dispara; arrasto e duplo-toque intactos | log `gncontrol` + movimento no jogo | swipe não detecta = thresholds SwipeDir (300 ms / 0.22) |
| B3 | Modificadores (fase H) | FullAxis em trigger HID (−1..1 → 0..1); sensibilidade 50% num stick; deadzone por binding vencendo a global | vídeo curto | valor não escala = ordem full→invert→scale→deadzone (`BindingModifiers.kt`) |
| B4 | Trigger engine (fase I) | SELECT→SELECT→FACE_TOP abre o radial SEM vazar o 1º SELECT (retardo só libera se a sequência morrer); long-press L3 = camada; overlap de 2 sequências com mesmo 1º botão | log `gncontrol` + jogo | vazou = disambiguação #1386 não aplicou (`TriggerEngine` + `PendingEmit`) |
| B5 | Expressões (J1 + F0) | `expr:toggle(face_bottom)` em botão de camada; `expr:deadzone(axis:left_y, 0.3) > 0.5` como sprint; EIXO `expr:` end-to-end (correção F0 — um `expr:` de eixo deve chegar ao jogo) | jogo reage; editor mostra parse ok | eixo morto de novo = regressão da F0 (`ExprBindingProcessor.kt:44-47`) |
| B6 | Chords (J2) | `expr:face_bottom + face_right` num botão: com FACE_BOTTOM segurado, FACE_RIGHT NÃO chega ao jogo (suppression) e o chord emite; soltar FACE_BOTTOM restaura o binding simples | logcat + jogo | sem supressão = `ChordLogic.suppressFinal`/superconjunto |
| B7 | F0 residual | perfil com token malformado: botão PASSA adiante (pass-through, não engole) | jogo recebe o botão | engoliu = `GamepadHub.kt:854-856` regrediu |

## 3. Sessão C — Gyro, catálogo e mouse (~30 min)

Base U1–U7 já PASSOU na sessão USB de 2026-08-14 (`pendentes-e-validacao-gamepad-
universal.md` §1.2) — aqui só o que MUDOU depois dela.

| # | Item | Passos | Evidência | Se falhar |
|---|---|---|---|---|
| C1 | Gyro v2 (fase G) | sensibilidade vertical ≠ horizontal visível; inversão X/Y funciona; toggle de ativação (só com botão) liga/desliga; shaping CAMERA (teto + anti-zona) perceptível; "Calibrar grip" muda o comportamento no dispositivo | vídeo + log `SensorUpdate` | campos novos null-default não aplicando = `merged()` desatualizado |
| C2 | Rumble de menu (A) | tick de camada + confirmação vibram o CONTROLE (BT) | toque físico + log | ver A1 — mesmo contrato |
| C3 | Catálogo (fase E) | browser offline abre; aplicar perfil muda o jogo NA HORA (A aplica override do JOGO via saveGameProfile); badge personalizado na Library | log do store + badge no card | badge ausente = `hasOverrides`/`overrideKeys` do store |
| C4 | K2/K1/K7 pré-requisitos | tocar o touchpad no modo touchpad→mouse (base do K2/K1); abrir o remap visual (base do K7) | funciona como antes | regressão de U2/B — abrir fix ANTES de K2/K1/K7 |

## 4. Tabela de resultados (humano preenche; fonte do fechamento)

| Sessão | Data | Aprovados | Falhas | Ações |
|---|---|---|---|---|
| A | | /8 | | |
| B | | /7 | | |
| C | | /4 | | |

Falhas viram fix-commits `fix(gamepad): ... (protocolo-on-device-v2, sessão X item Y)`.
Ao fim das 3 sessões verdes → liberar V2 do guia (retomada da fila).

## 5. Fora do escopo deste protocolo

On-device das fases AINDA NÃO implementadas (K6/K2/K1/K7) — os specs delas têm
protocolo próprio e rodam DEPOIS de cada fase (não antes); MIUI `adb input`
(morto por design — harness cobre); re-validação da base U1–U7 USB já aprovada
(exceto o que mudou — C1/C3).
