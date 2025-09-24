package com.flowlogix.maven.plugins;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import javax.inject.Inject;

@Mojo(name = "undeploy", requiresProject = false, threadSafe = true)
public class Undeploy extends AbstractMojo {
    @Inject
    JakartaEEDeployPlugin jakartaEEDeployPlugin;

    @Parameter(defaultValue = "http://localhost:4848", property = "payara.adminUrl")
    private String payaraAminURL;

    @Override
    public void execute() {
        getLog().info("Undeploying application...");
        jakartaEEDeployPlugin.sendUndeployCommand(payaraAminURL);
        getLog().info("Application undeployed.");
    }
}
