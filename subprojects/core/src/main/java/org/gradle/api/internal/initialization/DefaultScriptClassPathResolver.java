/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.api.internal.initialization;

import com.google.common.collect.Ordering;
import org.gradle.api.Action;
import org.gradle.api.JavaVersion;
import org.gradle.api.artifacts.ArtifactCollection;
import org.gradle.api.artifacts.ArtifactView;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.attributes.Bundling;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.attributes.Usage;
import org.gradle.api.attributes.java.TargetJvmVersion;
import org.gradle.api.attributes.plugin.GradlePluginApiVersion;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.artifacts.dsl.DependencyHandlerInternal;
import org.gradle.api.internal.artifacts.dsl.dependencies.DependencyFactoryInternal;
import org.gradle.api.internal.initialization.transform.BaseInstrumentingArtifactTransform;
import org.gradle.api.internal.initialization.transform.ExternalDependencyInstrumentingArtifactTransform;
import org.gradle.api.internal.initialization.transform.InstrumentationAnalysisTransform;
import org.gradle.api.internal.initialization.transform.MergeInstrumentationAnalysisTransform;
import org.gradle.api.internal.initialization.transform.ProjectDependencyInstrumentingArtifactTransform;
import org.gradle.api.internal.initialization.transform.services.CacheInstrumentationDataBuildService;
import org.gradle.api.internal.model.NamedObjectInstantiator;
import org.gradle.api.internal.provider.Providers;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.provider.Provider;
import org.gradle.internal.agents.AgentStatus;
import org.gradle.internal.classpath.ClassPath;
import org.gradle.internal.classpath.DefaultClassPath;
import org.gradle.internal.classpath.TransformedClassPath;
import org.gradle.internal.component.local.model.OpaqueComponentIdentifier;
import org.gradle.internal.component.local.model.TransformedComponentFileArtifactIdentifier;
import org.gradle.internal.id.IdGenerator;
import org.gradle.internal.id.LongIdGenerator;
import org.gradle.internal.logging.util.Log4jBannedVersion;
import org.gradle.util.GradleVersion;

import java.io.File;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkArgument;
import static org.gradle.api.internal.initialization.DefaultScriptClassPathResolver.InstrumentationPhase.ANALYZED_ARTIFACT;
import static org.gradle.api.internal.initialization.DefaultScriptClassPathResolver.InstrumentationPhase.INSTRUMENTED_AND_UPGRADED;
import static org.gradle.api.internal.initialization.DefaultScriptClassPathResolver.InstrumentationPhase.INSTRUMENTED_ONLY;
import static org.gradle.api.internal.initialization.DefaultScriptClassPathResolver.InstrumentationPhase.MERGED_ARTIFACT_ANALYSIS;
import static org.gradle.api.internal.initialization.DefaultScriptClassPathResolver.InstrumentationPhase.NOT_INSTRUMENTED;
import static org.gradle.internal.classpath.TransformedClassPath.ORIGINAL_FILE_PLACEHOLDER_SUFFIX;

public class DefaultScriptClassPathResolver implements ScriptClassPathResolver {

    private static final Set<DependencyFactoryInternal.ClassPathNotation> GRADLE_API_NOTATIONS = EnumSet.of(
        DependencyFactoryInternal.ClassPathNotation.GRADLE_API,
        DependencyFactoryInternal.ClassPathNotation.LOCAL_GROOVY
    );

    public enum InstrumentationPhase {
        NOT_INSTRUMENTED("not-instrumented"),
        ANALYZED_ARTIFACT("analyzed-artifact"),
        MERGED_ARTIFACT_ANALYSIS("merged-artifact-analysis"),
        INSTRUMENTED_AND_UPGRADED("instrumented-and-upgraded"),
        INSTRUMENTED_ONLY("instrumented-only");

        private final String value;

        InstrumentationPhase(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    public static final Attribute<String> INSTRUMENTED_ATTRIBUTE = Attribute.of("org.gradle.internal.instrumented", String.class);
    private final NamedObjectInstantiator instantiator;
    private final AgentStatus agentStatus;
    private final Gradle gradle;
    private final IdGenerator<Long> serviceId;

    public DefaultScriptClassPathResolver(
        NamedObjectInstantiator instantiator,
        AgentStatus agentStatus,
        Gradle gradle
    ) {
        this.instantiator = instantiator;
        this.agentStatus = agentStatus;
        this.gradle = gradle;
        this.serviceId = new LongIdGenerator();
    }

    @Override
    public ScriptClassPathResolutionContext prepareDependencyHandler(DependencyHandler dependencyHandler) {
        ((DependencyHandlerInternal) dependencyHandler).getDefaultArtifactAttributes()
            .attribute(INSTRUMENTED_ATTRIBUTE, NOT_INSTRUMENTED.value);

        // Register instrumentation pipelines
        Provider<CacheInstrumentationDataBuildService> service = registerNewService();
        registerInstrumentationAndUpgradesPipeline(dependencyHandler, service);
        registerInstrumentationOnlyPipeline(dependencyHandler);
        return new ScriptClassPathResolutionContext(service, dependencyHandler);
    }

    private void registerInstrumentationAndUpgradesPipeline(DependencyHandler dependencyHandler, Provider<CacheInstrumentationDataBuildService> service) {
        dependencyHandler.registerTransform(
            InstrumentationAnalysisTransform.class,
            spec -> {
                spec.getFrom().attribute(INSTRUMENTED_ATTRIBUTE, NOT_INSTRUMENTED.value);
                spec.getTo().attribute(INSTRUMENTED_ATTRIBUTE, ANALYZED_ARTIFACT.value);
                spec.parameters(params -> params.getBuildService().set(service));
            }
        );
        dependencyHandler.registerTransform(
            MergeInstrumentationAnalysisTransform.class,
            spec -> {
                spec.getFrom().attribute(INSTRUMENTED_ATTRIBUTE, ANALYZED_ARTIFACT.value);
                spec.getTo().attribute(INSTRUMENTED_ATTRIBUTE, MERGED_ARTIFACT_ANALYSIS.value);
                spec.parameters(params -> {
                    params.getBuildService().set(service);
                    params.getOriginalClasspath().setFrom(service.map(it -> it.getParameters().getOriginalClasspath()));
                });
            }
        );
        registerInstrumentingTransform(dependencyHandler, ExternalDependencyInstrumentingArtifactTransform.class, service, MERGED_ARTIFACT_ANALYSIS, INSTRUMENTED_AND_UPGRADED);
    }

    private void registerInstrumentationOnlyPipeline(DependencyHandler dependencyHandler) {
        registerInstrumentingTransform(dependencyHandler, ProjectDependencyInstrumentingArtifactTransform.class, Providers.notDefined(), NOT_INSTRUMENTED, INSTRUMENTED_ONLY);
    }

    private void registerInstrumentingTransform(
        DependencyHandler dependencyHandler,
        Class<? extends BaseInstrumentingArtifactTransform> transform,
        Provider<CacheInstrumentationDataBuildService> service,
        InstrumentationPhase fromPhase,
        InstrumentationPhase toPhase
    ) {
        dependencyHandler.registerTransform(
            transform,
            spec -> {
                spec.getFrom().attribute(INSTRUMENTED_ATTRIBUTE, fromPhase.value);
                spec.getTo().attribute(INSTRUMENTED_ATTRIBUTE, toPhase.value);
                spec.parameters(params -> {
                    params.getBuildService().set(service);
                    params.getAgentSupported().set(agentStatus.isAgentInstrumentationEnabled());
                });
            }
        );
    }

    private Provider<CacheInstrumentationDataBuildService> registerNewService() {
        return gradle.getSharedServices().registerIfAbsent(
            "__InternalCacheInstrumentationDataBuildService__::" + serviceId.generateId(),
            CacheInstrumentationDataBuildService.class
        );
    }

    @Override
    public void prepareClassPath(Configuration configuration, ScriptClassPathResolutionContext resolutionContext) {
        // should ideally reuse the `JvmPluginServices` but this code is too low level
        // and this service is therefore not available!
        AttributeContainer attributes = configuration.getAttributes();
        attributes.attribute(Usage.USAGE_ATTRIBUTE, instantiator.named(Usage.class, Usage.JAVA_RUNTIME));
        attributes.attribute(Category.CATEGORY_ATTRIBUTE, instantiator.named(Category.class, Category.LIBRARY));
        attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, instantiator.named(LibraryElements.class, LibraryElements.JAR));
        attributes.attribute(Bundling.BUNDLING_ATTRIBUTE, instantiator.named(Bundling.class, Bundling.EXTERNAL));
        attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, Integer.parseInt(JavaVersion.current().getMajorVersion()));
        attributes.attribute(GradlePluginApiVersion.GRADLE_PLUGIN_API_VERSION_ATTRIBUTE, instantiator.named(GradlePluginApiVersion.class, GradleVersion.current().getVersion()));

        DependencyHandler dependencyHandler = resolutionContext.getDependencyHandler();
        configuration.getDependencyConstraints().add(dependencyHandler.getConstraints().create(Log4jBannedVersion.LOG4J2_CORE_COORDINATES, constraint -> constraint.version(version -> {
            version.require(Log4jBannedVersion.LOG4J2_CORE_REQUIRED_VERSION);
            version.reject(Log4jBannedVersion.LOG4J2_CORE_VULNERABLE_VERSION_RANGE);
        })));
    }

    @Override
    public ClassPath resolveClassPath(Configuration classpathConfiguration, ScriptClassPathResolutionContext resolutionContext) {
        // Clear cached data after resolution, so it can be reused for other classpath resolutions
        return resolutionContext.runAndClearCachedDataAfter(buildService -> {
            buildService.getParameters().getAnalysisResult().setFrom(getAnalysisResult(classpathConfiguration));
            buildService.getParameters().getOriginalClasspath().setFrom(classpathConfiguration);
            ArtifactCollection instrumentedExternalDependencies = getInstrumentedExternalDependencies(classpathConfiguration);
            ArtifactCollection instrumentedProjectDependencies = getInstrumentedProjectDependencies(classpathConfiguration);
            ClassPath instrumentedClasspath = combineToClasspath(buildService, classpathConfiguration, instrumentedExternalDependencies, instrumentedProjectDependencies);
            return TransformedClassPath.handleInstrumentingArtifactTransform(instrumentedClasspath);
        });
    }

    private static FileCollection getAnalysisResult(Configuration classpathConfiguration) {
        return classpathConfiguration.getIncoming().artifactView((Action<? super ArtifactView.ViewConfiguration>) config -> {
            config.attributes(it -> it.attribute(INSTRUMENTED_ATTRIBUTE, ANALYZED_ARTIFACT.value));
            config.componentFilter(componentId -> !isGradleApi(componentId) && !isProjectDependency(componentId));
        }).getFiles();
    }

    private ArtifactCollection getInstrumentedExternalDependencies(Configuration classpathConfiguration) {
        return classpathConfiguration.getIncoming().artifactView((Action<? super ArtifactView.ViewConfiguration>) config -> {
            config.attributes(it -> it.attribute(INSTRUMENTED_ATTRIBUTE, INSTRUMENTED_AND_UPGRADED.value));
            config.componentFilter(DefaultScriptClassPathResolver::isExternalDependency);
        }).getArtifacts();
    }

    private ArtifactCollection getInstrumentedProjectDependencies(Configuration classpathConfiguration) {
        return classpathConfiguration.getIncoming().artifactView((Action<? super ArtifactView.ViewConfiguration>) config -> {
            config.attributes(it -> it.attribute(INSTRUMENTED_ATTRIBUTE, INSTRUMENTED_ONLY.value));
            config.componentFilter(DefaultScriptClassPathResolver::isProjectDependency);
        }).getArtifacts();
    }

    private static boolean isGradleApi(ComponentIdentifier componentId) {
        if (componentId instanceof OpaqueComponentIdentifier) {
            DependencyFactoryInternal.ClassPathNotation classPathNotation = ((OpaqueComponentIdentifier) componentId).getClassPathNotation();
            return DefaultScriptClassPathResolver.GRADLE_API_NOTATIONS.contains(classPathNotation);
        }
        return false;
    }

    private static boolean isProjectDependency(ComponentIdentifier componentId) {
        return componentId instanceof ProjectComponentIdentifier;
    }

    private static boolean isExternalDependency(ComponentIdentifier componentId) {
        return !isGradleApi(componentId) && !isProjectDependency(componentId);
    }


    /**
     * Combines external dependencies and project dependencies to one classpath that is sorted based on the original classpath.
     */
    private static ClassPath combineToClasspath(CacheInstrumentationDataBuildService buildService, Configuration classpathConfiguration, ArtifactCollection externalDependencies, ArtifactCollection projectDependencies) {
        Set<ResolvedArtifactResult> originalArtifacts = classpathConfiguration.getIncoming()
            .artifactView(config -> config.componentFilter(id -> !isGradleApi(id)))
            .getArtifacts().getArtifacts();
        List<OriginalArtifactIdentifier> identifiers = originalArtifacts.stream()
            .map(OriginalArtifactIdentifier::of)
            // In some cases we end up with the same artifact multiple times in different locations,
            // additional user's artifact transform can be injected in between and could produce multiple artifacts from one original artifact.
            .distinct()
            .collect(Collectors.toList());

        Ordering<OriginalArtifactIdentifier> ordering = Ordering.explicit(identifiers);
        List<File> classpath = Stream.concat(externalDependencies.getArtifacts().stream(), projectDependencies.getArtifacts().stream())
            .map(ClassPathTransformedArtifact::ofTransformedArtifact)
            // We sort based on the original classpath to we keep the original order,
            // we also rely on the fact that for ordered streams `sorted()` method has stable sort.
            .sorted((first, second) -> ordering.compare(first.originalIdentifier, second.originalIdentifier))
            .map(artifact -> getOriginalFile(buildService, artifact))
            .collect(Collectors.toList());
        return DefaultClassPath.of(classpath);
    }

    private static File getOriginalFile(CacheInstrumentationDataBuildService buildService, ClassPathTransformedArtifact artifact) {
        if (artifact.file.getName().endsWith(ORIGINAL_FILE_PLACEHOLDER_SUFFIX)) {
            String hash = artifact.file.getName().replace(ORIGINAL_FILE_PLACEHOLDER_SUFFIX, "");
            return buildService.getOriginalFile(hash);
        }
        return artifact.file;
    }

    private static class ClassPathTransformedArtifact {
        private final File file;
        private final OriginalArtifactIdentifier originalIdentifier;

        private ClassPathTransformedArtifact(File file, OriginalArtifactIdentifier originalIdentifier) {
            this.file = file;
            this.originalIdentifier = originalIdentifier;
        }

        public static ClassPathTransformedArtifact ofTransformedArtifact(ResolvedArtifactResult transformedArtifact) {
            checkArgument(transformedArtifact.getId() instanceof TransformedComponentFileArtifactIdentifier);
            return new ClassPathTransformedArtifact(transformedArtifact.getFile(), OriginalArtifactIdentifier.of(transformedArtifact));
        }
    }

    private static class OriginalArtifactIdentifier {
        private final String originalFileName;
        private final ComponentIdentifier componentIdentifier;

        private OriginalArtifactIdentifier(String originalFileName, ComponentIdentifier componentIdentifier) {
            this.originalFileName = originalFileName;
            this.componentIdentifier = componentIdentifier;
        }

        private static OriginalArtifactIdentifier of(ResolvedArtifactResult artifact) {
            if (artifact.getId() instanceof TransformedComponentFileArtifactIdentifier) {
                TransformedComponentFileArtifactIdentifier identifier = (TransformedComponentFileArtifactIdentifier) artifact.getId();
                return new OriginalArtifactIdentifier(identifier.getOriginalFileName(), identifier.getComponentIdentifier());
            } else {
                return new OriginalArtifactIdentifier(artifact.getFile().getName(), artifact.getId().getComponentIdentifier());
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            OriginalArtifactIdentifier that = (OriginalArtifactIdentifier) o;
            return Objects.equals(originalFileName, that.originalFileName) && Objects.equals(componentIdentifier, that.componentIdentifier);
        }

        @Override
        public int hashCode() {
            return Objects.hash(originalFileName, componentIdentifier);
        }
    }
}
