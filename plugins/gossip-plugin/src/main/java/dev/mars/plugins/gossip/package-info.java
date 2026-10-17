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

/**
 * A gossip membership plugin: peers exchange member records in periodic rounds.
 *
 * <h2>Lock order</h2>
 * The plugin's locks are taken in this order, outermost first. While holding one, a thread takes only locks further
 * down the list, never one above it, so no two threads can each wait for a lock the other holds.
 * <ol>
 *   <li>{@code GossipPlugin}, while it starts and stops.</li>
 *   <li>{@code GossipRuntime}: its close lock, then its own monitor. Under these it reaches the transport, the
 *       selector, the table and the DNS resolver.</li>
 *   <li>{@code GossipTransport}, which merges a reply into the table under its own lock, and {@code PeerSelector}.
 *       They are never held together.</li>
 *   <li>{@code MembershipTable}, which takes no other lock while holding its own.</li>
 *   <li>{@code GossipBatchSelector}, {@code GossipDnsResolver}, {@code GossipMetrics} and the transport's map of
 *       version-1 peers.</li>
 *   <li>{@code GossipLog}.</li>
 * </ol>
 * The order holds because no class keeps a reference to one above it, which {@code GossipLockOrderTest} checks. A
 * callback passed down the order must also reach only downwards: the snapshot supplier that the runtime passes to
 * {@code PeerSelector.resolved} is called under the selector's lock, and takes only the table's.
 */
package dev.mars.plugins.gossip;
