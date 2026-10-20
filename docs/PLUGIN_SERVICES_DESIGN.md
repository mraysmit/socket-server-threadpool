# Plugin services: letting plugins call each other

| Document metadata | Details |
| --- | --- |
| Status | Proposal, for review. Nothing in this document is implemented. |
| Last updated | 2026-10-20 |
| Applies to | The host module (`dev.mars.plugin`, `dev.mars.server`) and every plugin |
| Part of | A set of three documents. This one covers how plugins call each other. [`REPLICATION_ENGINE_DESIGN.md`](REPLICATION_ENGINE_DESIGN.md) uses it to build a generic replication engine, and [`REPLICATION_PROFILES_DESIGN.md`](REPLICATION_PROFILES_DESIGN.md) applies the engine to key-value data and to configuration. |

## 1. Summary

Plugins cannot call each other today. The replication work needs it: the replication plugin must ask the gossip plugin who
the active peers are, and the key-value and configuration plugins must call the replication plugin.

This document proposes one small host addition: a **per-server service registry**, reached through `PluginContext`, where a
plugin publishes a typed Java interface and others look it up. Existing plugins keep working without change.

### Decisions already made

| # | Decision |
| --- | --- |
| 1 | A typed service registry on `PluginContext`. Not loopback commands, and not direct class access. |
| 2 | The shared interfaces live in the host module. |
| 3 | A service is looked up by its interface. A name is optional, for the rare case of several providers. |
| 4 | No hard start order. A consumer may start before its provider and is told when the service appears. A consumer runs on its own when the provider is absent. |
| 5 | Consumers stop before their providers. A call to a stopped service fails with a clear `IllegalStateException`. |
| 6 | One registry per server instance, never static. |
| 7 | A plugin API version. A plugin that needs a newer version than the host has fails at start, not mid-call. |
| 8 | Typed services only. There is no in-process `invoke` of another plugin's instruction. |
| 9 | Published services are thread-safe and callable from any thread, including virtual threads. |
| 10 | A service cannot restrict its callers. Restrictions belong on the write path, which for configuration is proving knowledge of a shared secret (profiles document, section 3.4). |
| 55 | The host knows the stop order from each plugin's declared `uses()`, not from observed lookups. A cycle is refused at start. |
| 56 | A plugin may receive a `RequestContext` with the sender's address, the connection ID and the message ID (section 5). It is needed to throttle failed authentication by sender address (profiles document, decision 40). |

## 2. What exists today

All of this is in the code now.

- [`CommandPlugin`](../src/main/java/dev/mars/plugin/CommandPlugin.java) has `start(PluginContext)`, `stop()`,
  `instruction()` and `execute(Map)`. One instruction belongs to one provider. Instances are shared by all connections.
- [`PluginContext`](../src/main/java/dev/mars/plugin/PluginContext.java) is a record of `serverId`, `port` and `adminPort`,
  and nothing else. It is built in `ServerInstance.start` and handed to `PluginRegistry.start`.
- Providers start concurrently on virtual threads after both ports bind, so no plugin can assume another is ready.
- `ServerInstance.requestStop` closes the main listener first, then calls `PluginRegistry.stop`, then the admin monitor.
- Each server loads its plugin jars into one shared class loader, so two plugins in one server can see the same classes,
  while two servers in one JVM (as in the test harness) have separate loaders and separate plugin instances.

## 3. Goals and non-goals

Goals:

1. Let a plugin publish a Java interface for others to use, and find the interfaces others published.
2. Make it safe for plugins to start and stop in any order, and for a missing provider to be a normal case.
3. Keep every existing plugin working unchanged.
4. Support the harness running many servers in one JVM, each with its own services.

Non-goals:

1. Dependency injection, a plugin marketplace, or a general component model.
2. Isolating plugins from each other. They are trusted code in the same JVM (decision 10).
3. Calling another plugin's *instruction* without a socket (decision 8).
4. Remote services. A service is an object in the same server.
5. Extending the admin port with plugin commands. The admin port
   ([`ShutdownMonitor`](../src/main/java/dev/mars/server/ShutdownMonitor.java)) is a plain-text, single-threaded protocol
   with three fixed words, and it listens on every network interface exactly as the main port does. Extending it would add
   host work without adding a security boundary. Authority to change configuration is proved by a shared-secret
   MAC on the request instead (profiles document, section 3.4).

## 4. The registry

### 4.1 Interface

```java
public interface PluginServices {
    /** Makes the service available to other plugins in this server. One provider per interface unless a name is given. */
    <T> Registration publish(Class<T> type, T service);
    <T> Registration publish(Class<T> type, String name, T service);

    /** The service now, if some plugin has published it. */
    <T> Optional<T> find(Class<T> type);
    <T> Optional<T> find(Class<T> type, String name);

    /**
     * Calls the listener with the service when it is, or becomes, available, and again if it is replaced; calls
     * {@code unavailable} when it is withdrawn. Calls run on a virtual thread, never while the registry holds a lock.
     */
    <T> Subscription whenAvailable(Class<T> type, ServiceListener<T> listener);

    interface Registration extends AutoCloseable { @Override void close(); }
    interface Subscription extends AutoCloseable { @Override void close(); }
    interface ServiceListener<T> { void available(T service); default void unavailable(T service) { } }
}
```

`PluginContext` gains an accessor `services()` returning the server's `PluginServices`. The record's present three-argument
constructor stays, so code that builds a `PluginContext` for a test keeps compiling, and it gets an empty registry.

Using a service after its provider withdrew it throws `IllegalStateException("Service <type> is no longer available")`.
A caller that holds on to a service object (rather than looking it up each time) should treat that exception as "provider
gone" and fall back or wait for `available` again.

### 4.2 Publishing and withdrawing

A provider publishes in its `start` and closes the `Registration` in its `stop`. Publishing a second service for the same
interface and name fails, so two providers cannot silently shadow each other. The host closes any registration a plugin
left open when that plugin stops.

### 4.3 Consumers that start first

A consumer calls `whenAvailable` in its `start` and returns at once. It does not block waiting for a provider, which would
hold up the concurrent start of everything else. When the provider publishes, the listener runs, on a virtual thread. Until
then the consumer behaves as it does without the provider. This is decision 4: for example, the key-value plugin works as a
single-server store until a replication service appears.

### 4.4 Stop order

A plugin may declare what it uses:

```java
default Set<Class<?>> uses() { return Set.of(); }
```

At stop, the host stops plugins so that a plugin that uses a service stops before the plugin that publishes it. A cycle in
the declarations is refused when the server starts, with a message naming the plugins. A plugin that looks up a service
without declaring it still works, but it may be stopped after its provider and then sees the `IllegalStateException`
above, so declaring is the supported way. `uses()` is also used at start to log a warning if a declared service is never
published within the server's first minute, which is the usual symptom of a missing plugin jar.

### 4.5 Threading

- Every published service must be thread-safe and must not block for long on a caller's thread. Plugin instances are
  already shared across connections, so this is the existing rule, made explicit.
- Listener callbacks run on virtual threads and may block.
- The host never calls a listener while holding the registry's lock, so a listener may call `find` or `publish`.

### 4.6 One registry per server

A registry belongs to one `ServerInstance` and is created with it. There is no static state. Two servers in one JVM, as in
the test harness, have independent registries, so an `alpha-Server-001` replication service never receives a request meant
for `alpha-Server-002`.

### 4.7 API version

The host has an integer API version, and a plugin may declare the lowest version it needs:

```java
default int requiredApiVersion() { return 1; }
```

A plugin that needs a higher version than the host provides is refused at start with a message naming both numbers. This
turns "the plugin was built against a newer host" from a `NoSuchMethodError` in the middle of a request into a clear
failure at startup. This feature and the registry are version 2 of the host API; every existing plugin
declares nothing and keeps the default of 1.

## 5. Request context

### 5.1 Why

A plugin's `execute(Map)` receives only the parameters. It cannot tell who sent the request. The configuration plugin has to
throttle repeated failed authentication by sender address (profiles document, decision 40), and the host already knows the
address: `WorkerRunnable` has the client socket and writes the client address into every log line. It only has to be passed
on.

### 5.2 Interface

```java
public record RequestContext(String serverId, long connectionId, UUID messageId, InetSocketAddress remoteAddress) { }

public interface CommandPlugin {
    // existing
    default Object execute(Map<String, Object> parameters) throws Exception { throw new UnsupportedOperationException(...); }

    /** The host calls this one. Its default delegates to the method above, so existing plugins need no change. */
    default Object execute(Map<String, Object> parameters, RequestContext context) throws Exception {
        return execute(parameters);
    }
}
```

`connectionId` and `messageId` are the same values the reply carries and the server log prints, so a plugin can correlate its
own log lines with the host's. `remoteAddress` is the peer's address as the server sees it, which for a pooled keep-alive
connection is the same on every request of that connection. It is the address of the immediate peer, so a sender behind a proxy
appears as the proxy.

A plugin must not keep the context beyond the call.

## 6. Services published first

| Interface | Published by | Purpose |
| --- | --- | --- |
| `MembershipView` | the gossip plugin | The peers of this server and how it rates them. Used by the replication plugin. |
| `ReplicationService` | the replication plugin | Namespaces of replicated data. Used by the key-value and configuration plugins. Defined in the engine document. |

A sketch of `MembershipView`, which lives in the host module so that plugins do not depend on the gossip plugin's jar:

```java
public interface MembershipView {
    Optional<String> swarmId();
    Member self();
    /** A consistent snapshot of every member this server knows, with this server's own rating of each. */
    List<Member> members();
    /** Called when any member's rating changes, or a member is added or removed. */
    Subscription onChange(Runnable listener);

    enum Rating { CANDIDATE, ALIVE, SUSPECT, DEAD, LEFT }
    record Member(String serverId, String bootId, String host, int port, Rating rating) { }
}
```

The gossip plugin builds this from its `MembershipTable` snapshot, which already holds all of it. Publishing metadata at
runtime (used by an optional step in the engine document) would be a further method on a second interface, added when it
is needed.

## 7. Compatibility

- Existing plugins implement the same `CommandPlugin` methods and run unchanged. The new methods (`uses`,
  `requiredApiVersion`) are defaults.
- A plugin built for this API cannot run on an older host that lacks the registry. `requiredApiVersion` makes that a clear
  error on hosts that know about it, and a `NoSuchMethodError` on older ones, which is unavoidable.
- The test harness and the gossip plugin's tests build `PluginContext` with three arguments. That keeps working.

## 8. Test plan

Unit tests of the registry, with no sockets:

1. `publish` then `find` returns the service; a second `publish` of the same interface fails; a named publish allows a
   second one.
2. `whenAvailable` called before the publish fires on publish; called after, it fires at once; it fires again on
   replacement and calls `unavailable` on withdrawal; closing the subscription stops callbacks.
3. A call through a withdrawn service throws `IllegalStateException` with the type named.
4. A listener may call `find` and `publish` from inside the callback without deadlock.
5. Two registries are independent.
6. A plugin that leaves its registration open has it closed when the plugin stops.
7. `uses()` orders stops so that consumers precede providers, refuses a cycle with the plugins named, and warns about a
   service that never appears.
8. `requiredApiVersion` above the host's refuses the plugin at start, naming both versions.
9. Concurrent publish, find and withdraw from many threads leave a consistent registry.
9a. A plugin that implements only the one-argument `execute` still runs, and the two-argument default delegates to it.
9b. `RequestContext` carries the right server ID, connection ID, message ID and remote address for a loopback request, and the
    same address on a second request over one kept-open connection.

Server tests with real ports:

10. A plugin pair in one server where the consumer starts first receives the service when it appears.
11. Two servers in one JVM, each with the same two plugins, do not see each other's services.

## 9. Risks and review outcomes

Risks, and how the design deals with them:

1. **A larger host API to keep stable.** Once plugins publish interfaces, those interfaces are a compatibility promise.
   Keeping them in the host module and versioning them (decision 7) is the mitigation. The first set is deliberately small.
2. **Listener threads.** Many `whenAvailable` calls at start create short-lived virtual threads. This is cheap, but worth
   checking against the 100-server harness runs.
3. **Where `MembershipView` is defined.** In the host module, so the replication plugin does not need the gossip jar. The
   cost is that the host knows the word "rating". The values are the ones the gossip plugin already documents.
4. **`RequestContext` is a second change to the plugin method.** It is a default method, so no existing plugin changes, but
   the host's dispatch path must pass the address through.

Review outcomes:

| Point | Outcome |
| --- | --- |
| How the host orders stops: declared `uses()`, or derived from lookups | Declared `uses()` (decision 55). A plugin that forgets to declare may stop after its provider and then sees the `IllegalStateException` in 4.1. |
| Whether plugins can learn the sender's address | Yes, through `RequestContext` (decision 56). |

## 10. Implementation plan

This document describes the target. The order of work is in [`REPLICATION_PLAN.md`](REPLICATION_PLAN.md). The service registry,
`RequestContext` and the API version check are built in Phase 4, after key-value replication works without them (Phases 1 to 3),
and the gossip plugin publishes `MembershipView` in the same phase.
