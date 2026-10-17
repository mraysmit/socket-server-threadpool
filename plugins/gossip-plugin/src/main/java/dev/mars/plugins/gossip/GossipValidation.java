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

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static dev.mars.plugins.gossip.MembershipException.invalidRecord;

/** Shared lexical checks; never resolve advertised addresses during validation. */
final class GossipValidation {
    private static final int MAX_CACHED_HOSTS = 1024;
    // Results by host. A swarm has few distinct hosts and every record repeats one, so URI parsing runs once per host.
    // Every decoded record looks its host up here, so lookups take no lock; when full, the cache simply starts again.
    private static final Map<String, Boolean> HOSTS = new ConcurrentHashMap<>();

    private GossipValidation() { }

    /** 1-128 ASCII letters, digits, dots, underscores or hyphens. Every received record is checked, so no regex. */
    static boolean validId(String value) {
        if (value == null || value.isEmpty() || value.length() > 128) { return false; }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '_'
                    || c == '-')) {
                return false;
            }
        }
        return true;
    }

    /** The address of an IP literal, or null for a hostname, which is recognised without throwing an exception. */
    static InetAddress literal(String host) {
        if (host.indexOf(':') < 0 && !digitsAndDots(host)) { return null; }
        try { return InetAddress.ofLiteral(host); }
        catch (IllegalArgumentException notLiteral) { return null; }
    }

    private static boolean digitsAndDots(String host) {
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c != '.' && (c < '0' || c > '9')) { return false; }
        }
        return true;
    }

    /** Hosts arrive from the network, so length is checked before anything is cached; the cache stays under 0.5 MB. */
    static boolean validHost(String host) {
        if (host == null || host.length() > 253) { return false; }
        Boolean known = HOSTS.get(host);
        if (known != null) { return known; }
        boolean valid = parseHost(host);
        if (HOSTS.size() >= MAX_CACHED_HOSTS) { HOSTS.clear(); }
        HOSTS.put(host, valid);
        return valid;
    }

    static int cachedHosts() { return HOSTS.size(); }

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

    static int exactInteger(Object value, String field, int min, int max) {
        return (int) exactLong(value, field, min, max);
    }

    /**
     * An exact JSON integer in [min, max]: fractions, strings and other coercions are rejected. The range message is
     * also what deployed version-1 peers send for an unsupported gossipVersion, so its wording must not change.
     */
    static long exactLong(Object value, String field, long min, long max) {
        long number;
        if (value instanceof BigInteger integer) {
            try { number = integer.longValueExact(); }
            catch (ArithmeticException e) { throw invalidRecord(field + " is outside the supported integer range"); }
        } else if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long) {
            number = ((Number) value).longValue();
        } else { throw invalidRecord(field + " must be an integer"); }
        if (number < min || number > max) { throw invalidRecord(field + " must be between " + min + " and " + max); }
        return number;
    }
}
