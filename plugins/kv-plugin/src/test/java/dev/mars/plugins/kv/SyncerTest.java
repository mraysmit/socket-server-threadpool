/*
 * Copyright 2026 Mark Andrew Ray-Smith
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.mars.plugins.kv;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/** The server that starts a comparison, run against another store in the same process. */
class SyncerTest {
    private static final Peer PEER = new Peer("Server-B", "localhost", 9100);
    private static final int BUDGET = 32_768;

    private final VersionedStore a = new VersionedStore("Server-A", () -> 1, KvConfig.defaults());
    private final VersionedStore b = new VersionedStore("Server-B", () -> 1, KvConfig.defaults());

    /** Calls the other store's responder directly, and records what was asked. */
    private static final class InProcessLink implements SyncLink {
        final SyncResponder responder;
        final VersionedStore store;
        final List<String> calls = new ArrayList<>();
        final List<Integer> applyBatchSizes = new ArrayList<>();
        int failOnCall = Integer.MAX_VALUE;

        InProcessLink(VersionedStore store, int budget) {
            this.store = store;
            this.responder = new SyncResponder(store, budget);
        }

        private void note(String call) throws IOException {
            calls.add(call);
            if (calls.size() >= failOnCall) { throw new IOException("the peer stopped answering"); }
        }

        @Override public SyncResponder.RootReply syncRoot(Peer peer, String root) throws IOException {
            note("root");
            return responder.root(root);
        }

        @Override public SyncResponder.DigestReply syncDigest(Peer peer, SyncResponder.DigestRequest request) throws IOException {
            note("digest");
            return responder.digest(request);
        }

        @Override public void apply(Peer peer, List<KvRecord> records) throws IOException {
            note("apply");
            applyBatchSizes.add(records.size());
            store.apply(records);
        }
    }

    private static KvRecord written(String key, String value, long w, String server) {
        return new KvRecord(key, value, new Version(w, 0, server), false);
    }

    private static KvRecord deleted(String key, long w, String server) {
        return new KvRecord(key, null, new Version(w, 0, server), true);
    }

    private static Map<String, KvRecord> contents(VersionedStore store) {
        var all = new TreeMap<String, KvRecord>();
        for (var row : store.digestPage(null, Integer.MAX_VALUE).rows()) { store.raw(row.key()).ifPresent(r -> all.put(row.key(), r)); }
        return all;
    }

    @Test void whenTheRootsAreEqualOneExchangeIsAllItTakes() throws Exception {
        a.apply(List.of(written("x", "1", 100, "Server-A")));
        b.apply(List.of(written("x", "1", 100, "Server-A")));
        var link = new InProcessLink(b, BUDGET);
        var outcome = new Syncer(a, link, BUDGET).syncWith(PEER);
        assertTrue(outcome.equal());
        assertEquals(List.of("root"), link.calls);
    }

    @Test void storesThatDifferEndIdenticalWithTheNewerOfEveryRecord() throws Exception {
        a.apply(List.of(written("a", "only-a", 100, "Server-A"), written("b", "old", 100, "Server-A"), deleted("c", 300, "Server-A")));
        b.apply(List.of(written("b", "new", 200, "Server-B"), written("c", "was-here", 100, "Server-B"), written("d", "only-b", 100, "Server-B")));
        var outcome = new Syncer(a, new InProcessLink(b, BUDGET), BUDGET).syncWith(PEER);
        assertFalse(outcome.equal());
        assertEquals(a.root(), b.root());
        assertEquals(contents(a), contents(b));
        assertEquals("new", a.get("b").orElseThrow().valueJson());
        assertTrue(b.raw("c").orElseThrow().deleted(), "The deletion beat the older value on the other side");
        assertEquals("only-a", b.get("a").orElseThrow().valueJson());
        assertEquals("only-b", a.get("d").orElseThrow().valueJson());
        assertEquals(2, outcome.received(), "the newer b and d came to this server");
        assertEquals(2, outcome.sent(), "a, which the other side lacked, and the deletion of c went the other way");
    }

    @Test void startingFromTheOtherSideEndsTheSame() throws Exception {
        a.apply(List.of(written("a", "1", 100, "Server-A"), deleted("c", 300, "Server-A")));
        b.apply(List.of(written("c", "x", 100, "Server-B"), written("d", "4", 100, "Server-B")));
        new Syncer(b, new InProcessLink(a, BUDGET), BUDGET).syncWith(new Peer("Server-A", "localhost", 9000));
        assertEquals(a.root(), b.root());
    }

    @Test void aLargeDifferenceTakesSeveralPagesAndStillEndsIdentical() throws Exception {
        for (int i = 0; i < 150; i++) {
            a.apply(List.of(written(String.format("k%03d", i), "a-" + i, 100 + i, "Server-A")));
            if (i % 3 != 0) { b.apply(List.of(written(String.format("k%03d", i), i % 2 == 0 ? "b-" + i : "a-" + i, i % 2 == 0 ? 500 + i : 100 + i, i % 2 == 0 ? "Server-B" : "Server-A"))); }
        }
        for (int i = 150; i < 200; i++) { b.apply(List.of(written(String.format("k%03d", i), "b-" + i, 100 + i, "Server-B"))); }
        var link = new InProcessLink(b, 600);
        var outcome = new Syncer(a, link, 600).syncWith(PEER);
        assertTrue(outcome.pages() > 3, "pages: " + outcome.pages());
        assertEquals(a.root(), b.root());
        assertEquals(contents(a), contents(b));
    }

    @Test void anEmptyStoreIsFilledFromAFullOneThroughTheContinuationPath() throws Exception {
        for (int i = 0; i < 120; i++) { b.apply(List.of(written(String.format("k%03d", i), "x".repeat(80), 100 + i, "Server-B"))); }
        var link = new InProcessLink(b, 500);
        var outcome = new Syncer(a, link, 500).syncWith(PEER);
        assertEquals(120, outcome.received());
        assertEquals(0, outcome.sent());
        assertEquals(b.root(), a.root());
        assertEquals(120, a.liveCount());
        assertTrue(link.calls.stream().filter("digest"::equals).count() > 3, "several digest rounds: " + link.calls.size());
    }

    @Test void recordsAreHandedOverInBatchesThatFitTheBudget() throws Exception {
        for (int i = 0; i < 20; i++) { a.apply(List.of(written(String.format("k%02d", i), "x".repeat(150), 100 + i, "Server-A"))); }
        var link = new InProcessLink(b, 500);
        new Syncer(a, link, 500).syncWith(PEER);
        assertEquals(a.root(), b.root());
        assertTrue(link.applyBatchSizes.size() > 1, "more than one batch: " + link.applyBatchSizes);
        assertTrue(link.applyBatchSizes.stream().allMatch(size -> size <= 2), "each batch is small: " + link.applyBatchSizes);
    }

    @Test void aPeerThatFailsPartWayLeavesWhatWasDoneAndALaterSyncFinishesTheJob() throws Exception {
        for (int i = 0; i < 60; i++) { b.apply(List.of(written(String.format("k%02d", i), "x".repeat(80), 100 + i, "Server-B"))); }
        var link = new InProcessLink(b, 400);
        link.failOnCall = 4;
        var syncer = new Syncer(a, link, 400);
        assertThrows(IOException.class, () -> syncer.syncWith(PEER));
        assertTrue(a.liveCount() > 0 && a.liveCount() < 60, "some was done: " + a.liveCount());
        link.failOnCall = Integer.MAX_VALUE;
        syncer.syncWith(PEER);
        assertEquals(b.root(), a.root());
    }
}
