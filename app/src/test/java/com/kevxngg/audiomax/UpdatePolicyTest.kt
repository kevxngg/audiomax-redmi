package com.kevxngg.audiomax

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    @Test fun offersNewer() = assertTrue(UpdatePolicy.isOffered(3, 4, 0))
    @Test fun notOlder() = assertFalse(UpdatePolicy.isOffered(4, 4, 0))
    @Test fun skipped() = assertFalse(UpdatePolicy.isOffered(3, 4, 4))
    @Test fun requiredIgnoresSkip() = assertTrue(UpdatePolicy.isOffered(3, 4, 4, minBuild = 4))
    @Test fun neverRequiredByDefault() = assertFalse(UpdatePolicy.isRequired(3, 0))
}
