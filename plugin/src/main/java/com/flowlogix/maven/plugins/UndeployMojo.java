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
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Goal which undeploys application from the server.
 * Works for both Payara and GlassFish servers.
 */
@Mojo(name = "undeploy", requiresProject = false, threadSafe = true)
public class UndeployMojo extends CommonDevMojo {
    @Parameter(property = "name")
    String name;

    @Override
    public void execute() throws MojoFailureException {
        if (deployer.sendUndeployCommand(name, deployer::printResponse) != CommandResult.SUCCESS) {
            throw new MojoFailureException("Undeployment failed, see log for details.");
        }
        getLog().info("Application undeployed.");
    }
}
