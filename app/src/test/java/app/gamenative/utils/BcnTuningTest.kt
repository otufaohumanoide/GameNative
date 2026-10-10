package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Testes JVM da normalização de `WRAPPER_BCN_GPU_CAP_MB` (frente H2 do
 * `docs/plano-mali-mediatek.md`; contrato do lever verificado no blob por H3).
 */
class BcnTuningTest {

    @Test
    fun `blank and invalid values fall back to the wrapper default`() {
        assertEquals(BcnTuning.DEFAULT_GPU_CAP_MB, BcnTuning.gpuCapMb(null))
        assertEquals(BcnTuning.DEFAULT_GPU_CAP_MB, BcnTuning.gpuCapMb(""))
        assertEquals(BcnTuning.DEFAULT_GPU_CAP_MB, BcnTuning.gpuCapMb("   "))
        assertEquals(BcnTuning.DEFAULT_GPU_CAP_MB, BcnTuning.gpuCapMb("abc"))
        assertEquals(BcnTuning.DEFAULT_GPU_CAP_MB, BcnTuning.gpuCapMb("12.8"))
    }

    @Test
    fun `values below the wrapper minimum are clamped up`() {
        assertEquals(BcnTuning.MIN_GPU_CAP_MB, BcnTuning.gpuCapMb("0"))
        assertEquals(BcnTuning.MIN_GPU_CAP_MB, BcnTuning.gpuCapMb("5"))
        assertEquals(BcnTuning.MIN_GPU_CAP_MB, BcnTuning.gpuCapMb("-100"))
    }

    @Test
    fun `valid values pass through and are trimmed`() {
        assertEquals(16, BcnTuning.gpuCapMb("16"))
        assertEquals(128, BcnTuning.gpuCapMb("128"))
        assertEquals(512, BcnTuning.gpuCapMb("  512  "))
    }

    @Test
    fun `astc defaults on and only explicit zero turns it off`() {
        assertEquals(true, BcnTuning.astcEnabled(null))
        assertEquals(true, BcnTuning.astcEnabled(""))
        assertEquals(true, BcnTuning.astcEnabled("1"))
        assertEquals(true, BcnTuning.astcEnabled("true"))
        assertEquals(false, BcnTuning.astcEnabled("0"))
        assertEquals(false, BcnTuning.astcEnabled(" 0 "))
    }

    @Test
    fun `gpu transcode requires the gpu transcoder and astc`() {
        assertEquals(true, BcnTuning.gpuTranscodeEnabled("gpu", astcEnabled = true))
        assertEquals(true, BcnTuning.gpuTranscodeEnabled("GPU", astcEnabled = true))
        assertEquals(false, BcnTuning.gpuTranscodeEnabled("cpu", astcEnabled = true))
        assertEquals(false, BcnTuning.gpuTranscodeEnabled(null, astcEnabled = true))
        // ASTC off also disables the GPU path: the shader would write ASTC into RGBA8 (H3).
        assertEquals(false, BcnTuning.gpuTranscodeEnabled("gpu", astcEnabled = false))
    }
}
