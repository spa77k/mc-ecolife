package dev.spa.ecolife.invite;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

class InviteStoreTest {
    @TempDir Path dir;

    @Test
    void survivesRestartAndRetainsEligibilityAndNames() throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        Path path = dir.resolve("invite.db");
        try (var s = new InviteStore(path)) {
            assertEquals("one", s.metadata("secret", "one"));
            assertEquals("one", s.metadata("secret", "two"));
            s.person(a, "OldName", false, "ip1");
            s.person(b, "Newcomer", true, "ip2");
            s.bind(b, a, false, 2000, 1000, "test");
            s.person(a, "NewName", true, "ip3");
            assertFalse(s.person(a).eligible());
            assertNull(s.find("OldName"));
            assertEquals(a, s.find("newname").id());
            s.payment(b, true, "SENDING");
        }
        try (var s = new InviteStore(path)) {
            assertEquals("SENDING", s.link(b).inviterPay());
            assertEquals(a, s.link(b).inviter());
            assertFalse(s.cancel(b, "test"));
            s.resolve(b, true, true, "operator");
            s.payment(b, false, "PAID");
            assertTrue(s.complete(b));
            assertFalse(s.complete(b));
            assertEquals(1, s.top(0).getFirst().count());
            assertFalse(s.cancel(b, "test"));
        }
    }

    @Test
    void historicalIpsBlockAndPendingCancellationCanRebind() throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        try (var s = new InviteStore(dir.resolve("invite.db"))) {
            s.person(a, "A", false, "same");
            s.person(b, "B", true, "same");
            s.person(b, "B", false, "changed");
            assertTrue(s.sameIp(a, b));
            assertTrue(s.person(b).eligible());
            s.bind(b, a, true, 2000, 1000, "admin");
            assertTrue(s.link(b).overrideIp());
            assertTrue(s.cancel(b, "admin"));
            s.bind(b, a, false, 20, 10, "admin");
            assertEquals(20, s.link(b).inviterAmount());
            assertEquals("WAITING", s.link(b).state());
            s.state(b, "BLOCKED");
            s.override(b, "admin");
            assertEquals("WAITING", s.link(b).state());
            assertTrue(s.link(b).overrideIp());
        }
    }

    @Test
    void ambiguousNamesFailClosedAndUncertainPaymentNeedsExplicitResolution() throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        try (var s = new InviteStore(dir.resolve("invite.db"))) {
            s.person(a, "Name", false, "a");
            s.person(b, "NAME", true, "b");
            assertNull(s.find("name"));
            s.bind(b, a, false, 2000, 1000, "test");
            assertFalse(s.complete(b));
            assertThrows(java.sql.SQLException.class, () -> s.resolve(b, true, true, "admin"));
            s.payment(b, true, "SENDING");
            s.resolve(b, true, false, "admin");
            assertEquals("PENDING", s.link(b).inviterPay());
        }
    }
}
