/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.codec.vectors.diskbbq.next;

import org.elasticsearch.test.ESTestCase;

/**
 * Tests for {@link ESNextDiskBBQVectorsWriter#trimPostingToCap}: the SPANN posting-cut must never drop a
 * primary (a vector's only guaranteed copy), only the farthest replicas.
 */
public class ReplicaPostingTrimTests extends ESTestCase {

    private static boolean contains(int[] arr, int value) {
        for (int v : arr) {
            if (v == value) {
                return true;
            }
        }
        return false;
    }

    /** Under the cap, the posting is returned unchanged. */
    public void testNoTrimWhenUnderCap() {
        int[] ords = { 5, 9, 2 };
        float[] dists = { 0.1f, 0.2f, 0.3f };
        boolean[] isPrimary = { true, false, false };
        int[] kept = ESNextDiskBBQVectorsWriter.trimPostingToCap(ords, dists, isPrimary, 5);
        assertSame("under-cap posting should be returned as-is", ords, kept);
    }

    /** Over the cap, primaries are all kept and the farthest replicas are dropped down to the cap. */
    public void testTrimsFarthestReplicasKeepsPrimary() {
        // ord 0 is the primary; replicas at ords 1,2,3 with distances 0.5, 0.2, 0.9
        int[] ords = { 10, 11, 12, 13 };
        float[] dists = { 0.1f, 0.5f, 0.2f, 0.9f };
        boolean[] isPrimary = { true, false, false, false };
        int[] kept = ESNextDiskBBQVectorsWriter.trimPostingToCap(ords, dists, isPrimary, 2);

        assertEquals("trim to cap", 2, kept.length);
        assertTrue("primary (ord 10) must survive", contains(kept, 10));
        assertTrue("nearest replica (ord 12, dist 0.2) must survive", contains(kept, 12));
        assertFalse("farther replica ord 11 (0.5) dropped", contains(kept, 11));
        assertFalse("farthest replica ord 13 (0.9) dropped", contains(kept, 13));
    }

    /** When primaries alone exceed the cap, all primaries are kept (posting stays above cap) and replicas drop. */
    public void testNeverDropsPrimariesEvenAboveCap() {
        // three primaries (ords 20,21,22) plus a very-near replica (ord 23, dist 0.01); cap is 2
        int[] ords = { 20, 21, 22, 23 };
        float[] dists = { 0.1f, 0.2f, 0.3f, 0.01f };
        boolean[] isPrimary = { true, true, true, false };
        int[] kept = ESNextDiskBBQVectorsWriter.trimPostingToCap(ords, dists, isPrimary, 2);

        assertEquals("all primaries kept even above cap", 3, kept.length);
        assertTrue(contains(kept, 20));
        assertTrue(contains(kept, 21));
        assertTrue(contains(kept, 22));
        assertFalse("replica dropped even though it is the nearest member", contains(kept, 23));
    }

    /**
     * Property check on random postings: every primary survives, the result never exceeds
     * {@code max(primaryCount, trimCap)}, and when replicas are trimmed the kept replicas are the nearest ones.
     */
    public void testRandomPostingsPrimarySafe() {
        for (int iter = 0; iter < 200; iter++) {
            int n = randomIntBetween(1, 40);
            int[] ords = new int[n];
            float[] dists = new float[n];
            boolean[] isPrimary = new boolean[n];
            int primaryCount = 0;
            for (int i = 0; i < n; i++) {
                ords[i] = i;
                dists[i] = randomFloat();
                isPrimary[i] = randomBoolean();
                if (isPrimary[i]) {
                    primaryCount++;
                }
            }
            int trimCap = randomIntBetween(1, n + 5);
            int[] kept = ESNextDiskBBQVectorsWriter.trimPostingToCap(ords, dists, isPrimary, trimCap);

            // every primary survives
            for (int i = 0; i < n; i++) {
                if (isPrimary[i]) {
                    assertTrue("primary ord " + i + " must survive (cap=" + trimCap + ")", contains(kept, i));
                }
            }
            // never exceeds the effective cap
            assertTrue("kept size bounded", kept.length <= Math.max(primaryCount, trimCap));
            if (n <= trimCap) {
                assertEquals("no trim under cap", n, kept.length);
            } else {
                assertEquals("trimmed to effective cap", Math.max(primaryCount, trimCap), kept.length);
                // any dropped member must be a replica that is no nearer than the farthest kept replica
                float worstKeptReplica = Float.NEGATIVE_INFINITY;
                for (int ord : kept) {
                    if (isPrimary[ord] == false) {
                        worstKeptReplica = Math.max(worstKeptReplica, dists[ord]);
                    }
                }
                for (int i = 0; i < n; i++) {
                    if (isPrimary[i] == false && contains(kept, i) == false) {
                        assertTrue(
                            "dropped replica " + i + " (" + dists[i] + ") should be >= worst kept replica " + worstKeptReplica,
                            dists[i] >= worstKeptReplica - 1e-6f
                        );
                    }
                }
            }
        }
    }
}
