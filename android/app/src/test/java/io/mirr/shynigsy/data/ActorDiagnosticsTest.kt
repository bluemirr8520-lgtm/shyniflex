package io.mirr.shynigsy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActorDiagnosticsTest {
    @Test fun diagnosticLinesContainOnlyWhitelistedEventsAndNumbers() {
        val lines = mutableListOf<String>()
        var time = 0L
        val trace = ActorDiagnostics(ActorDiagnostics.Kind.VIEW, lines::add) { time }
        time = 2_500_000L
        trace.event(ActorDiagnostics.Event.HTTP, status = 503)
        trace.event(ActorDiagnostics.Event.ROWS, count = 3, more = true)

        assertEquals(3, lines.size)
        val format = Regex("n=[0-9]+ kind=(VIEW|PROVIDER) event=[A-Z_]+ ms=[0-9]+ status=[0-9]+ count=-?[0-9]+ more=[01]")
        assertTrue(lines.all(format::matches))
        assertTrue(lines[1].contains("event=HTTP ms=2 status=503"))
        assertTrue(lines[2].contains("count=3 more=1"))
    }

    @Test fun loggingFailureDoesNotChangeLookup() {
        val trace = ActorDiagnostics(ActorDiagnostics.Kind.PROVIDER, { throw IllegalStateException("synthetic") }) { 0L }
        trace.event(ActorDiagnostics.Event.TIMEOUT)
    }
}
