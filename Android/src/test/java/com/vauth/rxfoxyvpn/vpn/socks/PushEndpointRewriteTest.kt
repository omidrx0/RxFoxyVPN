package com.vauth.rxfoxyvpn.vpn.socks

import org.junit.Assert.assertEquals
import org.junit.Test

class PushEndpointRewriteTest {

    @Test
    fun pushPortsOnMtalkHostsMoveTo443() {
        assertEquals(443, PushEndpointRewrite.upstreamPort("mtalk.google.com", 5228))
        assertEquals(443, PushEndpointRewrite.upstreamPort("mtalk.google.com", 5229))
        assertEquals(443, PushEndpointRewrite.upstreamPort("MTALK.google.com.", 5230))
        assertEquals(443, PushEndpointRewrite.upstreamPort("mtalk4.google.com", 5228))
        assertEquals(443, PushEndpointRewrite.upstreamPort("alt1-mtalk.google.com", 5228))
    }

    @Test
    fun everythingElseIsUntouched() {
        assertEquals(5228, PushEndpointRewrite.upstreamPort("example.com", 5228))
        assertEquals(5228, PushEndpointRewrite.upstreamPort("evilmtalk.google.com.example.org", 5228))
        assertEquals(5228, PushEndpointRewrite.upstreamPort("evilmtalk.google.com", 5228))
        assertEquals(443, PushEndpointRewrite.upstreamPort("mtalk.google.com", 443))
        assertEquals(80, PushEndpointRewrite.upstreamPort("mtalk.google.com", 80))
        assertEquals(5231, PushEndpointRewrite.upstreamPort("mtalk.google.com", 5231))
    }
}
