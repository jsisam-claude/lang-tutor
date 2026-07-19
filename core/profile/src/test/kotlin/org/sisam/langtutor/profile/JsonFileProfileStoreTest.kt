package org.sisam.langtutor.profile

import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class JsonFileProfileStoreTest {

    @Test
    fun `updates persist across store instances`() = runTest {
        val file = createTempDirectory("profile-test").resolve("profile.json")

        val store = JsonFileProfileStore(file)
        store.update { it.copy(childName = "Noa", xp = it.xp + 5) }
        store.update { it.copy(xp = it.xp + 5) }
        assertEquals(10, store.current().xp)

        val reloaded = JsonFileProfileStore(file)
        assertEquals("Noa", reloaded.current().childName)
        assertEquals(10, reloaded.current().xp)
    }
}
