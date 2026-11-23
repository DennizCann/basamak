package com.basamak.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;

class UrlGuardTest {
    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.255.255.254", "0.0.0.0", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.0.1",
            "169.254.169.254", "100.64.0.1", "198.18.0.1", "224.0.0.1", "255.255.255.255", "::1", "::", "fe80::1", "fd12::1",
            "::ffff:10.0.0.1", "64:ff9b::7f00:1", "2002:7f00:1::"})
    void internalAddressesAreForbidden(String ip) throws Exception {
        assertThat(UrlGuard.isForbidden(InetAddress.getByName(ip))).as(ip).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"93.184.216.34", "8.8.8.8", "172.32.0.1", "100.128.0.1", "2606:4700::1111"})
    void publicAddressesAreAllowed(String ip) throws Exception {
        assertThat(UrlGuard.isForbidden(InetAddress.getByName(ip))).as(ip).isFalse();
    }

    @Test
    void trustedHostPortAppliesOnlyToThatPort() {
        UrlGuard g = new UrlGuard("localhost:8090");
        assertThat(g.isTrusted(java.net.URI.create("http://localhost:8090/x"))).isTrue();
        assertThat(g.isTrusted(java.net.URI.create("http://localhost:8080/x"))).isFalse();
        assertThat(g.isTrusted(java.net.URI.create("http://localhost/x"))).isFalse();
    }
}
