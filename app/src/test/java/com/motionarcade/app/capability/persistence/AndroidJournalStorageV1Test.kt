package com.motionarcade.app.capability.persistence

import android.system.OsConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class AndroidJournalStorageV1Test {
    @Test
    @Config(sdk = [26])
    fun api26OmitsCloseOnExecWithoutCallerControlledFlagInput() {
        assertEquals(
            OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW,
            AndroidJournalOpenPolicyV1.readOnlyFlags(),
        )
        assertEquals(
            OsConstants.O_WRONLY or
                OsConstants.O_CREAT or
                OsConstants.O_EXCL or
                OsConstants.O_NOFOLLOW,
            AndroidJournalOpenPolicyV1.exclusiveCreateFlags(),
        )
        assertFalse(
            AndroidJournalFileSystemV1::class.java.declaredMethods.any { method ->
                method.parameterTypes.any { Set::class.java.isAssignableFrom(it) }
            },
        )
    }

    @Test
    @Config(sdk = [27])
    fun api27AndLaterRequireCloseOnExec() {
        assertTrue(AndroidJournalOpenPolicyV1.readOnlyFlags() and OsConstants.O_CLOEXEC != 0)
        assertTrue(AndroidJournalOpenPolicyV1.exclusiveCreateFlags() and OsConstants.O_CLOEXEC != 0)
    }

}
