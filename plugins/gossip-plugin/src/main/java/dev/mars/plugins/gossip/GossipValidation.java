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
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Shared lexical checks; never resolve advertised addresses during validation. */
final class GossipValidation {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    // Results by host. A swarm has few distinct hosts and every record repeats one, so URI parsing runs once per host.
    private static final Map<String, Boolean> HOSTS = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) { return size() > 1024; }
    });

    private GossipValidation() { }

    static boolean validId(String value) {
        return value != null && ID.matcher(value).matches();
    }

    /** The address of an IP literal, or null for a hostname, which is recognised without throwing an exception. */
    static InetAddress literal(String host) {
        if (host.indexOf(':') < 0 && !host.chars().allMatch(c -> c == '.' || (c >= '0' && c <= '9'))) { return null; }
        try { return InetAddress.ofLiteral(host); }
        catch (IllegalArgumentException notLiteral) { return null; }
    }

    /** Hosts arrive from the network, so length is checked before anything is cached; the cache stays under 0.5 MB. */
    static boolean validHost(String host) {
        return host != null && host.length() <= 253 && HOSTS.computeIfAbsent(host, GossipValidation::parseHost);
    }

    private static boolean parseHost(String host) {
        if (host.isBlank() || host.contains("[") || host.contains("]")) {
            return false;
        }
        try {
            String authority = host.contains(":") ? "[" + host + "]" : host;
            URI endpoint = new URI("tcp://" + authority + ":1");
            return endpoint.getHost() != null && endpoint.getRawUserInfo() == null && endpoint.getPort() == 1
                    && endpoint.getRawPath().isEmpty() && endpoint.getRawQuery() == null
                    && endpoint.getRawFragment() == null;
        } catch (URISyntaxException e) { return false; }
    }
}
