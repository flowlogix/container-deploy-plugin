/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.flowlogix.plugins.livereload;

import jakarta.websocket.Session;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.ResponseBuilder;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.util.Set;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class LiveReloadTest {
    @Mock
    Set<Session> mockSessions;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    Session session;

    @Test
    @SuppressWarnings("checkstyle:MagicNumber")
    void broadcastReloadDoesNotFailWhenNoSessions() throws IOException {
        try (MockedStatic<ReloadEndpoint> reloadMock = mockStatic(ReloadEndpoint.class)) {
            reloadMock.when(() -> ReloadEndpoint.sessions(any())).thenReturn(Set.of());
            reloadMock.when(() -> ReloadEndpoint.broadcastReload(any())).thenCallRealMethod();

            ReloadEndpoint.broadcastReload("myapp");
            verifyNoMoreInteractions(mockSessions);
        }
    }

    @Test
    @SuppressWarnings("checkstyle:MagicNumber")
    void broadcastReloadDoesNotFailWhenOneSession() throws IOException {
        try (MockedStatic<ReloadEndpoint> reloadMock = mockStatic(ReloadEndpoint.class)) {
            reloadMock.when(() -> ReloadEndpoint.sessions(any())).thenReturn(Set.of(session));
            reloadMock.when(() -> ReloadEndpoint.broadcastReload(any())).thenCallRealMethod();

            ReloadEndpoint.broadcastReload("myapp");
            verify(session).getId();
            verify(session.getBasicRemote()).sendText("reload");
            verify(session, times(2)).getBasicRemote();
            verifyNoMoreInteractions(mockSessions, session);
        }
    }

    @Nested
    class ReloadTriggerTest {
        @Mock
        Response response;
        @Mock
        ResponseBuilder responseBuilder;

        @Test
        void reloadReturnsOkWhenBroadcastSucceeds() throws Exception {
            try (MockedStatic<ReloadEndpoint> reloadMock = mockStatic(ReloadEndpoint.class);
                 MockedStatic<Response> responseMock = mockStatic(Response.class)) {
                responseMock.when(Response::ok).thenReturn(responseBuilder);
                when(responseBuilder.build()).thenReturn(response);
                when(response.getStatus()).thenReturn(Response.Status.OK.getStatusCode());

                ReloadTrigger trigger = new ReloadTrigger();
                Response actualResponse = trigger.reload("abc");

                assertThat(actualResponse.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
            }
        }
    }
}
