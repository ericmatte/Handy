package computer.handy.android.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {

    private fun model(id: String) = ModelCatalog.byId(id) ?: error("missing model $id")

    @Test
    fun `ids are unique and every model has tokens`() {
        assertEquals(ModelCatalog.ALL.size, ModelCatalog.ALL.map { it.id }.toSet().size)
        ModelCatalog.ALL.forEach { assertTrue(it.id, ModelCatalog.TOKENS in it.files) }
    }

    @Test
    fun `parakeet v3 keeps the id of the first release so installed models are reused`() {
        assertEquals("parakeet-tdt-0.6b-v3-int8", ModelCatalog.DEFAULT.id)
    }

    @Test
    fun `auto language lets detecting models decide`() {
        assertEquals("", ModelCatalog.decodeLanguage(model("whisper-small"), ModelCatalog.AUTO, "fr"))
        assertEquals("", ModelCatalog.decodeLanguage(model("parakeet-tdt-0.6b-v3-int8"), ModelCatalog.AUTO, "fr"))
    }

    @Test
    fun `selected language is used only when supported`() {
        assertEquals("fr", ModelCatalog.decodeLanguage(model("whisper-small"), "fr", "en"))
        // SenseVoice has no French: fall back to its own detection.
        assertEquals("", ModelCatalog.decodeLanguage(model("sense-voice-int8"), "fr", "fr"))
    }

    @Test
    fun `single-language models are constrained`() {
        assertEquals("en", ModelCatalog.decodeLanguage(model("parakeet-tdt-0.6b-v2-int8"), "fr", "fr"))
        assertEquals("en", ModelCatalog.decodeLanguage(model("moonshine-base-en"), ModelCatalog.AUTO, "fr"))
    }

    @Test
    fun `canary needs a language and falls back to the phone language`() {
        val canary = model("canary-180m-flash")
        assertEquals("fr", ModelCatalog.decodeLanguage(canary, ModelCatalog.AUTO, "fr"))
        assertEquals("en", ModelCatalog.decodeLanguage(canary, ModelCatalog.AUTO, "ja"))
        assertEquals("de", ModelCatalog.decodeLanguage(canary, "de", "fr"))
    }

    @Test
    fun `whisper archives only extract the int8 weights`() {
        val small = model("whisper-small")
        assertEquals("small-encoder.int8.onnx", small.file(ModelCatalog.ENCODER))
        assertEquals("small-decoder.int8.onnx", small.file(ModelCatalog.DECODER))
        assertTrue(small.url.endsWith("/asr-models/sherpa-onnx-whisper-small.tar.bz2"))
    }
}
