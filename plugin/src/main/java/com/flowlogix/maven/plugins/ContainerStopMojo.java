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

import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;

/**
 * Goal which stops the application server.
 * Works for both Payara and GlassFish servers.
 */
@Mojo(name = "stop", requiresProject = false, threadSafe = true)
public class ContainerStopMojo extends CommonDevMojo {
    @Override
    public void execute() throws MojoFailureException {
        if (!callGenericMojo("org.codehaus.mojo", "exec-maven-plugin", "exec",
                "stop-payara-domain", project, session, pluginManager, this::addSkipConfiguration)) {
            throw new MojoFailureException("Failed to stop container domain");
        }
        getLog().info("Application Server Stopped.");
    }
}
