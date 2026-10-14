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
package dev.mars.plugin;

import java.util.Map;

/** Startup-discovered provider. Instances are shared: implementations must be thread-safe. */
public interface CommandPlugin {
    /** Optional initialization after both TCP ports bind. */
    default void start(PluginContext context) throws Exception { }

    /** Optional shutdown: stop background work, retaining state needed by accepted commands. */
    default void stop() throws Exception { }

    /** A null instruction identifies a startup-only provider. */
    default String instruction() { return null; }

    /** Command handlers override this along with instruction(). */
    default Object execute(Map<String, Object> parameters) throws Exception {
        throw new UnsupportedOperationException("This plugin has no command handler");
    }
}
