package io.github.nir0k.jotter.core.sync

import io.github.nir0k.jotter.core.format.parseJsonObject
import io.github.nir0k.jotter.core.format.parseTime
import io.github.nir0k.jotter.core.format.qtArray
import io.github.nir0k.jotter.core.format.qtString
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.exists
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the three-way merges of synchronisation (desktop spec section 12.4) against the merge
 * cases of the fixtures (`fixtures/README.md`), so the phone merges to the same bytes as the
 * desktop, and how note bodies split into the blocks those merges work on.
 */
@OptIn(ExperimentalPathApi::class)
class MergeTest {
    private val cases: Path = Paths.get(javaClass.getResource("/fixtures/merge")!!.toURI())

    /** Every file of every merge case merges into the case's merged version, with conflicts exactly where the case names them. */
    @Test
    fun sharedCasesMergeAsOnTheDesktop() {
        val names = cases.listDirectoryEntries().filter { Files.isDirectory(it) }.sortedBy { it.name }
        assertTrue(names.isNotEmpty())
        for (case in names) {
            val description = parseJsonObject(Files.readAllBytes(case.resolve("case.json")), "case.json")
            val now = parseTime(description["now"].qtString())!!
            val expectedConflicts = description["conflicts"].qtArray().map { it.qtString() }
            val local = case.resolve("local")
            val paths = local.walk().filter { it.isRegularFile() }.map { it.relativeTo(local).invariantSeparatorsPathString }.sorted().toList()
            val conflicts = mutableListOf<String>()
            for (path in paths) {
                fun read(side: String): ByteArray? = case.resolve(side).resolve(path).takeIf { it.exists() }?.let(Files::readAllBytes)
                val merged = mergeLibraryFile(path, read("base"), read("local")!!, read("remote")!!, now)
                assertNotNull("${case.name}/$path", merged)
                if (merged!!.conflict != null) conflicts += path
                assertEquals("${case.name}/$path", String(read("merged")!!), String(merged.data))
            }
            assertEquals(case.name, expectedConflicts, conflicts)
        }
    }

    /** Top-level blocks keep nested elements, void elements and text outside elements apart, and a `>` inside an attribute does not end a tag. */
    @Test
    fun bodyBlocksSplitAtTopLevelElements() {
        val body = "<p>One</p>\n<ul><li>a<ul><li>b</li></ul></li></ul>\n<hr>\nloose text\n<p><a href=\"x?a>b\">link</a><img src=\"i.png\"></p>"
        assertEquals(
            listOf("<p>One</p>", "<ul><li>a<ul><li>b</li></ul></li></ul>", "<hr>", "loose text", "<p><a href=\"x?a>b\">link</a><img src=\"i.png\"></p>"),
            bodyBlocks(body),
        )
    }
}
