package party.qwer.irisgui.scripting

import android.content.Context
import java.io.File

/**
 * ScriptStore — scripts 저장 (filesDir/scripts).
 *
 * layout:
 *   filesDir/scripts/<id>.py         스크립트 원문
 *   filesDir/scripts/index.json      [{id,name,enabled,updatedAt,manifest:{...}}]
 *   filesDir/scripts/installed/<pkg> 런타임 설치 wheel unpack 대상
 */
object ScriptStore {
    data class Meta(
        val id: String,
        val name: String,
        val source: String,
        val updatedAt: Long = 0L,
        val requires: List<String> = emptyList(),
        val grants: List<String> = emptyList()
    )

    private fun scriptsDir(context: Context) = File(context.filesDir, "scripts").apply {
        if (!exists()) mkdirs()
    }

    private fun indexFile(context: Context) = File(scriptsDir(context), "index.json")

    private fun parseHeader(source: String): Pair<List<String>, List<String>> {
        var requires = mutableListOf<String>()
        var grants = mutableListOf<String>()
        for (raw in source.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (!line.startsWith("#")) break
            val m = Regex("""^#\s*irisgui:\s*([a-zA-Z_][\w]*)\s*:\s*(.*)$""").find(line) ?: continue
            val key = m.groupValues[1].lowercase()
            val values = m.groupValues[2].split(Regex("[,\\s]+")).filter { it.isNotBlank() }
            when {
                key == "requires" -> requires += values
                key == "grant" || key == "grants" -> grants += values.map { it.lowercase() }
            }
        }
        return requires to grants
    }

    fun all(context: Context): List<Meta> {
        val dir = scriptsDir(context)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".py") } ?: return emptyList()
        return files.map { f ->
            val source = f.readText()
            val (requires, grants) = parseHeader(source)
            val name = Regex("""^#\s*irisgui:\s*name\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)
                .find(source)
                ?.groupValues?.get(1)?.trim()
                ?: f.nameWithoutExtension
            Meta(
                id = f.nameWithoutExtension,
                name = name,
                source = source,
                updatedAt = f.lastModified(),
                requires = requires,
                grants = grants
            )
        }.sortedByDescending { it.updatedAt }
    }

    fun read(context: Context, id: String): String? =
        scriptsDir(context).resolve("$id.py").takeIf { it.isFile }?.readText()

    fun write(context: Context, id: String, source: String) {
        scriptsDir(context).resolve("$id.py").writeText(source)
    }

    fun delete(context: Context, id: String) {
        scriptsDir(context).resolve("$id.py").delete()
    }
}
