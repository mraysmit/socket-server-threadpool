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

import java.io.IOException;
import java.util.List;

/** What a server asks of another when comparing stores: each call is one request and one reply. */
interface SyncLink {
    SyncResponder.RootReply syncRoot(Peer peer, String root) throws IOException;

    SyncResponder.DigestReply syncDigest(Peer peer, SyncResponder.DigestRequest request) throws IOException;

    /** Hands records over, for the other server to merge. */
    void apply(Peer peer, List<KvRecord> records) throws IOException;
}
