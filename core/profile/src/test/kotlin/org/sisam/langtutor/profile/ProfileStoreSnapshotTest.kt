package org.sisam.langtutor.profile

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [LearnerProfileStore.snapshot] exists so callers that cannot suspend do not
 * have to mirror settings into fields of their own. The mirror version of this
 * shipped once and crashed the app on launch — the collector that fed it lived
 * in AppContainer's init block and read a property declared further down the
 * class, which is null at that point. These pin the contract that replaced it.
 */
class ProfileStoreSnapshotTest {

    @Test
    fun `snapshot matches the suspending read`() = runTest {
        val store = InMemoryProfileStore(LearnerProfile.EMPTY.copy(xp = 42))
        assertEquals(store.current(), store.snapshot())
        assertEquals(42, store.snapshot().xp)
    }

    @Test
    fun `snapshot sees an update immediately, with no collector to catch up`() = runTest {
        // The mirror it replaced was eventually-consistent: a setting flipped
        // in Parent Zone reached the LLM loader only once a collector had run.
        // This reads through, so there is no window where the two disagree.
        val store = InMemoryProfileStore()
        store.update { it.copy(parentSettings = it.parentSettings.copy(tryNpuBackend = true)) }
        assertEquals(true, store.snapshot().parentSettings.tryNpuBackend)
    }
}
