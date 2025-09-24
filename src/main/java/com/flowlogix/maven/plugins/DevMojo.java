package com.flowlogix.maven.plugins;

import lombok.SneakyThrows;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static org.twdata.maven.mojoexecutor.MojoExecutor.artifactId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executeMojo;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executionEnvironment;
import static org.twdata.maven.mojoexecutor.MojoExecutor.goal;
import static org.twdata.maven.mojoexecutor.MojoExecutor.groupId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.plugin;

@Mojo(name = "dev", requiresProject = false, threadSafe = true,
        requiresDependencyResolution = ResolutionScope.COMPILE,
        requiresDependencyCollection = ResolutionScope.COMPILE)
public class DevMojo extends CommonDevMojo {
    enum CommandResult {
        NO_CONNECTION, ERROR, SUCCESS
    }

    @Override
    @SneakyThrows({IOException.class, InterruptedException.class})
    @SuppressWarnings({"checkstyle:CyclomaticComplexity", "checkstyle:NPathComplexity", "checkstyle:MethodLength"})
    public void execute() {
        getLog().info("Starting in dev mode, starting browser, monitoring src/main for changes...");
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
            if (deployer.sendEnableCommand() == CommandResult.ERROR) {
                deployer.sendDeployCommand();
            }
            String httpUrl = payaraAminURL.replaceFirst(":\\d+$", ":" + payaraHttpPort);
            Desktop.getDesktop().browse(URI.create("%s/%s".formatted(httpUrl, project.getBuild().getFinalName())));
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
                    boolean result = true;
                    if (compile) {
                        result &= callGenericMojo("org.apache.maven.plugins",
                                "maven-compiler-plugin", "compile", project, session, pluginManager);
                    }
                    if (explode) {
                        result &= callGenericMojo("org.apache.maven.plugins",
                                "maven-war-plugin", "exploded", project, session, pluginManager);
                    }
                    if (result && deploy) {
                        if (deployer.sendDisableCommand() == CommandResult.ERROR) {
                            deployer.sendDeployCommand();
                        } else {
                            deployer.sendEnableCommand();
                        }
                    }
                }
            }
        }
    }

    private boolean callGenericMojo(String groupId, String artifactId, String goal,
                                   MavenProject project, MavenSession session,
                                   BuildPluginManager pluginManager) {
        try {
            executeMojo(
                    plugin(groupId(groupId), artifactId(artifactId)),
                    goal(goal),
                    (Xpp3Dom) project.getPlugin("%s:%s".formatted(groupId, artifactId)).getConfiguration(),
                    executionEnvironment(project, session, pluginManager)
            );
            return true;
        } catch (MojoExecutionException e) {
            return false;
        }
    }

    @SneakyThrows(IOException.class)
    private void register(Path path, Map<WatchKey, Path> keys, WatchService watchService) {
        getLog().info("Registering path for watch: " + path);
        keys.put(path.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY), path);
    }
}
