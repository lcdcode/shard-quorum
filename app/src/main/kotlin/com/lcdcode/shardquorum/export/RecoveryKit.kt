package com.lcdcode.shardquorum.export

import android.content.res.AssetManager
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Assembles a per-recipient "recovery kit": one ZIP carrying a single shard plus
 * the durable artifacts that let a future holder rebuild the secret without this
 * app - the offline HTML tool, the language-agnostic spec, the known-answer
 * vectors, and a README. The four artifacts are bundled in the APK under
 * assets/recovery-kit/ (staged from the repo's canonical files at build time, so
 * they cannot drift); the shard is added at export time.
 */
object RecoveryKit {
    const val ASSET_DIR = "recovery-kit"

    // The artifacts shipped in the APK, identical for every recipient on a given
    // release. Names are also their entry names inside the exported ZIP.
    val BUNDLED_FILES = listOf(
        "ShardQuorum-recover.html",
        "RECOVERY-SPEC.md",
        "recovery-vectors.json",
        "README.txt",
    )

    // Fixed timestamp for every ZIP entry: makes two kits for the same shard
    // byte-identical and avoids stamping the kit with the moment it was made.
    // (1980-01-01, the floor of the ZIP/MS-DOS time format.)
    private const val FIXED_ENTRY_TIME = 315532800000L

    /** Entry name for a shard's QR-sheet image inside the kit. */
    fun shardPngName(index: Int, count: Int): String = "shard-$index-of-$count.png"

    /** Entry name for a shard's hand-typed words/text inside the kit. */
    fun shardTextName(index: Int, count: Int): String = "shard-$index-of-$count.txt"

    /**
     * Writes [entries] as a ZIP to [out]. Entries are emitted in sorted name
     * order with a fixed timestamp so the output is deterministic. Pure JVM (no
     * Android types) so it is unit-testable off-device.
     */
    fun writeZip(entries: Map<String, ByteArray>, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            for (name in entries.keys.sorted()) {
                val entry = ZipEntry(name).apply { time = FIXED_ENTRY_TIME }
                zip.putNextEntry(entry)
                zip.write(entries.getValue(name))
                zip.closeEntry()
            }
        }
    }

    /** Reads the APK-bundled recovery artifacts from assets/recovery-kit/. */
    fun assetEntries(assets: AssetManager): Map<String, ByteArray> =
        BUNDLED_FILES.associateWith { name ->
            assets.open("$ASSET_DIR/$name").use { it.readBytes() }
        }

    // README title when the caller has no name for the secret; keeps the
    // template's {secret_name} placeholder from reaching a recipient.
    private const val UNNAMED_SECRET_LABEL = "unnamed secret"

    /**
     * Builds a complete recovery-kit ZIP for one recipient: the bundled
     * artifacts plus this recipient's shard, as both a QR-sheet PNG and a
     * hand-typeable text file. The bundled README is personalized with
     * [secretName] (the user's label) and this shard's [index]/[count].
     */
    fun buildKit(
        assets: AssetManager,
        shardPng: ByteArray,
        shardText: String,
        index: Int,
        count: Int,
        secretName: String,
    ): ByteArray {
        val entries = assetEntries(assets).toMutableMap()
        entries[shardPngName(index, count)] = shardPng
        entries[shardTextName(index, count)] = shardText.toByteArray(Charsets.UTF_8)
        entries["README.txt"]?.let { readme ->
            entries["README.txt"] = personalizeReadme(
                readme.toString(Charsets.UTF_8), secretName, index, count,
            ).toByteArray(Charsets.UTF_8)
        }
        return ByteArrayOutputStream().also { writeZip(entries, it) }.toByteArray()
    }

    /**
     * Fills the README template's {index}/{count}/{secret_name} placeholders.
     * {secret_name} is replaced last so a name that itself contains a
     * placeholder token is never substituted into; a blank name gets a neutral
     * label instead of leaking the raw placeholder.
     */
    internal fun personalizeReadme(
        template: String,
        secretName: String,
        index: Int,
        count: Int,
    ): String = template
        .replace("{index}", index.toString())
        .replace("{count}", count.toString())
        .replace("{secret_name}", secretName.ifBlank { UNNAMED_SECRET_LABEL })
}
