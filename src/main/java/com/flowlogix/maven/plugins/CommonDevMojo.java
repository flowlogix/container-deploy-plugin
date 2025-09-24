package com.flowlogix.maven.plugins;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import javax.inject.Inject;

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
}
