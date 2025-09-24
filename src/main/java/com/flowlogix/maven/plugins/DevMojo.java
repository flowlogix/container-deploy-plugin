package com.flowlogix.maven.plugins;

import com.flowlogix.maven.plugins.Deployer.CommandResult;
import lombok.SneakyThrows;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

@Mojo(name = "dev", requiresProject = false, threadSafe = true,
        requiresDependencyResolution = ResolutionScope.COMPILE,
        requiresDependencyCollection = ResolutionScope.COMPILE)
public class DevMojo extends CommonDevMojo {
    /**
     * Group ID of Apache Maven Plugins
     */
    public static final String ORG_APACHE_MAVEN_PLUGINS = "org.apache.maven.plugins";
    private static final Set<String> CODE_CONTAINING_SRC_DIRS = Set.of(
            "java", "kotlin", "groovy", "scala", "clojure",
            "webapp/WEB-INF", "resources/META-INF"
    );

    @Override
    @SneakyThrows(IOException.class)
    public void execute() {
        if (project == null || project.getFile() == null) {
            getLog().warn("No Maven project found, skipping execution.");
            return;
        }

        Path explodedWarDir = Paths.get(project.getBuild().getDirectory(), project.getBuild().getFinalName());
        Path srcMainDir = Paths.get(project.getBasedir().getAbsolutePath(), "src", "main");
        getLog().info("Starting in dev mode, starting browser, monitoring %s for changes..."
                .formatted(srcMainDir));
        getLog().info("Exploded WAR directory: " + explodedWarDir);

        enableOrDeploy();
        watcher.watch(srcMainDir, this::onChange);
    }

    private void enableOrDeploy() throws IOException {
        if (deployer.sendEnableCommand() == CommandResult.ERROR) {
            deployer.sendDeployCommand();
        }
        String httpUrl = payaraAminURL.replaceFirst(":\\d+$", ":" + payaraHttpPort);
        Desktop.getDesktop().browse(URI.create("%s/%s".formatted(httpUrl, project.getBuild().getFinalName())));
    }

    private void onChange(Set<Path> modifiedFiles) {
        boolean codeChanged = modifiedFiles.stream().filter(this::isSourceCode).findAny()
                .map(var -> callGenericMojo(ORG_APACHE_MAVEN_PLUGINS,
                        "maven-compiler-plugin", "compile", project, session, pluginManager)).orElse(false);
        callGenericMojo(ORG_APACHE_MAVEN_PLUGINS,
                "maven-war-plugin", "exploded", project, session, pluginManager);
        if (codeChanged) {
            if (deployer.sendDisableCommand() == CommandResult.ERROR) {
                deployer.sendDeployCommand();
            } else {
                deployer.sendEnableCommand();
            }
        }
    }

    private boolean isSourceCode(Path path) {
        Path relativePath = project.getBasedir().toPath()
                .resolve("src/main").relativize(path);
        return CODE_CONTAINING_SRC_DIRS.stream().anyMatch(relativePath::startsWith);
    }
}
