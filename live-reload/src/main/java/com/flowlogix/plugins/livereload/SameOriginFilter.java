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

import com.flowlogix.plugins.common.LiveReloadProtocol;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

@WebFilter(LiveReloadProtocol.WEBSOCKET_PATH)
public class SameOriginFilter extends HttpFilter {
    private static final int HTTP_PORT = 80;
    private static final int HTTPS_PORT = 443;

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (isSameOrigin(request.getHeader("Origin"), request.getScheme(),
                request.getServerName(), request.getServerPort())) {
            chain.doFilter(request, response);
        } else {
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "WebSocket origin does not match the request origin");
        }
    }

    static boolean isSameOrigin(String originHeaderValue, String requestScheme, String requestHost, int requestPort) {
        if (originHeaderValue == null) {
            return false;
        }

        try {
            return canonicalOrigin(new URI(originHeaderValue)).equals(canonicalOrigin(new URI(requestScheme, null,
                    requestHost, requestPort, null, null, null)));
        } catch (IllegalArgumentException | URISyntaxException e) {
            return false;
        }
    }

    private static URI canonicalOrigin(URI uri) throws URISyntaxException {
        if (uri.getHost() == null || uri.getScheme() == null) {
            throw new IllegalArgumentException("Not an origin");
        }

        int defaultPort = switch (uri.getScheme().toLowerCase(Locale.ROOT)) {
            case "http" -> HTTP_PORT;
            case "https" -> HTTPS_PORT;
            default -> throw new IllegalArgumentException("Unsupported origin scheme");
        };
        int port = uri.getPort() < 0 ? defaultPort : uri.getPort();
        return new URI(uri.getScheme(), null, uri.getHost(), port, null, null, null);
    }
}
