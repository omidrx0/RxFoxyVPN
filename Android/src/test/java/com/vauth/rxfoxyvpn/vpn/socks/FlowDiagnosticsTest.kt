package com.vauth.rxfoxyvpn.vpn.socks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowDiagnosticsTest {

    private var now = 0L

    private fun diagnostics() = FlowDiagnostics(
        tunnelTcpIdleTimeoutMs = 300_000L,
        summaryIntervalMs = 60_000L,
        clock = { now },
    )

    @Test
    fun clientSideCloseAfterTheTunnelIdleTimeoutIsFlagged() {
        val d = diagnostics()
        val flow = d.openFlow(5228)
        now += 1_000
        flow.onClientToUpstream(10)
        now += 300_000
        flow.onEnded(FlowDiagnostics.Side.CLIENT)
        flow.finish()
        now += 60_000

        val summary = d.takeSummary()
        assertNotNull(summary)
        assertTrue(summary!!, summary.contains("5228-5230[flows=1 long=1 clientFirst=1 upstreamFirst=0 tunnelIdleLike=1"))
    }

    @Test
    fun shortIdleClientCloseIsNotFlagged() {
        val d = diagnostics()
        val flow = d.openFlow(443)
        now += 200_000
        flow.onUpstreamToClient(5)
        now += 5_000
        flow.onEnded(FlowDiagnostics.Side.CLIENT)
        flow.finish()
        now += 60_000

        val summary = d.takeSummary()!!
        assertTrue(summary, summary.contains("443[flows=1 long=1 clientFirst=1 upstreamFirst=0 tunnelIdleLike=0"))
    }

    @Test
    fun upstreamFirstCloseIsNeverFlaggedAsTunnelIdle() {
        val d = diagnostics()
        val flow = d.openFlow(5229)
        now += 300_000
        flow.onEnded(FlowDiagnostics.Side.UPSTREAM)
        flow.onEnded(FlowDiagnostics.Side.CLIENT) // later end must not override the first one
        flow.finish()
        now += 60_000

        val summary = d.takeSummary()!!
        assertTrue(summary, summary.contains("clientFirst=0 upstreamFirst=1 tunnelIdleLike=0"))
    }

    @Test
    fun edgeFailuresAndLocalRefusalsAreCountedPerPortBucket() {
        val d = diagnostics()
        d.recordEdgeFailure(5228, 403)
        d.recordEdgeFailure(5230, 403)
        d.recordEdgeFailure(443, null)
        d.recordLocalRefusal(5228)
        d.recordLocalRefusal(5228)
        d.recordLocalRefusal(5228)
        now += 60_000

        val summary = d.takeSummary()!!
        assertTrue(summary, summary.contains("443/nonex1"))
        assertTrue(summary, summary.contains("5228-5230/403x2"))
        assertTrue(summary, summary.contains("locally refused (backoff): 5228-5230x3"))
    }

    @Test
    fun summaryWaitsForTheIntervalAndResetsCounters() {
        val d = diagnostics()
        d.recordLocalRefusal(80)
        assertNull(d.takeSummary())

        now += 59_999
        assertNull(d.takeSummary())

        now += 1
        assertNotNull(d.takeSummary())
        now += 60_000
        assertNull("counters must be reset after a summary", d.takeSummary())
    }

    @Test
    fun forcedSummaryIgnoresTheInterval() {
        val d = diagnostics()
        d.recordLocalRefusal(80)
        assertNotNull(d.takeSummary(force = true))
    }

    @Test
    fun finishIsIdempotent() {
        val d = diagnostics()
        val flow = d.openFlow(443)
        flow.onEnded(FlowDiagnostics.Side.CLIENT)
        flow.finish()
        flow.finish()
        now += 60_000

        assertTrue(d.takeSummary()!!.contains("443[flows=1 "))
    }

    @Test
    fun portBucketsGroupPushPorts() {
        assertEquals("5228-5230", FlowDiagnostics.portBucket(5228))
        assertEquals("5228-5230", FlowDiagnostics.portBucket(5230))
        assertEquals("443", FlowDiagnostics.portBucket(443))
        assertEquals("80", FlowDiagnostics.portBucket(80))
        assertEquals("other", FlowDiagnostics.portBucket(8443))
        assertFalse(FlowDiagnostics.portBucket(5231) == "5228-5230")
    }
}
