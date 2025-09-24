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
import lombok.SneakyThrows;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

/**
 * Goal which deploys application in dev mode, opens browser and monitors
 * src/main for changes. On change, recompiles and redeploys code if needed,
 * Works for both Payara and GlassFish servers.
 */
@Mojo(name = "dev", requiresProject = false, threadSafe = true,
        requiresDependencyResolution = ResolutionScope.COMPILE,
        requiresDependencyCollection = ResolutionScope.COMPILE)
public class DevMojo extends CommonDevMojo {
    /**
     * Group ID of Apache Maven Plugins
     */
    public static final String ORG_APACHE_MAVEN_PLUGINS = "org.apache.maven.plugins";
    private static final Set<String> CODE_CONTAINING_SRC_DIRS = Set.of(
            "java", "kotlin", "groovy", "scala", "clojure",
            "webapp/WEB-INF", "resources/META-INF"
    );

    @Override
    @SneakyThrows(IOException.class)
    public void execute() {
        if (project == null || project.getFile() == null) {
            getLog().warn("No Maven project found, skipping execution.");
            return;
        }

        Path explodedWarDir = Paths.get(project.getBuild().getDirectory(), project.getBuild().getFinalName());
        Path srcMainDir = Paths.get(project.getBasedir().getAbsolutePath(), "src", "main");
        getLog().info("Starting in dev mode, starting browser, monitoring %s for changes..."
                .formatted(srcMainDir));
        getLog().info("Exploded WAR directory: " + explodedWarDir);

        enableOrDeploy();
        watcher.watch(srcMainDir, this::onChange);
    }

    private void enableOrDeploy() throws IOException {
        if (deployer.sendEnableCommand((a, b) -> { }) == CommandResult.ERROR) {
            deployer.sendDeployCommand(deployer::printResponse);
        }
        String httpUrl = payaraAminURL.replaceFirst(":\\d+$", ":" + payaraHttpPort);
        var browseURL = URI.create("%s/%s".formatted(httpUrl, project.getBuild().getFinalName()));
        getLog().info("Application URL at " + browseURL);
        Desktop.getDesktop().browse(browseURL);
    }

    private void onChange(Set<Path> modifiedFiles) {
        boolean codeChanged = modifiedFiles.stream().filter(this::isSourceCode).findAny()
                .map(var -> callGenericMojo(ORG_APACHE_MAVEN_PLUGINS,
                        "maven-compiler-plugin", "compile", project, session, pluginManager)).orElse(false);
        callGenericMojo(ORG_APACHE_MAVEN_PLUGINS,
                "maven-war-plugin", "exploded", project, session, pluginManager);
        if (codeChanged) {
            getLog().info("Reloading " + project.getBuild().getFinalName());
            if (deployer.sendDisableCommand(deployer::printResponse) == CommandResult.ERROR) {
                deployer.sendDeployCommand(deployer::printResponse);
            } else {
                deployer.sendEnableCommand(deployer::printResponse);
            }
        }
    }

    private boolean isSourceCode(Path path) {
        Path relativePath = project.getBasedir().toPath()
                .resolve("src/main").relativize(path);
        return CODE_CONTAINING_SRC_DIRS.stream().anyMatch(relativePath::startsWith);
    }
}
