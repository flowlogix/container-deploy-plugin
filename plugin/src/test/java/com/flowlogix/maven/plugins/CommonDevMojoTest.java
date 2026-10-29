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

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.twdata.maven.mojoexecutor.MojoExecutor;
import java.util.Properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.twdata.maven.mojoexecutor.MojoExecutor.configuration;
import static org.twdata.maven.mojoexecutor.MojoExecutor.element;

@ExtendWith(MockitoExtension.class)
class CommonDevMojoTest {
    private final CommonDevMojo mojo = new CommonDevMojo() {
        @Override
        public void execute() {
        }
    };
    @Mock
    private Log log;
    @Mock(strictness = Mock.Strictness.LENIENT)
    private MavenSession session;
    @Mock
    private BuildPluginManager pluginManager;
    @Mock(answer = Answers.CALLS_REAL_METHODS)
    private MockedStatic<MojoExecutor> executor;
    private final Plugin dependencyPlugin = new Plugin();
    private final Properties userProperties = new Properties();
    private final Properties systemProperties = new Properties();

    @BeforeEach
    void setUp() {
        mojo.project = new MavenProject();
        mojo.session = session;
        mojo.pluginManager = pluginManager;
        mojo.mojoExecution = new MojoExecution(new MojoDescriptor());
        mojo.setLog(log);
        when(mojo.session.getCurrentProject()).thenReturn(mojo.project);
        when(mojo.session.getUserProperties()).thenReturn(userProperties);
        when(mojo.session.getSystemProperties()).thenReturn(systemProperties);
        dependencyPlugin.setGroupId(CommonDevMojo.ORG_APACHE_MAVEN_PLUGINS);
        dependencyPlugin.setArtifactId(CommonDevMojo.MAVEN_DEPENDENCY_PLUGIN);
        mojo.project.getBuild().addPlugin(dependencyPlugin);
    }

    @ParameterizedTest
    @ValueSource(strings = {"project", "user", "system"})
    void acceptsProperties(String source) {
        var properties = switch (source) {
            case "user" -> userProperties;
            case "system" -> systemProperties;
            default -> mojo.project.getProperties();
        };
        properties.setProperty("includeScope", "runtime");
        properties.setProperty("excludeArtifactIds", "postgresql, checker-qual, asm");
        copyDependencies();
        verify(log, never()).warn(anyString());
    }

    @ParameterizedTest
    @CsvSource({
        "runtime, postgresql, false",
        "test, postgresql, true",
        "runtime, , true",
        ", postgresql, true",
        ", , true"
    })
    void checksPluginConfiguration(String scope, String exclusions, boolean warning) {
        dependencyPlugin.setConfiguration(configuration(
                element("includeScope", scope), element("excludeArtifactIds", exclusions)));
        copyDependencies();
        if (warning) {
            verify(log, atLeastOnce()).warn(anyString());
        } else {
            verify(log, never()).warn(anyString());
        }
    }

    @Test
    void resolvesConfigurationExpressions() {
        dependencyPlugin.setConfiguration(configuration(element("includeScope", "${copy.scope}"),
                element("excludeArtifactIds", "${copy.exclusions}")));
        mojo.project.getProperties().setProperty("copy.scope", "runtime");
        mojo.project.getProperties().setProperty("copy.exclusions", "postgresql");
        copyDependencies();
        verify(log, never()).warn(anyString());
    }

    @Test
    void honorsExplicitConfigurationOverProperties() {
        dependencyPlugin.setConfiguration(configuration(element("includeScope", "test")));
        mojo.project.getProperties().setProperty("includeScope", "runtime");
        mojo.project.getProperties().setProperty("excludeArtifactIds", "postgresql");
        copyDependencies();
        verify(log, atLeastOnce()).warn(anyString());
    }

    @Test
    void honorsCommandLineOverride() {
        mojo.project.getProperties().setProperty("includeScope", "test");
        mojo.project.getProperties().setProperty("excludeArtifactIds", "postgresql");
        userProperties.setProperty("includeScope", "runtime");
        copyDependencies();
        verify(log, never()).warn(anyString());
    }

    @Test
    void preservesExplicitExecutionBehavior() {
        var execution = new PluginExecution();
        execution.setId("default-cli");
        dependencyPlugin.addExecution(execution);
        copyDependencies();
        verify(log, never()).warn(anyString());
    }

    @Test
    void acceptsPropertiesWithoutPluginDeclaration() {
        mojo.project.getBuild().getPlugins().clear();
        mojo.project.getProperties().setProperty("includeScope", "runtime");
        mojo.project.getProperties().setProperty("excludeArtifactIds", "postgresql");
        copyDependencies();
        verify(log, never()).warn(anyString());
    }

    private void copyDependencies() {
        executor.when(() -> MojoExecutor.executeMojo(
                any(Plugin.class), eq("copy-dependencies"),
                any(Xpp3Dom.class), any(MojoExecutor.ExecutionEnvironment.class)))
                .thenAnswer(invocation -> null);
        assertThat(mojo.copyDependencies("target/dependencies")).isTrue();
        executor.verify(() -> MojoExecutor.executeMojo(
                any(Plugin.class), eq("copy-dependencies"),
                any(Xpp3Dom.class), any(MojoExecutor.ExecutionEnvironment.class)));
    }
}
