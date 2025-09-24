package com.flowlogix.maven.plugins;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.experimental.Delegate;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
class Deployer {
    /**
     *  Key for default parameter, which is the file to be deployed, enabled, disabled or undeployed.
     */
    public static final String DEFAULT = "DEFAULT";

    enum CommandResult {
        NO_CONNECTION, ERROR, SUCCESS
    }

    @Delegate
    private final CommonDevMojo mojo;

    CommandResult sendDisableCommand() {
        getLog().info("Sending disable command");
        return sendCommand("disable", Map.of(DEFAULT, mojo.project.getBuild().getFinalName()));
    }

    CommandResult sendEnableCommand() {
        getLog().info("Sending enable command");
        return sendCommand("enable", Map.of(DEFAULT, mojo.project.getBuild().getFinalName()));
    }

    CommandResult sendDeployCommand() {
        getLog().info("Sending deploy command");
        return sendCommand("deploy", Map.of(
                "name", mojo.project.getBuild().getFinalName(),
                "availabilityenabled", String.valueOf(mojo.availabilityenabled),
                "keepstate", String.valueOf(mojo.keepstate),
                "force", String.valueOf(mojo.force),
                "properties", "warlibs=%s".formatted(String.valueOf(mojo.warlibs)),
                DEFAULT, Paths.get(mojo.project.getBuild().getDirectory(),
                        mojo.project.getBuild().getFinalName()).toString()
        ));
    }

    CommandResult sendUndeployCommand() {
        getLog().info("Sending undeploy command");
        return sendCommand("undeploy", Map.of(DEFAULT, mojo.project.getBuild().getFinalName()));
    }

    @SneakyThrows({IOException.class, InterruptedException.class})
    @SuppressWarnings("checkstyle:MagicNumber")
    private CommandResult sendCommand(String command, Map<String, String> parameters) {
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
        } catch (ConnectException e) {
            getLog().warn("Failed to connect to server at %s. Is it running?"
                    .formatted(mojo.payaraAminURL));
            return CommandResult.NO_CONNECTION;
        }
        if (response.statusCode() != 200) {
            getLog().error("Command %s failed with response code %d".formatted(command, response.statusCode()));
            getLog().error("Response body: %s".formatted(response.body()));
        }
        return response.statusCode() == 200 ? CommandResult.SUCCESS : CommandResult.ERROR;
    }
}
