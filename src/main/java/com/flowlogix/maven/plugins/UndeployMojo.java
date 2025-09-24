package com.flowlogix.maven.plugins;

import com.flowlogix.maven.plugins.Deployer.CommandResult;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;

@Mojo(name = "undeploy", requiresProject = false, threadSafe = true)
public class UndeployMojo extends CommonDevMojo {
    @Override
    public void execute() throws MojoFailureException {
        if (deployer.sendUndeployCommand(deployer::printResponse) == CommandResult.ERROR) {
            throw new MojoFailureException("Undeployment failed, see log for details.");
        }
        getLog().info("Application undeployed.");
    }
}
