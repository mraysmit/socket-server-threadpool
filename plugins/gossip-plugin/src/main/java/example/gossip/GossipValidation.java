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
package example.gossip;

import java.net.URI;
import java.net.URISyntaxException;

/** Shared lexical checks; never resolve advertised addresses during validation. */
final class GossipValidation {
    private GossipValidation() { }

    static boolean validId(String value) {
        return value != null && value.matches("[A-Za-z0-9._-]{1,128}");
    }

    static boolean validHost(String host) {
        if (host == null || host.isBlank() || host.length() > 253 || host.contains("[") || host.contains("]")) {
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
