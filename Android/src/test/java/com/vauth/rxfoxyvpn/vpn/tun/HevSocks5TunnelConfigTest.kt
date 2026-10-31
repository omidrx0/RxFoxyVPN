package com.vauth.rxfoxyvpn.vpn.tun

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HevSocks5TunnelConfigTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testYamlConfigIncludesIpv4AndIpv6() {
        val folder = tempFolder.newFolder("files")
        val mockContext = MockContext(folder)

        val filePath = HevSocks5TunnelConfig.write(mockContext, 1080)
        val configFile = java.io.File(filePath)

        assertTrue("Config file must exist", configFile.exists())
        val content = configFile.readText()

        assertTrue("Config must contain ipv4 address", content.contains("ipv4: '${HevSocks5TunnelConfig.TUN_ADDRESS}'"))
        assertTrue("Config must contain ipv6 address", content.contains("ipv6: '${HevSocks5TunnelConfig.TUN_ADDRESS_IPV6}'"))
        assertTrue("Config must contain socks5 port", content.contains("port: 1080"))
    }

    private class MockContext(private val filesDirectory: java.io.File) : android.content.ContextWrapper(null) {
        override fun getFilesDir(): java.io.File = filesDirectory
    }
}
