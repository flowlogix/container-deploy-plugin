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
import jakarta.websocket.OnClose;
import jakarta.websocket.OnMessage;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.java.Log;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Log
@ServerEndpoint(value = LiveReloadProtocol.WEBSOCKET_PATH)
public class ReloadEndpoint {
    private static final ConcurrentMap<String, Set<Session>> SESSIONS = new ConcurrentHashMap<>();

    @OnMessage
    public void onMessage(String message, Session session) {
        register(SESSIONS, message, session);
    }

    @OnClose
    public void onClose(Session session) {
        unregister(SESSIONS, session);
    }

    static void register(ConcurrentMap<String, Set<Session>> sessionsByApplication,
                         String application, Session session) {
        sessionsByApplication.compute(application, (key, sessions) -> {
            Set<Session> updatedSessions = sessions == null ? new CopyOnWriteArraySet<>() : sessions;
            updatedSessions.add(session);
            return updatedSessions;
        });
    }

    static void unregister(ConcurrentMap<String, Set<Session>> sessionsByApplication, Session session) {
        sessionsByApplication.keySet().forEach(application ->
                sessionsByApplication.compute(application, (key, sessions) -> {
                    if (sessions == null) {
                        return null;
                    }
                    sessions.remove(session);
                    return sessions.isEmpty() ? null : sessions;
                }));
    }

    static boolean broadcastReload(String application, ReloadStatus status) {
        log.fine("Broadcasting %s to Web LiveReload application %s. Registered applications: %s".formatted(
                status.getDescription(), application, registeredApplications()));
        boolean messageSent = false;
        for (Session session : sessions(application)) {
            log.fine("Sending %s to Web LiveReload application %s session %s".formatted(
                    status.getDescription(), application, session.getId()));
            try {
                session.getBasicRemote().sendText(status.getDescription());
                messageSent = true;
            } catch (IOException e) {
                log.fine("Failed to send %s to Web LiveReload application %s session %s: %s".formatted(
                        status.getDescription(), application, session.getId(), e.getMessage()));
            }
        }
        return messageSent;
    }

    static Set<Session> sessions(String application) {
        return Optional.ofNullable(SESSIONS.get(application)).orElse(Set.of());
    }

    static Set<String> registeredApplications() {
        return Set.copyOf(SESSIONS.keySet());
    }

    static void shutdown() {
        SESSIONS.values().stream().flatMap(Set::stream).distinct().forEach(ReloadEndpoint::shutdown);
    }

    private static void shutdown(Session session) {
        try (session) {
            session.getBasicRemote().sendText(LiveReloadProtocol.SHUTDOWN_MESSAGE);
        } catch (IOException e) {
            log.fine("Failed to shut down Web LiveReload session %s: %s".formatted(
                    session.getId(), e.getMessage()));
        }
    }
}
