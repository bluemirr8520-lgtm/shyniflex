package io.mirr.shynigsy

import io.mirr.shynigsy.data.ApiException
import io.mirr.shynigsy.data.Library
import io.mirr.shynigsy.data.MediaItem
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class HomeContinueLoadPolicyTest {
    private val ordinary = MediaItem(10, 1, "공개 영화", "movie", viewItemId = 100, resumeMs = 80_000)
    private val hidden = MediaItem(20, 2, "비공개 영상", "movie", viewItemId = 200, resumeMs = 90_000, isSpoiler = true)
    private val catalogs = listOf(Library(1, "공개", "movie"), Library(2, "비공개", "movie", isSpoiler = true))
    private val empty = HomeContinueLoad.Success(emptyList())
    private val unavailable = HomeContinueLoad.Failure(transient = true)
    private val forbidden = HomeContinueLoad.Failure(transient = false)

    private fun resolve(
        publicLoad: HomeContinueLoad = unavailable,
        privateLoad: HomeContinueLoad = unavailable,
        previous: List<MediaItem> = listOf(ordinary, hidden),
        libraries: List<Library> = catalogs,
        unlocked: Boolean = true,
        sameAccount: Boolean = true,
        sameGrant: Boolean = true,
        previousOwnerMatches: Boolean = true,
    ) = resolveHomeContinueWatching(publicLoad, privateLoad, previous, libraries, unlocked, sameAccount, sameGrant, previousOwnerMatches)

    @Test fun networkAndServerFailurePreserveOnlyCurrentAccountPermittedRowsWithWarning() {
        assertTrue(failedHomeContinueLoad(IOException("offline")).transient)
        assertTrue(failedHomeContinueLoad(ApiException("offline").apply { initCause(IOException("network")) }).transient)
        assertTrue(failedHomeContinueLoad(ApiException("unavailable", 503)).transient)
        assertEquals(listOf(ordinary, hidden), resolve())
        assertNotNull(homeContinueLoadError(unavailable, empty))
        assertNotNull(homeContinueLoadError(empty, unavailable))
    }

    @Test fun successfulEmptyResponsesClearPreviousHistoryWithoutWarning() {
        assertTrue(resolve(empty, empty).isEmpty())
        assertNull(homeContinueLoadError(empty, empty))
    }

    @Test fun successfulResponseReplacesTheOldPositionInsteadOfReusingCachedProgress() {
        val current = ordinary.copy(resumeMs = 150_000)
        assertEquals(listOf(current), resolve(HomeContinueLoad.Success(listOf(current)), empty))
    }

    @Test fun disappearedOrRevokedViewsAreRemovedEvenOnTransientFailure() {
        assertEquals(listOf(ordinary), resolve(libraries = listOf(catalogs.first())))
        assertTrue(resolve(libraries = emptyList()).isEmpty())
    }

    @Test fun privateLockImmediatelyDropsRetainedAndFreshPrivateRows() {
        assertEquals(listOf(ordinary), resolve(unlocked = false))
        assertEquals(listOf(ordinary), resolve(privateLoad = HomeContinueLoad.Success(listOf(hidden)), unlocked = false))
    }

    @Test fun aReplacedGrantCannotPublishOldPrivateResponsesOrFallback() {
        assertEquals(listOf(ordinary), resolve(sameGrant = false))
        assertEquals(listOf(ordinary), resolve(privateLoad = HomeContinueLoad.Success(listOf(hidden)), sameGrant = false))
    }

    @Test fun logoutOrProfileChangeCannotReuseEitherOldOrNewRowsFromThePreviousClient() {
        assertTrue(resolve(sameAccount = false).isEmpty())
        assertTrue(resolve(HomeContinueLoad.Success(listOf(ordinary)), HomeContinueLoad.Success(listOf(hidden)),
            sameAccount = false).isEmpty())
    }

    @Test fun sameNumberedViewsDoNotRetainThePreviousAccountsRowsAfterNewClientLogin() {
        val firstClient = Any()
        val nextClient = Any()
        val owner = HomeContinueOwner(firstClient, "first-account")
        assertTrue(owner.matches(firstClient, "first-account"))
        assertFalse(owner.matches(nextClient, "next-account"))
        assertTrue(resolve(previousOwnerMatches = owner.matches(nextClient, "next-account")).isEmpty())
        val current = ordinary.copy(resumeMs = 200_000)
        assertEquals(listOf(current), resolve(HomeContinueLoad.Success(listOf(current)), empty,
            previousOwnerMatches = owner.matches(nextClient, "next-account")))
    }

    @Test fun tokenReplacementOnTheSameClientInvalidatesTheRetainedSnapshot() {
        val client = Any()
        val owner = HomeContinueOwner(client, "old-session")
        assertFalse(owner.matches(client, "new-session"))
        assertTrue(resolve(previousOwnerMatches = owner.matches(client, "new-session")).isEmpty())
    }

    @Test fun equalOriginAndDisplayNamesAreNotEvidenceOfTheSamePublishedAccount() {
        data class Profile(val origin: String, val displayName: String)
        val first = Profile("https://example.invalid", "same-name")
        val replacement = Profile("https://example.invalid", "same-name")
        assertEquals(first, replacement)
        val owner = HomeContinueOwner(first, "session-a")
        assertFalse(owner.matches(replacement, "session-b"))
        assertTrue(resolve(previousOwnerMatches = owner.matches(replacement, "session-b")).isEmpty())
    }

    @Test fun permissionRejectionsFailClosedInsteadOfRetainingPreviousCatalogRows() {
        for (status in listOf(400, 403, 404, 409, 422)) {
            assertFalse(failedHomeContinueLoad(ApiException("rejected", status)).transient)
        }
        assertTrue(resolve(forbidden, forbidden).isEmpty())
        assertEquals(listOf(hidden), resolve(forbidden, unavailable))
        assertEquals(listOf(ordinary), resolve(unavailable, forbidden))
    }

    @Test fun accountExpirationPropagatesToExistingLogoutPath() {
        val expired = ApiException("expired", 401)
        try {
            failedHomeContinueLoad(expired)
            fail("401 must propagate")
        } catch (error: ApiException) {
            assertSame(expired, error)
        }
    }

    @Test fun retainedRowsCannotCrossThePublicPrivateBoundaryWhenAViewChangesVisibility() {
        val nowPrivate = listOf(catalogs.first().copy(isSpoiler = true), catalogs.last())
        assertEquals(listOf(hidden), resolve(libraries = nowPrivate))
        val nowPublic = catalogs.map { it.copy(isSpoiler = false) }
        assertEquals(listOf(ordinary), resolve(libraries = nowPublic))
    }

    @Test fun unexpectedFailuresDoNotEnableStaleFallback() {
        assertFalse(failedHomeContinueLoad(IllegalStateException("invalid data")).transient)
        assertFalse(failedHomeContinueLoad(ApiException("invalid server response")).transient)
    }

    @Test fun oneSuccessfulEmptySideDoesNotKeepItsOwnPreviousRowsWhenTheOtherSideFails() {
        assertEquals(listOf(hidden), resolve(empty, unavailable))
        assertEquals(listOf(ordinary), resolve(unavailable, empty))
    }
}
