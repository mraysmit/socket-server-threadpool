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

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CommandProtocolKeepAliveTest {
    private static final UUID ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private static String request(String extra) {
        return "{\"version\":1,\"messageId\":\"" + ID + "\",\"instruction\":\"echo\",\"parameters\":{}" + extra + "}";
    }

    @Test
    void connectionFieldIsOptionalAndOnlyKeepAliveKeepsTheConnectionOpen() throws Exception {
        assertFalse(CommandProtocol.parse(request("")).keepAlive());
        assertFalse(CommandProtocol.parse(request(",\"connection\":\"close\"")).keepAlive());
        var parsed = CommandProtocol.parse(request(",\"connection\":\"keep-alive\""));
        assertTrue(parsed.keepAlive());
        assertEquals(ID, parsed.request().messageId());
        assertEquals("echo", parsed.request().instruction());
        assertEquals(CommandProtocol.parseRequest(request(",\"connection\":\"keep-alive\"")), parsed.request());
    }

    @Test
    void anyOtherConnectionValueIsInvalidAndKeepsTheMessageId() {
        for (String bad : new String[]{"\"forever\"", "\"Keep-Alive\"", "\"\"", "true", "1", "null", "{}"}) {
            var error = assertThrows(InvalidRequestException.class,
                    () -> CommandProtocol.parse(request(",\"connection\":" + bad)), bad);
            assertEquals(ID, error.messageId(), "The reply to a rejected request still names its message ID");
            assertTrue(error.getMessage().contains("keep-alive"), error.getMessage());
        }
    }

    @Test
    void unknownFieldsAreStillRejected() {
        var error = assertThrows(InvalidRequestException.class, () -> CommandProtocol.parse(request(",\"keepAlive\":true")));
        assertTrue(error.getMessage().contains("Unknown field"), error.getMessage());
    }
}
