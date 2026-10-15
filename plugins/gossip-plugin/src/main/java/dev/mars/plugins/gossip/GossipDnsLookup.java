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

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Comparator;

/** Isolated system-resolver entry point. Only JDK classes are needed on the helper classpath. */
public final class GossipDnsLookup {
    private GossipDnsLookup() { }

    public static void main(String[] args) {
        try {
            if (args.length != 1) { throw new IllegalArgumentException("Expected one hostname"); }
            var addresses = Arrays.stream(InetAddress.getAllByName(args[0]))
                    .distinct().sorted(Comparator.comparingInt(address -> address.getAddress().length)).limit(16).toList();
            System.out.println("GOSSIP_DNS_V1");
            for (var address : addresses) { System.out.println(address.getHostAddress()); }
        } catch (Exception e) {
            System.out.println("GOSSIP_DNS_FAILED");
            System.exit(1);
        }
    }
}
