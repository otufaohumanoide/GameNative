package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the central ARM/Mali classification (spec 2026-10-09-mali-mediatek-performance).
 *
 * Two behaviours are locked here:
 *  - **regression guard:** the Adreno/Xclipse branches must stay byte-identical to the
 *    previous inline `excludeBcnCompute = isAdreno || (isWrapperGamenative && isXclipse)`;
 *  - **new:** Mali/Immortalis with the gamenative wrapper, and the Immortalis blind spot —
 *    "Immortalis-G715" does not contain the substring "Mali", so the old
 *    `renderer.contains("Mali")` checks silently skipped those devices.
 */
class GpuClassificationTest {

    private val adreno = "Adreno (TM) 650"
    private val xclipse = "Xclipse 920"
    private val maliG78 = "Mali-G78"
    private val immortalis = "Immortalis-G715"

    // ── isMali: covers Mali AND Immortalis, case-insensitive, null-safe ────

    @Test
    fun `mali renderer strings are detected`() {
        assertTrue(GpuClassification.isMali("Mali-G57"))
        assertTrue(GpuClassification.isMali("Mali-G68"))
        assertTrue(GpuClassification.isMali("Mali-G77"))
        assertTrue(GpuClassification.isMali(maliG78))
        assertTrue(GpuClassification.isMali("Mali-G715"))
        assertTrue(GpuClassification.isMali("Mali-G57 MC2"))
        assertTrue(GpuClassification.isMali("mali-g78"))
    }

    @Test
    fun `immortalis is recognised as arm mali - regression`() {
        // The old checks used renderer.contains("Mali"), which "Immortalis-G715"
        // does NOT satisfy, so BOX64_MMAP32 stayed 1 and zink_dlls was extracted.
        assertTrue(GpuClassification.isMali(immortalis))
        assertTrue(GpuClassification.isMali("Immortalis-G720"))
        assertTrue(GpuClassification.isMali("immortalis-g925"))
    }

    @Test
    fun `non mali renderers are not detected`() {
        assertFalse(GpuClassification.isMali(adreno))
        assertFalse(GpuClassification.isMali(xclipse))
        assertFalse(GpuClassification.isMali("PowerVR Rogue GE8320"))
        assertFalse(GpuClassification.isMali(""))
        assertFalse(GpuClassification.isMali("unknown"))
        assertFalse(GpuClassification.isMali(null))
    }

    // ── maliModel / maliSeries ────────────────────────────────────────────

    @Test
    fun `model token is extracted from mali and immortalis names`() {
        assertEquals("g57", GpuClassification.maliModel("Mali-G57"))
        assertEquals("g78", GpuClassification.maliModel(maliG78))
        assertEquals("g57", GpuClassification.maliModel("Mali-G57 MC2"))
        assertEquals("g715", GpuClassification.maliModel("Mali-G715"))
        assertEquals("g715", GpuClassification.maliModel(immortalis))
        assertEquals("t860", GpuClassification.maliModel("Mali-T860"))
    }

    @Test
    fun `model token is null when the gpu has no model number or is not mali`() {
        assertNull(GpuClassification.maliModel("Mali-400"))
        assertNull(GpuClassification.maliModel(adreno))
        assertNull(GpuClassification.maliModel(""))
        assertNull(GpuClassification.maliModel(null))
    }

    @Test
    fun `numeric series is derived from the model token`() {
        assertEquals(57, GpuClassification.maliSeries("Mali-G57"))
        assertEquals(78, GpuClassification.maliSeries(maliG78))
        assertEquals(715, GpuClassification.maliSeries(immortalis))
        assertEquals(860, GpuClassification.maliSeries("Mali-T860"))
        assertNull(GpuClassification.maliSeries(adreno))
        assertNull(GpuClassification.maliSeries("Mali-400"))
    }

    // ── vendorFamily ──────────────────────────────────────────────────────

    @Test
    fun `vendor family prefers the renderer string over the vendor id`() {
        assertEquals(GpuVendor.MALI, GpuClassification.vendorFamily(0, maliG78))
        assertEquals(GpuVendor.ADRENO, GpuClassification.vendorFamily(0, adreno))
        assertEquals(GpuVendor.XCLIPSE, GpuClassification.vendorFamily(0, xclipse))
        assertEquals(GpuVendor.POWERVR, GpuClassification.vendorFamily(0, "PowerVR Rogue GE8320"))
        assertEquals(GpuVendor.OTHER, GpuClassification.vendorFamily(0, "unknown"))
    }

    @Test
    fun `vendor family falls back to ids when the renderer is useless`() {
        assertEquals(GpuVendor.MALI, GpuClassification.vendorFamily(GpuClassification.VENDOR_ARM, null))
        assertEquals(GpuVendor.MALI, GpuClassification.vendorFamily(GpuClassification.VENDOR_ARM, "unknown"))
        assertEquals(GpuVendor.ADRENO, GpuClassification.vendorFamily(GpuClassification.VENDOR_QUALCOMM, null))
        assertEquals(GpuVendor.XCLIPSE, GpuClassification.vendorFamily(GpuClassification.VENDOR_SAMSUNG, null))
        assertEquals(
            GpuVendor.POWERVR,
            GpuClassification.vendorFamily(GpuClassification.VENDOR_IMAGINATION, null),
        )
    }

    // ── shouldExcludeBcnCompute: regression + new Mali branch ─────────────

    @Test
    fun `adreno is always excluded - regression`() {
        assertTrue(GpuClassification.shouldExcludeBcnCompute(0x5143, adreno, isWrapperGamenative = false))
        assertTrue(GpuClassification.shouldExcludeBcnCompute(0x5143, adreno, isWrapperGamenative = true))
        assertTrue(GpuClassification.shouldExcludeBcnCompute(0x5143, null, isWrapperGamenative = false))
    }

    @Test
    fun `xclipse is excluded only with the gamenative wrapper - regression`() {
        assertFalse(GpuClassification.shouldExcludeBcnCompute(0x144D, xclipse, isWrapperGamenative = false))
        assertTrue(GpuClassification.shouldExcludeBcnCompute(0x144D, xclipse, isWrapperGamenative = true))
    }

    @Test
    fun `mali is excluded only with the gamenative wrapper - new`() {
        assertTrue(GpuClassification.shouldExcludeBcnCompute(0x13B5, maliG78, isWrapperGamenative = true))
        assertTrue(GpuClassification.shouldExcludeBcnCompute(0x13B5, "Mali-G57", isWrapperGamenative = true))
        assertFalse(GpuClassification.shouldExcludeBcnCompute(0x13B5, maliG78, isWrapperGamenative = false))
    }

    @Test
    fun `immortalis follows the mali rule - new`() {
        assertTrue(GpuClassification.shouldExcludeBcnCompute(0, immortalis, isWrapperGamenative = true))
        assertFalse(GpuClassification.shouldExcludeBcnCompute(0, immortalis, isWrapperGamenative = false))
    }

    @Test
    fun `mali is detected by renderer even when the vendor probe fails`() {
        // getVendorID can return 0 when the probe cannot create an instance.
        assertTrue(GpuClassification.shouldExcludeBcnCompute(0, maliG78, isWrapperGamenative = true))
    }

    @Test
    fun `unknown devices keep bcn compute enabled`() {
        assertFalse(GpuClassification.shouldExcludeBcnCompute(0, "unknown", isWrapperGamenative = true))
        assertFalse(GpuClassification.shouldExcludeBcnCompute(0, null, isWrapperGamenative = true))
        assertFalse(GpuClassification.shouldExcludeBcnCompute(0, "", isWrapperGamenative = true))
    }
}
