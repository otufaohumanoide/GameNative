# Milestones do projeto

Mapa dos pontos importantes da história do projeto. Cada milestone é uma **tag anotada**
do git — permanente, nomeada e à prova de GC/reflog. Para voltar a um milestone:

```bash
git switch -c <nome-do-branch> milestone-<data>-<nome>   # ex.:
git switch -c retorno-milestone milestone-2026-08-07-librashader-funcional
```

Novos milestones: `tools/milestone.sh <nome-curto> "<descrição>"` (cria tag + registra aqui + push).

---

| Milestone (tag) | Commit (HEAD na época) | Data | O que marca |
|---|---|---|---|
| `milestone-2026-08-07-librashader-funcional` | `12de2797` | 2026-08-07 | **Shaders RetroArch (librashader) FUNCIONANDO no device** (Xiaomi Mi 11/Adreno 650). Tela preta corrigida (7 bugs: import AHB, finalLayout/transições, atlas→filterOutput, split submissions, cursor loadOp=CLEAR, config não aplicada no launch, crash cold-start). 131 presets libretro embarcados, default `film/technicolor` verificado por pixel-stats. Loop de teste automatizado `tools/shader-test-loop/shader_test_loop.py` (setprop `debug.gamenative.preset` + screencap + logcat; 16/17 shaders OK; `misc/glass` falha compile). Deferred preset reload na render thread (sem SIGSEGV). Commits-chave: `06aef179` (fix), `cf8085aa` (shaders+loop), `fc95cb4b` (docs). |
| *(próximo)* | | | |
