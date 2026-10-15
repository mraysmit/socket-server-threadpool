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
package gossip.fixture;

/** Process-level fixture for malformed and nonresponsive resolver replies. */
public final class DnsProcessFixture {
    public static void main(String[] args) throws Exception {
        if (args[0].startsWith("wait")) {
            while (!java.nio.file.Files.exists(java.nio.file.Path.of(args[1]))) { Thread.sleep(5); }
            System.out.println("GOSSIP_DNS_V1\n127.0.0.1");
            return;
        }
        if (args[0].startsWith("stall")) { Thread.sleep(300_000); }
        else if (args[0].startsWith("bad")) { System.out.println("GOSSIP_DNS_V1\nnot-an-address"); }
        else if (args[0].startsWith("fail")) { System.exit(1); }
        else { System.out.println("GOSSIP_DNS_V1\n127.0.0.1\n::1"); }
    }
}
