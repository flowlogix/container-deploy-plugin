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
import com.flowlogix.plugins.common.ReloadStatus;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * Goal which reloads the application on the server.
 * Works for both Payara and GlassFish servers.
 */
@Mojo(name = "reload", requiresProject = false, threadSafe = true,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
        requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class ReloadMojo extends CommonDevMojo {
    @Override
    public void execute() throws MojoFailureException {
        getLog().info("Application URL at " + getAppURL());
        boolean isDeployError = deployer.sendDisableCommand(deployer::printResponse) != CommandResult.SUCCESS;
        boolean compilationSucceeded = false;
        String failureMessage = null;
        if (isDeployError) {
            failureMessage = "Application disable failed, see log for details.";
        } else {
            getLog().info("Packaging application for deployment...");
            compilationSucceeded = compileSources();
            isDeployError = !explodedWar();
            if (deployer.sendEnableCommand(deployer::printResponse) != CommandResult.SUCCESS) {
                isDeployError = true;
                failureMessage = "Application enable failed, see log for details.";
            }
        }
        if (deployer.sendReloadCommand(getBaseURL(), project.getBuild().getFinalName(),
                !compilationSucceeded || isDeployError ? ReloadStatus.ERROR : ReloadStatus.RELOAD,
                deployer::printResponse) == CommandResult.ERROR) {
            getLog().warn("Website Reload failed");
        }
        if (isDeployError) {
            throw new MojoFailureException(failureMessage);
        }
        getLog().info("Application reloaded.");
    }
}
