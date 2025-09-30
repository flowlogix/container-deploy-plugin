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

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jspecify.annotations.Nullable;
import javax.inject.Inject;
import java.util.function.Consumer;
import static org.twdata.maven.mojoexecutor.MojoExecutor.artifactId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.configuration;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executeMojo;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executionEnvironment;
import static org.twdata.maven.mojoexecutor.MojoExecutor.goal;
import static org.twdata.maven.mojoexecutor.MojoExecutor.groupId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.plugin;

/**
 * Common parameters and code for dev and redeploy mojos
 */
abstract class CommonDevMojo extends AbstractMojo {
    /**
     * Group ID of Apache Maven Plugins
     */
    public static final String ORG_APACHE_MAVEN_PLUGINS = "org.apache.maven.plugins";
    /**
     * Artifact ID of Maven Dependency Plugin
     */
    public static final String MAVEN_DEPENDENCY_PLUGIN = "maven-dependency-plugin";

    @Inject
    MavenProject project;

    @Inject
    MavenSession session;

    @Inject
    BuildPluginManager pluginManager;

    /**
     * Administration URL to connect to the Server
     */
    @Parameter(defaultValue = "http://localhost:4848", property = "payara.adminUrl")
    String payaraAminURL;

    /**
     * HTTP port where the application is deployed
     */
    @Parameter(defaultValue = "8080", property = "payara.httpPort")
    String payaraHttpPort;

    /**
     * Force deployment even if the server says the application is already deployed
     */
    @Parameter(defaultValue = "false", property = "payara.force")
    boolean force;

    /**
     * Whether to include libraries from server's lib/warlibs directory
     */
    @Parameter(defaultValue = "true", property = "payara.warlibs")
    boolean warlibs;

    /**
     * Whether to enable availability on the deployed application
     */
    @Parameter(defaultValue = "true", property = "payara.availabilityenabled")
    boolean availabilityenabled;

    /**
     * Whether to keep the state of the application on redeploy (secondary option to availabilityenabled)
     */
    @Parameter(defaultValue = "false", property = "payara.keepstate")
    boolean keepstate;

    final Deployer deployer = new Deployer(this);
    final Watcher watcher = new Watcher(this);

    boolean callGenericMojo(String groupId, String artifactId, String goal,
                            @Nullable String execution, MavenProject project, MavenSession session,
                            BuildPluginManager pluginManager, Consumer<Xpp3Dom> configurator) {
        try {
            var plugin = project.getPlugin("%s:%s".formatted(groupId, artifactId));
            Xpp3Dom configuration;
            if (execution != null) {
                PluginExecution pluginExecution = plugin.getExecutionsAsMap().get(execution);
                if (pluginExecution == null) {
                    getLog().warn("Cannot find execution %s for execution of %s:%s"
                            .formatted(execution, groupId, artifactId));
                    if (MAVEN_DEPENDENCY_PLUGIN.equals(artifactId)
                            && "copy-dependencies".equals(goal)
                        && ORG_APACHE_MAVEN_PLUGINS.equals(groupId)) {
                        getLog().warn("""
                            Copied dependencies are probably incorrect.
                            You probably need to add the following in your pom.xml:
                            <plugin>
                                <artifactId>maven-dependency-plugin</artifactId>
                                <executions>
                                    <execution>
                                        <id>default-cli</id>
                                        <configuration>
                                            <includeScope>runtime</includeScope>
                                            <excludeArtifactIds>postgresql, checker-qual</excludeArtifactIds>
                                        </configuration>
                                    </execution>
                                </executions>
                            </plugin>
                            """);
                    }
                    configuration = (Xpp3Dom) plugin.getConfiguration();
                } else {
                    configuration = (Xpp3Dom) pluginExecution.getConfiguration();
                }
            } else {
                configuration = (Xpp3Dom) plugin.getConfiguration();
            }
            if (configuration == null) {
                configuration = configuration();
            }
            configurator.accept(configuration);
            executeMojo(
                    plugin(groupId(groupId), artifactId(artifactId)),
                    goal(goal),
                    configuration,
                    executionEnvironment(project, session, pluginManager)
            );
            return true;
        } catch (MojoExecutionException e) {
            getLog().debug("Failed to execute %s:%s:%s".formatted(groupId, artifactId, goal), e);
            return false;
        }
    }

    void addSkipConfiguration(Xpp3Dom configuration) {
        Xpp3Dom skipValue;
        if (configuration.getChild("skip") != null) {
            skipValue = configuration.getChild("skip");
            skipValue.setValue("false");
        } else {
            skipValue = new Xpp3Dom("skip");
            skipValue.setValue("false");
            configuration.addChild(skipValue);
        }
    }

    boolean extractAppServer() {
        return callGenericMojo(ORG_APACHE_MAVEN_PLUGINS, MAVEN_DEPENDENCY_PLUGIN, "unpack",
                "unpack-payara", project, session, pluginManager, this::addSkipConfiguration);
    }

    boolean copyDependencies(String location) {
        return callGenericMojo(ORG_APACHE_MAVEN_PLUGINS, MAVEN_DEPENDENCY_PLUGIN, "copy-dependencies",
                "default-cli", project, session, pluginManager, config -> {
                    addSkipConfiguration(config);
                    var outputDirectory = new Xpp3Dom("outputDirectory");
                    outputDirectory.setValue(location);
                    config.addChild(outputDirectory);
                });
    }

    boolean startAppServer() {
        if (!deployer.pingServer()) {
            return callGenericMojo("org.codehaus.mojo", "exec-maven-plugin", "exec",
                    "start-payara-domain", project, session, pluginManager, this::addSkipConfiguration);
        }
        getLog().info("Server already running");
        return false;
    }
}
