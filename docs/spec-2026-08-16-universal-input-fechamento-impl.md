# Fechamento — Universal Input (impl doc do checklist V3)

**Data:** 2026-08-16
**Checklist-fonte:** `docs/spec-2026-08-16-universal-input-fechamento.md` (V3 do guia)
**Guia:** `docs/spec-2026-08-16-guia-universal-input-fechamento.md`
**Master:** `docs/spec-2026-08-16-master-roadmap-universal-input.md` (§7 — 8/8 ✅)
**Resultado:** roadmap universal input 2026-H2 FECHADO (código + gates verdes + milestone).
Verificação on-device consolidada pendente — humana (protocolo v2, sessões A/B/C).

## 1. Commits por fase (tabela do checklist §2.1)

| Fase | Commit | Gate (evidência no impl doc da fase) |
|---|---|---|
| 0 — correções J1 | `d1c7f600` | tests `*Expr* *Gamepad*` + assemble (verificação H/I/J) |
| K3 — detecção por capacidades | `aa0132c2` | tests `*CapabilityMapping* *Mapping* *Sdl* *Gamepad*` + assemble |
| K4 — quirks vid/pid | `5a12bd7e` | tests `*Quirk* *Gamepad*` + assemble |
| K5 — autoconfig por device | `4af898ed` | tests `*Autoconfig* *DeviceMapping* *Gamepad*` + assemble |
| K6 — intercâmbio SDL | `99609ae1` | tests `*Sdl* *Mapping* *Gamepad*` = 192, 0 falhas + assemble (impl K6 §3) |
| K2 — modo mouse universal | `f64df99e` | tests `*MouseMode* *Gamepad*` = 123, 0 falhas + assemble (impl K2 §3) |
| K1 — gamepad virtual de toque | `f33fc076` | tests `*Virtual* *TouchGamepad* *Gamepad*` = 118, 0 falhas + assemble (impl K1 §3) |
| K7 — calibração visual | `7792c986` | tests `*Stick* *Curve* *Gamepad*` = 167, 0 falhas + assemble (impl K7 §3) |
| Checkpoint por fase | `3e45cb57` (K6), `05e845fa` (K2), `10b92b23` (K1), `90350684` | impl doc + tabelas §5 (retomada) / §7 (master) |

Fix-commits de bug fora de fase:

| Fix | Commit | Origem |
|---|---|---|
| Eixo `expr:` end-to-end + pass-through de token malformado | `d1c7f600` | verificação independente H/I/J §5 (Fase 0) |
| Stick direito morto no pipeline universal (AXIS_Z/RZ 2/3 → 11/14) | `e7ca45f0` | V0 (guia) — bug de mapping/hub pré-K6 |

## 2. Estado on-device final (checklist §2.1)

**Pendente — dono: humano (protocolo v2).** Nenhuma sessão A/B/C do
`docs/spec-2026-08-16-protocolo-on-device-consolidado-v2.md` foi executada até o
fechamento do código (V1 é trabalho humano; o agente executou V0 e V2). Toda a
dívida on-device das fases K (F0/K3/K4/K5/K6/K2/K1/K7) está centralizada nas
§4 "on-device pendente" de cada impl doc + no protocolo v2 — nenhuma linha
"pendente" sem dono (regra anti-acúmulo do checklist §3): o protocolo v2 é a
agenda única.

Limitações de stack já documentadas (não são pendência — são resultado):
- **Rumble via USB no DS4/MIUI**: `rumbleDevice` retorna false — o
  `dumpsys vibrator_manager` do Mi 11 só lista o motor do telefone
  (pendentes-e-validacao §1.2 F2.1). Re-testar em BT é linha do protocolo v2.
- **`reference/androidx`**: sparse-checkout quebrado (só arquivos raiz;
  `games/controller` não existe no branch) — sem uso; housekeeping opcional
  (master §6), NÃO bloqueia nada.

## 3. Desvios aceitos consolidados (checklist §2.1)

Origem: verificação de meio-termo §3 (2) + impl docs K6/K2/K1/K7 (§2 de cada).

### V0 (verificação de meio-termo)
1. **K3:** linha de origem do mapping foi para `DeviceDiagnosticsCard.kt` (lugar
   do card da fase C), não `SettingsGroupGamepad.kt` — spec §2 apontava o arquivo
   errado; o card é o lugar descrito no §1.5.
2. **K5:** não há testes JVM do `GamepadHub` (hub importa `android.*`; o repo
   testa os objetos puros — precedente K4); a cadeia é composição de partes
   testadas; cobertura via gate de compilação + assemble.

### K6 (impl K6 §2 — resumo)
3. GUID no layout **SDL2 Android** (product nos bytes 8..9), não SDL3 do spec
   §1.1 — o DB pinado e o parser do fork (F1.4) usam SDL2; seguir o SDL3
   quebraria o round-trip e o ecossistema.
4. Import valida com `AutoconfigValidation` (spec §1.2 não menciona validação
   explícita — string sem B/dpad quebraria menus; mesma regra do RetroArch).
5. GUID legado (hex-do-nome) no import → sem aviso de affinity (não há vid/pid).
6. Export usa o mapping BASE pré-quirk nos dois lugares (card e remap) —
   consistência com o K5.
7. Scroll horizontal não é exportado/importado (sink não tem hscroll — não-meta).
8. Keycodes alias (BACK=4/MENU=82) encodam como b4/b6; DPAD_CENTER=23 omitido
   (sem bN no vocabulário Android).

### K2 (impl K2 §2 — resumo)
9. dt fixo de 50 ms no `onStick` (sem timer; reproduz o postDelayed do moonlight
   — sem salto após pausa).
10. Haptic via `rumbleDevice` (o `vibrateDevice` exige Context que o hub não tem).
11. Só o stick ESQUERDO move o cursor (spec §1.1 "o stick"; configurar é
    follow-up declarado).
12. B = `rightClick()` no down (o sink não expõe pressRight/releaseRight).
13. `routeMouseMode` roda também no `onAxis` (o flush de eixos é o onAxis).
14. Overlay suspende via holder `overlayOpen` no hub (espelho booleano do
    `OverlayInputState`, lido no momento do evento — lição C1).

### K1 (impl K1 §2 — resumo)
15. Ponte no `ControlElement`, não no `ControllerManager` (quem emite bindings de
    gamepad é o `handleTouch*`; 16 chamadas → 1 método).
16. Flag dupla (`virtualGamepadPipeline` AND `gamepadUniversalEnabled`) — o
    virtual É o pipeline universal; sem as duas o overlay fica mudo.
17. Capacidades estáticas completas do device virtual (o layout restringe o que
    EMITE, não o que o pipeline aceita — tier MODEL vence de qualquer forma).
18. START do overlay não abre o QuickMenu (sem KeyEvent Android — documentado).
19. `InputEvent.deviceId` virou membro da interface (refactor mínimo).

### K7 (impl K7 §2 — resumo)
1. **"Aba" = seção no corpo do dialog** (o remap dialog não tem tabs — a seção
   própria após o bloco F1 usa a mesma navegação gamepad do dialog).
2. **Fonte RAW = eixos LÓGICOS do bus** (não o MotionEvent cru — o hub não expõe
   o cru no bus; a comparação é "como está hoje" vs "config proposta").
3. **Hysteresis não entrou na tab** (o `GamepadProfile` não expõe hysteresis —
   adicionar campo seria escopo fora do §1.1, que define só antiDeadzone/
   maxOutput; follow-up anotado).
4. **maxOutput = clamp (teto), não multiplicador** (ordem do spec §1.1; UI usa
   0.1..1 como o `gyroStickMaxOutput` do G4).
5. **Backlog #1 (GUI Kp/Ki) — stretch NÃO entregue**: sem preview de fusão e sem
   sliders Kp/Ki (o dialog segue sem editar `gyroFusionKp/Ki`); o #1 fica
   "parcial" no backlog (follow-up).

## 4. Milestone

`milestone-2026-08-16-universal-input-completo` — ver `docs/MILESTONES.md`
(gerado por `tools/milestone.sh` no fechamento).

## 5. Backlog pós-K (checklist §2.3)

- **#1 (GUI de Kp/Ki)**: absorvido — sliders Kp/Ki na seção de fusão da tab de
  calibração do K7 — stretch NÃO entregue (impl K7 §2.5): Kp/Ki continuam sem GUI; o #1 fica "parcial" e segue no backlog como follow-up..
- **#12 (calibração no mock visual)**: absorvido — tab de calibração visual do
  K7 (JoystickHistoryView RAW|CALIBRATED).
- Reavaliação dos demais (10 itens): #3 "gyro como ponteiro nos menus" ficou
  mais barato com o K2 (sink de mouse já existe) — segue como candidato;
  #7 "turbo configurável" não foi tocado — segue; os demais permanecem com o
  valor/esforço originais.

## 6. Não-metas confirmadas (checklist §4)

Calibração automática de stick (follow-up K7); contribuir autoconfigs ao
SDL_GameControllerDB upstream (follow-up K6); sincronização com o DB upstream;
uinput//dev/input raw; remap de apps externos; HD haptics/lightbar/adaptive
triggers; migração C++/Rust (baseline F0 p95 3–5 ms — arquivada); `LibraryList.kt`
(código morto); substituir camadas/triggers/expressões (as fases COMPÕEM);
reescrever o editor de layout do winlator (K1 preserva).

## 7. Critérios de "fechado" (checklist §3) — estado

- Tabela §7 do master 8/8 ✅ com hash por linha + rodapé apontando para este doc.
- Milestone anotada (ver §4).
- Impl doc de fechamento commitado (este arquivo).
- Backlog atualizado (ver §5).
- Protocolos on-device com estado explícito: sessões A/B/C pendentes (humano,
  protocolo v2) — nenhuma linha sem dono.

## 8. Docs do fluxo (V0–V3)

- `spec-2026-08-16-guia-universal-input-fechamento.md` — o mapa (V2 do guia).
- `spec-2026-08-16-universal-input-meio-termo-verificacao.md` — V0: auditoria +
  gate pós-clean VERDE (239 testes) + bug AXIS_Z/RZ (fix `e7ca45f0`).
- `spec-2026-08-16-protocolo-on-device-consolidado-v2.md` — V1 (HUMANO): sessões
  A/B/C pendentes — agenda única da dívida on-device (regra anti-acúmulo).
- `spec-2026-08-16-universal-input-retomada-fila.md` — V2: fila K6→K2→K1→K7
  (tabela §5 4/4 ✅ no checkpoint K7).
- `spec-2026-08-16-universal-input-fechamento.md` — V3: este checklist (fonte
  deste doc).
- **README/AGENTS** (checklist §2.6): SEM mudança — decisão do humano; as fases K
  adicionaram pacotes (`gamepad/virtual`, `gamepad/mapping` ampliado,
  `gamepad/processing/MouseModeProcessor`) mas o AGENTS.md atual já cobre o
  pipeline; registrar no README fica a critério do humano.
