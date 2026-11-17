package io.github.nir0k.jotter.data.library

import io.github.nir0k.jotter.core.sftp.SftpConnection
import io.github.nir0k.jotter.core.sftp.SignInMethod
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryNameTest {
    private fun named(folder: String) = libraryName(SftpConnection("notes.example.org", 22, "anna", folder, SignInMethod.Password))

    @Test
    fun namesALibraryAfterItsRemoteFolder() {
        assertEquals("notes", named("/home/anna/notes"))
        assertEquals("notes", named("jotter/notes/"))
        assertEquals("notes", named("notes"))
    }

    // These paths have no name of their own, and would tell the person nothing.
    @Test
    fun namesALibraryInTheHomeFolderAfterTheServer() {
        for (folder in listOf("", "/", ".", "~", "./")) assertEquals("notes.example.org", named(folder))
    }
}
