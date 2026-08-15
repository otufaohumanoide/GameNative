# Spec de implementação — Focus Feedback v2 (linguagem visual de foco madura)

**Data:** 2026-08-16
**Base:** spec 2026-08-15-focus-feedback-v2.md (aprovado).
**Commit da implementação:** `73472c32` (implementado antes do roadmap 2026-08-16; este doc
fecha o loop spec → impl doc → MILESTONES — fase F0 do master roadmap UX 2026-H2).
**Status:** implementado — D1, D2 e D3 fechados; navegação (dedupe, guards, bootstrap)
intocada (spec §1.4/§2: este spec é APENAS visual/feedback).

---

## 1. O que foi implementado (evidências file:line)

### D1 — Focused mais forte

| Peça | Arquivo | Detalhe |
|---|---|---|
| Defaults 3dp/1200ms | `app/src/main/java/app/gamenative/ui/component/GamepadFocus.kt:86-87` | `width: Dp = 3.dp`, `durationMillis: Int = 1200` (uma volta a cada 1,2 s) |
| Defaults alinhados no wrapper | `app/src/main/java/app/gamenative/ui/component/FocusRing.kt:25-26` | `focusRing` passa a usar 3dp/1200ms para uniformidade fora do QuickMenu |
| Anel-base sólido claro | `GamepadFocus.kt:43` (`BASE_RING_LIGHTEN_FRACTION = 0.35f`) + `:170` (`baseRingColor = lerp(accentColor, Color.White, …)`) + `:211` (`drawOutline(outline, color = baseRingColor, …)`) | "primária clara" sob o sweep — o ring nunca desaparece entre as cores do gradiente |
| Sweep com alpha 0.75 | `GamepadFocus.kt:41` (`SWEEP_ALPHA = 0.75f`) + `:221` (`alpha = SWEEP_ALPHA`) | o sweep continua (identidade visual) mas o anel-base sempre aparece através |
| Overlay de fundo no foco | `GamepadFocus.kt:39` (`FOCUSED_OVERLAY_ALPHA = 0.08f`) + `:171` (overlayPaint) + `:199` (`canvas.drawRect(bounds, overlayPaint)`) | a row focada acende inteira, clipada no shape, ANTES do ring (padrão da Library original) |
| Ordem de desenho | `GamepadFocus.kt:193-199` | conteúdo → overlay → anel-base → sweep (tudo clipado no shape) |

### D2 — Selected rebaixado (fim da competição com o foco)

| Peça | Arquivo | Detalhe |
|---|---|---|
| Tint persistente 0.10 | `GamepadFocus.kt:45` (`SELECTED_TINT_ALPHA = 0.10f`) + `:250` (tintPaint) + `:102` (`Selected -> selectedTint(…)`) | checked de toggle/aba ativa/preset ativo continuam visíveis, sem borda sólida |
| Hairline 1dp alpha 0.5 | `GamepadFocus.kt:47` (`SELECTED_HAIRLINE_ALPHA = 0.5f`) + `:260` (`.border(1.dp, …)`) | borda fina opcional do Selected |
| `selectedTint` | `GamepadFocus.kt:237-261` | nova função privada: tint + hairline clipados no shape |
| Hierarquia documentada | `GamepadFocus.kt:60-91` (enum + KDoc) | Focused (animado+brilhante) > Locked (sólido grosso) > Selected (tint) — §1.4; Locked é o único estático sólido além do foco |
| KDoc dos modifiers | `GamepadModifiers.kt:107-114` (gamepadSelectable) e `:192-201` (gamepadAdjustableRow) | borda animada/brilhante exclusiva do foco; Selected = tint |

### D3 — Auditoria de nós invisíveis

| Peça | Arquivo | Detalhe |
|---|---|---|
| Fallback de rail confirmado visível | `app/src/main/java/app/gamenative/ui/component/QuickMenu.kt:573-591` | os botões da rail são alvos sempre-VISÍVEIS que desenham o mesmo ring (`QuickMenuTabButton -> gamepadSelectable`); fallback mantido só como último recurso (loading transitório) |
| `QuickMenuEmptyStateRow` (novo) | `QuickMenu.kt:2088-2143` | estado "nada navegável" explicitamente focusable: mensagem de conteúdo vazio com `gamepadFocusable` + ring padrão; não clicável (A propaga) |
| TOOLS sem processos usa o novo alvo | `QuickMenu.kt:1468-1473` | substitui o `Text` antigo por `QuickMenuEmptyStateRow` com `firstItemFocusRequester` + `onFocusIndexChanged` |

### Causa raiz do ring perdido (interação sem replay)

| Peça | Arquivo | Detalhe |
|---|---|---|
| Collector no escopo do chamador | `GamepadFocus.kt:90-98` | `val focused by interactionSource.collectIsFocusedAsState()` roda na PRIMEIRA composição (mesmo com `state == null`), antes do `when` — o collector antigo vivia dentro de `animatedFocusRing`, que só existe com `state == Focused`; um `MutableInteractionSource` não tem replay, então o collector nascesse DEPOIS do evento de foco e o ring nunca renderizaria |
| `animatedFocusRing` recebe `focused` pronto | `GamepadFocus.kt:122-136` | o ring agora recebe o estado `focused` por parâmetro (não coleta mais nada — a coleta é do `gamepadFocus`) |

## 2. Verificação

- **Build:** `JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:assembleModernDebug` → OK
  (re-executado como gate da fase F0 do master roadmap — prova de ambiente antes de código real).
- **JVM:** sem mudança de lógica (spec §2: zero mudança de comportamento de navegação) — os
  testes de foco existentes continuam válidos; nenhum teste novo previsto no spec.
- **On-device (spec §3, pendente — exige DS4 + Mi 11):**
  - [ ] QuickMenu: ring visível a 1 m; troca de tab com foco visível em toda transição (incl. tab vazia);
  - [ ] Settings → Gamepad: com 3+ toggles ON, apenas UMA row "acesa" (a focada); checked sem foco = tint sutil;
  - [ ] Slider com A-lock: lock claramente distinto de foco;
  - [ ] Radial editor / Shader browser / remap dialog: mesmo ring, mesma hierarquia.

## 3. Fora de escopo (spec §4)

- Redesign do remap dialog visual (mock estilo PPSSPP) — fase B do master roadmap
  (`spec-2026-08-16-B-remap-visual-ppsspp.md`).
- Rumble fallback no telefone + `USAGE_MEDIA` — fase A (`spec-2026-08-16-A-rumble-fallback-usage-media.md`).
