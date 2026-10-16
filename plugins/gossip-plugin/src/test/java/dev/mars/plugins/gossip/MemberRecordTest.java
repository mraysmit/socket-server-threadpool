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
package dev.mars.plugins.gossip;

import dev.mars.protocol.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static dev.mars.plugins.gossip.MemberRecord.State.ALIVE;
import static dev.mars.plugins.gossip.MembershipException.Reason.INVALID_RECORD;
import static org.junit.jupiter.api.Assertions.*;

class MemberRecordTest {
    private static final MemberKey KEY = new MemberKey("Peer", UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));

    private static MemberRecord record(Map<String, Object> metadata) {
        return new MemberRecord(KEY, "::1", 65535, Long.MAX_VALUE, ALIVE, metadata);
    }

    @Test void realJsonRoundTripPreservesExactIntegersUnicodeNullsAndCanonicalMetadata() throws Exception {
        var nested = new LinkedHashMap<String, Object>();
        nested.put("null", null);
        nested.put("text", "雪\n\"quoted\"");
        nested.put("list", Arrays.asList(null, 1, new BigDecimal("1.00"), new BigDecimal("0.1250")));
        var original = record(Map.of("nested", nested));
        String wire = Json.write(original.toWire());
        var decodedMap = Json.parameters(Json.read(wire));
        assertInstanceOf(BigInteger.class, decodedMap.get("heartbeat"));
        assertInstanceOf(BigInteger.class, decodedMap.get("port"));
        assertEquals(original, MemberRecord.fromWire(decodedMap));
        assertEquals(wire.getBytes(StandardCharsets.UTF_8).length, original.serializedBytes());
    }

    @Test void metadataAndWireSnapshotsAreDeeplyImmutableAndDetachedFromInput() {
        var values = new ArrayList<Object>(Arrays.asList("before", null));
        var nested = new LinkedHashMap<String, Object>();
        nested.put("values", values);
        var input = new LinkedHashMap<String, Object>();
        input.put("nested", nested);
        var member = record(input);
        values.set(0, "after");
        nested.put("new", true);
        input.clear();
        Map<?, ?> stored = (Map<?, ?>) member.metadata().get("nested");
        assertEquals(Arrays.asList("before", null), stored.get("values"));
        assertFalse(stored.containsKey("new"));
        assertThrows(UnsupportedOperationException.class, member.metadata()::clear);
        assertThrows(UnsupportedOperationException.class, stored::clear);
        assertThrows(UnsupportedOperationException.class, ((List<?>) stored.get("values"))::clear);
        assertThrows(UnsupportedOperationException.class, member.toWire()::clear);
    }

    @Test void equalMetadataIgnoresInputMapOrderAndNumericWrapperDifferences() {
        var first = new LinkedHashMap<String, Object>();
        first.put("b", 1000);
        first.put("a", new BigDecimal("0.100"));
        var second = new LinkedHashMap<String, Object>();
        second.put("a", 0.1d);
        second.put("b", new BigDecimal("1E+3"));
        assertEquals(record(first), record(second));
    }

    @Test void metadataNumbersMustAlsoFitTheActualHostJsonReader() throws Exception {
        var accepted = record(Map.of("number", BigInteger.TEN.pow(999)));
        assertEquals(accepted, MemberRecord.fromWire(Json.parameters(Json.read(Json.write(accepted.toWire())))));
        assertInvalid(() -> record(Map.of("number", BigInteger.TEN.pow(1000))));
    }

    @Test void everyAcceptedRecordReadsBackThroughTheHostCodecAcrossTheReadBackGate() throws Exception {
        // The read-back runs only for long numbers, so sweep number lengths across that gate and the codec's limit.
        int accepted = 0;
        int rejected = 0;
        for (int digits = 1; digits <= 1200; digits += digits < 150 ? 1 : 7) {
            String nines = "9".repeat(digits);
            for (Object number : List.of(new BigInteger(nines), new BigInteger("-" + nines), new BigDecimal("0." + nines),
                    new BigDecimal("1" + nines + "E-" + (digits + 5)), new BigDecimal(nines + ".5"))) {
                MemberRecord record;
                try { record = record(Map.of("n", number, "list", List.of(number))); }
                catch (MembershipException e) { rejected++; continue; }
                accepted++;
                String json = Json.write(record.metadata());
                assertDoesNotThrow(() -> Json.read(json), () -> "Accepted metadata the host cannot read: " + json.length() + " chars");
            }
        }
        assertTrue(accepted > 0 && rejected > 0, "The sweep must cross the codec's limit");
        assertEquals(100, MemberRecord.READ_BACK_NUMBER_LENGTH);
        assertInvalid(() -> record(Map.of("number", BigInteger.TEN.pow(1000))));
        assertInvalid(() -> record(Map.of("number", new BigDecimal("0." + "9".repeat(1999)))));
    }

    @Test void serializedSizeEqualsTheActualEncodingForBuiltDerivedAndDecodedRecords() throws Exception {
        var samples = List.<Map<String, Object>>of(Map.of(), Map.of("text", "\"quoted\" \\ / é 节点 😀 \u0001"),
                Map.of("nested", Map.of("list", List.of(1, -2.5, true, "x")), "big", new BigInteger("123456789012345678901234567890")),
                Map.of("long", "x".repeat(3000)));
        for (var metadata : samples) {
            var built = record(metadata);
            var derived = built.with(7, MemberRecord.State.LEFT);
            var decoded = MemberRecord.fromWire(Json.parameters(Json.read(Json.write(built.toWire()))));
            for (var record : List.of(built, derived, decoded)) {
                assertEquals(Json.write(record.toWire()).getBytes(StandardCharsets.UTF_8).length, record.serializedBytes(), record::toString);
            }
        }
    }

    @Test void unpairedUnicodeSurrogatesCannotSilentlyChangeOnUtf8Encoding() throws Exception {
        String invalid = String.valueOf((char) 0xd800);
        assertInvalid(() -> record(Map.of("value", invalid)));
        assertInvalid(() -> record(Map.of(invalid, "value")));
        assertInvalid(() -> record(Map.of("value", String.valueOf((char) 0xdc00))));
        var valid = record(Map.of("value", "\uD83D\uDE80"));
        assertEquals(valid, MemberRecord.fromWire(Json.parameters(Json.read(Json.write(valid.toWire())))));
    }

    @Test void metadataLimitCountsUtf8BytesAndAllowsExactBoundary() {
        // {"v":""} uses eight bytes; budget counts the serialized object, not just its value.
        record(Map.of("v", "a".repeat(MemberRecord.MAX_METADATA_BYTES - 8)));
        assertInvalid(() -> record(Map.of("v", "a".repeat(MemberRecord.MAX_METADATA_BYTES - 7))));
        assertInvalid(() -> record(Map.of("v", "雪".repeat(1363))));
        assertInvalid(() -> record(Map.of("v", "\n".repeat(2045))));
    }

    @Test void rejectsCyclesDeepGraphsUnsupportedObjectsAndUnboundedNumbersBeforeSerialization() {
        var cycle = new ArrayList<Object>();
        cycle.add(cycle);
        assertInvalid(() -> record(Map.of("cycle", cycle)));
        Object deep = "leaf";
        for (int i = 0; i < 17; i++) { deep = List.of(deep); }
        Object tooDeep = deep;
        assertInvalid(() -> record(Map.of("deep", tooDeep)));
        for (Object value : List.of(new Object(), new AtomicLong(1), Double.NaN, Double.POSITIVE_INFINITY,
                BigInteger.ONE.shiftLeft(20_000), new BigDecimal("1e999999"))) {
            assertInvalid(() -> record(Map.of("value", value)));
        }
        var badKeys = new LinkedHashMap<Object, Object>();
        badKeys.put(1, "value");
        assertInvalid(() -> record(Map.of("nested", badKeys)));
    }

    @Test void portAndHeartbeatRequireExactIntegersWithoutFractionsOrOverflow() {
        for (Object invalid : List.of(new BigDecimal("1.0"), 1.0, "1", true, new AtomicLong(1),
                BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE), BigInteger.valueOf(-1))) {
            var fields = new LinkedHashMap<>(record(Map.of()).toWire());
            fields.put("heartbeat", invalid);
            assertInvalid(() -> MemberRecord.fromWire(fields));
            fields.put("heartbeat", BigInteger.ONE);
            fields.put("port", invalid);
            assertInvalid(() -> MemberRecord.fromWire(fields));
        }
        var fields = new LinkedHashMap<>(record(Map.of()).toWire());
        fields.put("port", BigInteger.valueOf(65536));
        assertInvalid(() -> MemberRecord.fromWire(fields));
        fields.put("port", BigInteger.ZERO);
        assertInvalid(() -> MemberRecord.fromWire(fields));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1-1-1-1-1", "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA", "not-a-uuid", ""})
    void bootIdsMustBeCanonicalLowercaseUuids(String bootId) {
        assertInvalid(() -> MemberKey.fromWire("Peer", bootId));
    }

    @Test void rejectsMalformedFieldsStatesEndpointsAndIdentities() {
        for (String missing : record(Map.of()).toWire().keySet()) {
            var fields = new LinkedHashMap<>(record(Map.of()).toWire());
            fields.remove(missing);
            assertInvalid(() -> MemberRecord.fromWire(fields));
        }
        for (var change : Map.<String, Object>of("serverId", "bad id", "host", "http://localhost", "state", "DEAD",
                "metadata", List.of(), "unknown", true, "bootId", 123).entrySet()) {
            var fields = new LinkedHashMap<>(record(Map.of()).toWire());
            fields.put(change.getKey(), change.getValue());
            assertInvalid(() -> MemberRecord.fromWire(fields));
        }
        assertInvalid(() -> MemberRecord.fromWire(null));
        assertInvalid(() -> new MemberRecord(KEY, "localhost", 9000, -1, ALIVE, Map.of()));
        assertInvalid(() -> MemberKey.fromWire("Peer", null));
    }

    private static void assertInvalid(org.junit.jupiter.api.function.Executable operation) {
        assertEquals(INVALID_RECORD, assertThrows(MembershipException.class, operation).reason());
    }
}
