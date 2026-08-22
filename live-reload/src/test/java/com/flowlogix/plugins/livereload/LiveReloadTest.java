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

import com.flowlogix.plugins.common.ReloadStatus;
import jakarta.websocket.Session;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.ResponseBuilder;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

    @ParameterizedTest
    @EnumSource(ReloadStatus.class)
    @SuppressWarnings("checkstyle:MagicNumber")
    void broadcastDoesNotFailWhenNoSessions(ReloadStatus status) throws IOException {
        try (MockedStatic<ReloadEndpoint> reloadMock = mockStatic(ReloadEndpoint.class)) {
            reloadMock.when(() -> ReloadEndpoint.sessions(any())).thenReturn(Set.of());
            reloadMock.when(() -> ReloadEndpoint.broadcastReload(any(), any())).thenCallRealMethod();

            ReloadEndpoint.broadcastReload("myapp", status);
            verifyNoMoreInteractions(mockSessions);
        }
    }

    @ParameterizedTest
    @EnumSource(ReloadStatus.class)
    @SuppressWarnings("checkstyle:MagicNumber")
    void broadcastDoesNotFailWhenOneSession(ReloadStatus status) throws IOException {
        try (MockedStatic<ReloadEndpoint> reloadMock = mockStatic(ReloadEndpoint.class)) {
            reloadMock.when(() -> ReloadEndpoint.sessions(any())).thenReturn(Set.of(session));
            reloadMock.when(() -> ReloadEndpoint.broadcastReload(any(), any())).thenCallRealMethod();

            ReloadEndpoint.broadcastReload("myapp", status);
            verify(session).getId();
            verify(session.getBasicRemote()).sendText(status.getDescription());
            verify(session, times(2)).getBasicRemote();
            verifyNoMoreInteractions(mockSessions, session);
        }
    }

    @Test
    @SuppressWarnings("checkstyle:MagicNumber")
    void broadcastReloadDelegatesToReloadStatusReload() throws IOException {
        try (MockedStatic<ReloadEndpoint> reloadMock = mockStatic(ReloadEndpoint.class)) {
            reloadMock.when(() -> ReloadEndpoint.sessions(any())).thenReturn(Set.of(session));
            reloadMock.when(() -> ReloadEndpoint.broadcastReload(any(), any())).thenCallRealMethod();

            ReloadEndpoint.broadcastReload("myapp", ReloadStatus.RELOAD);
            verify(session.getBasicRemote()).sendText(ReloadStatus.RELOAD.getDescription());
            verify(session).getId();
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

        @ParameterizedTest
        @EnumSource(ReloadStatus.class)
        void reloadReturnsOkWhenBroadcastSucceeds(ReloadStatus status) throws Exception {
            try (MockedStatic<ReloadEndpoint> reloadMock = mockStatic(ReloadEndpoint.class);
                 MockedStatic<Response> responseMock = mockStatic(Response.class)) {
                responseMock.when(Response::ok).thenReturn(responseBuilder);
                responseMock.when(() -> Response.status(Response.Status.EXPECTATION_FAILED)).thenReturn(responseBuilder);
                when(responseBuilder.build()).thenReturn(response);
                when(response.getStatus()).thenReturn(Response.Status.OK.getStatusCode());

                ReloadTrigger trigger = new ReloadTrigger();
                Response actualResponse = trigger.reload("abc", status.getDescription());

                assertThat(actualResponse.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
                reloadMock.verify(() -> ReloadEndpoint.broadcastReload("abc", status));
            }
        }
    }
}
