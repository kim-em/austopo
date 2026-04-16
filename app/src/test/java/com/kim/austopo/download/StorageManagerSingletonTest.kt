package com.kim.austopo.download

import android.content.Context
import org.junit.After
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The StorageManager must be process-wide per canonical filesDir so that
 * every activity shares the same Mutex. Otherwise cross-activity writes
 * (e.g. bookmark save vs. tile eviction) can race.
 */
class StorageManagerSingletonTest {

    private lateinit var dirA: File
    private lateinit var dirB: File

    @Before fun setUp() {
        StorageManager.resetForTest()
        dirA = File.createTempFile("storage-singleton-a", "").apply { delete(); mkdirs() }
        dirB = File.createTempFile("storage-singleton-b", "").apply { delete(); mkdirs() }
    }

    @After fun tearDown() {
        dirA.deleteRecursively()
        dirB.deleteRecursively()
        StorageManager.resetForTest()
    }

    @Test fun `get returns same instance for same files dir`() {
        val ctx1 = FakeContext(dirA)
        val ctx2 = FakeContext(dirA)
        val s1 = StorageManager.get(ctx1)
        val s2 = StorageManager.get(ctx2)
        assertSame(s1, s2)
    }

    @Test fun `get returns different instances for different files dirs`() {
        val s1 = StorageManager.get(FakeContext(dirA))
        val s2 = StorageManager.get(FakeContext(dirB))
        assertNotSame(s1, s2)
    }

    /** Minimal Context stub that only answers getFilesDir(). */
    private class FakeContext(private val dir: File) : android.content.ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }
}
