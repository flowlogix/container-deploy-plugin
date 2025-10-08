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

import com.flowlogix.maven.plugins.Deployer.CommandResult;
import lombok.Getter;
import lombok.SneakyThrows;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import static com.flowlogix.maven.plugins.Deployer.DEFAULT;
import static com.flowlogix.maven.plugins.Deployer.FLOWLOGIX_LIVERELOAD;
import static java.util.function.Predicate.not;

/**
 * Goal which deploys application in dev mode, opens browser and monitors
 * src/main for changes. On change, recompiles and redeploys code if needed,
 * Works for both Payara and GlassFish servers.
 */
@Mojo(name = "dev", requiresProject = false, threadSafe = true,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
        requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class DevModeMojo extends CommonDevMojo {
    static final String FLOWLOGIX_LIVERELOAD_HELPER_APP_NAME = "flowlogix-livereload-helper";
    private static final Set<String> CODE_CONTAINING_SRC_DIRS = Set.of(
            "java", "kotlin", "groovy", "scala", "clojure",
            "webapp/WEB-INF", "resources/META-INF"
    );
    private static final Set<String> IGNORED_FILE_SUFFIXES = Set.of(
            ".swp", "~", ".tmp"
    );

    @Parameter(property = "livereload-helper-version", defaultValue = "0.5.1")
    String livereloadHelperVersion;

    @Getter(lazy = true)
    private final Path explodedWarDir = Paths.get(project.getBuild().getDirectory(), project.getBuild().getFinalName());
    @Getter(lazy = true)
    private final Path srcMainDir = Paths.get(project.getBasedir().getAbsolutePath(), "src", "main");
    @Getter(lazy = true)
    private final String baseURL = computeBaseURL();
    @Getter(lazy = true)
    private final String appURL = computeApplicationURL();

    @Override
    @SneakyThrows(IOException.class)
    public void execute() {
        if (project == null || project.getFile() == null) {
            getLog().warn("No Maven project found, skipping execution.");
            return;
        }

        getLog().info("Starting in dev mode, starting browser, monitoring %s for changes..."
                .formatted(getSrcMainDir()));
        getLog().info("Exploded WAR directory: " + getExplodedWarDir());

        enableOrDeploy();
        watcher.watch(getSrcMainDir(), this::onChange);
    }

    private void enableOrDeploy() throws IOException {
        var result = deployer.sendEnableCommand((a, b) -> { });
        if (result == CommandResult.NO_CONNECTION) {
            extractAppServer();
            startAppServer();
            result = deployer.sendEnableCommand((a, b) -> { });
        }
        if (result == CommandResult.ERROR) {
            if (!getExplodedWarDir().toFile().exists()) {
                compileSources();
                explodedWar();
            }
            deployer.sendDeployCommand(deployer::printResponse, null, 0);
        }

        getLog().info("Application URL at " + getAppURL());
        getLog().info("App Server at %s".formatted(deployer.serverLocations().properties().baseRoot()));
        getLog().info("Domain at %s".formatted(deployer.serverLocations().properties().instanceRoot()));
        getLog().info("Logging at %s/logs/server.log".formatted(deployer.serverLocations().properties().instanceRoot()));
        getLog().info("Deps (optional) at %s/lib/warlibs/".formatted(deployer.serverLocations().properties().instanceRoot()));
        ForkJoinPool.commonPool().execute(this::openBrowser);
        ForkJoinPool.commonPool().execute(this::deployLiveReloadHelper);
    }

    private void openBrowser() {
        @SuppressWarnings("checkstyle:MagicNumber")
        boolean websiteDeployed = IntStream.range(0, 30).anyMatch(this::pingWebsite);
        if (websiteDeployed) {
            try {
                Desktop.getDesktop().browse(URI.create(getAppURL()));
            } catch (UnsupportedOperationException | IOException e) {
                getLog().debug("Cannot open browser", e);
            }
        } else {
            getLog().warn("Website not available after 30 seconds.");
        }
    }

    private void deployLiveReloadHelper() {
        if (!deployer.pingWebsite("%s/%s/ping".formatted(baseURL, FLOWLOGIX_LIVERELOAD))) {
            getLog().info("Deploying LiveReload helper application");
            if (deployer.sendCommand("deploy-remote-archive", Map.of(
                    "name", FLOWLOGIX_LIVERELOAD_HELPER_APP_NAME,
                    "force", Boolean.TRUE.toString(),
                    "contextroot", FLOWLOGIX_LIVERELOAD,
                    "additionalRepositories", "https://nexus.flowlogix.com/repository/maven-releases",
                    DEFAULT, "%s:%s:%s"
                            .formatted("com.flowlogix.plugins", "live-reload",
                                    livereloadHelperVersion)), deployer::printResponse) == CommandResult.ERROR) {
                getLog().warn("LiveReload helper deployment failed");
            }
        }
    }

    @SneakyThrows(InterruptedException.class)
    @SuppressWarnings("checkstyle:MagicNumber")
    private boolean pingWebsite(int attempt) {
        boolean result = deployer.pingWebsite(getAppURL());
        if (!result) {
            getLog().debug("Website not available yet, waiting...");
            Thread.sleep(1000);
        }
        return result;
    }

    private void onChange(Set<Path> modifiedFiles) {
        var filteredFiles = modifiedFiles.stream().filter(not(this::isIgnoredFile))
                .collect(Collectors.toSet());
        boolean codeChanged = filteredFiles.stream().filter(this::isSourceCode).findAny()
                .map(var -> compileSources()).orElse(false);
        if (filteredFiles.isEmpty()) {
            return;
        }

        explodedWar();
        if (codeChanged) {
            getLog().info("Reloading " + project.getBuild().getFinalName());
            if (deployer.sendDisableCommand(deployer::printResponse) == CommandResult.ERROR) {
                deployer.sendDeployCommand(deployer::printResponse, null, 0);
            } else {
                deployer.sendEnableCommand(deployer::printResponse);
            }
        }
        if (deployer.sendReloadCommand(getBaseURL(), project.getBuild().getFinalName(),
                deployer::printResponse) == CommandResult.ERROR) {
            getLog().warn("Website Reload failed");
        }
    }

    private boolean compileSources() {
        return callGenericMojo(ORG_APACHE_MAVEN_PLUGINS,
                "maven-compiler-plugin", "compile", null,
                project, session, pluginManager, config -> { });
    }

    private boolean explodedWar() {
        return callGenericMojo(ORG_APACHE_MAVEN_PLUGINS,
                "maven-war-plugin", "exploded", null,
                project, session, pluginManager, config -> { });
    }

    private boolean isSourceCode(Path path) {
        Path relativePath = project.getBasedir().toPath()
                .resolve("src/main").relativize(path);
        return CODE_CONTAINING_SRC_DIRS.stream().anyMatch(relativePath::startsWith);
    }

    private boolean isIgnoredFile(Path path) {
        return IGNORED_FILE_SUFFIXES.stream().anyMatch(path.toString()::endsWith);
    }

    private String computeBaseURL() {
        return payaraAminURL.replaceFirst(":\\d+$", ":" + payaraHttpPort);
    }

    private String computeApplicationURL() {
        return "%s/%s".formatted(getBaseURL(), project.getBuild().getFinalName());
    }
}
