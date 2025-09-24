package com.flowlogix.maven.plugins;

import org.apache.maven.plugins.annotations.Mojo;

@Mojo(name = "undeploy", requiresProject = false, threadSafe = true)
public class UndeployMojo extends CommonDevMojo {
    @Override
    public void execute() {
        getLog().info("Undeploying application...");
        if (deployer.sendUndeployCommand() == DevMojo.CommandResult.ERROR) {
            throw new RuntimeException("Undeployment failed, see log for details.");
        }
        getLog().info("Application undeployed.");
    }
}
