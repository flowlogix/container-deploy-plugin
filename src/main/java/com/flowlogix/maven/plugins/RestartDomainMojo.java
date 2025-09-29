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
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Goal which restarts the application server.
 * Works for both Payara and GlassFish servers.
 */
@Mojo(name = "restart", requiresProject = false, threadSafe = true)
public class RestartDomainMojo extends CommonDevMojo {
    @Override
    public void execute() throws MojoFailureException {
        if (!deployer.pingServer()) {
            throw new MojoFailureException("Server is not running.");
        }
        if (deployer.sendCommand("restart-domain", Map.of(),
                deployer::printResponse) == CommandResult.ERROR) {
            throw new MojoFailureException("Restart failed, see log for details.");
        }
        @SuppressWarnings("checkstyle:MagicNumber")
        boolean serverStarted = IntStream.range(0, 30).anyMatch(this::ping);
        if (serverStarted) {
            getLog().info("Application Server restarted.");
        } else {
            getLog().warn("Application Server restart timed out after 30 seconds.");
        }
    }

    @SneakyThrows(InterruptedException.class)
    @SuppressWarnings("checkstyle:MagicNumber")
    private boolean ping(int attempt) {
        Thread.sleep(1000);
        return deployer.pingServer();
    }
}
