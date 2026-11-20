package lechuck.intellij.wsl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The mapping runs on every OS by design (the `SystemInfo` guard lives in [TaskWslSupport]), so
 * these assertions hold wherever the suite runs -- which is the point: this is the half of WSL
 * support that can be tested without a Windows host or an installed distribution.
 */
class WslPathsTest {

    @Test
    fun testParsesTheWindowsSpelling() {
        val unc = WslPaths.parse("""\\wsl.localhost\Ubuntu\home\x\Taskfile.yml""")

        assertEquals("//wsl.localhost/Ubuntu", unc?.root)
        assertEquals("Ubuntu", unc?.distributionId)
        assertEquals("/home/x/Taskfile.yml", unc?.linuxPath)
    }

    /**
     * The same file reaches this code in two spellings: the VFS hands out forward slashes, while
     * `java.io.File.getParent` -- which the working directory is derived with -- hands back
     * backslashes. Both have to parse identically or the two halves of one run disagree.
     */
    @Test
    fun testParsesTheVfsSpellingIdentically() {
        assertEquals(
            WslPaths.parse("""\\wsl.localhost\Ubuntu\home\x\Taskfile.yml"""),
            WslPaths.parse("//wsl.localhost/Ubuntu/home/x/Taskfile.yml"),
        )
    }

    /** The legacy root is still what older Windows builds mount a distribution under. */
    @Test
    fun testParsesTheLegacyRoot() {
        val unc = WslPaths.parse("""\\wsl$\Ubuntu\home\x""")

        assertEquals("//wsl\$/Ubuntu", unc?.root)
        assertEquals("Ubuntu", unc?.distributionId)
        assertEquals("/home/x", unc?.linuxPath)
    }

    @Test
    fun testPrefixMatchIsCaseInsensitive() {
        assertEquals("Ubuntu", WslPaths.parse("""\\WSL.LOCALHOST\Ubuntu\home""")?.distributionId)
    }

    @Test
    fun testDistributionIdsWithDotsAndDashes() {
        assertEquals(
            "Ubuntu-24.04",
            WslPaths.parse("//wsl.localhost/Ubuntu-24.04/home/x")?.distributionId,
        )
        assertEquals(
            "docker-desktop",
            WslPaths.parse("//wsl.localhost/docker-desktop/home/x")?.distributionId,
        )
    }

    /** A bare root names the distribution, not a path inside it. */
    @Test
    fun testBareDistributionRootIsNotAPath() {
        assertNull(WslPaths.parse("//wsl.localhost/Ubuntu"))
        assertNull(WslPaths.parse("//wsl.localhost/"))
        assertNull(WslPaths.parse("//wsl.localhost//"))
    }

    @Test
    fun testDistributionRootWithTrailingSeparator() {
        assertEquals("/", WslPaths.parse("//wsl.localhost/Ubuntu/")?.linuxPath)
    }

    @Test
    fun testNonWslPaths() {
        for (path in
            listOf(
                """C:\projects\x\Taskfile.yml""",
                "C:/projects/x/Taskfile.yml",
                "/home/x/Taskfile.yml",
                """\\fileserver\share\Taskfile.yml""",
                "sub/Taskfile.yml",
                "task",
                "",
            )) {
            assertNull(path, WslPaths.parse(path))
        }
    }

    @Test
    fun testToHostIsTheInverseOfParse() {
        val path = "//wsl.localhost/Ubuntu/home/x/Taskfile.yml"
        val unc = WslPaths.parse(path)!!

        assertEquals(path, WslPaths.toHost(unc.root, unc.linuxPath))
    }

    @Test
    fun testRoundTripNormalisesSeparators() {
        val unc = WslPaths.parse("""\\wsl.localhost\Ubuntu\home\x""")!!

        assertEquals("//wsl.localhost/Ubuntu/home/x", WslPaths.toHost(unc.root, unc.linuxPath))
    }

    @Test
    fun testPathsWithSpaces() {
        val unc = WslPaths.parse("""\\wsl.localhost\Ubuntu\home\x\mio progetto\Taskfile.yml""")

        assertEquals("/home/x/mio progetto/Taskfile.yml", unc?.linuxPath)
    }

    // --- hostMapper ---

    /**
     * The prefix comes from the reference path rather than being reconstructed, so the result is
     * the exact spelling the VFS already holds -- the platform's own `getWindowsPath` would rebuild
     * it with the legacy root whatever the project is mounted under.
     */
    @Test
    fun testHostMapperReusesTheReferenceRoot() {
        val mapper = WslPaths.hostMapper("//wsl.localhost/Ubuntu/home/x/Taskfile.yml")

        assertEquals("//wsl.localhost/Ubuntu/home/x/included.yml", mapper("/home/x/included.yml"))
    }

    @Test
    fun testHostMapperKeepsTheLegacyRoot() {
        val mapper = WslPaths.hostMapper("""\\wsl$\Ubuntu\home\x\Taskfile.yml""")

        assertEquals("//wsl\$/Ubuntu/home/x/included.yml", mapper("/home/x/included.yml"))
    }

    /** Identity for a non-WSL reference, so callers can apply it without branching. */
    @Test
    fun testHostMapperIsIdentityOffWsl() {
        val mapper = WslPaths.hostMapper("""C:\projects\x\Taskfile.yml""")

        assertEquals("/home/x/included.yml", mapper("/home/x/included.yml"))
        assertEquals("", mapper(""))
    }

    /** Only a distro-absolute path needs a root; anything already host-shaped is left alone. */
    @Test
    fun testHostMapperLeavesHostShapedPathsAlone() {
        val mapper = WslPaths.hostMapper("//wsl.localhost/Ubuntu/home/x/Taskfile.yml")

        assertEquals("", mapper(""))
        assertEquals("""C:\other\Taskfile.yml""", mapper("""C:\other\Taskfile.yml"""))
        assertEquals(
            "//wsl.localhost/Ubuntu/home/x/a.yml",
            mapper("//wsl.localhost/Ubuntu/home/x/a.yml"),
        )
    }
}
