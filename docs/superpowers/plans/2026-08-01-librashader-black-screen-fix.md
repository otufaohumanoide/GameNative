# Librashader Black Screen Fix — Implementation Plan (Hipótese A / atlas melonDS)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolver a tela preta ao aplicar shaders RetroArch no GameNative (Xiaomi Mi 11 / Adreno 650 / Vulkan 1.1.128), reimplantando o padrão do melonDS (atlas intermediário dedicado + cópia no mesmo CB do filtro + submit-and-wait + presente em GENERAL), precedido por experimentos de diagnóstico P1–P4 que discriminam o mecanismo.

**Architecture:** A hipótese eleita (docs/hypotheses/decision.md, score A=161) é: a leitura da saída do filtro com **destino = swapchain no mesmo ciclo** é a configuração problemática; o fix robusto é **topologia de imagens** — `filterOutput` (saída da filter chain) → cópia por copy engine (`vkCmdCopyImage`, primário) para um **atlas dedicado** (`atlasOutput`) no mesmo command buffer do `applyFrame` + submit-and-wait (fence UINT64_MAX), depois present do atlas em `GENERAL` num submission posterior, com layout tracking manual por imagem (`resource.layout`), swapchain revertida a só `COLOR_ATTACHMENT`.

**Tech Stack:** C++17, Vulkan 1.1.128 (Adreno 650 driver stock), librashader C API ABI 2 (dlopen `liblibrashader.so`), Gradle CMake (target `vulkan_renderer`), JNI, Kotlin (UI).

## Global Constraints

- **Alvo de hardware:** Xiaomi Mi 11 (alioth), Adreno 650, Vulkan 1.1.128, driver stock (`/vendor/lib64/hw/vulkan.adreno.so`). Não validar em outra GPU.
- **NÃO repetir as 12 anti-soluções** (docs/librashader-failed-attempts.md §6): não rediagnosticar renderização do librashader; não testar sampler vs transfer como causa; não split de submissions como fix isolado; não mexer em `TRANSFER_DST` da swapchain como fix (exceção: **desvio declarado** — reverter swapchain a só `COLOR_ATTACHMENT` no fix, "reverter não re-testar", melonDS:1484); não mexer em alpha/compositeAlpha; não alargar barreiras `ALL_COMMANDS` como fix; não re-adicionar `queueMtx` como solução (manter por segurança é ok); não reintroduzir prebuilts `libvulkan_renderer.so` em `jniLibs`; não descomentar target `winlator` no CMake (fontes incompletas); não reativar TEST MODE como resposta (diagnóstico só); não assumir problema no layout de `offscreenImage` (fixado em 3.5); não mudar `use_dynamic_rendering` para `true`.
- **`use_dynamic_rendering=false`** (mantido; issue upstream #225 mostra preto com dynamic rendering).
- **Formatos:** tudo `R8G8B8A8_UNORM` (offscreen, filterOutput, atlas, swapchain). `vkCmdCopyImage` exige 1:1 formato/extent.
- **`VkTable`** é o despacho de funções Vulkan do renderer; funções novas (ex.: `vkCmdCopyImage`, `vkCmdCopyImageToBuffer`, `vkCmdBlitImage`, `vkUnmapMemory`) precisam ser adicionadas ao `VkTable` e carregadas em `loadDeviceDispatch`/`loadInstanceDispatch`.
- **Sincronização do filtro:** CB/fence dedicados (`filterCmdBuf`/`filterFence`); `WaitForFences(..., UINT64_MAX)` na CPU entre a submission do filtro e a gravação do presente (padrão melonDS 2228-2243). `queueMtx` pode permanecer por segurança.
- **Comandos de build:** `./gradlew assembleModernDebug --no-daemon` (usar env do `AGENTS.md`/`build-apk.sh` do repo). Verificação do APK: `strings <apk> | grep nativeLoadLibrashaderPreset`; `unzip -l <apk> | grep liblibrashader`.
- **Base de evidências a manter em log:** `READBACK-P` (readback do processedImage), `READBACK-SC` (swapchain), `READBACK offscreen`. Instrumentação de diagnóstico nunca é removida antes do fix final confirmar.

---

### Task 1: Restaurar a base da integração (C++/JNI/build/UI)

A integração librashader foi revertida para o upstream. O commit `6a648093` (WIP) contém a base do renderer (offscreen/processed, filterCmdBuf, applyFrame, blit, JNI, build.gradle, UI). O wrapper `VulkanLibrashader.*` NÃO está em nenhum commit — será escrito na Task 2.

**Files:**
- Restore: `app/src/main/cpp/winlator/VulkanRendererContext.cpp`, `app/src/main/cpp/winlator/VulkanRendererContext.h`, `app/src/main/cpp/winlator/vulkan_jni.cpp`, `app/src/main/cpp/CMakeLists.txt`, `app/build.gradle.kts`, `app/src/main/java/com/winlator/renderer/VulkanRenderer.java`, `app/src/main/java/app/gamenative/ui/component/ScreenEffectsPanel.kt`, `.gitignore`
- Delete: `app/src/legacy/jniLibs/arm64-v8a/libvulkan_renderer.so`, `app/src/modern/jniLibs/arm64-v8a/libvulkan_renderer.so` (anti-solução 8: prebuilt sem JNI do librashader causa `UnsatisfiedLinkError`; o `.so` do CMake é o único fonte)

- [ ] **Step 1: Restaurar arquivos do commit WIP 6a648093**

```bash
git checkout 6a648093 -- \
  app/src/main/cpp/winlator/VulkanRendererContext.cpp \
  app/src/main/cpp/winlator/VulkanRendererContext.h \
  app/src/main/cpp/winlator/vulkan_jni.cpp \
  app/src/main/cpp/CMakeLists.txt \
  app/build.gradle.kts \
  app/src/main/java/com/winlator/renderer/VulkanRenderer.java \
  app/src/main/java/app/gamenative/ui/component/ScreenEffectsPanel.kt \
  .gitignore
git rm --cached app/src/legacy/jniLibs/arm64-v8a/libvulkan_renderer.so
git rm --cached app/src/modern/jniLibs/arm64-v8a/libvulkan_renderer.so
rm -f app/src/legacy/jniLibs/arm64-v8a/libvulkan_renderer.so
rm -f app/src/modern/jniLibs/arm64-v8a/libvulkan_renderer.so
```

Expected: os arquivos restaurados; `git status` mostra as 8 alterações + 2 deletions de `.so`.

- [ ] **Step 2: Garantir que o `liblibrashader.so` entra no build sem a fonte Rust**

O `build.gradle.kts` do WIP referencia `librashaderSourceDir = rootProject.file("librashader")`, que **não existe** no repo (fonte deletada). Como o `.so` pré-buildado já existe em `app/build/generated/librashader/jniLibs/{arm64-v8a,x86_64,armeabi-v7a}/liblibrashader.so`, ajuste a tarefa para **copiar o prebuilt** em vez de compilar com cargo (anti-solução 8 não se aplica ao `liblibrashader.so`, que é uma dependência externa, não o `libvulkan_renderer.so`).

Edite `app/build.gradle.kts`: na seção `abiConfigs.forEach { ... }`, substitua o bloco `compileLibrashader*`/`copyLibrashader*` por cópia direta do prebuilt:

```kotlin
val librashaderPrebuiltRoot = rootProject.file("app/build/generated/librashader/jniLibs")
abiConfigs.forEach { config ->
    val abi = config.abi
    val copyTaskName = "copyLibrashader${abi.replaceFirstChar { it.uppercase() }}"
    tasks.register(copyTaskName) {
        dependsOn(copyLibrashaderHeaders)
        doLast {
            val srcSo = librashaderPrebuiltRoot.resolve("$abi/liblibrashader.so")
            val jniLibsDir = librashaderBuildDir.map { it.dir("jniLibs/$abi") }.get().asFile
            jniLibsDir.mkdirs()
            if (srcSo.exists()) {
                srcSo.copyTo(jniLibsDir.resolve("liblibrashader.so"), overwrite = true)
                logger.lifecycle("librashader: copied prebuilt ${srcSo} -> $jniLibsDir/liblibrashader.so")
            } else {
                throw GradleException("prebuilt librashader .so not found at ${srcSo}")
            }
        }
    }
}
```

Expected: `./gradlew :app:copyLibrashaderArm64V8A --no-daemon` copia o `.so` sem invocar cargo.

- [ ] **Step 3: Build inicial (base sem o wrapper) e verificar falha esperada**

```bash
./gradlew assembleModernDebug --no-daemon
```

Expected: falha de compilação **esperada** — `VulkanLibrashader.h` ausente (o `.h` restaurado faz `#include "VulkanLibrashader.h"`). Confirma que a Task 2 é necessária. Se o build passar, algo está errado na restauração (checar se o `.h` inclui o wrapper).

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "wip: restore librashader integration base from 6a648093 (renderer, JNI, build, UI)"
```

---

### Task 1b: Reconstruir UI helpers de shaders (ShaderImporter, RetroArchShaderConfig, dialog extensions)

**Gap descoberto na execução (Task 1):** o `ScreenEffectsPanel.kt` restaurado do WIP referencia classes que **nunca foram commitadas** (estavam só no working tree, revertidas e irrecuperáveis do git):
`ShaderImporter`, `RetroArchShaderConfig`, e as extensões de `app.gamenative.ui.component.dialog` (`categoryOf`, `ensureBundledShaders`, `friendlyName`, `loadShaderConfig`, `persistShaderConfig`). Sem elas o build Kotlin falha. O usuário decidiu **reconstruir** (não stub, não remover).

**Files:**
- Create: `app/src/main/java/com/winlator/renderer/RetroArchShaderConfig.java`
- Create: `app/src/main/java/com/winlator/renderer/ShaderImporter.java`
- Create: `app/src/main/java/app/gamenative/ui/component/dialog/RetroArchShaderDialog.kt` (extensões `categoryOf`/`friendlyName`/`ensureBundledShaders`/`loadShaderConfig`/`persistShaderConfig`)
- Create: `app/src/main/assets/retroarch/` (presets `.slangp` curados — mínimo 3 simples e funcionais: e.g. `crt`, `scalefx`/`sharp-bilinear`, `smooth`)

**Interfaces (contrato exigido pelo ScreenEffectsPanel.kt WIP — verificado):**
- `ShaderImporter(Context)` com `List<Map.Entry<String, String>> listBundledPresets()` (key = caminho relativo, value = nome amigável) e `ImportResult importBundledPreset(String key)` com `.success: boolean` e `.presetPath: String`.
- `RetroArchShaderConfig(boolean enabled, String presetPath, String presetName, String description, String relativePath)` com getters `enabled/presetPath/presetName/relativePath`.
- Extensões `dialog`:
  - `fun String.friendlyName(): String` (nome amigável do arquivo `.slangp`)
  - `fun String.categoryOf(): String?` (categoria derivada do subdir)
  - `fun ensureBundledShaders(context: Context)` (copia assets/retroarch → dir de presets do app se ausente)
  - `fun loadShaderConfig(container: Container?): RetroArchShaderConfig`
  - `fun persistShaderConfig(container: Container?, config: RetroArchShaderConfig)`
- Seguir o padrão de persistência de `ScreenEffectsConfig.kt` (`container.getExtra`/`putExtra` + `container.saveData()`).

**Presets curados:** criar `.slangp` simples e válidos (ex.: `crt/easymode.slangp`, `interpolation/nearest.slangp`, `interpolation/linear.slangp`) — cada um com 1 pass simples em `#version 450`/GLSL, copiados para assets. Devem carregar sem erro no librashader (verificar com `libra_preset_create` no log da Task 3/6). Não exige `.slang` externos: usar passes inline em `shader0.slang` embutido no preset é inválido no `.slangp` (exige arquivo); portanto incluir também os arquivos `.slang` correspondentes em assets/retroarch, OU usar o formato com `#reference`/shader inline aceito. **Decisão:** criar presets com arquivos `.slangp` + `.slang` juntos (padrão RetroArch).

---

### Task 2: Escrever o wrapper `VulkanLibrashader.h/.cpp`

O wrapper dlopen da C API (ABI 2). Interface exigida pelo `VulkanRendererContext` restaurado (verificado em 6a648093):
`loadLibrary()`, `init(instance, physicalDevice, device, queue, gipa)`, `isLoaded()`, `reloadPreset(path)`, `isActive()`, `getLastError()`, `applyFrame(cb, frameCount, srcImage, srcFormat, srcW, srcH, dstImage, dstFormat, dstW, dstH, viewportExtent, clearHistory)`, `setParam(name, value)`.

**Files:**
- Create: `app/src/main/cpp/winlator/VulkanLibrashader.h`
- Create: `app/src/main/cpp/winlator/VulkanLibrashader.cpp`
- Modify: `app/src/main/cpp/CMakeLists.txt` (já lista `VulkanLibrashader.cpp` na Task 1)

**Interfaces:**
- Consumes: `libra_device_vk_t`, `filter_chain_vk_opt_t`, `libra_image_vk_t`, `libra_viewport_t`, `frame_vk_opt_t` (definidos em `app/build/generated/librashader/include/librashader.h`; include dir = `${CMAKE_CURRENT_BINARY_DIR}`, e o header gerado precisa estar acessível — verificar include path na Task 3).
- Produces: classe `VulkanLibrashader` com os métodos acima; usada por `VulkanRendererContext` (Task 1) e chamada via JNI (Task 1 já restaura o JNI).

- [ ] **Step 1: Escrever o header**

`app/src/main/cpp/winlator/VulkanLibrashader.h`:

```cpp
#pragma once
#include <vulkan/vulkan.h>
#include <dlfcn.h>
#include <string>
#include <mutex>

struct libra_shader_preset;
typedef struct _shader_preset *libra_shader_preset_t;
typedef struct _filter_chain_vk *libra_vk_filter_chain_t;
typedef struct libra_preset_ctx_t *libra_preset_ctx_t;
struct libra_device_vk_t;
struct filter_chain_vk_opt_t;
struct libra_image_vk_t;
struct libra_viewport_t;
struct frame_vk_opt_t;

typedef int libra_error_t;

class VulkanLibrashader {
public:
    VulkanLibrashader();
    ~VulkanLibrashader();

    bool loadLibrary();
    bool isLoaded() const { return handle != nullptr; }

    bool init(VkInstance instance, VkPhysicalDevice physicalDevice,
              VkDevice device, VkQueue queue, PFN_vkGetInstanceProcAddr gipa);

    void reloadPreset(const std::string& presetPath);
    bool isActive() const { return chain != nullptr; }
    void setParam(const std::string& name, float value);

    bool applyFrame(VkCommandBuffer cb, uint64_t frameCount,
                    VkImage srcImage, VkFormat srcFormat, uint32_t srcW, uint32_t srcH,
                    VkImage dstImage, VkFormat dstFormat, uint32_t dstW, uint32_t dstH,
                    VkExtent2D viewportExtent, bool clearHistory);

    const std::string& getLastError() const { return lastError; }

private:
    void* handle = nullptr;
    PFN_vkGetInstanceProcAddr gipa = nullptr;
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;

    libra_shader_preset_t preset = nullptr;
    libra_preset_ctx_t presetCtx = nullptr;
    libra_vk_filter_chain_t chain = nullptr;

    std::string presetPath;
    std::string lastError;
    std::mutex mtx;

    // C API function pointers
    libra_error_t (*fnPresetCreateWithOptions)(const char*, libra_preset_ctx_t*,
        libra_shader_preset_t*, struct libra_preset_opt_t*) = nullptr;
    libra_error_t (*fnPresetFree)(libra_shader_preset_t*) = nullptr;
    libra_error_t (*fnPresetCtxCreate)(libra_preset_ctx_t*) = nullptr;
    libra_error_t (*fnPresetCtxFree)(libra_preset_ctx_t*) = nullptr;
    libra_error_t (*fnPresetCtxSetAllowRotation)(libra_preset_ctx_t*, bool) = nullptr;
    libra_error_t (*fnVkFilterChainCreate)(libra_shader_preset_t*,
        struct libra_device_vk_t, const struct filter_chain_vk_opt_t*,
        libra_vk_filter_chain_t*) = nullptr;
    libra_error_t (*fnVkFilterChainFrame)(libra_vk_filter_chain_t*, VkCommandBuffer,
        size_t, struct libra_image_vk_t, struct libra_image_vk_t,
        const struct libra_viewport_t*, const float*, const struct frame_vk_opt_t*) = nullptr;
    libra_error_t (*fnVkFilterChainFree)(libra_vk_filter_chain_t*) = nullptr;
    libra_error_t (*fnVkFilterChainSetParam)(libra_vk_filter_chain_t*, const char*, float) = nullptr;
};
```

- [ ] **Step 2: Escrever a implementação**

`app/src/main/cpp/winlator/VulkanLibrashader.cpp`:

```cpp
#include "VulkanLibrashader.h"
#include <librashader.h>
#include <android/log.h>
#include <cstring>
#define LLOG(...) __android_log_print(ANDROID_LOG_DEBUG,"Winlator_Librashader",__VA_ARGS__)
#define LLOG_E(...) __android_log_print(ANDROID_LOG_ERROR,"Winlator_Librashader",__VA_ARGS__)

VulkanLibrashader::VulkanLibrashader() = default;
VulkanLibrashader::~VulkanLibrashader() {
    if (chain) { fnVkFilterChainFree(&chain); chain = nullptr; }
    if (preset) { fnPresetFree(preset); preset = nullptr; }
    if (presetCtx) { fnPresetCtxFree(presetCtx); presetCtx = nullptr; }
    if (handle) { dlclose(handle); handle = nullptr; }
}

bool VulkanLibrashader::loadLibrary() {
    if (handle) return true;
    handle = dlopen("liblibrashader.so", RTLD_NOW | RTLD_GLOBAL);
    if (!handle) { lastError = "dlopen failed: " + std::string(dlerror()); LLOG_E("%s", lastError.c_str()); return false; }

    auto sym = [&](const char* n) { return dlsym(handle, n); };
    fnPresetCreateWithOptions = (decltype(fnPresetCreateWithOptions))sym("libra_preset_create_with_options");
    fnPresetFree              = (decltype(fnPresetFree))sym("libra_preset_free");
    fnPresetCtxCreate         = (decltype(fnPresetCtxCreate))sym("libra_preset_ctx_create");
    fnPresetCtxFree           = (decltype(fnPresetCtxFree))sym("libra_preset_ctx_free");
    fnPresetCtxSetAllowRotation = (decltype(fnPresetCtxSetAllowRotation))sym("libra_preset_ctx_set_allow_rotation");
    fnVkFilterChainCreate     = (decltype(fnVkFilterChainCreate))sym("libra_vk_filter_chain_create");
    fnVkFilterChainFrame      = (decltype(fnVkFilterChainFrame))sym("libra_vk_filter_chain_frame");
    fnVkFilterChainFree       = (decltype(fnVkFilterChainFree))sym("libra_vk_filter_chain_free");
    fnVkFilterChainSetParam   = (decltype(fnVkFilterChainSetParam))sym("libra_vk_filter_chain_set_param");

    if (!fnPresetCreateWithOptions || !fnPresetFree || !fnPresetCtxCreate || !fnPresetCtxFree ||
        !fnVkFilterChainCreate || !fnVkFilterChainFrame || !fnVkFilterChainFree) {
        lastError = "dlsym: missing required librashader symbols";
        LLOG_E("%s", lastError.c_str());
        return false;
    }
    if (fnPresetCtxSetAllowRotation) LLOG("librashader: allow_rotation symbol present");
    LLOG("librashader: library loaded");
    return true;
}

bool VulkanLibrashader::init(VkInstance inst, VkPhysicalDevice pdev, VkDevice dev, VkQueue q, PFN_vkGetInstanceProcAddr g) {
    instance = inst; physicalDevice = pdev; device = dev; queue = q; gipa = g;
    return true;
}

void VulkanLibrashader::reloadPreset(const std::string& path) {
    std::lock_guard<std::mutex> lk(mtx);
    presetPath = path;
    if (!handle) { lastError = "library not loaded"; return; }

    if (chain) { fnVkFilterChainFree(&chain); chain = nullptr; }
    if (preset) { fnPresetFree(preset); preset = nullptr; }
    if (presetCtx) { fnPresetCtxFree(presetCtx); presetCtx = nullptr; }

    if (path.empty()) { lastError.clear(); return; }

    libra_error_t err = fnPresetCtxCreate(&presetCtx);
    if (err != 0) { lastError = "preset_ctx_create failed: " + std::to_string(err); LLOG_E("%s", lastError.c_str()); return; }
    if (fnPresetCtxSetAllowRotation) fnPresetCtxSetAllowRotation(presetCtx, false);

    err = fnPresetCreateWithOptions(path.c_str(), &presetCtx, &preset, nullptr);
    if (err != 0) { lastError = "preset_create_with_options failed: " + std::to_string(err); LLOG_E("%s", lastError.c_str()); return; }

    libra_device_vk_t vkDev{};
    vkDev.physical_device = physicalDevice;
    vkDev.instance = instance;
    vkDev.device = device;
    vkDev.queue = queue;
    vkDev.entry = gipa;

    filter_chain_vk_opt_t opt{};
    opt.version = 2;
    opt.frames_in_flight = 3;
    opt.force_no_mipmaps = false;
    opt.use_dynamic_rendering = false;
    opt.disable_cache = false;

    err = fnVkFilterChainCreate(&preset, vkDev, &opt, &chain);
    if (err != 0) { lastError = "vk_filter_chain_create failed: " + std::to_string(err); LLOG_E("%s", lastError.c_str()); chain = nullptr; return; }
    lastError.clear();
    LLOG("librashader: filter chain created for %s", path.c_str());
}

void VulkanLibrashader::setParam(const std::string& name, float value) {
    std::lock_guard<std::mutex> lk(mtx);
    if (chain && fnVkFilterChainSetParam) {
        libra_error_t err = fnVkFilterChainSetParam(&chain, name.c_str(), value);
        if (err != 0) LLOG_E("librashader: set_param %s failed: %d", name.c_str(), (int)err);
    }
}

bool VulkanLibrashader::applyFrame(VkCommandBuffer cb, uint64_t frameCount,
    VkImage srcImage, VkFormat srcFormat, uint32_t srcW, uint32_t srcH,
    VkImage dstImage, VkFormat dstFormat, uint32_t dstW, uint32_t dstH,
    VkExtent2D viewportExtent, bool clearHistory)
{
    std::lock_guard<std::mutex> lk(mtx);
    if (!chain) { lastError = "no active filter chain"; return false; }

    libra_image_vk_t src{};
    src.handle = srcImage; src.format = srcFormat; src.width = srcW; src.height = srcH;
    libra_image_vk_t out{};
    out.handle = dstImage; out.format = dstFormat; out.width = dstW; out.height = dstH;

    libra_viewport_t vp{};
    vp.x = 0.f; vp.y = 0.f; vp.width = viewportExtent.width; vp.height = viewportExtent.height;

    frame_vk_opt_t fopt{};
    fopt.version = 2;
    fopt.clear_history = clearHistory;
    fopt.aspect_ratio = 0.f;

    libra_error_t err = fnVkFilterChainFrame(&chain, cb, (size_t)frameCount, src, out, &vp, nullptr, &fopt);
    if (err != 0) { lastError = "filter_chain_frame failed: " + std::to_string(err); LLOG_E("%s", lastError.c_str()); return false; }
    return true;
}
```

- [ ] **Step 3: Ajustar include path do header gerado no CMake**

`app/src/main/cpp/CMakeLists.txt` (restaurado na Task 1): adicionar o dir do header gerado aos includes do target:

```cmake
target_include_directories(vulkan_renderer PRIVATE
    ${CMAKE_CURRENT_BINARY_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/../../build/generated/librashader/include)
```

- [ ] **Step 4: Build e validar link**

```bash
./gradlew assembleModernDebug --no-daemon
```

Expected: build OK (o `.cpp` compila, dlopen resolve em runtime). Checar avisos do wrapper em `-Wall -Wextra`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/cpp/winlator/VulkanLibrashader.h app/src/main/cpp/winlator/VulkanLibrashader.cpp app/src/main/cpp/CMakeLists.txt
git commit -m "feat: add dlopen wrapper for librashader C API (ABI 2)"
```

---

### Task 3: Instrumentação P1+P2 — log do readback e readback in-frame

Diagnósticos puros no path atual (não mudam comportamento de apresentação). Objetivo: corrigir a base de evidência e discriminar H1(layout) × H2(visibilidade).

**Files:**
- Modify: `app/src/main/cpp/winlator/VulkanRendererContext.cpp` (readback existente; adicionar `vkCmdCopyImageToBuffer`+`vkUnmapMemory` no `VkTable` se ausentes)

**Interfaces:**
- Consumes: `VkTable` com `CmdCopyImageToBuffer`, `UnmapMemory` (já adicionados na integração anterior; verificar restauração da Task 1).
- Produces: logs `READBACK-P` com **posição relativa ao applyFrame** e `oldLayout`; função `readbackProcessedInFrame()`.

- [ ] **Step 1: Adicionar log da posição do READBACK-P existente**

No bloco do `READBACK-P` (readback do processedImage por transfer em CB one-time separado), antes da transição de reescrita do próximo frame, logar:

```cpp
RLOG("READBACK-P frame=%llu pos=after_applyFrame oldLayout=%d",
     (unsigned long long)libraFrameCount, (int)VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
```

Além disso, no `recordCompositorPass`/`applyFrame` do frame N+1, logar quando o readback roda **antes** vs **depois** da transição `UNDEFINED→CA` que reescreve `processedImage`. Marcar no log o valor lido (`ff0c1028` etc.).

- [ ] **Step 2: Implementar `readbackProcessedInFrame`**

Função que faz copy engine do `processedImage` → buffer host-visível **imediatamente após** `applyFrame` no MESMO frame, antes da transição `CAO→SRO`:

```cpp
void VulkanRendererContext::readbackProcessedInFrame(VkCommandBuffer cb) {
    if (processedImage == VK_NULL_HANDLE || !processedReadbackBuffer) return;
    VkImageMemoryBarrier b{}; b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    b.oldLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    b.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = processedImage;
    b.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
    b.srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    b.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);
    VkBufferImageCopy r{}; r.bufferOffset = 0; r.bufferRowLength = 0; r.bufferImageHeight = 0;
    r.imageSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
    r.imageExtent = { (uint32_t)surfaceWidth, (uint32_t)surfaceHeight, 1 };
    vk_.CmdCopyImageToBuffer(cb, processedImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, processedReadbackBuffer, 1, &r);
    b.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL; b.newLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    b.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT; b.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);
}
```

Adicionar buffer `processedReadbackBuffer`/`processedReadbackMem` (host-visível, device-local não requerido para diagnóstico) e logar o primeiro pixel após `WaitForFences` do submit do frame:

```cpp
// no renderFrame, após QueueSubmit+WaitForFences do filterCmdBuf:
void* p = nullptr; vk_.MapMemory(device, processedReadbackMem, 0, 4, 0, &p);
uint32_t px = 0; memcpy(&px, p, 4); vk_.UnmapMemory(device, processedReadbackMem);
RLOG("READBACK-P-INFRAME frame=%llu px=%08x", (unsigned long long)libraFrameCount, px);
```

- [ ] **Step 3: Declarações no header**

Adicionar a `VulkanRendererContext.h` (restaurado): `void readbackProcessedInFrame(VkCommandBuffer cb);`, `VkBuffer processedReadbackBuffer = VK_NULL_HANDLE;`, `VkDeviceMemory processedReadbackMem = VK_NULL_HANDLE;`.

- [ ] **Step 4: Build + instalar no Mi 11 + coletar logs**

```bash
./gradlew assembleModernDebug --no-daemon
adb install -r app/build/outputs/apk/modern/debug/*.apk
adb logcat -s Winlator_Renderer | grep -E "READBACK-P|READBACK-P-INFRAME"
```

Expected: registra (a) posição do readback relativa ao `applyFrame` (base de evidência corrigida), (b) se o readback in-frame lê conteúdo (`px != 0`) ou preto. **Parar aqui se informativo.** Registrar resultado no log de tentativas.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/cpp/winlator/VulkanRendererContext.cpp app/src/main/cpp/winlator/VulkanRendererContext.h
git commit -m "diag: P1+P2 readback position log and in-frame processed readback"
```

---

### Task 4: Probe P3 — presente em GENERAL (desambiguação de layout)

Testa a hipótese B-H2 (GENERAL é o layout tolerado de amostragem no Adreno; melonDS amostra 100% em GENERAL, 2625/3094-3142). Barato, sem topologia nova.

**Files:**
- Modify: `app/src/main/cpp/winlator/VulkanRendererContext.cpp` (transição pós-filtro e descriptor do blit)

**Interfaces:**
- Consumes: `transition()` existente; `blitImageToSwapchain()` existente.
- Produces: variante de blit que amostra `processedImage` em `GENERAL`.

- [ ] **Step 1: Adicionar variante de transição+blit em GENERAL**

No path real (após `applyFrame`), substituir temporariamente a transição `CAO→SRO` por `CAO→GENERAL` e o descriptor do blit para `imageLayout = VK_IMAGE_LAYOUT_GENERAL`:

```cpp
// ANTES:
// transition(filterCmdBuf, processedImage, CAO, SRO, COLOR_ATTACHMENT_WRITE, SHADER_READ, COLOR_ATTACHMENT_OUTPUT, FRAGMENT_SHADER);
// blitProcessedToSwapchain(filterCmdBuf, imgIdx);
// DEPOIS (probe P3):
transition(filterCmdBuf, processedImage,
    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
    VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
blitImageToSwapchainLayout(filterCmdBuf, imgIdx, processedView, blitSampler, VK_IMAGE_LAYOUT_GENERAL);
```

Adicionar a função (derivada de `blitImageToSwapchain`) com parâmetro de layout:

```cpp
void VulkanRendererContext::blitImageToSwapchainLayout(VkCommandBuffer cb, uint32_t imgIdx,
    VkImageView srcView, VkSampler srcSampler, VkImageLayout layout)
{
    // idêntico a blitImageToSwapchain, mas dii.imageLayout = layout;
}
```

- [ ] **Step 2: Build + coletar log**

```bash
./gradlew assembleModernDebug --no-daemon
adb install -r app/build/outputs/apk/modern/debug/*.apk
adb logcat -s Winlator_Renderer
```

Expected: se a tela deixar de ser preta com `GENERAL`, layout de amostragem é contribuinte (suporta B-H2). Se continuar preta, não refuta — `GENERAL` é contribuinte, não discriminador isolado (cross-review).

- [ ] **Step 3: Reverter o probe ou manter com flag de diagnóstico**

Reverter para `CAO→SRO` + `blitProcessedToSwapchain` se o probe não resolver, **antes** de seguir. Registrar resultado.

- [ ] **Step 4: Commit**

```bash
git commit -am "diag: P3 GENERAL present probe on processedImage"
```

---

### Task 5: Probe P4 — barreira transfer-larga + blit para imagem dedicada

Parâmetro do melonDS (2258/2270) nunca testado no caminho transfer do GameNative (3.11 testou o caminho sampler em 3.8; P4 é transfer com srcAccess largo). NÃO é a anti-solução 6.

**Files:**
- Modify: `app/src/main/cpp/winlator/VulkanRendererContext.cpp` (+ `VkTable` se `CmdBlitImage` ausente)

**Interfaces:**
- Consumes: `VkTable.CmdBlitImage` (adicionar se ausente), `createBuffer()` existente.
- Produces: função `blitProcessedToDedicated()` que blita para uma imagem dedicada (nunca a swapchain).

- [ ] **Step 1: Adicionar imagem dedicada de destino de diagnóstico**

Criar `diagDstImage/diagDstView` (R8G8B8A8_UNORM, `TRANSFER_DST|SAMPLED`, mesmas dimensões de `processedImage`) e `transition`-helper com srcAccess largo:

```cpp
void VulkanRendererContext::transferBarrierWide(VkCommandBuffer cb, VkImage img,
    VkImageLayout oldLayout, VkImageLayout newLayout, VkAccessFlags dstAccess, VkPipelineStageFlags dstStage)
{
    VkImageMemoryBarrier b{}; b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    b.oldLayout = oldLayout; b.newLayout = newLayout;
    b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = img;
    b.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
    b.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    b.dstAccessMask = dstAccess;
    vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, dstStage, 0, 0, nullptr, 0, nullptr, 1, &b);
}
```

- [ ] **Step 2: Implementar blit para a imagem dedicada**

```cpp
void VulkanRendererContext::blitProcessedToDedicated(VkCommandBuffer cb) {
    if (processedImage == VK_NULL_HANDLE || diagDstImage == VK_NULL_HANDLE) return;
    transferBarrierWide(cb, processedImage,
        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    transferBarrierWide(cb, diagDstImage,
        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    VkImageBlit blit{};
    blit.srcSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
    blit.srcOffsets[0] = { 0, 0, 0 };
    blit.srcOffsets[1] = { (int32_t)surfaceWidth, (int32_t)surfaceHeight, 1 };
    blit.dstSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
    blit.dstOffsets[0] = { 0, 0, 0 };
    blit.dstOffsets[1] = { (int32_t)surfaceWidth, (int32_t)surfaceHeight, 1 };
    vk_.CmdBlitImage(cb, processedImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        diagDstImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_NEAREST);
    // readback de diagDstImage para confirmar conteúdo (READBACK-D)
}
```

- [ ] **Step 3: Build + coletar log**

```bash
./gradlew assembleModernDebug --no-daemon
adb install -r app/build/outputs/apk/modern/debug/*.apk
adb logcat -s Winlator_Renderer
```

Expected: `READBACK-D` com conteúdo ≠ 0 → a barreira transfer-larga + destino dedicado leem a saída do filtro corretamente (confirma P4; destino swapchain como variável). Preto → a hipótese do "destino" não se sustenta por este caminho; registrar e seguir para o fix atlas (que é robusto sob múltiplos mecanismos).

- [ ] **Step 4: Commit**

```bash
git commit -am "diag: P4 wide transfer barrier + blit to dedicated image"
```

---

### Task 6: Fix — topologia atlas (padrão melonDS)

Reimplantação fiel: `filterOutput` (saída da filter chain) → cópia por **copy engine** (`vkCmdCopyImage`, primário) para `atlasOutput` dedicado **no mesmo CB do `applyFrame`** + submit-and-wait + present do atlas em `GENERAL` num submission posterior + layout tracking manual por imagem. Swapchain reverte a só `COLOR_ATTACHMENT` (desvio consciente da anti-4, melonDS:1484). O sucesso do fix NÃO é leitura de mecanismo específico.

**Files:**
- Modify: `app/src/main/cpp/winlator/VulkanRendererContext.cpp`, `app/src/main/cpp/winlator/VulkanRendererContext.h`
- Modify: `app/src/main/cpp/CMakeLists.txt` (se precisar adicionar `VkTable` funcs)
- Modify (se aplicável): `app/src/main/cpp/winlator/window.frag` (descriptor `GENERAL` no blit do atlas — melhor em código, `dii.imageLayout = VK_IMAGE_LAYOUT_GENERAL`)

**Interfaces:**
- Consumes: `VkTable.CmdCopyImage`, `CmdBlitImage`, `CmdCopyImageToBuffer`, `UnmapMemory`; helpers `transition()`, `transferBarrierWide()` (Task 5), `createBuffer()`; `VulkanLibrashader::applyFrame` (Task 2).
- Produces: membros `filterOutputImage/View/Mem`, `atlasImage/View/Mem`, layout tracking `VkImageLayout atlasLayout`; funções `recordFilterChainPass()`, `presentAtlasToSwapchain()`.

- [ ] **Step 1: Criar `filterOutput` e `atlasOutput` (imagens dedicadas)**

Em `createOffscreenTargets`, adicionar criação de duas imagens `R8G8B8A8_UNORM` com `usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT`, tiling OPTIMAL, initialLayout UNDEFINED (replicar `createRetroArchImage` do melonDS 1980-2038). `filterOutputImage` = target do `applyFrame`; `atlasImage` = destino da cópia.

- [ ] **Step 2: `recordFilterChainPass` — applyFrame + cópia no mesmo CB + submit-and-wait**

```cpp
void VulkanRendererContext::recordFilterChainPass(VkCommandBuffer cb, uint32_t frameCount, bool clearHistory) {
    if (filterOutputImage == VK_NULL_HANDLE || atlasImage == VK_NULL_HANDLE) return;
    VkCommandBufferBeginInfo bi{}; bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    vk_.BeginCommandBuffer(cb, &bi);

    // transição de entrada (offscreen já em SRO após compositor)
    // applyFrame: offscreenImage -> filterOutputImage
    bool ok = libraShader.applyFrame(cb, frameCount,
        offscreenImage, VK_FORMAT_R8G8B8A8_UNORM, (uint32_t)surfaceWidth, (uint32_t)surfaceHeight,
        filterOutputImage, VK_FORMAT_R8G8B8A8_UNORM, (uint32_t)surfaceWidth, (uint32_t)surfaceHeight,
        VkExtent2D{ (uint32_t)surfaceWidth, (uint32_t)surfaceHeight }, clearHistory);
    if (!ok) RLOG_E("librashader: applyFrame failed: %s", libraShader.getLastError().c_str());

    // CAMINHO PRIMÁRIO: copy engine filterOutput -> atlas (mesmo CB)
    transferBarrierWide(cb, filterOutputImage,
        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    transferBarrierWide(cb, atlasImage,
        atlasLayout, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    VkImageCopy ic{};
    ic.srcSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
    ic.srcOffset = { 0, 0, 0 };
    ic.dstSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
    ic.dstOffset = { 0, 0, 0 };
    ic.extent = { (uint32_t)surfaceWidth, (uint32_t)surfaceHeight, 1 };
    vk_.CmdCopyImage(cb, filterOutputImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        atlasImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &ic);

    // atlas -> GENERAL para amostragem posterior
    VkImageMemoryBarrier b{}; b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    b.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL; b.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = atlasImage;
    b.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
    b.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT; b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
        0, 0, nullptr, 0, nullptr, 1, &b);
    atlasLayout = VK_IMAGE_LAYOUT_GENERAL;

    vk_.EndCommandBuffer(cb);

    // submit-and-wait (melonDS 2228-2243)
    vk_.ResetFences(device, 1, &filterFence);
    VkSubmitInfo si{}; si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    si.commandBufferCount = 1; si.pCommandBuffers = &cb;
    if (vk_.QueueSubmit(graphicsQueue, 1, &si, filterFence) != VK_SUCCESS) {
        RLOG_E("filter submit failed"); return;
    }
    vk_.WaitForFences(device, 1, &filterFence, VK_TRUE, UINT64_MAX);
}
```

- [ ] **Step 3: `presentAtlasToSwapchain` — amostra o atlas em GENERAL**

```cpp
void VulkanRendererContext::presentAtlasToSwapchain(VkCommandBuffer cb, uint32_t imgIdx) {
    if (atlasImage == VK_NULL_HANDLE) return;
    VkCommandBufferBeginInfo bi{}; bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    vk_.BeginCommandBuffer(cb, &bi);

    // barreira GENERAL->GENERAL (melonDS 3094-3142)
    VkImageMemoryBarrier b{}; b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    b.oldLayout = VK_IMAGE_LAYOUT_GENERAL; b.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = atlasImage;
    b.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
    b.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
    b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
        0, 0, nullptr, 0, nullptr, 1, &b);

    // blit do atlas em GENERAL (dii.imageLayout = VK_IMAGE_LAYOUT_GENERAL)
    blitImageToSwapchainLayout(cb, imgIdx, atlasView, blitSampler, VK_IMAGE_LAYOUT_GENERAL);
    vk_.EndCommandBuffer(cb);
}
```

- [ ] **Step 4: Integrar no `renderFrame` (path libraPath)**

Reorganizar: compositor → `filterCmdBuf` com `recordFilterChainPass` (applyFrame + copy → atlas, submit-and-wait) → CB de presente (`presentAtlasToSwapchain`, samplero do atlas em GENERAL) → submit+present. `offscreenRenderPass` com `finalLayout=COLOR_ATTACHMENT_OPTIMAL` mantido (3.5). Swapchain: remover `TRANSFER_DST` se presente no `createSwapchain` (voltar a só `COLOR_ATTACHMENT`, melonDS:1484 — **desvio consciente e documentado** da anti-4; reverter, não re-testar).

- [ ] **Step 5: Build + instalar + validar visual**

```bash
./gradlew assembleModernDebug --no-daemon
adb install -r app/build/outputs/apk/modern/debug/*.apk
adb logcat -s Winlator_Renderer Winlator_Librashader
```

Expected: `preset chain active=1`, `filter chain created`, sem TEST MODE, tela com efeito do shader visível (não preta), PID estável. Se ainda preta: **registrar qual experimento (P1–P4) "acendeu" antes** e reabrir (atlas preto → investigar `use_dynamic_rendering=true` com investigação própria, fora do escopo).

- [ ] **Step 6: Limpeza + `gLibraTestBlitOffscreen=false` + commit**

```bash
# garantir TEST MODE desligado
git grep -n "gLibraTestBlitOffscreen" app/src/main/cpp/winlator/VulkanRendererContext.cpp
# remover diagnósticos não mais necessários (manter READBACK instrumentado útil)
git add -A
git commit -m "fix: present filtered output via dedicated atlas (melonDS topology, copy engine primary)"
```

---

## Self-Review

**1. Spec coverage (docs/hypotheses/decision.md §4.6):**
- P1 log posição/oldLayout → Task 3 Step 1.
- P2 readback in-frame → Task 3 Step 2.
- P3 probe GENERAL → Task 4.
- P4 barreira transfer-larga + blit para imagem dedicada (NUNCA swapchain) → Task 5.
- Fix atlas (filterOutput + atlas + copy engine primário no mesmo CB + submit-and-wait + present GENERAL + swapchain só COLOR_ATTACHMENT + tracking) → Task 6.
- Base revertida reaplicada → Task 1; wrapper dlopen → Task 2.

**2. Placeholder scan:** Nenhum "TBD/TODO". Código completo nas tasks críticas. Task 4/5 reutilizam helpers já restaurados da Task 1 (`transition`, `createBuffer`, `blitImageToSwapchain`); variantes novas têm corpo declarado.

**3. Type consistency:** `applyFrame` (Task 2) bate com a chamada da Task 6 (mesma assinatura). `transferBarrierWide` definido na Task 5 e consumido na Task 6. `blitImageToSwapchainLayout` definido na Task 4 e consumido na Task 6. `VkTable.CmdCopyImage/CmdBlitImage/CmdCopyImageToBuffer/UnmapMemory` precisam estar carregados (listados nas Global Constraints e verificados na Task 1/3).

**4. Nota metodológica (por design):** o sucesso do atlas na Task 6 NÃO confirma mecanismo; a causa exata é registrada pelos experimentos P1–P4 que "acenderem" antes. Se P2 (readback in-frame) ler conteúdo, o problema colapsa no caminho do presente e a moldura D (menção honrosa) vira a explicação preferida — mas o fix continua o mesmo.
