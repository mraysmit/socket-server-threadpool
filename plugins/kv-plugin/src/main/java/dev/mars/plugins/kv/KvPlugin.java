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

import dev.mars.plugin.CommandPlugin;
import dev.mars.plugin.InvalidParametersException;
import dev.mars.plugin.PluginContext;
import dev.mars.plugin.PluginParameters;
import dev.mars.protocol.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * An in-memory store of key-value pairs, one per server, behind the {@code kv} instruction. Its operations are
 * {@code put}, {@code get}, {@code delete} and {@code list}; a value may be any JSON value. The store accepts
 * commands only while the plugin is running, and is lost when the server stops.
 */
public final class KvPlugin implements CommandPlugin {
    /** The longest key, in characters. */
    static final int MAX_KEY_LENGTH = 256;
    /** The largest value, as UTF-8 bytes of its JSON. */
    static final int MAX_VALUE_BYTES = 16 * 1024;
    /** The most pairs one server holds, so clients cannot exhaust its memory. */
    static final int MAX_PAIRS = 1000;
    private static final String OPERATION = "operation";
    private static final String KEY = "key";
    private static final String VALUE = "value";

    private enum State { NEW, RUNNING, STOPPED }

    /**
     * Sorted, so {@code list} returns keys in order. Each value is held as its JSON text, which gives its exact size
     * and is a copy that no caller can change afterwards.
     */
    private final TreeMap<String, String> pairs = new TreeMap<>();
    private volatile State state = State.NEW;

    @Override public String instruction() { return "kv"; }

    @Override public void start(PluginContext context) { state = State.RUNNING; }

    @Override public void stop() { state = State.STOPPED; }

    @Override public Map<String, Object> execute(Map<String, Object> parameters) throws InvalidParametersException {
        switch (state) {
            case NEW -> throw new IllegalStateException("The key-value store has not started");
            case STOPPED -> throw new IllegalStateException("The key-value store has stopped");
            case RUNNING -> { }
        }
        return switch (PluginParameters.string(parameters, OPERATION)) {
            case "put" -> {
                PluginParameters.requireKeys(parameters, OPERATION, KEY, VALUE);
                yield put(key(parameters), encode(parameters.get(VALUE)));
            }
            case "get" -> {
                PluginParameters.requireKeys(parameters, OPERATION, KEY);
                yield get(key(parameters));
            }
            case "delete" -> {
                PluginParameters.requireKeys(parameters, OPERATION, KEY);
                yield delete(key(parameters));
            }
            case "list" -> {
                PluginParameters.requireKeys(parameters, OPERATION);
                yield list();
            }
            default -> throw new InvalidParametersException("Supported operations: put, get, delete, list");
        };
    }

    /** Creates the pair, or replaces its value; a new key is refused once the store is full. */
    private synchronized Map<String, Object> put(String key, String value) throws InvalidParametersException {
        boolean exists = pairs.containsKey(key);
        if (!exists && pairs.size() >= MAX_PAIRS) {
            throw new InvalidParametersException("The store is full: it holds at most " + MAX_PAIRS + " pairs");
        }
        pairs.put(key, value);
        return reply(KEY, key, "created", !exists);
    }

    private Map<String, Object> get(String key) {
        String value;
        synchronized (this) { value = pairs.get(key); }
        if (value == null) { return reply(KEY, key, "found", false); }
        // A JSON null value is stored as the text "null", so a stored null is still found.
        return reply(KEY, key, "found", true, VALUE, decode(value));
    }

    private synchronized Map<String, Object> delete(String key) {
        return reply(KEY, key, "deleted", pairs.remove(key) != null);
    }

    private synchronized Map<String, Object> list() {
        var keys = List.copyOf(pairs.keySet());
        return reply("keys", keys, "count", keys.size());
    }

    /** A reply whose fields stay in the order given, which is the order a reader sees them in. */
    private static Map<String, Object> reply(Object... namesAndValues) {
        var reply = new LinkedHashMap<String, Object>();
        for (int i = 0; i < namesAndValues.length; i += 2) { reply.put((String) namesAndValues[i], namesAndValues[i + 1]); }
        return reply;
    }

    private static String key(Map<String, Object> parameters) throws InvalidParametersException {
        String key = PluginParameters.string(parameters, KEY);
        if (key.isEmpty() || key.length() > MAX_KEY_LENGTH) {
            throw new InvalidParametersException("Parameter 'key' must have 1 to " + MAX_KEY_LENGTH + " characters");
        }
        return key;
    }

    private static String encode(Object value) throws InvalidParametersException {
        String json;
        try { json = Json.write(value); }
        catch (IOException e) { throw new InvalidParametersException("Parameter 'value' must be a JSON value"); }
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_BYTES) {
            throw new InvalidParametersException("Parameter 'value' exceeds " + MAX_VALUE_BYTES + " bytes of JSON");
        }
        return json;
    }

    /** Reads back JSON this store wrote itself, so it cannot fail. */
    private static Object decode(String json) {
        try { return Json.value(Json.read(json)); }
        catch (IOException e) { throw new UncheckedIOException("Stored value is not JSON", e); }
    }
}
