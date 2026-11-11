package dev.neosync.boox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.neosync.boox.library.PiccoFinder;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

public class PiccoFinderTest {

    @Test
    public void lastKnownThenGatewayThenSubnet() {
        List<String> c = PiccoFinder.candidates("192.168.1.244", "192.168.1.1", "192.168.1.20");
        assertEquals("192.168.1.244", c.get(0));
        assertEquals("192.168.1.1", c.get(1));
        assertEquals("192.168.1.2", c.get(2));
        assertEquals("192.168.1.3", c.get(3));
        assertEquals(253, c.size()); // 254 hosts minus own; .1 and .244 deduped;
        assertFalse(c.contains("192.168.1.20"));
        assertEquals(c.size(), new HashSet<>(c).size());
    }

    @Test
    public void nullsSkipped() {
        assertTrue(PiccoFinder.candidates(null, "", null).isEmpty());
        List<String> c = PiccoFinder.candidates(null, "10.0.0.1", null);
        assertEquals(Arrays.asList("10.0.0.1"), c);
    }

    @Test
    public void findReturnsFirstHit() {
        List<String> c = PiccoFinder.candidates(null, null, "192.168.1.20");
        assertEquals("192.168.1.57",
                PiccoFinder.find(c, a -> a.endsWith(".57"), 32, 5000));
    }

    @Test
    public void findPrefersEarlierCandidateWhenBothAnswer() {
        List<String> c = PiccoFinder.candidates("192.168.1.244", null, "192.168.1.20");
        assertEquals("192.168.1.244", PiccoFinder.find(c,
                a -> a.endsWith(".244") || a.endsWith(".57"), 32, 5000));
    }

    @Test
    public void findReturnsNullWhenNoneAnswer() {
        List<String> c = PiccoFinder.candidates(null, null, "192.168.1.20");
        assertNull(PiccoFinder.find(c, a -> false, 32, 5000));
    }

    @Test
    public void findHonoursDeadline() {
        List<String> c = PiccoFinder.candidates(null, null, "192.168.1.20");
        long start = System.currentTimeMillis();
        String hit = PiccoFinder.find(c, a -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return false;
        }, 32, 300);
        assertNull(hit);
        assertTrue(System.currentTimeMillis() - start < 1000);
    }
}
