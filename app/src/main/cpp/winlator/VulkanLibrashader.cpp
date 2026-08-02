#include "VulkanLibrashader.h"
#define LIBRA_RUNTIME_VULKAN
#include <librashader.h>
#include <android/log.h>
#include <cstring>
#define LLOG(...) __android_log_print(ANDROID_LOG_DEBUG,"Winlator_Librashader",__VA_ARGS__)
#define LLOG_E(...) __android_log_print(ANDROID_LOG_ERROR,"Winlator_Librashader",__VA_ARGS__)

VulkanLibrashader::VulkanLibrashader() = default;
VulkanLibrashader::~VulkanLibrashader() {
    unloadLibrary();
}

bool VulkanLibrashader::loadLibrary() {
    if (handle) return true;
    handle = dlopen("liblibrashader.so", RTLD_NOW | RTLD_GLOBAL);
    if (!handle) {
        lastError = "dlopen failed: " + std::string(dlerror());
        LLOG_E("%s", lastError.c_str());
        return false;
    }

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
        dlclose(handle);
        handle = nullptr;
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
    if (preset) { fnPresetFree(&preset); preset = nullptr; }
    if (presetCtx) { fnPresetCtxFree(&presetCtx); presetCtx = nullptr; }

    if (path.empty()) { lastError.clear(); return; }

    libra_error_t err = fnPresetCtxCreate(&presetCtx);
    if (err != 0) { lastError = "preset_ctx_create failed"; LLOG_E("%s", lastError.c_str()); return; }
    if (fnPresetCtxSetAllowRotation) fnPresetCtxSetAllowRotation(&presetCtx, false);

    libra_preset_opt_t presetOpt{};
    presetOpt.version = 2;
    err = fnPresetCreateWithOptions(path.c_str(), &presetCtx, &presetOpt, &preset);
    if (err != 0) {
        lastError = "preset_create_with_options failed";
        LLOG_E("%s", lastError.c_str());
        if (presetCtx) { fnPresetCtxFree(&presetCtx); presetCtx = nullptr; }
        return;
    }
    presetCtx = nullptr;

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
    if (err != 0) {
        lastError = "vk_filter_chain_create failed";
        LLOG_E("%s", lastError.c_str());
        if (preset) { fnPresetFree(&preset); preset = nullptr; }
        chain = nullptr;
        return;
    }
    preset = nullptr;

    lastError.clear();
    LLOG("librashader: filter chain created for %s", path.c_str());
}

void VulkanLibrashader::setParam(const std::string& name, float value) {
    std::lock_guard<std::mutex> lk(mtx);
    if (chain && fnVkFilterChainSetParam) {
        libra_error_t err = fnVkFilterChainSetParam(&chain, name.c_str(), value);
        if (err != 0) LLOG_E("librashader: set_param %s failed", name.c_str());
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
    if (err != 0) {
        lastError = "filter_chain_frame failed";
        LLOG_E("%s", lastError.c_str());
        return false;
    }
    return true;
}

void VulkanLibrashader::destroyFilterChain() {
    std::lock_guard<std::mutex> lk(mtx);
    if (!handle) return;
    if (chain) { fnVkFilterChainFree(&chain); chain = nullptr; }
    if (preset) { fnPresetFree(&preset); preset = nullptr; }
    if (presetCtx) { fnPresetCtxFree(&presetCtx); presetCtx = nullptr; }
}

void VulkanLibrashader::unloadLibrary() {
    destroyFilterChain();
    std::lock_guard<std::mutex> lk(mtx);
    if (handle) { dlclose(handle); handle = nullptr; }
}
