# Backlog UX — follow-ups fora do roadmap A–F (ordenados por valor/esforço)

**Data:** 2026-08-16
**Regra:** nada aqui entra no master roadmap sem spec própria aprovada.

| # | Item | Origem (fora de escopo de) | Trigger | Esforço |
|---|---|---|---|---|
| 1 | GUI de Kp/Ki da fusão Mahony | desvio nº 4 impl doc input-core | usuário com drift de pitch em accel ruim | S |
| 2 | Wizard de onboarding do controle (detecta→badges→testa→pronto) | roadmap §4 | primeiro usuário novo | M |
| 3 | Gyro como ponteiro nos menus (QuickMenu/browser) | roadmap §2 | usuário gamepad-only pede | M |
| 4 | Tick háptico opcional na mudança de FOCO (não só camada/setor) | roadmap §3 | feedback de navegação | S |
| 5 | Indicador granular de herança por campo (AUTO/GLOBAL/JOGO) | B §4 | confusão "de onde veio este binding" | S |
| 6 | Probing de vibrators por sub-device (moonlight `ControllerHandler.java:733`) | A §4 | BT re-teste do DS4 listar vibrator em outro deviceId | S |
| 7 | Turbo com período configurável por binding na UI | F §4 | usuário quer cadência própria | S |
| 8 | Submenus >1 nível e ícones custom do usuário | F §4 | demanda de power-user | M |
| 9 | Sensibilidade/swipes de 2 dedos configuráveis | D §4 | demanda | M |
| 10 | Favoritos + versionamento no catálogo de perfis | E §4 | catálogo crescer | M |
| 11 | Re-leitura do gatilho HOLD do radial após execução TAP_RELEASE | desvio nº 6 (parcialmente resolvido por HOLD mode) | UX do painel | S |
| 12 | Perfil de calibração de stick no mock visual | B §4 | usuário ajusta curva no desenho | L |

Cada item, quando aprovado: spec própria em `docs/spec-<data>-<nome>.md`,
seguindo o workflow spec → revisão → implementação → impl doc → MILESTONES.
