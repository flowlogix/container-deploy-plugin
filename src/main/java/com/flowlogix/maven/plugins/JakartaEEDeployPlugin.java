package com.flowlogix.maven.plugins;

import lombok.SneakyThrows;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;
import org.eclipse.aether.impl.ArtifactResolver;
import javax.inject.Inject;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

@Mojo(name = "dev", requiresProject = false, threadSafe = true)
public class JakartaEEDeployPlugin extends AbstractMojo {
    enum CommandResult {
        NO_CONNECTION, ERROR, SUCCESS
    };

    @Inject
    MavenProject project;

    @Inject
    MavenSession session;

    @Inject
    BuildPluginManager pluginManager;

    @Inject
    ArtifactResolver artifactResolver;

    @Inject
    RepositorySystem repositorySystem;

    @Parameter(defaultValue = "http://localhost:4848", property = "payara.serverUrl")
    private String payaraServerURL;

    @Override
    @SneakyThrows({IOException.class, InterruptedException.class})
    @SuppressWarnings({"checkstyle:CyclomaticComplexity", "checkstyle:NPathComplexity", "checkstyle:MethodLength"})
    public void execute() {
        getLog().info("Hello from JakartaEEDeployPlugin");
        if (project == null || project.getFile() == null) {
            getLog().warn("No Maven project found, skipping execution.");
            return;
        }

        Path explodedWarDir = Paths.get(project.getBuild().getDirectory(), project.getBuild().getFinalName());
        getLog().info("Exploded WAR directory: " + explodedWarDir.toString());
        Path srcMainDir = Paths.get(project.getBasedir().getAbsolutePath(), "src", "main");
        getLog().info("Source main directory: " + srcMainDir);
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            Map<WatchKey, Path> keys = new ConcurrentHashMap<>();
            try (var stream = Files.walk(srcMainDir)) {
                stream.filter(Files::isDirectory).forEach(path -> register(path, keys, watchService));
            }
            if (sendEnableCommand() == CommandResult.ERROR) {
                sendDeployCommand();
            }
            while (!Thread.interrupted()) {
                WatchKey key = watchService.poll(1, TimeUnit.SECONDS);
                if (key == null) {
                    continue;
                }
                boolean explode = false;
                boolean compile = false;
                boolean deploy = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    Path path = keys.get(key).resolve((Path) event.context());
                    getLog().info("Event kind: " + event.kind() + ". File affected: " + event.context());
                    getLog().info("key path: " + path);
                    if (event.kind() == ENTRY_CREATE && path.toFile().isDirectory()) {
                        register(path, keys, watchService);
                    }
                    if (path.toFile().isFile()) {
                        explode = true;
                        if (path.toString().endsWith(".java") || path.toString().endsWith(".kt")
                                || path.toString().endsWith(".groovy")) {
                            compile = true;
                            deploy = true;
                        }
                    }
                }
                key.reset();
                if (compile || explode) {
                    getLog().info("Changes detected, running compile: " + compile + ", explode: " + explode);
                    List<String> commandList = new ArrayList<>();
                    if (System.getenv("MVND_HOME") != null) {
                        commandList.add(Paths.get(System.getenv("MVND_HOME"), "bin", "mvnd").toString());
                    } else if (System.getenv("MAVEN_HOME") != null) {
                        commandList.add("mvn");
                    } else {
                        commandList.add(project.getBasedir().toPath().resolve("mvnw").toString());
                    }
                    commandList.add("-B");
                    commandList.add("-q");
                    commandList.add("-f");
                    commandList.add(project.getBasedir().getPath());
                    if (compile) {
                        commandList.add("compiler:compile");
                    }
                    if (explode) {
                        commandList.add("war:exploded");
                    }
                    getLog().info("Running command: " + String.join(" ", commandList));
                    var process = new ProcessBuilder(commandList).redirectErrorStream(true).start();
                    String buildLog = process.inputReader().lines()
                            .filter(line -> !line.contains("org.jline"))
                            .filter(line -> !line.startsWith("WARNING: Unable to create a system terminal"))
                            .collect(Collectors.joining(System.lineSeparator()));
                    if (process.waitFor() != 0) {
                        getLog().warn(buildLog);
                    } else if (deploy) {
                        if (sendDisableCommand() == CommandResult.ERROR) {
                            sendDeployCommand();
                        } else {
                            sendEnableCommand();
                        }
                    }
                }
            }
        }
    }

    @SneakyThrows(IOException.class)
    private void register(Path path, Map<WatchKey, Path> keys, WatchService watchService) {
        getLog().info("Registering path for watch: " + path);
        keys.put(path.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY), path);
    }

    private CommandResult sendDisableCommand() {
        getLog().info("Sending disable command");
        return sendCommand("disable", Map.of("DEFAULT", project.getBuild().getFinalName()),
                payaraServerURL);
    }

    private CommandResult sendEnableCommand() throws IOException, InterruptedException {
        getLog().info("Sending enable command");
        return sendCommand("enable", Map.of("DEFAULT", project.getBuild().getFinalName()),
                payaraServerURL);
    }

    private CommandResult sendDeployCommand() {
        getLog().info("Sending deploy command");
        return sendCommand("deploy", Map.of(
                "name", project.getBuild().getFinalName(),
                "availabilityenabled", "true",
                "properties", "warlibs=true",
                "DEFAULT", Paths.get(project.getBuild().getDirectory(),
                        project.getBuild().getFinalName()).toString()
        ), payaraServerURL);
    }

    CommandResult sendUndeployCommand(String payaraServerURL) {
        getLog().info("Sending undeploy command");
        return sendCommand("undeploy", Map.of("DEFAULT", project.getBuild().getFinalName()),
                payaraServerURL);
    }

    @SneakyThrows({IOException.class, InterruptedException.class})
    @SuppressWarnings("checkstyle:MagicNumber")
    private CommandResult sendCommand(String command, Map<String, String> parameters, String payaraServerURL) {
        getLog().info("Parameters: " + parameters);
        HttpResponse<String> response;
        try {
            HttpClient client = HttpClient.newHttpClient();
            String formData = parameters.entrySet().stream()
                    .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                            + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                    .collect(Collectors.joining("&"));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("%s/command/%s".formatted(payaraServerURL, command)))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("X-requested-by", "cli")
                    .POST(HttpRequest.BodyPublishers.ofString(formData))
                    .build();

            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (ConnectException e) {
            getLog().warn("Failed to connect to server at %s. Is it running?"
                    .formatted(payaraServerURL), e);
            return CommandResult.NO_CONNECTION;
        }
        if (response.statusCode() != 200) {
            getLog().error("Command %s failed with response code %d".formatted(command, response.statusCode()));
            getLog().error("Response body: %s".formatted(response.body()));
        }
        return response.statusCode() == 200 ? CommandResult.SUCCESS : CommandResult.ERROR;
    }
}
