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
package com.flowlogix.maven.plugins;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.annotation.JsonbProperty;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.experimental.Delegate;
import java.io.IOException;
import java.io.StringReader;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Common code for deploy, undeploy, enable and disable server commands.
 */
@RequiredArgsConstructor
class Deployer {
    /**
     *  Key for default parameter, which is the file to be deployed, enabled, disabled or undeployed.
     */
    static final String DEFAULT = "DEFAULT";
    static final String FLOWLOGIX_LIVERELOAD = "flowlogix-livereload";

    enum CommandResult {
        NO_CONNECTION, ERROR, SUCCESS
    }
    record CommandResponse(int statusCode, String body) { }
    public record ServerLocations(
            String message,
            String command,
            String exit_code,
            Properties properties
    ) {
        public record Properties(
                @JsonbProperty("Restart-Required") String restartRequired,
                @JsonbProperty("Instance-Root") String instanceRoot,
                @JsonbProperty("Base-Root") String baseRoot,
                @JsonbProperty("Uptime") String uptime,
                @JsonbProperty("Domain-Root") String domainRoot,
                @JsonbProperty("Pid") String pid,
                @JsonbProperty("Config-Dir") String configDir
        ) { }
    }

    @Delegate
    private final CommonDevMojo mojo;

    CommandResult sendDisableCommand(@NonNull BiConsumer<String, CommandResponse> responseCallback) {
        getLog().debug("Sending disable command");
        return sendCommand("disable", Map.of(DEFAULT, mojo.project.getBuild().getFinalName()), responseCallback);
    }

    CommandResult sendEnableCommand(@NonNull BiConsumer<String, CommandResponse> responseCallback) {
        getLog().debug("Sending enable command");
        return sendCommand("enable", Map.of(DEFAULT, mojo.project.getBuild().getFinalName()), responseCallback);
    }

    CommandResult sendDeployCommand(@NonNull BiConsumer<String, CommandResponse> responseCallback,
                                    String name, Integer cacheTTL) {
        getLog().info("Sending deploy command");
        String properties = Stream.of("warlibs=%s".formatted(String.valueOf(mojo.warlibs)),
                        cacheTTL != null ? "cacheTTL=%d".formatted(cacheTTL) : null)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(":"));
        return sendCommand("deploy", Map.of(
                "name", name != null ? name : mojo.project.getBuild().getFinalName(),
                "availabilityenabled", String.valueOf(mojo.availabilityenabled),
                "keepstate", String.valueOf(mojo.keepstate),
                "force", String.valueOf(mojo.force),
                "properties", properties,
                DEFAULT, Paths.get(mojo.project.getBuild().getDirectory(),
                        mojo.project.getBuild().getFinalName()).toString()
        ), responseCallback);
    }

    CommandResult sendUndeployCommand(String name, @NonNull BiConsumer<String, CommandResponse> responseCallback) {
        getLog().info("Sending undeploy command");
        return sendCommand("undeploy", Map.of(DEFAULT, name == null
                ? mojo.project.getBuild().getFinalName() : name), responseCallback);
    }

    public boolean pingServer() {
        return sendCommand("ping", Map.of(), (a, b) -> { }) != CommandResult.NO_CONNECTION;
    }

    ServerLocations serverLocations() {
        AtomicReference<ServerLocations> serverLocations = new AtomicReference<>();
        return switch (sendCommand("__locations", Map.of(),
                (command, response) -> serverLocationsResponse(command, response, serverLocations))) {
            case NO_CONNECTION, ERROR -> null;
            case SUCCESS -> serverLocations.get();
        };
    }

    @SuppressWarnings("checkstyle:MagicNumber")
    private void serverLocationsResponse(String command, CommandResponse response,
                                         AtomicReference<ServerLocations> serverLocations) {
        if (response.statusCode() != 200) {
            printResponse(command, response);
            return;
        }

        String body = response.body();
        int dataIndex = body.lastIndexOf("data:");
        if (dataIndex != -1) {
            body = body.substring(dataIndex + 5).trim();
        }
        try (var jsonb = JsonbBuilder.create();
             JsonReader reader = Json.createReader(new StringReader(body))) {
            JsonObject actionReport = reader.readObject().getJsonObject("action-report");
            if (actionReport != null) {
                serverLocations.set(jsonb.fromJson(jsonb.toJson(actionReport), ServerLocations.class));
            } else {
                serverLocations.set(jsonb.fromJson(response.body(), ServerLocations.class));
            }
        } catch (Exception e) {
            getLog().error("Failed to parse server locations response: %s - %s"
                    .formatted(e.getMessage(), response.body()));
        }
    }

    @SuppressWarnings("checkstyle:MagicNumber")
    @SneakyThrows(InterruptedException.class)
    CommandResult sendCommand(String command, Map<String, String> parameters,
                                      @NonNull BiConsumer<String, CommandResponse> responseCallback) {
        getLog().debug("Parameters: " + parameters);
        HttpResponse<String> response;
        try {
            HttpClient client = HttpClient.newHttpClient();
            String formData = parameters.entrySet().stream()
                    .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                            + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                    .collect(Collectors.joining("&"));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("%s/command/%s".formatted(mojo.payaraAminURL, command)))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("X-requested-by", "cli")
                    .POST(HttpRequest.BodyPublishers.ofString(formData))
                    .build();

            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            responseCallback.accept(command, new CommandResponse(0, e.getMessage()));
            return e instanceof ConnectException ? CommandResult.NO_CONNECTION : CommandResult.ERROR;
        }
        responseCallback.accept(command, new CommandResponse(response.statusCode(), response.body()));
        return response.statusCode() == 200 ? CommandResult.SUCCESS : CommandResult.ERROR;
    }

    @SneakyThrows(InterruptedException.class)
    @SuppressWarnings("checkstyle:MagicNumber")
    boolean pingWebsite(String applicationUrl) {
        HttpResponse<Void> response;
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(applicationUrl))
                    .GET()
                    .build();
            response = client.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            return false;
        }
        return response.statusCode() != 404 && response.statusCode() != 500;
    }

    @SuppressWarnings("checkstyle:MagicNumber")
    @SneakyThrows({IOException.class, InterruptedException.class})
    public CommandResult sendReloadCommand(String baseURL, String applicationName,
                                           @NonNull BiConsumer<String, CommandResponse> responseCallback) {
        HttpResponse<Void> response;
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("%s/%s/reload/%s".formatted(baseURL, FLOWLOGIX_LIVERELOAD, applicationName)))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            response = client.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (ConnectException e) {
            responseCallback.accept("reload", null);
            return CommandResult.NO_CONNECTION;
        }
        responseCallback.accept("reload", new CommandResponse(response.statusCode(), null));
        return response.statusCode() == 200 ? CommandResult.SUCCESS : CommandResult.ERROR;
    }

    @SuppressWarnings("checkstyle:MagicNumber")
    void printResponse(String command, CommandResponse response) {
        if (response == null) {
            getLog().warn("Failed to connect to server at %s. Is it running?"
                    .formatted(mojo.payaraAminURL));
            return;
        }
        if (response.statusCode() != 200 && response.statusCode() != 0) {
            getLog().error("Command %s failed with response code %d".formatted(command, response.statusCode()));
            getLog().error("Response body: %s".formatted(response.body()));
        }
    }
}
