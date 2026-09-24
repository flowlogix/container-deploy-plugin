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
import com.flowlogix.plugins.common.LiveReloadProtocol;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.ResponseBuilder;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class LiveReloadTest {
    private static final int THREAD_TIMEOUT_SECONDS = 5;

    @Mock
    Set<Session> mockSessions;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    Session session;

    @Test
    @SuppressWarnings("checkstyle:MagicNumber")
    void preservesRegistrationAddedWhileEmptySessionSetIsRemoved() throws Exception {
        String application = "myapp";
        Session closingSession = session;
        Session connectingSession = mock(Session.class);
        CountDownLatch emptySetObserved = new CountDownLatch(1);
        CountDownLatch finishCleanup = new CountDownLatch(1);
        Set<Session> sessions = new CopyOnWriteArraySet<>() {
            @Override
            public boolean isEmpty() {
                boolean empty = super.isEmpty();
                emptySetObserved.countDown();
                try {
                    if (!finishCleanup.await(THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out waiting to finish session cleanup");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return empty;
            }
        };
        sessions.add(closingSession);
        ConcurrentMap<String, Set<Session>> sessionsByApplication = new ConcurrentHashMap<>();
        sessionsByApplication.put(application, sessions);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread closeThread = new Thread(() -> runAndCapture(
                () -> ReloadEndpoint.unregister(sessionsByApplication, closingSession), failure));
        Thread registrationThread = new Thread(() -> runAndCapture(
                () -> ReloadEndpoint.register(sessionsByApplication, application, connectingSession), failure));

        try {
            closeThread.start();
            assertThat(emptySetObserved.await(THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            registrationThread.start();
            awaitBlockedOrTerminated(registrationThread);
        } finally {
            finishCleanup.countDown();
            closeThread.join(TimeUnit.SECONDS.toMillis(THREAD_TIMEOUT_SECONDS));
            registrationThread.join(TimeUnit.SECONDS.toMillis(THREAD_TIMEOUT_SECONDS));
        }

        assertThat(failure.get()).isNull();
        assertThat(closeThread.isAlive()).isFalse();
        assertThat(registrationThread.isAlive()).isFalse();
        assertThat(sessionsByApplication.get(application)).containsExactly(connectingSession);
    }

    private static void awaitBlockedOrTerminated(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(THREAD_TIMEOUT_SECONDS);
        while (thread.isAlive() && thread.getState() != Thread.State.BLOCKED
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(thread.isAlive() && thread.getState() != Thread.State.BLOCKED).isFalse();
    }

    private static void runAndCapture(Runnable action, AtomicReference<Throwable> failure) {
        try {
            action.run();
        } catch (Throwable throwable) {
            failure.compareAndSet(null, throwable);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://dev.example.com",
        "http://DEV.EXAMPLE.COM:80",
        "https://dev.example.com:443"
    })
    @SuppressWarnings("checkstyle:MagicNumber")
    void acceptsSameWebSocketOrigins(String origin) {
        String scheme = origin.startsWith("https") ? "https" : "http";
        int port = "https".equals(scheme) ? 443 : 80;

        assertThat(SameOriginFilter.isSameOrigin(origin, scheme, "dev.example.com", port)).isTrue();
    }

    @Test
    @SuppressWarnings("checkstyle:MagicNumber")
    void acceptsSameIpv6WebSocketOrigin() {
        assertThat(SameOriginFilter.isSameOrigin(
                "https://[2001:db8::1]:8443", "https", "2001:db8::1", 8443)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "null",
        "file://dev.example.com",
        "http://evil.example.com",
        "not an origin"
    })
    @SuppressWarnings("checkstyle:MagicNumber")
    void rejectsDifferentOrInvalidWebSocketOrigins(String origin) {
        assertThat(SameOriginFilter.isSameOrigin(
                origin, "http", "dev.example.com", 80)).isFalse();
    }

    @Test
    @SuppressWarnings("checkstyle:MagicNumber")
    void rejectsDifferentWebSocketOriginSchemeOrPort() {
        assertThat(SameOriginFilter.isSameOrigin(
                "https://dev.example.com", "http", "dev.example.com", 80)).isFalse();
        assertThat(SameOriginFilter.isSameOrigin(
                "http://dev.example.com:8081", "http", "dev.example.com", 8080)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(ReloadStatus.class)
    @SuppressWarnings("checkstyle:MagicNumber")
    void broadcastDoesNotFailWhenNoSessions(ReloadStatus status) throws IOException {
        try (MockedStatic<ReloadEndpoint> reloadMock = mockStatic(ReloadEndpoint.class)) {
            reloadMock.when(() -> ReloadEndpoint.sessions(any(), any())).thenReturn(Set.of());
            reloadMock.when(() -> ReloadEndpoint.broadcastReload(any(), any())).thenCallRealMethod();

            boolean messageSent = ReloadEndpoint.broadcastReload("myapp", status);

            assertThat(messageSent).isFalse();
            verifyNoMoreInteractions(mockSessions);
        }
    }

    @ParameterizedTest
    @EnumSource(ReloadStatus.class)
    @SuppressWarnings("checkstyle:MagicNumber")
    void broadcastDoesNotFailWhenOneSession(ReloadStatus status) throws IOException {
        try (MockedStatic<ReloadEndpoint> reloadMock = mockStatic(ReloadEndpoint.class)) {
            reloadMock.when(() -> ReloadEndpoint.sessions(any(), any())).thenReturn(Set.of(session));
            reloadMock.when(() -> ReloadEndpoint.broadcastReload(any(), any())).thenCallRealMethod();

            boolean messageSent = ReloadEndpoint.broadcastReload("myapp", status);

            assertThat(messageSent).isTrue();
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
            reloadMock.when(() -> ReloadEndpoint.sessions(any(), any())).thenReturn(Set.of(session));
            reloadMock.when(() -> ReloadEndpoint.broadcastReload(any(), any())).thenCallRealMethod();

            boolean messageSent = ReloadEndpoint.broadcastReload("myapp", ReloadStatus.RELOAD);

            assertThat(messageSent).isTrue();
            verify(session.getBasicRemote()).sendText(ReloadStatus.RELOAD.getDescription());
            verify(session).getId();
            verify(session, times(2)).getBasicRemote();
            verifyNoMoreInteractions(mockSessions, session);
        }
    }

    @Test
    void shutdownContinuesAfterSessionFailure() throws IOException {
        Session failedSession = mock(Session.class);
        RemoteEndpoint.Basic failedRemote = mock(RemoteEndpoint.Basic.class);
        Session activeSession = mock(Session.class);
        RemoteEndpoint.Basic activeRemote = mock(RemoteEndpoint.Basic.class);
        when(failedSession.getBasicRemote()).thenReturn(failedRemote);
        when(activeSession.getBasicRemote()).thenReturn(activeRemote);
        when(failedSession.getId()).thenReturn("failed");
        org.mockito.Mockito.doThrow(new IOException("session already closed"))
                .when(failedRemote).sendText(LiveReloadProtocol.SHUTDOWN_MESSAGE);
        ConcurrentMap<String, Set<Session>> sessionsByApplication = new ConcurrentHashMap<>();
        ReloadEndpoint.register(sessionsByApplication, "first-app", failedSession);
        ReloadEndpoint.register(sessionsByApplication, "second-app", activeSession);

        ReloadEndpoint.shutdown(sessionsByApplication);

        verify(failedSession).close();
        verify(activeRemote).sendText(LiveReloadProtocol.SHUTDOWN_MESSAGE);
        verify(activeSession).close();
        assertThat(ReloadEndpoint.registeredApplications(sessionsByApplication))
                .containsExactlyInAnyOrder("first-app", "second-app");
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
                reloadMock.when(() -> ReloadEndpoint.broadcastReload("abc", status)).thenReturn(true);

                ReloadTrigger trigger = new ReloadTrigger();
                Response actualResponse = trigger.reload("abc", status.getDescription());

                assertThat(actualResponse.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
                reloadMock.verify(() -> ReloadEndpoint.broadcastReload("abc", status));
            }
        }

        @ParameterizedTest
        @EnumSource(ReloadStatus.class)
        void reloadReturnsExpectationFailedWhenBroadcastReachesNoBrowsers(ReloadStatus status) throws Exception {
            try (MockedStatic<ReloadEndpoint> reloadMock = mockStatic(ReloadEndpoint.class);
                 MockedStatic<Response> responseMock = mockStatic(Response.class)) {
                responseMock.when(() -> Response.status(Response.Status.EXPECTATION_FAILED))
                        .thenReturn(responseBuilder);
                when(responseBuilder.entity("No browser sessions registered for application 'abc'. "
                        + "Registered applications: [other]")).thenReturn(responseBuilder);
                when(responseBuilder.build()).thenReturn(response);
                when(response.getStatus()).thenReturn(Response.Status.EXPECTATION_FAILED.getStatusCode());
                reloadMock.when(() -> ReloadEndpoint.broadcastReload("abc", status)).thenReturn(false);
                reloadMock.when(() -> ReloadEndpoint.registeredApplications(any())).thenReturn(Set.of("other"));

                ReloadTrigger trigger = new ReloadTrigger();
                Response actualResponse = trigger.reload("abc", status.getDescription());

                assertThat(actualResponse.getStatus()).isEqualTo(Response.Status.EXPECTATION_FAILED.getStatusCode());
                reloadMock.verify(() -> ReloadEndpoint.broadcastReload("abc", status));
                reloadMock.verify(() -> ReloadEndpoint.registeredApplications(any()));
                responseMock.verify(() -> Response.status(Response.Status.EXPECTATION_FAILED));
                verify(responseBuilder).entity("No browser sessions registered for application 'abc'. "
                        + "Registered applications: [other]");
                verify(responseBuilder).build();
                verify(response).getStatus();
                verifyNoInteractions(mockSessions);
            }
        }
    }
}
