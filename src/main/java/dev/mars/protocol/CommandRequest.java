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
package dev.mars.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record CommandRequest(int version, UUID messageId, String instruction, Map<String, Object> parameters) {
    public CommandRequest {
        if (version != 1) { throw new IllegalArgumentException("Unsupported protocol version"); }
        if (messageId == null) { throw new IllegalArgumentException("Message ID is required"); }
        if (instruction == null || !instruction.matches("[A-Za-z][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("Instruction must be a nonblank command name");
        }
        if (parameters == null) { throw new IllegalArgumentException("Parameters must be an object"); }
        parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
    }
}
