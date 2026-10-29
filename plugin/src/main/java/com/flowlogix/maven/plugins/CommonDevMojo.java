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

import lombok.Getter;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.PluginDescriptorParsingException;
import org.apache.maven.plugin.PluginNotFoundException;
import org.apache.maven.plugin.PluginResolutionException;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;
import org.jspecify.annotations.Nullable;
import javax.inject.Inject;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import static org.twdata.maven.mojoexecutor.MojoExecutor.artifactId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.configuration;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executeMojo;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executionEnvironment;
import static org.twdata.maven.mojoexecutor.MojoExecutor.goal;
import static org.twdata.maven.mojoexecutor.MojoExecutor.groupId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.plugin;

/**
 * Common parameters and code for dev and redeploy mojos.
 */
abstract class CommonDevMojo extends AbstractMojo {
    /**
     * Group ID of Apache Maven Plugins.
     */
    public static final String ORG_APACHE_MAVEN_PLUGINS = "org.apache.maven.plugins";
    /**
     * Artifact ID of Maven Dependency Plugin.
     */
    public static final String MAVEN_DEPENDENCY_PLUGIN = "maven-dependency-plugin";

    @Inject
    MavenProject project;

    @Inject
    MavenSession session;

    @Inject
    BuildPluginManager pluginManager;

    @Parameter(defaultValue = "${mojoExecution}", readonly = true)
    MojoExecution mojoExecution;

    /**
     * Administration URL to connect to the Server.
     */
    @Parameter(defaultValue = "http://localhost:4848", property = "server.adminUrl")
    String serverAminURL;

    /**
     * HTTP port where the application is deployed.
     */
    @Parameter(defaultValue = "8080", property = "server.httpPort")
    String serverHttpPort;

    /**
     * Force deployment even if the server says the application is already deployed.
     */
    @Parameter(defaultValue = "false", property = "server.force")
    boolean force;

    /**
     * Whether to include libraries from server's lib/warlibs directory.
     */
    @Parameter(defaultValue = "true", property = "server.warlibs")
    boolean warlibs;

    /**
     * Whether to enable availability on the deployed application.
     */
    @Parameter(defaultValue = "true", property = "server.availabilityenabled")
    boolean availabilityenabled;

    /**
     * Whether to keep the state of the application on redeploy (secondary option to availabilityenabled).
     */
    @Parameter(defaultValue = "false", property = "server.keepstate")
    boolean keepstate;

    final Deployer deployer = new Deployer(this);
    final Watcher watcher = new Watcher(this);

    @Getter(lazy = true)
    private final Path explodedWarDir = Paths.get(project.getBuild().getDirectory(), project.getBuild().getFinalName());
    @Getter(lazy = true)
    private final Path srcMainDir = Paths.get(project.getBasedir().getAbsolutePath(), "src", "main");
    @Getter(lazy = true)
    private final String baseURL = computeBaseURL();
    @Getter(lazy = true)
    private final String appURL = computeApplicationURL();

    boolean callGenericMojo(String groupId, String artifactId, String goal,
                            @Nullable String execution, MavenProject project, MavenSession session,
                            BuildPluginManager pluginManager, Consumer<Xpp3Dom> configurator) {
        try {
            var plugin = Optional.ofNullable(project.getPlugin("%s:%s".formatted(groupId, artifactId)));
            Xpp3Dom configuration = plugin.map(value -> (Xpp3Dom) value.getConfiguration()).orElse(null);
            if (execution != null) {
                PluginExecution pluginExecution = plugin.map(value -> value.getExecutionsAsMap().get(execution)).orElse(null);
                if (pluginExecution == null) {
                    warnMissingExecution(groupId, artifactId, goal, execution, configuration, session);
                } else {
                    configuration = (Xpp3Dom) pluginExecution.getConfiguration();
                }
            }
            if (configuration == null) {
                configuration = configuration();
            } else {
                // detach from the Model
                configuration = new Xpp3Dom(configuration);
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

    private void warnMissingExecution(String groupId, String artifactId, String goal, String execution,
                                      @Nullable Xpp3Dom configuration, MavenSession session)
            throws MojoExecutionException {
        boolean copyingDependencies = MAVEN_DEPENDENCY_PLUGIN.equals(artifactId)
                && "copy-dependencies".equals(goal) && ORG_APACHE_MAVEN_PLUGINS.equals(groupId);
        if (copyingDependencies && hasDependencyCopyConfiguration(configuration, session)) {
            return;
        }
        if (copyingDependencies) {
            getLog().warn("""
                Copied dependencies are probably incorrect.
                You probably need to add the following to your pom.xml:
                <plugin>
                    <artifactId>maven-dependency-plugin</artifactId>
                    <executions>
                        <execution>
                            <id>default-cli</id>
                            <configuration>
                                <includeScope>runtime</includeScope>
                                <excludeArtifactIds>postgresql, checker-qual, asm</excludeArtifactIds>
                            </configuration>
                        </execution>
                    </executions>
                </plugin>
                """);
        } else {
            getLog().warn("Cannot find execution %s for execution of %s:%s"
                    .formatted(execution, groupId, artifactId));
        }
    }

    private boolean hasDependencyCopyConfiguration(@Nullable Xpp3Dom configuration, MavenSession session)
            throws MojoExecutionException {
        var evaluator = new PluginParameterExpressionEvaluator(session, mojoExecution);
        try {
            var scope = evaluator.evaluate(parameterExpression(configuration, "includeScope"));
            var exclusions = evaluator.evaluate(parameterExpression(configuration, "excludeArtifactIds"));
            return "runtime".equals(scope) && exclusions instanceof String value && !value.isBlank();
        } catch (ExpressionEvaluationException e) {
            throw new MojoExecutionException("Could not evaluate dependency-copy configuration", e);
        }
    }

    private String parameterExpression(@Nullable Xpp3Dom configuration, String name) {
        return Optional.ofNullable(configuration)
                .map(value -> value.getChild(name))
                .map(Xpp3Dom::getValue)
                .orElseGet(() -> "${%s}".formatted(name));
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
                "unpack-server", project, session, pluginManager, this::addSkipConfiguration);
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
                    "start-domain", project, session, pluginManager, this::addSkipConfiguration);
        }
        getLog().info("Server already running");
        return false;
    }

    boolean compileSources() {
        return callGenericMojo(ORG_APACHE_MAVEN_PLUGINS,
                "maven-compiler-plugin", "compile", null,
                project, session, pluginManager, config -> { });
    }

    boolean explodedWar() {
        return callGenericMojo(ORG_APACHE_MAVEN_PLUGINS,
                "maven-war-plugin", "exploded", null,
                project, session, pluginManager, config -> { });
    }

    boolean runTests() {
        if (callGenericMojo(ORG_APACHE_MAVEN_PLUGINS,
                "maven-compiler-plugin", "testCompile", null,
                project, session, pluginManager, config -> { })) {
            resetSurefireContext();
            return withDependencyProperties(() -> callGenericMojo(ORG_APACHE_MAVEN_PLUGINS,
                    "maven-surefire-plugin", "test", null,
                    project, session, pluginManager, config -> { }));
        }
        return false;
    }

    private <T> T withDependencyProperties(Supplier<T> action) {
        var props = project.getProperties();
        var saved = new HashMap<String, String>();
        var added = new ArrayList<String>();
        project.getArtifacts().stream()
                .filter(artifact -> artifact.getFile() != null)
                .forEach(artifact -> {
                    var key = artifact.getDependencyConflictId();
                    var prior = props.getProperty(key);
                    if (prior != null) {
                        saved.put(key, prior);
                    } else {
                        added.add(key);
                    }
                    props.setProperty(key, artifact.getFile().getAbsolutePath());
                });
        try {
            return action.get();
        } finally {
            added.forEach(props::remove);
            saved.forEach(props::setProperty);
        }
    }

    private void resetSurefireContext() {
        try {
            var surefire = project.getPlugin("%s:maven-surefire-plugin".formatted(ORG_APACHE_MAVEN_PLUGINS));
            if (surefire == null) {
                return;
            }
            PluginDescriptor descriptor = pluginManager.loadPlugin(surefire,
                    project.getRemotePluginRepositories(), session.getRepositorySession());
            session.getPluginContext(descriptor, project).clear();
        } catch (PluginResolutionException | PluginDescriptorParsingException
                 | PluginNotFoundException | org.apache.maven.plugin.InvalidPluginDescriptorException e) {
            getLog().warn("Could not reset surefire plugin context", e);
        }
    }

    private String computeBaseURL() {
        return serverAminURL.replaceFirst(":\\d+$", ":" + serverHttpPort);
    }

    private String computeApplicationURL() {
        return "%s/%s".formatted(getBaseURL(), project.getBuild().getFinalName());
    }
}
