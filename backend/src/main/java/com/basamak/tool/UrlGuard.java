package com.basamak.tool;

import com.basamak.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * SSRF protection for every outgoing tool request (scenario 7).
 * <ul>
 *   <li>Only http/https, no user-info, host must be literal text in the definition (never templated).</li>
 *   <li>Names such as localhost, *.local, *.internal and cloud metadata names are refused before DNS.</li>
 *   <li>The host is resolved and EVERY address is checked (loopback, private, link-local incl. 169.254.169.254,
 *       CGNAT, ULA, multicast, reserved, IPv4 embedded in IPv6). Odd encodings (2130706433, 0x7f.1, ::ffff:127.0.0.1)
 *       end up as the same InetAddress and are caught by the same check.</li>
 *   <li>The HTTP client connects only to the addresses returned by {@link #resolveChecked} (see ToolExecutor's
 *       DnsResolver), so a DNS answer cannot change between the check and the connection (DNS rebinding).</li>
 *   <li>Redirects are followed by the executor manually and every hop goes through {@link #check} again.</li>
 * </ul>
 * Trusted hosts (host:port, e.g. the local mock API) are allowed ONLY for staff-defined CMS tools, never for member tools.
 */
@Component
public class UrlGuard {
    public interface Resolver { InetAddress[] resolve(String host) throws UnknownHostException; }

    private static final List<String> BLOCKED_SUFFIXES = List.of(".localhost", ".local", ".internal", ".localdomain", ".home.arpa");
    private static final Set<String> BLOCKED_NAMES = Set.of("localhost", "metadata", "metadata.google.internal", "instance-data");

    private final Set<String> trustedHostPorts = new CopyOnWriteArraySet<>();
    private volatile Resolver resolver = InetAddress::getAllByName;

    public UrlGuard(@Value("${basamak.tools.trusted-hosts:}") String trusted) {
        for (String t : trusted.split(",")) if (!t.isBlank()) trustedHostPorts.add(t.trim().toLowerCase(Locale.ROOT));
    }

    /** Validates a concrete URL. Returns the parsed URI; throws blocked_url with a member-readable reason. */
    public URI check(String url, boolean trustedTool) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw blocked("Adres geçerli bir URL değil.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) throw blocked("Yalnız http ve https adreslerine istek gönderilebilir.");
        if (uri.getRawUserInfo() != null) throw blocked("Adreste kullanıcı adı ya da şifre olamaz.");
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw blocked("Adreste bir sunucu adı olmalı.");
        if (trustedTool && isTrusted(uri)) return uri;
        checkName(host);
        resolveChecked(host);
        return uri;
    }

    public boolean isTrusted(URI uri) {
        return trustedHostPorts.contains(hostPort(uri));
    }

    public boolean isTrustedHostName(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return trustedHostPorts.stream().anyMatch(hp -> hp.substring(0, hp.lastIndexOf(':')).equals(h));
    }

    static String hostPort(URI uri) {
        int port = uri.getPort() != -1 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
        return uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }

    private static void checkName(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        if (BLOCKED_NAMES.contains(h) || BLOCKED_SUFFIXES.stream().anyMatch(h::endsWith)) {
            throw blocked("İç ağ adreslerine (" + host + ") istek gönderilemez.");
        }
    }

    /** Resolve + verify every address. Used both for validation and as the HTTP client's DNS resolver. */
    public InetAddress[] resolveChecked(String host) {
        String h = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        InetAddress[] all;
        try {
            all = resolver.resolve(h);
        } catch (UnknownHostException e) {
            throw blocked("Sunucu adı çözülemedi: " + host);
        }
        if (all.length == 0) throw blocked("Sunucu adı çözülemedi: " + host);
        for (InetAddress a : all) {
            if (isForbidden(a)) throw blocked("İç ağ ya da özel bir adrese (" + host + " → " + a.getHostAddress() + ") istek gönderilemez.");
        }
        return all;
    }

    public static boolean isForbidden(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) return true;
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) return forbiddenV4(b);
        // IPv6
        if ((b[0] & 0xfe) == 0xfc) return true;                       // fc00::/7 unique local
        if ((b[0] & 0xff) == 0xfe && (b[1] & 0xc0) == 0x80) return true; // fe80::/10
        boolean first10Zero = true;
        for (int i = 0; i < 10; i++) if (b[i] != 0) { first10Zero = false; break; }
        if (first10Zero && ((b[10] == 0 && b[11] == 0) || (b[10] == (byte) 0xff && b[11] == (byte) 0xff))) {
            return forbiddenV4(Arrays.copyOfRange(b, 12, 16));          // ::a.b.c.d and ::ffff:a.b.c.d
        }
        if (b[0] == 0x00 && b[1] == 0x64 && b[2] == (byte) 0xff && b[3] == (byte) 0x9b) {
            return forbiddenV4(Arrays.copyOfRange(b, 12, 16));          // 64:ff9b::/96 NAT64
        }
        if (b[0] == 0x20 && b[1] == 0x02) return forbiddenV4(Arrays.copyOfRange(b, 2, 6)); // 2002::/16 6to4
        return false;
    }

    private static boolean forbiddenV4(byte[] b) {
        int o0 = b[0] & 0xff, o1 = b[1] & 0xff, o2 = b[2] & 0xff;
        return o0 == 0                                   // 0.0.0.0/8
                || o0 == 10                              // 10/8
                || o0 == 127                             // loopback
                || (o0 == 100 && (o1 & 0xc0) == 64)      // 100.64/10 CGNAT
                || (o0 == 169 && o1 == 254)              // link-local + cloud metadata
                || (o0 == 172 && (o1 & 0xf0) == 16)      // 172.16/12
                || (o0 == 192 && o1 == 168)              // 192.168/16
                || (o0 == 192 && o1 == 0 && o2 == 0)     // 192.0.0/24
                || (o0 == 198 && (o1 & 0xfe) == 18)      // 198.18/15 benchmarking
                || o0 >= 224;                            // multicast + reserved + broadcast
    }

    static ApiException blocked(String msg) {
        return ApiException.badRequest("blocked_url", msg);
    }

    // ---- test hooks
    public void setResolverForTests(Resolver r) { this.resolver = r == null ? InetAddress::getAllByName : r; }
    public void trustForTests(String hostPort) { trustedHostPorts.add(hostPort.toLowerCase(Locale.ROOT)); }
}
