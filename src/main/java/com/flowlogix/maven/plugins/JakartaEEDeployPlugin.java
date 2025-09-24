package com.flowlogix.maven.plugins;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.project.MavenProject;
import javax.inject.Inject;
import static org.twdata.maven.mojoexecutor.MojoExecutor.artifactId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.configuration;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executeMojo;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executionEnvironment;
import static org.twdata.maven.mojoexecutor.MojoExecutor.goal;
import static org.twdata.maven.mojoexecutor.MojoExecutor.groupId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.plugin;

@Mojo(name = "execute", requiresProject = false, threadSafe = true)
public class JakartaEEDeployPlugin extends AbstractMojo {
    @Inject
    MavenProject project;

    @Inject
    MavenSession session;

    @Inject
    BuildPluginManager pluginManager;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        getLog().info("Hello from JakartaEEDeployPlugin");
        if (project == null || project.getFile() == null) {
            getLog().warn("No Maven project found, skipping execution.");
            return;
        }
        executeMojo(
                plugin(groupId("org.apache.maven.plugins"),
                        artifactId("maven-war-plugin")),
                goal("exploded"),
                configuration(),
                executionEnvironment(project, session, pluginManager)
        );
    }
}
