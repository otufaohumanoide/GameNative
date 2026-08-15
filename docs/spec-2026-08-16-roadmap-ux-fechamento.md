# Roadmap UX 2026-H2 — Fechamento, nits da fase F e verificação on-device consolidada

**Data:** 2026-08-16
**Base:** master roadmap + specs A–F. Este doc fecha as PENDÊNCIAS identificadas
na revisão da fase F e consolida toda verificação on-device do roadmap.

## 1. Nits da fase F (aplicar ANTES do commit — ordem)

1. `TurboScheduler.nextToggleAt(nowMs, periodMs, phase)` — `phase` não é usado na
   função pura: remover o parâmetro OU manter e documentar como contrato de
   assinatura no KDoc (a fase vive no handler). Teste `TurboSchedulerTest` alinhado.
2. HOLD mode (overlay com jogo rodando): verificação manual do consumo de touch —
   o gesto do overlay não pode vazar para o jogo (`change.consume()` +
   `overlayInputState` OVERLAY já cobrem; confirmar).
3. Rodar o gate: `--tests "*Radial*" --tests "*LayerResolver*" --tests "*Turbo*"`
   + `assembleModernDebug`.
4. Commit: `feat(gamepad): radial v2 + mode shift + turbo (...)` + checkpoint §6
   do master + impl doc (já escrito) + MILESTONES.

## 2. Verificação on-device consolidada A–F (humano, Mi 11 + DS4 + Silksong)

| Fase | Protocolo (harness `setprop debug.gamenative.input ...`) | Evidência esperada |
|---|---|---|
| A | `rumble:0.6:0.6:300` + botão Testar vibração no card | log `rumble → true/false` + destino CONTROLLER/PHONE/NONE; `dumpsys` com USAGE_MEDIA; toggle OFF silencia |
| B | Abrir remap pelo QuickMenu | mock renderiza no FaceStyle do device; tap→captura→bind salva; flash 600 ms; escopo Este jogo/Todos os jogos; restaurar automático |
| C | Expandir DeviceDiagnosticsCard | viewer acende; readouts gyro/touchpad variam; recentrar zera |
| F | Perfil v1 antigo carrega; submenu abre/volta; HOLD executa sem fechar (anti-repeat 120 ms); shift consome o botão sem abrir radial; turbo pulsa e solta limpo | screenshots + logcat `gncontrol` |
| D | (após implementar) `touch:0.5:0.5` + swipe sintético rápido vs arrasto lento | macro/radial dispara; arrasto/duplo-toque intactos |
| E | (após implementar) Browser offline, aplicar perfil muda o jogo na hora, badge na Library | log do store + badge no card |

Registrar resultados no padrão "on-device pendente" dos specs (cada fase marca
seu próprio §3).

## 3. Expectativas para D e E (agente)

D e E seguem o loop do master (§1) com os gates já definidos nas specs. Ao fim
de E: MILESTONES anotada (`tools/milestone.sh`) e este doc recebe o status
"ROADMAP COMPLETO" com a tabela de commits por fase.

### 3.1 Status: ROADMAP COMPLETO (2026-08-16)

Todas as fases fechadas com gate verde (impl docs em `docs/spec-2026-08-16-*-impl.md`);
milestone anotada `milestone-2026-08-16-roadmap-ux-completo` → `48427239`.

| Fase | Commit(s) | Gate | On-device |
|---|---|---|---|
| F0 | `31002f42` (milestone `milestone-2026-08-14-focus-feedback-v2`) | assemble | pendente (registrado no spec §3) |
| A | `015d6d09` | tests `*RumblePhoneCurve* *GamepadProfileStore*` + assemble | pendente (consolidado §2 linha A) |
| B | `29f5cbd4` | tests `*ControllerVisual* *Gamepad*` + assemble | pendente (consolidado §2 linha B) |
| C | `d5179c7b` | tests `*Gamepad*` + assemble | pendente (consolidado §2 linha C) |
| F | `1068604f` + nits `5c79933a` (milestone `milestone-2026-08-16-radial-v2-modeshift-turbo`) | tests `*Radial* *LayerResolver* *Turbo*` + assemble | pendente (consolidado §2 linha F) |
| D | `96a3e872` | tests `*Touchpad*` + assemble | pendente (consolidado §2 linha D) |
| E | `c74e52dc` | determinismo do sync (2× → diff vazio) + tests `*ProfileCatalog*` + assemble | pendente (consolidado §2 linha E) |

Nits da fase F (§1): 1 aplicado por REMOÇÃO do parâmetro `phase` (KDoc + teste
alinhados, commit `5c79933a`); 2 confirmado NO CÓDIGO (`change.consume()` no overlay +
`radialState.open` no contexto OVERLAY do bus — impl doc F §5; confirmação física
segue no protocolo humano); 3 gate re-rodado verde; 4 commit + checkpoint §6 + impl
doc + MILESTONES concluídos.

A verificação on-device consolidada (§2) é do HUMANO (Mi 11 + DS4 + Silksong) e
permanece registrada como "on-device pendente" no §3 de cada spec — não bloqueia o
fechamento (regra do master §1.3).

## 4. Handoff

Follow-ups fora do roadmap (Kp/Ki, wizard de onboarding, gyro no QuickMenu etc.)
vivem em `docs/spec-2026-08-16-backlog-ux-follow-ups.md` — NÃO entram nas fases
A–F nem bloqueiam o fechamento.
