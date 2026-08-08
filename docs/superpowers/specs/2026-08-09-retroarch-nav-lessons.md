# Lições de navegação mobile do RetroArch (Ozone) — 2026-08-09

> **Fonte:** clone de referência `RetroArch/` (libretro/RetroArch). Driver de menu padrão no
> mobile: **Ozone** (`menu/drivers/ozone.c`), com **MaterialUI** (`materialui.c`) como
> alternativa. Ambos são desenhados para navegação 100% por controle.

## Padrões do Ozone verificados no código

| # | Padrão | Onde no código | Tradução contextual p/ GameNative |
|---|---|---|---|
| 1 | **Footer hints contextuais** — barra inferior com ações do botão para a entrada atual (OK, Back, Search, Reset, Random, Cycle thumbnails, Help, Clear, Scan, Manage), strings localizadas via `msg_hash` | `ozone.c:356-363, 515-541, 9960-10040` (`ozone_cache_footer_labels`) | **Implementar:** `GamepadActionBar` (já existe, localizado, `shouldShowGamepadUI`) no rodapé do QuickMenu com A=Selecionar, B=Voltar, L1/R1=Abas, L2/R2=Página — resolve P3-16 do spec de gamepad e a descoberta ("o que cada botão faz") |
| 2 | **L1/R1 = scroll por página; L2/R2 = salto por letra** (configurável/swap) | `menu_driver.c:5829-5837` (`MENU_SCROLL_PAGE`/`MENU_SCROLL_START_LETTER`) | Lista de 131 presets precisa de navegação rápida: **L2/R2 = página** no conteúdo da aba EFFECTS (L1/R1 fica para trocar abas, consistente com a LibraryScreen) |
| 3 | **L3/R3 = início/fim da lista** | `menu_driver.c:5841-5842` (`MENU_SCROLL_HOME/END`) | Home/End na lista de presets (opcional) |
| 4 | **A=OK, B=Cancel, START=ações rápidas, hotkey=menu toggle** | `menu_driver.c:5860-5876`, `MENU_ACTION_START:8035` | Já temos A=ativar; B hierárquico no QuickMenu; START como atalho futuro |
| 5 | **Enter/leave sidebar com LEFT/RIGHT** (foco vira contexto) | `ozone.c:4873, 8852` (`ozone_leave_sidebar`) | QuickMenu: LEFT volta ao rail, RIGHT entra no conteúdo — **implementar** (hoje o foco só anda por DPAD dentro do grafo; um RIGHT explícito do rail→conteúdo e LEFT conteúdo→rail torna o mapa mental explícito) |
| 6 | **Repeat sempre permitido** (segurar navega) | `menu_driver.c:5778-5781` ("Always allow repeat direction") | JoystickFocusNavigator já repete via cooldown ✓ |
| 7 | **Remember selection** (`menu_remember_selection`): restaurar posição ao reentrar | `ozone.c:8852` + settings `menu_remember_selection` | Reabrir o menu de shaders mantém o último preset focado/scroll — **implementar** (scrollState já persiste; falta restaurar o foco) |
| 8 | **Thumbnails/contexto com RIGHT STICK** | `menu_driver.c:5809-5826` (`RARCH_ANALOG_RIGHT_*` → cycle thumbnails) | Idéia: RIGHT stick no menu de shaders cicla presets vizinhos (pré-visualização ao vivo) — futuro |
| 9 | **Tab/sidebar = destinos de primeiro nível** | Ozone sidebar + categories | Nosso rail de abas já segue o padrão Material/Google navigation rail ✓ |

## Implementações desta rodada

1. **Footer hints no QuickMenu** (`GamepadActionBar`): A=Selecionar, B=Voltar, L1/R1=Abas,
   L2/R2=Página — visível apenas com gamepad conectado (`shouldShowGamepadUI`), strings
   localizadas (14 locales).
2. **L1/R1 = trocar aba selecionada** (consistente com LibraryScreen) + **L2/R2 = scroll por
   página** no conteúdo da aba EFFECTS (lista de presets).

## Não implementado (futuro)

- LEFT/RIGHT explícito rail↔conteúdo; L3/R3 home/end; remember-selection de foco; right-stick
  pré-visualização de presets; START = ações rápidas.
