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

import jakarta.websocket.OnClose;
import jakarta.websocket.OnMessage;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import lombok.SneakyThrows;
import lombok.extern.java.Log;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Log
@ServerEndpoint(value = "/livereload")
public class ReloadEndpoint {
    private static final Map<String, Set<Session>> SESSIONS = new ConcurrentHashMap<>();

    @OnMessage
    public void onMessage(String message, Session session) {
        SESSIONS.computeIfAbsent(message, var -> new CopyOnWriteArraySet<>()).add(session);
    }

    @OnClose
    public void onClose(Session session) {
        SESSIONS.forEach((var, value) -> value.remove(session));
        SESSIONS.entrySet().stream()
                .filter(entry -> entry.getValue().isEmpty())
                .map(Map.Entry::getKey) .distinct()
                .forEach(SESSIONS::remove);
    }

    public static void broadcastReload(String application) throws IOException {
        log.fine("broadcasting reload endpoint %s".formatted(application));
        for (Session session : sessions(application)) {
            log.fine("Reloading Web LiveReload application %s session %s".formatted(application, session.getId()));
            session.getBasicRemote().sendText("reload");
        }
    }

    static Set<Session> sessions(String application) {
        return Optional.ofNullable(SESSIONS.get(application)).orElse(Set.of());
    }

    static void shutdown() {
        SESSIONS.values().stream().flatMap(Set::stream).distinct().forEach(ReloadEndpoint::shutdown);
    }

    @SneakyThrows(IOException.class)
    private static void shutdown(Session session) {
        session.getBasicRemote().sendText("shutdown");
        session.close();
    }
}
