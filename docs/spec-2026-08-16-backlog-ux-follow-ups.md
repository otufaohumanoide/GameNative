# Backlog UX — follow-ups fora do roadmap A–F (ordenados por valor/esforço)

**Data:** 2026-08-16
**Regra:** nada aqui entra no master roadmap sem spec própria aprovada.
**Atualização (fechamento do universal input, 2026-08-16):** #12 absorvido pelo
K7 (tab de calibração visual); #1 parcial (ver abaixo). Reavaliação dos demais à
luz das fases K: #3 ficou mais barato com o K2 (sink de mouse existe);
#7 não foi tocado — segue.

| # | Item | Origem (fora de escopo de) | Trigger | Esforço | Estado |
|---|---|---|---|---|---|
| 1 | GUI de Kp/Ki da fusão Mahony | desvio nº 4 impl doc input-core | usuário com drift de pitch em accel ruim | S | **parcial** — o K7 entregou a tab de calibração visual mas o stretch de sliders Kp/Ki NÃO foi entregue (impl K7 §2.5); segue como follow-up |
| 2 | Wizard de onboarding do controle (detecta→badges→testa→pronto) | roadmap §4 | primeiro usuário novo | M | segue |
| 3 | Gyro como ponteiro nos menus (QuickMenu/browser) | roadmap §2 | usuário gamepad-only pede | M | segue — ficou mais barato com o K2 (sink de mouse universal existe) |
| 4 | Tick háptico opcional na mudança de FOCO (não só camada/setor) | roadmap §3 | feedback de navegação | S | segue |
| 5 | Indicador granular de herança por campo (AUTO/GLOBAL/JOGO) | B §4 | confusão "de onde veio este binding" | S | segue |
| 6 | Probing de vibrators por sub-device (moonlight `ControllerHandler.java:733`) | A §4 | BT re-teste do DS4 listar vibrator em outro deviceId | S | segue |
| 7 | Turbo com período configurável por binding na UI | F §4 | usuário quer cadência própria | S | segue |
| 8 | Submenus >1 nível e ícones custom do usuário | F §4 | demanda de power-user | M | segue |
| 9 | Sensibilidade/swipes de 2 dedos configuráveis | D §4 | demanda | M | segue |
| 10 | Favoritos + versionamento no catálogo de perfis | E §4 | catálogo crescer | M | segue |
| 11 | Re-leitura do gatilho HOLD do radial após execução TAP_RELEASE | desvio nº 6 (parcialmente resolvido por HOLD mode) | UX do painel | S | segue |
| ~~12~~ | ~~Perfil de calibração de stick no mock visual~~ | B §4 | ~~usuário ajusta curva no desenho~~ | L | **absorvido pelo K7** (tab de calibração visual — JoystickHistoryView RAW vs CALIBRADO) |

Cada item, quando aprovado: spec própria em `docs/spec-<data>-<nome>.md`,
seguindo o workflow spec → revisão → implementação → impl doc → MILESTONES.

Follow-ups declarados das fases K (não estão na tabela acima — entram com spec
própria quando pedidos): contribuir autoconfigs ao upstream SDL_GameControllerDB
(K6 §1.4); configurar o botão/chord do toggle do modo mouse (K2 §5);
velocidade do modo mouse por jogo (K2 §5); calibração automática de stick (K7
§5); edição visual da LUT por gesto (K7 §5); Notch/gates do Dolphin (K7 §5);
hysteresis configurável por perfil (K7 §2.3); scroll horizontal no modo mouse
(K6 §2.5/K2 — sink sem hscroll).
