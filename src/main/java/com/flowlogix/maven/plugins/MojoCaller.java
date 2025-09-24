package com.flowlogix.maven.plugins;

import lombok.SneakyThrows;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.artifact.resolver.filter.CumulativeScopeArtifactFilter;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.impl.ArtifactResolver;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.twdata.maven.mojoexecutor.MojoExecutor;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import static org.twdata.maven.mojoexecutor.MojoExecutor.artifactId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.configuration;
import static org.twdata.maven.mojoexecutor.MojoExecutor.element;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executeMojo;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executionEnvironment;
import static org.twdata.maven.mojoexecutor.MojoExecutor.goal;
import static org.twdata.maven.mojoexecutor.MojoExecutor.groupId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.plugin;

public class MojoCaller {
    @SneakyThrows(DependencyResolutionRequiredException.class)
    public void callMojo(MavenProject project, MavenSession session, ArtifactResolver resolver,
                         BuildPluginManager pluginManager, boolean compile, boolean explode) throws MojoExecutionException {
        List<String> compileClasspathElements = project.getDependencies().stream()
                .filter(dep -> "compile".equals(dep.getScope()) || "provided".equals(dep.getScope()))
                .map(dep -> resolvePath(dep, resolver, project, session))
                .filter(Objects::nonNull)
                .toList();

        project.setResolvedArtifacts(project.getDependencies().stream()
                .map(tdep -> resolveArtifact(tdep, project, session, resolver))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));
        String classpath = String.join(":", compileClasspathElements);

        MojoExecutor.Element compilerArgs = element("compilerArgs",
                element("arg", "-classpath"),
                element("arg", classpath)
        );

        project.setArtifactFilter(new CumulativeScopeArtifactFilter(List.of("compile", "provided")));
        project.getArtifacts();
        project.getCompileClasspathElements();

        if (compile) {
            executeMojo(
                    plugin(groupId("org.apache.maven.plugins"),
                            artifactId("maven-compiler-plugin")),
                    goal("compile"),
                    configuration(),
                    executionEnvironment(project, session, pluginManager)
            );
        }
        if (explode) {
            executeMojo(
                    plugin(groupId("org.apache.maven.plugins"),
                            artifactId("maven-war-plugin")),
                    goal("exploded"),
                    configuration(),
                    executionEnvironment(project, session, pluginManager)
            );
        }
    }

    String resolvePath(Dependency dep, ArtifactResolver resolver, MavenProject project, MavenSession session) {
        Artifact result = resolveArtifact(dep, project, session, resolver);
        if (result != null) {
            return result.getFile().getAbsolutePath();
        }
        return null;
    }

    @SneakyThrows(ArtifactResolutionException.class)
    Artifact resolveArtifact(Dependency dep, MavenProject project, MavenSession session, ArtifactResolver artifactResolver) {
        RepositorySystemSession repoSession = session.getRepositorySession();
        List<RemoteRepository> repos = project.getRemoteProjectRepositories();

        DefaultArtifact artifact = new DefaultArtifact(
                dep.getGroupId(), dep.getArtifactId(), dep.getClassifier(), dep.getType(), dep.getVersion()
        );
        ArtifactRequest request = new ArtifactRequest();
        request.setArtifact(artifact);
        request.setRepositories(repos);
        ArtifactResult result = artifactResolver.resolveArtifact(repoSession, request);
        if (result.isResolved() && result.getArtifact().getFile() != null) {
            var handler = new DefaultArtifactHandler();
            handler.setAddedToClasspath(true);
            org.apache.maven.artifact.Artifact mavenArtifact = new org.apache.maven.artifact.DefaultArtifact(
                    dep.getGroupId(), dep.getArtifactId(), dep.getVersion(), dep.getScope(),
                    dep.getType(), dep.getClassifier(), handler);
            mavenArtifact.setFile(result.getArtifact().getFile());
            mavenArtifact.setResolved(true);
            return mavenArtifact;
        }
        return null;
    }
}
