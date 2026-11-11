package dev.neosync.boox.library;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Finds the Picco on the local network: builds the ordered list of addresses
 * worth trying and probes them in parallel under a deadline. Pure Java; the
 * caller supplies the gateway and own IP.
 */
public final class PiccoFinder {

    public static final int THREADS = 32;
    public static final int PROBE_TIMEOUT_MS = 600;
    public static final long DEADLINE_MS = 10_000;

    public interface Prober {
        boolean isPicco(String address);
    }

    private PiccoFinder() {}

    /**
     * lastKnown, then the gateway, then every x.y.z.1..254 of ownIp's /24
     * except ownIp itself. Null/empty inputs are skipped, duplicates dropped.
     */
    public static List<String> candidates(String lastKnown, String gatewayIp, String ownIp) {
        Set<String> out = new LinkedHashSet<>();
        addIfPresent(out, lastKnown);
        addIfPresent(out, gatewayIp);
        if (ownIp != null) {
            String own = ownIp.trim();
            int dot = own.lastIndexOf('.');
            if (dot > 0) {
                String prefix = own.substring(0, dot + 1);
                for (int i = 1; i <= 254; i++) {
                    String address = prefix + i;
                    if (!address.equals(own)) {
                        out.add(address);
                    }
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static void addIfPresent(Set<String> out, String value) {
        if (value != null && !value.trim().isEmpty()) {
            out.add(value.trim());
        }
    }

    /**
     * Probes the candidates in parallel and returns the first one, in
     * candidate order, that answers; null when none does before the deadline.
     * Returns as soon as every earlier candidate has finished without a hit.
     */
    public static String find(List<String> candidates, Prober prober, int threads, long deadlineMs) {
        if (candidates.isEmpty()) {
            return null;
        }
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMs);
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.max(1, Math.min(threads, candidates.size())), runnable -> {
                    Thread t = new Thread(runnable, "picco-finder");
                    t.setDaemon(true);
                    return t;
                });
        try {
            List<Future<Boolean>> futures = new ArrayList<>(candidates.size());
            for (String address : candidates) {
                futures.add(pool.submit(() -> prober.isPicco(address)));
            }
            for (int i = 0; i < futures.size(); i++) {
                long left = end - System.nanoTime();
                if (left <= 0) {
                    return null;
                }
                try {
                    if (futures.get(i).get(left, TimeUnit.NANOSECONDS)) {
                        return candidates.get(i);
                    }
                } catch (TimeoutException e) {
                    return null;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                } catch (Exception e) {
                    // This probe failed; the next candidate may still answer.
                }
            }
            return null;
        } finally {
            pool.shutdownNow();
        }
    }
}
