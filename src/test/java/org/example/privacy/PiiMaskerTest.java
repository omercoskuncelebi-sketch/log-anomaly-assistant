package org.example.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class PiiMaskerTest {

    private final PiiMasker piiMasker = new PiiMasker();

    @Test
    void masksEmailAddress() {
        assertEquals("u***@example.com", piiMasker.maskEmail("user1071@example.com"));
    }

    @Test
    void masksClientIpAddress() {
        assertEquals("185.220.xxx.xxx", piiMasker.maskClientIp("185.220.101.47"));
    }

    @Test
    void masksIpv6ClientIpAddress() {
        assertEquals("[ipv6-masked]", piiMasker.maskClientIp("2001:db8::1"));
    }

    @Test
    void masksPiiInsideContext() {
        assertEquals(
                "email=u***@example.com ip=185.220.xxx.xxx",
                piiMasker.mask("email=user1071@example.com ip=185.220.101.47")
        );
    }

    @Test
    void doesNotMaskIsoTimestampAsIpv6() {
        assertEquals(
                "time=2026-09-21T03:12:00Z",
                piiMasker.mask("time=2026-09-21T03:12:00Z")
        );
    }

    @Test
    void acceptsNullValues() {
        assertNull(piiMasker.mask(null));
        assertNull(piiMasker.maskEmail(null));
        assertNull(piiMasker.maskClientIp(null));
    }
}
