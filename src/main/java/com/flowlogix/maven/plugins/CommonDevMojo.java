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
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import javax.inject.Inject;
import static org.twdata.maven.mojoexecutor.MojoExecutor.artifactId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executeMojo;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executionEnvironment;
import static org.twdata.maven.mojoexecutor.MojoExecutor.goal;
import static org.twdata.maven.mojoexecutor.MojoExecutor.groupId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.plugin;

/**
 * Common parameters and code for dev and redeploy mojos
 */
abstract class CommonDevMojo extends AbstractMojo {
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
}
