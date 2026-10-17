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

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The plugin takes its locks in one order, written down in package-info.java: while holding a lock, a thread takes only
 * those further down. That holds because no class keeps a reference to one whose lock comes before its own, which this
 * test checks. A callback passed down the order must reach only downwards too; the test cannot see those.
 */
class GossipLockOrderTest {
    /** Outermost first. Classes on one line never reference each other. */
    private static final List<List<Class<?>>> ORDER = List.of(
            List.of(GossipPlugin.class),
            List.of(GossipRuntime.class),
            List.of(GossipTransport.class, PeerSelector.class),
            List.of(MembershipTable.class),
            List.of(GossipBatchSelector.class, GossipDnsResolver.class, GossipMetrics.class),
            List.of(GossipLog.class));

    @Test void noClassKeepsAReferenceToOneWhoseLockComesBeforeItsOwn() {
        var problems = new ArrayList<String>();
        for (int rank = 0; rank < ORDER.size(); rank++) {
            for (var owner : ORDER.get(rank)) {
                for (var field : fields(owner)) { problems.addAll(upwardReferences(field, rank)); }
            }
        }
        assertEquals(List.of(), problems);
    }

    /** The classes at or above {@code rank} that the field could hold, such as a transport behind its interface. */
    private static List<String> upwardReferences(Field field, int rank) {
        // Named by the class that declares it, so a field of a nested class reads as Outer.Nested.field.
        String declaredIn = field.getDeclaringClass().getName().substring(GossipLockOrderTest.class.getPackageName().length() + 1);
        var found = new ArrayList<String>();
        for (int above = 0; above <= rank; above++) {
            for (var ranked : ORDER.get(above)) {
                if (field.getType() != Object.class && field.getType().isAssignableFrom(ranked)) {
                    found.add(declaredIn.replace('$', '.') + "." + field.getName() + " can hold a " + ranked.getSimpleName());
                }
            }
        }
        return found;
    }

    /** Every field of the class and of the classes nested in it, besides those the compiler adds. */
    private static List<Field> fields(Class<?> type) {
        return Stream.concat(Arrays.stream(type.getDeclaredFields()).filter(field -> !field.isSynthetic()),
                Arrays.stream(type.getDeclaredClasses()).flatMap(nested -> fields(nested).stream())).toList();
    }
}
