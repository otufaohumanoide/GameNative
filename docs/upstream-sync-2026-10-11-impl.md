# Impl doc — sync com upstream `c78519cd9` (2026-10-11)

**Data:** 2026-10-11
**Upstream:** `utkarshdalal/GameNative` `c78519cd9` (**104 commits** desde `f1e7a7354`)
**Base do fork:** `2458bf786` · **Merge:** `180565b5d` · **Ponto de retorno:** tag `pre-sync-2026-10-11`
**Doutrina:** `docs/upstream-sync.md` (P1–P5)
**Status:** mergeado e verificado — build OK, **269 testes / 0 falhas**, shader intacto.

---

## 1. Números

| Métrica | Valor |
|---|---|
| commits do upstream | 104 |
| arquivos alterados pelo merge | 579 (+135.431 / −3.553) |
| arquivos tocados pelos **dois** lados | 31 |
| **conflitos** | **7** (2 `modify/delete` + 5 de conteúdo) |
| classes de teste executadas | 29 |
| testes | **269**, 0 falhas, 0 erros |

## 2. Conflitos e resolução

### 2.1 O conflito que decidia o shader: os `.so` do renderer

```
CONFLICT (modify/delete): app/src/{legacy,modern}/jniLibs/arm64-v8a/libvulkan_renderer.so
        deleted in HEAD and modified in upstream/master.
```

**Resolução: mantido deletado** (`git rm`). Motivo verificado, não presumido:

- `app/src/main/cpp/CMakeLists.txt:51-56` define `add_library(vulkan_renderer SHARED ...)` e inclui
  `winlator/VulkanLibrashader.cpp` — no fork o renderer é **compilado do fonte** com o librashader.
- O commit que restaurou a integração (`97a378618`) **deletou** esses `.so` (Bin 508552 → 0) em
  `legacy` e `modern`.
- Logo, o `.so` pré-compilado do upstream é artefato obsoleto para este fork: adotá-lo deixaria o
  app carregando um renderer **sem** librashader.

### 2.2 `SettingsScreen.kt` — manter as duas seções

O upstream adicionou `SettingsGroupGameNativeAccount()`; o fork tem a seção de gamepad
(`SettingsGroupGamepad()`, com gate `LocalInspectionMode`). Sem sobreposição real: o único `}`
compartilhado fechava a estrutura de cada lado de forma diferente. Resolvido com **as duas seções**,
na ordem do arquivo (conta primeiro), cada uma com seu fechamento.

### 2.3 `ElementEditorDialog.kt` — P1 puro

O fork não tinha nada no ponto; o upstream adicionou um `Slider` de opacidade ao lado do `Text` de
porcentagem. Entra a versão do upstream.

### 2.4 `TouchGestureSettingsDialog.kt` — picker do upstream + foco do fork

Três hunks entrelaçados. O upstream reescreveu o picker com **keys estáveis**
(`item(key = …)`, `items(..., key = { … })`) e removeu os offsets manuais; o fork envolvia tudo em
`GamepadFocusScope` e usava `actionOffset`/`categoryOffset` para o bootstrap `isFirstRow`.

Resolução: **keys do upstream** + **`GamepadFocusScope` do fork**, e o índice que o foco precisa veio
de `itemsIndexed(category.actions, key = { _, (k, _) -> "action-$categoryIndex-$k" })` — os dois
requisitos no mesmo call site. `isFirstRow` passou a usar `categoryIndex == 0 && actionIndex == 0`.
Os offsets manuais saíram (as keys os substituem).

### 2.5 `PerfVerdicts.kt` — o fork é superior, e por isso a duplicação não volta

O upstream reintroduziu `private val thermalNames = arrayOf("NONE", "LIGHT", …)`. O fork o havia
**removido de propósito** no commit `4d50ace6` ("ThermalStatuses vira fonte única dos rótulos"),
fazendo `thermalName(status)` delegar a `ThermalStatuses.name(status)`.

Verificação antes de decidir: `thermalNames` tinha **1 uso — a própria declaração** (código morto após
o refactor), enquanto `HELPER_SATURATED_CPU`/`HELPER_GAME_MAX_CPU`/`MAX_HELPER_NOTES` têm **2 usos
cada** (`saturatedHelpers`, código novo do upstream). Resolução: **mantidas as `HELPER_*`; `thermalNames`
não volta** — reintroduzi-lo restauraria exatamente a duplicação que o fork eliminou (P3 + P5).

### 2.6 `WinHandler.java` — o upstream vira dono; o fork reancora **um** hook

O caso mais delicado: as duas partes implementaram rumble no mesmo lugar, com **decisões
contraditórias**.

| | Fork (antes) | Upstream (novo) |
|---|---|---|
| amplitude | dual-motor (`low`/`high` separados) | `max(low, high)` único |
| telefone | fallback **removido** de propósito (spec U5/V11) | fallback com throttle e curva |
| gate | `gamepadRumbleEnabled` guarda tudo | `vibrationIntensity` por container |
| extras | — | keepalive/re-arm, `setScRumbleForwarder` (Steam Controller BLE, #1706) |

Como o fork mexeu **só em rumble** nesse arquivo (19 inserções / 79 remoções), a base passou a ser o
**arquivo do upstream** (`git checkout --theirs`), com o hook do fork reancorado:

- `startDeviceVibration(slot, deviceId, low, high)` → delega a `GamepadHaptics.rumbleDevice`
  (dual-motor + gate). **Preservado do upstream:** a assinatura por `slot` (o keepalive e o
  SC forwarder indexam por slot), o `vibrationIntensity` (escalando `low`/`high` — sem isso o ajuste
  por container ficaria inerte) e o `controllerRumbleAmplitude[slot]` (o keepalive **re-arma com
  ele** em `:957-959`; sem preencher, o re-arm vibraria amplitude 0).
- `stopDeviceVibration(deviceId)` → `rumbleDevice(deviceId, 0f, 0f, 0L)`, o contrato do fork.
- Removidos os agora-mortos `getPhoneRumbleAmplitude`, `PHONE_RUMBLE_FALLBACK_DURATION_MS`,
  `STANDALONE_PHONE_RUMBLE_*` e `lastStandalonePhoneRumbleMs`.

Resultado: comportamento do fork (dual-motor + gate) **e** as features novas do upstream
(forwarder do Steam Controller, keepalive).

## 3. Verificação do shader (feature obrigatória do fork)

| Verificação | Resultado |
|---|---|
| Arquivos do shader tocados pelo merge? | **Não** — `VulkanLibrashader.cpp/.h`, `renderer/VulkanRenderer.java`, o pacote `app/gamenative/shaders/` e `catalog.json` não aparecem no diff |
| Pontos de librashader em `VulkanRendererContext.cpp` | **67 → 67** (inalterados); a única mudança vinda do upstream ali é um fix de superfície XR em `enableXrTarget()` |
| Build do librashader (`app/build.gradle.kts`) | **28 → 28** referências: `cargoBin`, `ndkVersion = "27.3.13750724"`, `copyLibrashaderHeaders` e as tarefas `compileLibrashader*/copyLibrashader*` por ABI |
| Compilação | `:app:testModernDebugUnitTest` → **BUILD SUCCESSFUL** (compila main Kotlin+Java e o librashader em Rust) |
| Testes do shader | **12 classes, 78 testes, 0 falhas** (`PerGameShaderStore`, `ShaderCatalog`, `ShaderConfigResolve`, `ShaderDoubleClickLogic`, `ShaderFavorites`, `ShaderPackFiles`, `ShaderPagingLogic`, `ShaderPresetCost`, `ShaderToggleSubtitle`, `ShaderBrowserNav`, …) |
| Rumo ao rumble | `GamepadHapticsTest` (5) e os 8 `Gamepad*` verdes — o reancoramento não quebrou contrato |

## 4. O que o upstream trouxe de relevante

Steam Controller "Triton" completo por BLE (#1706) · OpenComposite v10 + OpenVR 32-bit + Pico (#2108) ·
cloud saves do Rockstar e vinculação de conta Steam/Rockstar/EA (#2095–#2099) · correções do headless
Steam (#2100) · decodificadores nativos WMA/WMV 64-bit (#2085) · Proton 11.0-2 e vkd3d-proton nightly
(#2089, #2077) · delta updates do Steam (#2101, #2111) · support cards com pacotes de wrapper e builds
pinadas do LSFG (#2109) · compatibilidade de jogos ciente de hardware (#2048) · bump para 1.3.0.
O asset `wrapper-gamenative.tzst` também foi atualizado pelo upstream.

## 5. Pendências e riscos

1. **Verificação on-device não feita.** O build compila e os testes passam, mas nenhum jogo foi
   rodado com este merge. Os pontos sensíveis são o rumble (keepalive + gate) e o picker de touch
   gesture (foco gamepad) — ambos têm teste de unidade verde, mas são comportamentais.
2. **`.so` deletados**: se algum dia o upstream passar a ser a única fonte do renderer, esta decisão
   precisa ser revisitada — hoje ela é o que garante o shader.
3. **Costuras novas** (31 arquivos) registradas em `docs/upstream-sync.md`; 24 auto-mesclaram, então
   a próxima rodada tende a conflitar menos.
4. **Não publicar sem decidir o destino do branch**: o merge está em `sync/upstream-2026-10-11`;
   `master` ainda aponta para `2458bf786`, e a tag `pre-sync-2026-10-11` permite voltar.
