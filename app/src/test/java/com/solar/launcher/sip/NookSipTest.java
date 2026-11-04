package com.solar.launcher.sip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

/** 2026-11-03 — the SIP bits that run without a network: parsing, digest, params. */
public class NookSipTest {
    @Test public void md5MatchesRfc2617Example() throws Exception {
        // RFC 2617 section 3.5: Mufasa / "Circle Of Life", qop=auth
        String ha1 = NookSip.md5("Mufasa:testrealm@host.com:Circle Of Life");
        String ha2 = NookSip.md5("GET:/dir/index.html");
        String resp = NookSip.md5(ha1 + ":dcd98b7102dd2f0e8b11d0f600bfb0c093:00000001:0a4f113b:auth:" + ha2);
        assertEquals("6629fae49393a05397450978507c4ef1", resp);
    }

    @Test public void parsesAsteriskChallenge() {
        Map<String, String> p = NookSip.authParams(
                "Digest realm=\"asterisk\",nonce=\"1793766205/abc\",opaque=\"5e9f\",algorithm=md5,qop=\"auth\"");
        assertEquals("asterisk", p.get("realm"));
        assertEquals("1793766205/abc", p.get("nonce"));
        assertEquals("auth", p.get("qop"));
        assertEquals("md5", p.get("algorithm"));
    }

    @Test public void parsesResponseHeadersAndBody() {
        NookSip.Msg m = NookSip.Msg.parse("SIP/2.0 200 OK\r\nVia: SIP/2.0/UDP a\r\nv: SIP/2.0/UDP b\r\n"
                + "To: <sip:7000@x>;tag=abc\r\ni: cid@solar\r\nContent-Length: 4\r\n\r\nv=0\n");
        assertEquals(200, m.code);
        assertEquals(2, m.all("Via").size());          // compact "v" folds into Via
        assertEquals("cid@solar", m.header("Call-ID"));
        assertEquals("abc", NookSip.param(m.header("To"), "tag"));
        assertEquals("v=0\n", m.body);
        assertTrue(!m.isRequest());
    }

    @Test public void parsesRequest() {
        NookSip.Msg m = NookSip.Msg.parse("BYE sip:6003@100.85.5.19:4000 SIP/2.0\r\nCall-ID: x\r\n\r\n");
        assertEquals("BYE", m.method);
        assertNull(NookSip.param("<sip:a>", "tag"));
    }
}
