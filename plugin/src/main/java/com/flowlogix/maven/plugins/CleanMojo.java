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

import com.flowlogix.maven.plugins.Deployer.ServerLocations;
import lombok.SneakyThrows;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.util.FileUtils;
import java.io.IOException;
import static com.flowlogix.maven.plugins.DevModeMojo.FLOWLOGIX_LIVERELOAD_HELPER_APP_NAME;

/**
 * Goal which removes dependencies from lib/warlibs directory.
 */
@Mojo(name = "clean", requiresProject = false, threadSafe = true)
public class CleanMojo extends CommonDevMojo {
    @Parameter(property = "name")
    String name;

    @Override
    @SneakyThrows(IOException.class)
    public void execute() throws MojoFailureException {
        ServerLocations locations = deployer.serverLocations();
        if (locations == null) {
            throw new MojoFailureException("Error determining server locations, is the server running?");
        }
        String destination = "%s/lib/warlibs".formatted(locations.properties().instanceRoot());
        FileUtils.cleanDirectory(destination);
        getLog().info("Removed dependencies from %s - restart may be required (mvn payara:restart)".formatted(destination));
        deployer.sendUndeployCommand(FLOWLOGIX_LIVERELOAD_HELPER_APP_NAME, deployer::printResponse);
        if (deployer.sendUndeployCommand(name, deployer::printResponse) != Deployer.CommandResult.SUCCESS) {
            throw new MojoFailureException("Undeployment failed, see log for details.");
        }
    }
}
