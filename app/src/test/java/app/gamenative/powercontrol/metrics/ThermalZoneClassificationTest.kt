package app.gamenative.powercontrol.metrics
            
            import org.junit.Assert.assertEquals
            import org.junit.Assert.assertFalse
            import org.junit.Assert.assertNull
            import org.junit.Assert.assertTrue
            import org.junit.Test
            
            /**
             * Testes JVM da classificação pura de zonas térmicas (itens I1a/I1b do spec
             * 2026-10-09-mali-mediatek-performance).
             *
             * O bug que estes testes travam: `cpuTempPaths()`/`gpuTempPaths()` rankeavam por
             * `contains("cpu")`/`contains("gpu")`/`contains("soc")` sem descartar
             *  - trip points `*-step` (limiares de mitigação, não termômetros); e
             *  - `soc` isolado, que em Qualcomm é State of Charge da bateria (%).
             * Uma bateria a 85 % virava a "temperatura de CPU" de 85 °C.
             *
             * Referência externa: `reference/FPS-Meter-Android/.../SocThermalMonitor.kt:228-270`.
             */
            class ThermalZoneClassificationTest {
            
                // ── I1a: trip points `*-step` nunca são leitura ────────────────────────
            
                @Test
                fun `step trip points are excluded`() {
                    for (type in listOf(
                        "cpu-0-step",
                        "cpu4-step",
                        "cpu-7-step",
                        "gpu-step",
                        "gpu-step-0",
                        "gpuss-0-step",
                        "avg-step",
                        "max-step",
                        "skin-step",
                        "soc-step",
                        "CPU-0-STEP",
                    )) {
                        assertTrue("deveria excluir: $type", ThermalZoneClassification.isExcludedZoneType(type))
                        assertNull("cpuRank deveria ignorar: $type", ThermalZoneClassification.cpuRank(type))
                        assertNull("gpuRank deveria ignorar: $type", ThermalZoneClassification.gpuRank(type))
                    }
                }
            
                @Test
                fun `legit cpu and gpu zones are not excluded`() {
                    for (type in listOf("cpu-0", "cpu-silicon", "cpuss-0-usr", "gpu", "gpu-silicon", "gpuss-0", "mali0", "kgsl-3d0")) {
                        assertFalse("não deveria excluir: $type", ThermalZoneClassification.isExcludedZoneType(type))
                    }
                }
            
                // ── I1b: `soc` isolado é bateria (%), não temperatura ──────────────────
            
                @Test
                fun `standalone soc is rejected as a temperature`() {
                    // Regressão do bug: antes `contains("soc")` dava rank 3 para CPU.
                    assertTrue(ThermalZoneClassification.isExcludedZoneType("soc"))
                    assertTrue(ThermalZoneClassification.isExcludedZoneType("SOC"))
                    assertNull(ThermalZoneClassification.cpuRank("soc"))
                    // `soc-step` já cai no I1a; continua fora.
                    assertNull(ThermalZoneClassification.cpuRank("soc-step"))
                }
            
                @Test
                fun `qualified soc zones stay valid - mediatek keeps them`() {
                    for (type in listOf("soc_thermal", "soc-thermal", "soc_max", "soc-temp", "soc_cpu")) {
                        assertFalse("não deveria excluir: $type", ThermalZoneClassification.isExcludedZoneType(type))
                        // `soc_cpu` contém "cpu" e por isso casa o rank 2 (genérico) antes do rank 3
                        // de `soc`; os demais chegam ao rank 3. Ambos são leitura válida.
                        val expectedRank = if (type.contains("cpu")) 2 else 3
                        assertEquals("cpuRank de $type", expectedRank, ThermalZoneClassification.cpuRank(type))
                    }
                }
            
                // ── I1c: `gpuss` JÁ é coberto por contains("gpu") — não "consertar" ────
            
                @Test
                fun `gpuss is already matched by the gpu ranker`() {
                    assertEquals(1, ThermalZoneClassification.gpuRank("gpuss-0-usr"))
                    assertEquals(1, ThermalZoneClassification.gpuRank("gpuss-1-usr"))
                }
            
                // ── Regressão: ranks originais preservados ─────────────────────────────
            
                @Test
                fun `cpu ranks are preserved`() {
                    assertEquals(0, ThermalZoneClassification.cpuRank("cpu-silicon"))
                    assertEquals(1, ThermalZoneClassification.cpuRank("cpu-0"))
                    assertEquals(2, ThermalZoneClassification.cpuRank("cpu"))
                    assertEquals(3, ThermalZoneClassification.cpuRank("soc_thermal"))
                    assertEquals(4, ThermalZoneClassification.cpuRank("s5p-tmu"))
                    // `cputop` contém "cpu" e por isso casa o rank 2 antes do rank 5 na cadeia
                    // `when` (comportamento pré-existente, preservado por I1a/I1b).
                    assertEquals(2, ThermalZoneClassification.cpuRank("cputop"))
                    assertEquals(6, ThermalZoneClassification.cpuRank("tsens_tz_sensor0"))
                    assertEquals(7, ThermalZoneClassification.cpuRank("cluster0"))
                    assertEquals(8, ThermalZoneClassification.cpuRank("big0"))
                    assertEquals(8, ThermalZoneClassification.cpuRank("little0"))
                }
            
                @Test
                fun `gpu ranks are preserved`() {
                    assertEquals(0, ThermalZoneClassification.gpuRank("gpu-silicon"))
                    assertEquals(1, ThermalZoneClassification.gpuRank("gpu"))
                    assertEquals(2, ThermalZoneClassification.gpuRank("g3d"))
                    assertEquals(3, ThermalZoneClassification.gpuRank("kgsl-3d0"))
                    assertEquals(4, ThermalZoneClassification.gpuRank("mali0"))
                }
            
                @Test
                fun `cpu ranker never matches a gpu-only zone`() {
                    assertNull(ThermalZoneClassification.cpuRank("gpu"))
                    assertNull(ThermalZoneClassification.cpuRank("gpu-usr"))
                }
            
                @Test
                fun `unknown and empty zone types are ignored`() {
                    assertNull(ThermalZoneClassification.cpuRank("battery"))
                    assertNull(ThermalZoneClassification.cpuRank("pmic-therm"))
                    assertNull(ThermalZoneClassification.cpuRank(""))
                    assertNull(ThermalZoneClassification.cpuRank("   "))
                    assertNull(ThermalZoneClassification.gpuRank("nsp-usr"))
                    assertTrue(ThermalZoneClassification.isExcludedZoneType(""))
                }
            
                @Test
                fun `matching is case insensitive and trims`() {
                    assertEquals(2, ThermalZoneClassification.cpuRank("  CPU  "))
                    assertEquals(1, ThermalZoneClassification.gpuRank(" GPU "))
                    assertNull(ThermalZoneClassification.cpuRank(" CPU-0-STEP "))
                }
            }
            