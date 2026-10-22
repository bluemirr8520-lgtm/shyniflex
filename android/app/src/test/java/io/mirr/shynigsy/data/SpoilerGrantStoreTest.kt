package io.mirr.shynigsy.data

import org.junit.Assert.*
import org.junit.Test

class SpoilerGrantStoreTest {
    private class Fixture {
        var wall = 1_000_000L
        var mono = 10_000_000L
        val store = SpoilerGrantStore({ wall }, { mono })
        fun advance(ms: Long) { wall += ms; mono += ms * 1_000_000 }
        fun unlock(key: String = "account-device", token: String = "spc_first", seconds: Long = 30,
            pin: String = "1234"): SpoilerAccessRegistry.Grant {
            val fence = store.beginUnlock(key)
            val chars = pin.toCharArray()
            assertTrue(store.completeUnlock(key, fence, token, seconds, chars, store.started()))
            chars.fill('\u0000')
            return store.get(key)!!
        }
    }

    @Test fun cachedPinIsKeyBoundCopiedAndConsumedOnce() {
        val f = Fixture()
        f.unlock()
        f.unlock("other-account-device", pin = "5678")
        assertNull(f.store.beginReauthentication("different-server"))
        val first = f.store.beginReauthentication("account-device")!!
        assertArrayEquals("1234".toCharArray(), first.pin)
        assertNull(f.store.beginReauthentication("account-device"))
        val other = f.store.beginReauthentication("other-account-device")!!
        assertArrayEquals("5678".toCharArray(), other.pin)
        f.store.finishReauthentication("account-device", first)
        assertTrue(first.pin.all { it == '\u0000' })
        assertArrayEquals("5678".toCharArray(), other.pin)
        f.store.finishReauthentication("other-account-device", other)
    }

    @Test fun expiryUsesMonotonicTimeAndWipesAnInFlightPin() {
        val f = Fixture()
        f.unlock(seconds = 10)
        val attempt = f.store.beginReauthentication("account-device")!!
        f.wall += 86_400_000
        assertEquals(10_000L, f.store.remainingMs("account-device"))
        f.mono += 10_000_000_000
        assertNull(f.store.get("account-device"))
        assertEquals(0L, f.store.remainingMs("account-device"))
        assertTrue(attempt.pin.all { it == '\u0000' })
        assertNull(f.store.completeReauthentication("account-device", attempt, "spc_late", 30, f.store.started()))
        assertFalse(f.store.failedReauthentication("account-device"))
    }

    @Test fun aDelayedInitialResponseCannotClaimMoreThanItsRequestTimeLifetime() {
        val f = Fixture()
        val fence = f.store.beginUnlock("key")
        val started = f.store.started()
        f.advance(3_000)
        assertTrue(f.store.completeUnlock("key", fence, "spc_token", 10, "1234".toCharArray(), started))
        assertEquals(7_000L, f.store.remainingMs("key"))
        assertEquals(10_000L, f.store.get("key")!!.lifetimeMs)
        f.advance(7_000)
        assertNull(f.store.get("key"))
    }

    @Test fun clearingAllWipesCachedAndCopiedPinsAndFencesLateReplies() {
        val f = Fixture()
        f.unlock()
        f.unlock("other")
        val attempt = f.store.beginReauthentication("account-device")!!
        val pendingUnlock = f.store.beginUnlock("pending")
        f.store.clearAll()
        assertTrue(attempt.pin.all { it == '\u0000' })
        assertNull(f.store.get("account-device"))
        assertNull(f.store.get("other"))
        assertNull(f.store.beginReauthentication("other"))
        assertNull(f.store.completeReauthentication("account-device", attempt, "spc_late", 30, f.store.started()))
        assertFalse(f.store.completeUnlock("pending", pendingUnlock, "spc_late", 30,
            "1234".toCharArray(), f.store.started()))
    }

    @Test fun startingANewUnlockFencesBothOldSuccessAndOldFailure() {
        val f = Fixture()
        f.unlock()
        val attempt = f.store.beginReauthentication("account-device")!!
        val next = f.store.beginUnlock("account-device")
        assertTrue(attempt.pin.all { it == '\u0000' })
        assertNull(f.store.completeReauthentication("account-device", attempt, "spc_old_reply", 30, f.store.started()))
        assertFalse(f.store.failReauthentication("account-device", attempt.grant))
        assertTrue(f.store.completeUnlock("account-device", next, "spc_manual", 30,
            "5678".toCharArray(), f.store.started()))
        f.store.finishReauthentication("account-device", attempt)
        assertEquals("spc_manual", f.store.get("account-device")!!.token)
        assertFalse(f.store.failedReauthentication("account-device"))
        assertArrayEquals("5678".toCharArray(), f.store.beginReauthentication("account-device")!!.pin)
    }

    @Test fun onlyFullyPreparedPlaybackCanCacheThePinOnTheRotatedGrant() {
        val f = Fixture()
        f.unlock()
        val attempt = f.store.beginReauthentication("account-device")!!
        f.advance(1_000)
        val rotated = f.store.completeReauthentication("account-device", attempt, "spc_rotated", 30, f.store.started())!!
        assertEquals("spc_rotated", f.store.get("account-device")!!.token)
        assertNull(f.store.beginReauthentication("account-device"))
        assertTrue(f.store.cacheReauthenticatedPin("account-device", rotated, attempt))
        f.store.finishReauthentication("account-device", attempt)
        assertTrue(attempt.pin.all { it == '\u0000' })
        val next = f.store.beginReauthentication("account-device")!!
        assertEquals("spc_rotated", next.grant.token)
        assertArrayEquals("1234".toCharArray(), next.pin)
    }

    @Test fun failureIsScopedAndManualOrBackgroundLockClearsItsReason() {
        val f = Fixture()
        f.unlock()
        f.unlock("other")
        val attempt = f.store.beginReauthentication("account-device")!!
        assertTrue(f.store.failReauthentication("account-device", attempt.grant))
        assertNull(f.store.get("account-device"))
        assertTrue(f.store.failedReauthentication("account-device"))
        assertTrue(attempt.pin.all { it == '\u0000' })
        assertNotNull(f.store.get("other"))
        assertFalse(f.store.failedReauthentication("other"))
        f.store.remove("account-device")
        assertFalse(f.store.failedReauthentication("account-device"))
        f.unlock()
        val second = f.store.beginReauthentication("account-device")!!
        assertTrue(f.store.failReauthentication("account-device", second.grant))
        f.store.clearAll()
        assertFalse(f.store.failedReauthentication("account-device"))
    }

    @Test fun failureAfterRotationCannotClearOrCacheOverANewerManualGrant() {
        val f = Fixture()
        f.unlock()
        val attempt = f.store.beginReauthentication("account-device")!!
        val rotated = f.store.completeReauthentication("account-device", attempt, "spc_rotated", 30, f.store.started())!!
        f.unlock(token = "spc_new_manual", pin = "5678")
        assertFalse(f.store.failReauthentication("account-device", rotated))
        assertFalse(f.store.cacheReauthenticatedPin("account-device", rotated, attempt))
        f.store.finishReauthentication("account-device", attempt)
        assertEquals("spc_new_manual", f.store.get("account-device")!!.token)
        assertArrayEquals("5678".toCharArray(), f.store.beginReauthentication("account-device")!!.pin)
    }
}
