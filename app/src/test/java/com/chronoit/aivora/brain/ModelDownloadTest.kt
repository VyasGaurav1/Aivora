package com.chronoit.aivora.brain

import com.chronoit.aivora.connectivity.NetworkRoute
import com.chronoit.aivora.connectivity.NetworkSnapshot
import org.junit.Assert.*
import org.junit.Test

class ModelDownloadTest {
    private fun wifi(usableSpace: Long = ModelDownloadPolicy.REQUIRED_FREE_BYTES + 1) =
        NetworkSnapshot(validated = true, wifiEnabled = true, route = NetworkRoute.WIFI)

    @Test fun downloadSourceIsPinnedToOneRevisionAndFile() {
        assertEquals("https://huggingface.co/ggml-org/Qwen3-0.6B-GGUF/resolve/13ac06624948e4d8589cb4b6f90100837342fcb8/Qwen3-0.6B-Q4_0.gguf", ModelDownloadPolicy.DOWNLOAD_URL)
        assertEquals("Qwen3-0.6B-Q4_0.gguf", ModelDownloadPolicy.UPSTREAM_FILENAME)
    }

    @Test fun sizeAndChecksumAreTheSameConstantsTheEngineAndBuildCheckUse() {
        assertEquals(428970080L, ModelDownloadPolicy.MODEL_BYTES)
        assertEquals(428970080L, LlamaLocalReasoningEngine.MODEL_BYTES)
        assertEquals("da2572f16c06133561ce56accaa822216f2391ef4d37fba427801cd6736417d4", ModelDownloadPolicy.SHA256)
        assertEquals(LlamaLocalReasoningEngine.SHA256, ModelDownloadPolicy.SHA256)
        assertEquals(ModelDownloadPolicy.MODEL_BYTES + 64L * 1024 * 1024, ModelDownloadPolicy.REQUIRED_FREE_BYTES)
    }

    @Test fun wifiGateRefusesCellularVpnEthernetAndUnvalidatedConnections() {
        assertNull(ModelDownloadPolicy.canStart(wifi(), ModelDownloadPolicy.REQUIRED_FREE_BYTES + 1))
        for (route in listOf(NetworkRoute.MOBILE, NetworkRoute.VPN, NetworkRoute.ETHERNET, null)) {
            val refusal = ModelDownloadPolicy.canStart(NetworkSnapshot(validated = true, route = route), Long.MAX_VALUE)
            assertNotNull("route $route must be refused", refusal)
            assertTrue(refusal!!.contains("Wi-Fi", ignoreCase = true))
        }
        val unvalidated = ModelDownloadPolicy.canStart(NetworkSnapshot(validated = false, route = NetworkRoute.WIFI), Long.MAX_VALUE)
        assertNotNull(unvalidated)
        assertTrue(unvalidated!!.contains("internet", ignoreCase = true))
    }

    @Test fun storageGateNeedsMoreThanTheModelPlusHeadroom() {
        assertNotNull(ModelDownloadPolicy.canStart(wifi(), ModelDownloadPolicy.REQUIRED_FREE_BYTES))
        assertNotNull(ModelDownloadPolicy.canStart(wifi(), ModelDownloadPolicy.MODEL_BYTES))
        assertNull(ModelDownloadPolicy.canStart(wifi(), ModelDownloadPolicy.REQUIRED_FREE_BYTES + 1))
        val refusal = ModelDownloadPolicy.canStart(wifi(), 0L)!!
        assertTrue(refusal.contains("free space", ignoreCase = true))
    }

    @Test fun onlyExactPhrasesStartRemoveOrCancelADownload() {
        for (phrase in listOf(
            "download the offline model",
            "Download the offline model.",
            "YES, DOWNLOAD THE OFFLINE MODEL",
            "yes download the offline model",
            "install the offline model",
        )) assertEquals(phrase, ModelDownloadPolicy.Command.DOWNLOAD, ModelDownloadPolicy.match(phrase))
        for (phrase in listOf("yes", "download it", "download the model", "download the offline answer", "call Mom")) {
            assertNull("'$phrase' must never start a download", ModelDownloadPolicy.match(phrase))
        }
        assertEquals(ModelDownloadPolicy.Command.REMOVE, ModelDownloadPolicy.match("remove the offline model"))
        assertEquals(ModelDownloadPolicy.Command.REMOVE, ModelDownloadPolicy.match("Delete the offline model!"))
        assertEquals(ModelDownloadPolicy.Command.CANCEL, ModelDownloadPolicy.match("cancel the offline model download"))
        assertNull(ModelDownloadPolicy.match("cancel"))
        assertNull(ModelDownloadPolicy.match("stop"))
    }

    @Test fun progressPercentIsDerivedFromBytes() {
        assertEquals(0, ModelDownloadState.Downloading(0).percent)
        assertEquals(50, ModelDownloadState.Downloading(214485040).percent)
        assertEquals(100, ModelDownloadState.Downloading(428970080).percent)
        assertEquals(100, ModelDownloadState.Downloading(999999999).percent)
    }

    @Test fun statusLabelsAreHonestAboutInstallState() {
        assertTrue(ModelDownloadPolicy.statusLabel(ModelDownloadState.NotInstalled).contains("not installed"))
        assertTrue(ModelDownloadPolicy.statusLabel(ModelDownloadState.Downloading(214485040)).contains("50%"))
        assertTrue(ModelDownloadPolicy.statusLabel(ModelDownloadState.Installed).contains("installed"))
        assertTrue(ModelDownloadPolicy.statusLabel(ModelDownloadState.Failed("no Wi-Fi")).contains("no Wi-Fi"))
        assertTrue(ModelDownloadPolicy.NOT_INSTALLED_MESSAGE.contains("429 MB"))
        assertTrue(ModelDownloadPolicy.NOT_INSTALLED_MESSAGE.contains("yes, download the offline model"))
    }
}
