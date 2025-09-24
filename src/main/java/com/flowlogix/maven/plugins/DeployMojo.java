package com.flowlogix.maven.plugins;

import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;

@Mojo(name = "deploy", requiresProject = false, threadSafe = true)
public class DeployMojo extends CommonDevMojo {
    @Override
    public void execute() throws MojoFailureException {
        getLog().info("Deploying application...");
        if (deployer.sendDeployCommand() == DevMojo.CommandResult.ERROR) {
            throw new MojoFailureException("Deployment failed, see log for details.");
        }
        getLog().info("Application deployed.");
    }
}
