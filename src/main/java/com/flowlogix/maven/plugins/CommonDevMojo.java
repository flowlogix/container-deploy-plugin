package com.flowlogix.maven.plugins;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import javax.inject.Inject;
import static org.twdata.maven.mojoexecutor.MojoExecutor.artifactId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executeMojo;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executionEnvironment;
import static org.twdata.maven.mojoexecutor.MojoExecutor.goal;
import static org.twdata.maven.mojoexecutor.MojoExecutor.groupId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.plugin;

abstract class CommonDevMojo extends AbstractMojo {
    @Inject
    MavenProject project;

    @Inject
    MavenSession session;

    @Inject
    BuildPluginManager pluginManager;

    @Parameter(defaultValue = "http://localhost:4848", property = "payara.adminUrl")
    String payaraAminURL;

    @Parameter(defaultValue = "8080", property = "payara.httpPort")
    String payaraHttpPort;

    @Parameter(defaultValue = "false", property = "payara.force")
    boolean force;

    @Parameter(defaultValue = "true", property = "payara.warlibs")
    boolean warlibs;

    @Parameter(defaultValue = "true", property = "payara.availabilityenabled")
    boolean availabilityenabled;

    @Parameter(defaultValue = "false", property = "payara.keepstate")
    boolean keepstate;

    final Deployer deployer = new Deployer(this);
    final Watcher watcher = new Watcher(this);

    boolean callGenericMojo(String groupId, String artifactId, String goal,
                            MavenProject project, MavenSession session,
                            BuildPluginManager pluginManager) {
        try {
            executeMojo(
                    plugin(groupId(groupId), artifactId(artifactId)),
                    goal(goal),
                    (Xpp3Dom) project.getPlugin("%s:%s".formatted(groupId, artifactId)).getConfiguration(),
                    executionEnvironment(project, session, pluginManager)
            );
            return true;
        } catch (MojoExecutionException e) {
            return false;
        }
    }
}
