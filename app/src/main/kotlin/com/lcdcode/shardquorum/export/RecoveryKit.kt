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

    /**
     * Builds a complete recovery-kit ZIP for one recipient: the bundled
     * artifacts plus this recipient's shard, as both a QR-sheet PNG and a
     * hand-typeable text file.
     */
    fun buildKit(
        assets: AssetManager,
        shardPng: ByteArray,
        shardText: String,
        index: Int,
        count: Int,
    ): ByteArray = buildKit(assets, shardPng, shardText, index, count, "")

    /**
     * Builds a recovery kit with a custom README that names the secret and
     * shard. [secretName] is the user's label; [index] and [count] identify
     * this shard within the set.
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
        if (secretName.isNotEmpty()) {
            val genericReadme = entries["README.txt"]?.toString(Charsets.UTF_8) ?: ""
            entries["README.txt"] = genericReadme
                .replace("{secret_name}", secretName)
                .replace("{index}", index.toString())
                .replace("{count}", count.toString())
                .toByteArray(Charsets.UTF_8)
        }
        return ByteArrayOutputStream().also { writeZip(entries, it) }.toByteArray()
    }
}
