package com.winlator.renderer;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Imports bundled RetroArch shader presets shipped under {@code assets/retroarch}.
 *
 * <p>The presets are first materialized into the app's files dir (see {@link #getBundledPresetsDir()})
 * so they can be enumerated and loaded from a plain file path. {@link #importBundledPreset(String)}
 * then copies the requested preset (plus any sibling {@code .slang} dependencies) into an
 * app-writable directory and returns the absolute path to the {@code .slangp} file, ready to be
 * handed to {@link VulkanRenderer#loadRetroArchShaderPreset(String)}.
 */
public class ShaderImporter {
    private static final String TAG = "ShaderImporter";
    private static final String ASSET_ROOT = "retroarch";
    private static final String BUNDLED_DIR = "retroarch";
    private static final String IMPORTED_DIR = "retroarch_presets";

    private final Context context;

    public ShaderImporter(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Directory where bundled presets are materialized (app files dir + assets/retroarch copy). */
    public File getBundledPresetsDir() {
        return new File(context.getFilesDir(), BUNDLED_DIR);
    }

    /** Directory where imported presets (slangp + slang deps) are copied for the renderer. */
    public File getImportedPresetsDir() {
        return new File(context.getFilesDir(), IMPORTED_DIR);
    }

    /**
     * Lists every bundled {@code .slangp} preset as key/value entries. The key is the relative
     * path (e.g. {@code crt/easymode.slangp}) and the value is the friendly display name.
     */
    public List<Map.Entry<String, String>> listBundledPresets() {
        Map<String, String> presets = new TreeMap<>();
        File root = getBundledPresetsDir();
        if (root.isDirectory()) {
            collectSlangpPresets(root, root, presets);
        }
        return new ArrayList<>(presets.entrySet());
    }

    private void collectSlangpPresets(File dir, File root, Map<String, String> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                collectSlangpPresets(child, root, out);
            } else if (child.getName().endsWith(".slangp")) {
                String relative = relativePath(root, child);
                out.put(relative, friendlyName(relative));
            }
        }
    }

    private static String relativePath(File root, File file) {
        String rootPath = root.getAbsolutePath();
        String filePath = file.getAbsolutePath();
        if (filePath.startsWith(rootPath)) {
            String rel = filePath.substring(rootPath.length());
            return rel.startsWith("/") ? rel.substring(1) : rel;
        }
        return file.getName();
    }

    /**
     * Copies the preset referenced by {@code key} (and its sibling {@code .slang} dependencies)
     * into the app-writable imported presets dir, preserving the relative folder structure.
     *
     * @return a result carrying {@code success} and the absolute {@code .slangp} path on success.
     */
    public ImportResult importBundledPreset(String key) {
        if (key == null || key.isEmpty()) {
            return new ImportResult(false, "");
        }

        File bundledPreset = new File(getBundledPresetsDir(), key);
        if (!bundledPreset.isFile()) {
            Log.w(TAG, "Bundled preset not found: " + key);
            return new ImportResult(false, "");
        }

        // Copy the whole relative folder so .slang dependencies resolve next to the .slangp.
        File sourceDir = bundledPreset.getParentFile();
        File destDir = new File(getImportedPresetsDir(), relativePath(getBundledPresetsDir(), sourceDir));
        try {
            copyDirectory(sourceDir, destDir);
        } catch (IOException e) {
            Log.e(TAG, "Failed to import preset " + key, e);
            return new ImportResult(false, "");
        }

        File imported = new File(destDir, bundledPreset.getName());
        return new ImportResult(imported.isFile(), imported.getAbsolutePath());
    }

    /**
     * Best-effort materialization of {@code assets/retroarch} into the bundled presets dir when it
     * is missing. Usually performed by {@code ensureBundledShaders}; kept here for robustness.
     */
    public boolean ensureBundledPresets() {
        File root = getBundledPresetsDir();
        if (root.isDirectory() && root.listFiles() != null && root.listFiles().length > 0) {
            return true;
        }
        try {
            copyAssetTree(context.getAssets(), ASSET_ROOT, root);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to materialize bundled presets", e);
            return false;
        }
    }

    /** Human-readable preset name derived from a relative path, e.g. {@code crt/easymode.slangp} -> "Easymode". */
    public static String friendlyName(String relativePath) {
        String name = relativePath;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        if (name.endsWith(".slangp")) {
            name = name.substring(0, name.length() - ".slangp".length());
        }
        return titleCase(name);
    }

    private static String titleCase(String name) {
        if (name.isEmpty()) return name;
        String[] parts = name.split("[_\\-\\s]+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) sb.append(part.substring(1));
        }
        return sb.toString();
    }

    private static void copyDirectory(File src, File dst) throws IOException {
        File[] children = src.listFiles();
        if (children == null) return;
        if (!dst.exists() && !dst.mkdirs()) {
            throw new IOException("Could not create " + dst);
        }
        for (File child : children) {
            File target = new File(dst, child.getName());
            if (child.isDirectory()) {
                copyDirectory(child, target);
            } else {
                copyFile(child, target);
            }
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new java.io.FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
    }

    private static void copyAssetTree(AssetManager assets, String assetPath, File dst) throws IOException {
        String[] children = assets.list(assetPath);
        if (children == null || children.length == 0) return;
        if (!dst.exists() && !dst.mkdirs()) {
            throw new IOException("Could not create " + dst);
        }
        for (String child : children) {
            String childPath = assetPath + "/" + child;
            String[] sub = assets.list(childPath);
            if (sub != null && sub.length > 0) {
                copyAssetTree(assets, childPath, new File(dst, child));
            } else {
                try (InputStream in = assets.open(childPath);
                     OutputStream out = new FileOutputStream(new File(dst, child))) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                    }
                }
            }
        }
    }

    /** Result of {@link #importBundledPreset(String)}. */
    public static class ImportResult {
        private final boolean success;
        private final String presetPath;

        public ImportResult(boolean success, String presetPath) {
            this.success = success;
            this.presetPath = presetPath;
        }

        public boolean getSuccess() {
            return success;
        }

        public String getPresetPath() {
            return presetPath;
        }
    }
}
