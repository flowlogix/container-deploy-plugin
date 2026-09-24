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
package com.flowlogix.plugins.common;

/**
 * Wire-level values shared by the LiveReload client, endpoint and Maven mojo.
 */
@SuppressWarnings("checkstyle:InterfaceIsType")
public sealed interface LiveReloadProtocol permits LiveReloadProtocol.NONE {
    /** Version of the LiveReload helper application. */
    String LIVE_RELOAD_HELPER_VERSION = "1.5.1";

    /** Context root used when deploying the LiveReload helper application. */
    String CONTEXT_ROOT = "flowlogix-livereload";
    /** WebSocket path used by the browser client. */
    String WEBSOCKET_PATH = "/livereload";
    /** HTTP path prefix used to request a browser reload. */
    String RELOAD_PATH = "/reload";
    /** HTTP path used by the helper readiness check. */
    String PING_PATH = "/ping";
    /** WebSocket message sent while the helper is shutting down. */
    String SHUTDOWN_MESSAGE = "shutdown";

    final class NONE implements LiveReloadProtocol { }
}
