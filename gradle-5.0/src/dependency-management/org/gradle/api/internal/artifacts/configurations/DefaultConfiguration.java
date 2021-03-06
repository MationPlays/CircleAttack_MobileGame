/*
 * Copyright 2011 the original author or authors.
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

package org.gradle.api.internal.artifacts.configurations;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import groovy.lang.Closure;
import org.gradle.api.Action;
import org.gradle.api.Describable;
import org.gradle.api.DomainObjectSet;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.ArtifactCollection;
import org.gradle.api.artifacts.ArtifactView;
import org.gradle.api.artifacts.ConfigurablePublishArtifact;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationPublications;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencyConstraint;
import org.gradle.api.artifacts.DependencyConstraintSet;
import org.gradle.api.artifacts.DependencyResolutionListener;
import org.gradle.api.artifacts.DependencySet;
import org.gradle.api.artifacts.ExcludeRule;
import org.gradle.api.artifacts.FileCollectionDependency;
import org.gradle.api.artifacts.PublishArtifact;
import org.gradle.api.artifacts.PublishArtifactSet;
import org.gradle.api.artifacts.ResolutionStrategy;
import org.gradle.api.artifacts.ResolvableDependencies;
import org.gradle.api.artifacts.ResolveException;
import org.gradle.api.artifacts.ResolvedConfiguration;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolutionResult;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.capabilities.Capability;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.CompositeDomainObjectSet;
import org.gradle.api.internal.DefaultDomainObjectSet;
import org.gradle.api.internal.DocumentationRegistry;
import org.gradle.api.internal.DomainObjectContext;
import org.gradle.api.internal.artifacts.ConfigurationResolver;
import org.gradle.api.internal.artifacts.DefaultDependencyConstraintSet;
import org.gradle.api.internal.artifacts.DefaultDependencySet;
import org.gradle.api.internal.artifacts.DefaultExcludeRule;
import org.gradle.api.internal.artifacts.DefaultPublishArtifactSet;
import org.gradle.api.internal.artifacts.DefaultResolverResults;
import org.gradle.api.internal.artifacts.ExcludeRuleNotationConverter;
import org.gradle.api.internal.artifacts.Module;
import org.gradle.api.internal.artifacts.ResolverResults;
import org.gradle.api.internal.artifacts.dependencies.DefaultDependencyConstraint;
import org.gradle.api.internal.artifacts.dsl.dependencies.ProjectFinder;
import org.gradle.api.internal.artifacts.ivyservice.DefaultLenientConfiguration;
import org.gradle.api.internal.artifacts.ivyservice.ResolvedArtifactCollectingVisitor;
import org.gradle.api.internal.artifacts.ivyservice.ResolvedFilesCollectingVisitor;
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.RootComponentMetadataBuilder;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.BuildDependenciesVisitor;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.SelectedArtifactSet;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.projectresult.ResolvedProjectConfiguration;
import org.gradle.api.internal.attributes.AttributeContainerInternal;
import org.gradle.api.internal.attributes.ImmutableAttributeContainerWithErrorMessage;
import org.gradle.api.internal.attributes.ImmutableAttributes;
import org.gradle.api.internal.attributes.ImmutableAttributesFactory;
import org.gradle.api.internal.file.AbstractFileCollection;
import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.api.internal.file.FileCollectionInternal;
import org.gradle.api.internal.file.FileSystemSubset;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.project.ProjectState;
import org.gradle.api.internal.project.ProjectStateRegistry;
import org.gradle.api.internal.tasks.AbstractTaskDependency;
import org.gradle.api.internal.tasks.TaskDependencyResolveContext;
import org.gradle.api.specs.Spec;
import org.gradle.api.specs.Specs;
import org.gradle.api.tasks.TaskDependency;
import org.gradle.initialization.ProjectAccessListener;
import org.gradle.internal.Cast;
import org.gradle.internal.Describables;
import org.gradle.internal.DisplayName;
import org.gradle.internal.Factories;
import org.gradle.internal.Factory;
import org.gradle.internal.ImmutableActionSet;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.component.model.ComponentResolveMetadata;
import org.gradle.internal.event.ListenerBroadcast;
import org.gradle.internal.event.ListenerManager;
import org.gradle.internal.operations.BuildOperationContext;
import org.gradle.internal.operations.BuildOperationDescriptor;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.RunnableBuildOperation;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.internal.typeconversion.NotationParser;
import org.gradle.listener.ClosureBackedMethodInvocationDispatch;
import org.gradle.util.CollectionUtils;
import org.gradle.util.DeprecationLogger;
import org.gradle.util.Path;
import org.gradle.util.WrapUtil;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.gradle.api.internal.artifacts.configurations.ConfigurationInternal.InternalState.*;
import static org.gradle.util.ConfigureUtil.configure;

public class DefaultConfiguration extends AbstractFileCollection implements ConfigurationInternal, MutationValidator {

    private static final Action<Throwable> DEFAULT_ERROR_HANDLER = new Action<Throwable>() {
        @Override
        public void execute(Throwable throwable) {
            throw UncheckedException.throwAsUncheckedException(throwable);
        }
    };

    private final ConfigurationResolver resolver;
    private final ListenerManager listenerManager;
    private final DependencyMetaDataProvider metaDataProvider;
    private final DefaultDependencySet dependencies;
    private final DefaultDependencyConstraintSet dependencyConstraints;
    private final DefaultDomainObjectSet<Dependency> ownDependencies;
    private final DefaultDomainObjectSet<DependencyConstraint> ownDependencyConstraints;
    private CompositeDomainObjectSet<Dependency> inheritedDependencies;
    private CompositeDomainObjectSet<DependencyConstraint> inheritedDependencyConstraints;
    private DefaultDependencySet allDependencies;
    private DefaultDependencyConstraintSet allDependencyConstraints;
    private ImmutableActionSet<DependencySet> defaultDependencyActions = ImmutableActionSet.empty();
    private ImmutableActionSet<DependencySet> withDependencyActions = ImmutableActionSet.empty();
    private final DefaultPublishArtifactSet artifacts;
    private final DefaultDomainObjectSet<PublishArtifact> ownArtifacts;
    private CompositeDomainObjectSet<PublishArtifact> inheritedArtifacts;
    private DefaultPublishArtifactSet allArtifacts;
    private final ConfigurationResolvableDependencies resolvableDependencies;
    private ListenerBroadcast<DependencyResolutionListener> dependencyResolutionListeners;
    private final BuildOperationExecutor buildOperationExecutor;
    private final Instantiator instantiator;
    private final NotationParser<Object, ConfigurablePublishArtifact> artifactNotationParser;
    private final NotationParser<Object, Capability> capabilityNotationParser;
    private final ProjectAccessListener projectAccessListener;
    private final ProjectFinder projectFinder;
    private Factory<ResolutionStrategyInternal> resolutionStrategyFactory;
    private ResolutionStrategyInternal resolutionStrategy;
    private final FileCollectionFactory fileCollectionFactory;
    private final DocumentationRegistry documentationRegistry;

    private final Set<MutationValidator> childMutationValidators = Sets.newHashSet();
    private final MutationValidator parentMutationValidator = new MutationValidator() {
        @Override
        public void validateMutation(MutationType type) {
            DefaultConfiguration.this.validateParentMutation(type);
        }
    };
    private final RootComponentMetadataBuilder rootComponentMetadataBuilder;
    private final ConfigurationsProvider configurationsProvider;

    private final Path identityPath;
    private final Path path;

    // These fields are not covered by mutation lock
    private final String name;
    private final DefaultConfigurationPublications outgoing;

    private boolean visible = true;
    private boolean transitive = true;
    private Set<Configuration> extendsFrom = new LinkedHashSet<Configuration>();
    private String description;
    private final Set<Object> excludeRules = new LinkedHashSet<Object>();
    private Set<ExcludeRule> parsedExcludeRules;

    private final ProjectStateRegistry.SafeExclusiveLock resolutionLock;
    private final Object observationLock = new Object();
    private volatile InternalState observedState = UNRESOLVED;
    private volatile InternalState resolvedState = UNRESOLVED;
    private boolean insideBeforeResolve;

    private ResolverResults cachedResolverResults;
    private boolean dependenciesModified;
    private boolean canBeConsumed = true;
    private boolean canBeResolved = true;

    private boolean canBeMutated = true;
    private AttributeContainerInternal configurationAttributes;
    private final DomainObjectContext domainObjectContext;
    private final ImmutableAttributesFactory attributesFactory;
    private final FileCollection intrinsicFiles;

    private final ProjectStateRegistry projectStateRegistry;

    private final DisplayName displayName;

    public DefaultConfiguration(DomainObjectContext domainObjectContext,
                                String name,
                                ConfigurationsProvider configurationsProvider,
                                ConfigurationResolver resolver,
                                ListenerManager listenerManager,
                                DependencyMetaDataProvider metaDataProvider,
                                Factory<ResolutionStrategyInternal> resolutionStrategyFactory,
                                ProjectAccessListener projectAccessListener,
                                ProjectFinder projectFinder,
                                FileCollectionFactory fileCollectionFactory,
                                BuildOperationExecutor buildOperationExecutor,
                                Instantiator instantiator,
                                NotationParser<Object, ConfigurablePublishArtifact> artifactNotationParser,
                                NotationParser<Object, Capability> capabilityNotationParser,
                                ImmutableAttributesFactory attributesFactory,
                                RootComponentMetadataBuilder rootComponentMetadataBuilder,
                                ProjectStateRegistry projectStateRegistry,
                                DocumentationRegistry documentationRegistry

    ) {
        this.identityPath = domainObjectContext.identityPath(name);
        this.name = name;
        this.configurationsProvider = configurationsProvider;
        this.resolver = resolver;
        this.listenerManager = listenerManager;
        this.metaDataProvider = metaDataProvider;
        this.resolutionStrategyFactory = resolutionStrategyFactory;
        this.projectAccessListener = projectAccessListener;
        this.projectFinder = projectFinder;
        this.fileCollectionFactory = fileCollectionFactory;
        this.dependencyResolutionListeners = listenerManager.createAnonymousBroadcaster(DependencyResolutionListener.class);
        this.buildOperationExecutor = buildOperationExecutor;
        this.instantiator = instantiator;
        this.artifactNotationParser = artifactNotationParser;
        this.capabilityNotationParser = capabilityNotationParser;
        this.attributesFactory = attributesFactory;
        this.configurationAttributes = attributesFactory.mutable();
        this.domainObjectContext = domainObjectContext;
        this.intrinsicFiles = new ConfigurationFileCollection(Specs.<Dependency>satisfyAll());
        this.projectStateRegistry = projectStateRegistry;
        this.documentationRegistry = documentationRegistry;
        this.resolutionLock = projectStateRegistry.newExclusiveOperationLock();
        this.resolvableDependencies = instantiator.newInstance(ConfigurationResolvableDependencies.class, this);

        displayName = Describables.memoize(new ConfigurationDescription(identityPath));

        this.ownDependencies = new DefaultDomainObjectSet<Dependency>(Dependency.class);
        this.ownDependencies.beforeCollectionChanges(validateMutationType(this, MutationType.DEPENDENCIES));
        this.ownDependencyConstraints = new DefaultDomainObjectSet<DependencyConstraint>(DependencyConstraint.class);
        this.ownDependencyConstraints.beforeCollectionChanges(validateMutationType(this, MutationType.DEPENDENCIES));

        this.dependencies = new DefaultDependencySet(Describables.of(displayName, "dependencies"), this, ownDependencies);
        this.dependencyConstraints = new DefaultDependencyConstraintSet(Describables.of(displayName, "dependency constraints"), ownDependencyConstraints);

        this.ownArtifacts = new DefaultDomainObjectSet<PublishArtifact>(PublishArtifact.class);
        this.ownArtifacts.beforeCollectionChanges(validateMutationType(this, MutationType.ARTIFACTS));

        this.artifacts = new DefaultPublishArtifactSet(Describables.of(displayName, "artifacts"), ownArtifacts, fileCollectionFactory);

        this.outgoing = instantiator.newInstance(DefaultConfigurationPublications.class, displayName, artifacts, new AllArtifactsProvider(), configurationAttributes, instantiator, artifactNotationParser, capabilityNotationParser, fileCollectionFactory, attributesFactory);
        this.rootComponentMetadataBuilder = rootComponentMetadataBuilder;
        path = domainObjectContext.projectPath(name);
    }

    private static Action<Void> validateMutationType(final MutationValidator mutationValidator, final MutationType type) {
        return new Action<Void>() {
            @Override
            public void execute(Void arg) {
                mutationValidator.validateMutation(type);
            }
        };
    }

    public String getName() {
        return name;
    }

    public State getState() {
        if (resolvedState == ARTIFACTS_RESOLVED || resolvedState == GRAPH_RESOLVED) {
            if (cachedResolverResults.hasError()) {
                return State.RESOLVED_WITH_FAILURES;
            } else {
                return State.RESOLVED;
            }
        } else {
            return State.UNRESOLVED;
        }
    }

    public InternalState getResolvedState() {
        return resolvedState;
    }

    public Module getModule() {
        return metaDataProvider.getModule();
    }

    public boolean isVisible() {
        return visible;
    }

    public Configuration setVisible(boolean visible) {
        validateMutation(MutationType.DEPENDENCIES);
        this.visible = visible;
        return this;
    }

    public Set<Configuration> getExtendsFrom() {
        return Collections.unmodifiableSet(extendsFrom);
    }

    public Configuration setExtendsFrom(Iterable<Configuration> extendsFrom) {
        validateMutation(MutationType.DEPENDENCIES);
        for (Configuration configuration : this.extendsFrom) {
            if (inheritedArtifacts != null) {
                inheritedArtifacts.removeCollection(configuration.getAllArtifacts());
            }
            if (inheritedDependencies != null) {
                inheritedDependencies.removeCollection(configuration.getAllDependencies());
            }
            if (inheritedDependencyConstraints != null) {
                inheritedDependencyConstraints.removeCollection(configuration.getAllDependencyConstraints());
            }
            ((ConfigurationInternal) configuration).removeMutationValidator(parentMutationValidator);
        }
        this.extendsFrom = new LinkedHashSet<Configuration>();
        for (Configuration configuration : extendsFrom) {
            extendsFrom(configuration);
        }
        return this;
    }

    public Configuration extendsFrom(Configuration... extendsFrom) {
        validateMutation(MutationType.DEPENDENCIES);
        for (Configuration configuration : extendsFrom) {
            if (configuration.getHierarchy().contains(this)) {
                throw new InvalidUserDataException(String.format(
                    "Cyclic extendsFrom from %s and %s is not allowed. See existing hierarchy: %s", this,
                    configuration, configuration.getHierarchy()));
            }
            if (this.extendsFrom.add(configuration)) {
                if (inheritedArtifacts != null) {
                    inheritedArtifacts.addCollection(configuration.getAllArtifacts());
                }
                if (inheritedDependencies != null) {
                    inheritedDependencies.addCollection(configuration.getAllDependencies());
                }
                if (inheritedDependencyConstraints != null) {
                    inheritedDependencyConstraints.addCollection(configuration.getAllDependencyConstraints());
                }
                ((ConfigurationInternal) configuration).addMutationValidator(parentMutationValidator);
            }
        }
        return this;
    }

    public boolean isTransitive() {
        return transitive;
    }

    public Configuration setTransitive(boolean transitive) {
        validateMutation(MutationType.DEPENDENCIES);
        this.transitive = transitive;
        return this;
    }

    public String getDescription() {
        return description;
    }

    public Configuration setDescription(String description) {
        this.description = description;
        return this;
    }

    public Set<Configuration> getHierarchy() {
        if (extendsFrom.isEmpty()) {
            return Collections.<Configuration>singleton(this);
        }
        Set<Configuration> result = WrapUtil.<Configuration>toLinkedSet(this);
        collectSuperConfigs(this, result);
        return result;
    }

    private void collectSuperConfigs(Configuration configuration, Set<Configuration> result) {
        for (Configuration superConfig : configuration.getExtendsFrom()) {
            if (result.contains(superConfig)) {
                result.remove(superConfig);
            }
            result.add(superConfig);
            collectSuperConfigs(superConfig, result);
        }
    }

    @Override
    public Configuration defaultDependencies(final Action<? super DependencySet> action) {
        validateMutation(MutationType.DEPENDENCIES);
        defaultDependencyActions = defaultDependencyActions.add(new Action<DependencySet>() {
            @Override
            public void execute(DependencySet dependencies) {
                if (dependencies.isEmpty()) {
                    action.execute(dependencies);
                }
            }
        });
        return this;
    }

    @Override
    public Configuration withDependencies(final Action<? super DependencySet> action) {
        validateMutation(MutationType.DEPENDENCIES);
        withDependencyActions = withDependencyActions.add(action);
        return this;
    }

    public void runDependencyActions() {
        defaultDependencyActions.execute(dependencies);
        withDependencyActions.execute(dependencies);

        // Discard actions after execution
        defaultDependencyActions = ImmutableActionSet.empty();
        withDependencyActions = ImmutableActionSet.empty();

        for (Configuration superConfig : extendsFrom) {
            ((ConfigurationInternal) superConfig).runDependencyActions();
        }
    }

    public Set<Configuration> getAll() {
        return ImmutableSet.<Configuration>copyOf(configurationsProvider.getAll());
    }

    public Set<File> resolve() {
        return getFiles();
    }

    @Override
    public Iterator<File> iterator() {
        return intrinsicFiles.iterator();
    }

    @Override
    public Set<File> getFiles() {
        return intrinsicFiles.getFiles();
    }

    @Override
    public boolean contains(File file) {
        return intrinsicFiles.contains(file);
    }

    @Override
    public boolean isEmpty() {
        return intrinsicFiles.isEmpty();
    }

    public Set<File> files(Dependency... dependencies) {
        return fileCollection(dependencies).getFiles();
    }

    public Set<File> files(Closure dependencySpecClosure) {
        return fileCollection(dependencySpecClosure).getFiles();
    }

    public Set<File> files(Spec<? super Dependency> dependencySpec) {
        return fileCollection(dependencySpec).getFiles();
    }

    public FileCollection fileCollection(Spec<? super Dependency> dependencySpec) {
        return new ConfigurationFileCollection(dependencySpec);
    }

    public FileCollection fileCollection(Closure dependencySpecClosure) {
        return new ConfigurationFileCollection(dependencySpecClosure);
    }

    public FileCollection fileCollection(Dependency... dependencies) {
        return new ConfigurationFileCollection(WrapUtil.toLinkedSet(dependencies));
    }

    public void markAsObserved(InternalState requestedState) {
        markThisObserved(requestedState);
        markParentsObserved(requestedState);
    }

    private void markThisObserved(InternalState requestedState) {
        synchronized (observationLock) {
            if (observedState.compareTo(requestedState) < 0) {
                observedState = requestedState;
            }
        }
    }

    private void markParentsObserved(InternalState requestedState) {
        for (Configuration configuration : extendsFrom) {
            ((ConfigurationInternal) configuration).markAsObserved(requestedState);
        }
    }

    public ResolvedConfiguration getResolvedConfiguration() {
        resolveToStateOrLater(ARTIFACTS_RESOLVED);
        return cachedResolverResults.getResolvedConfiguration();
    }

    private void resolveToStateOrLater(final InternalState requestedState) {
        assertIsResolvable();

        if (!hasMutableProjectState()) {
            // We don't have mutable access to the project, so we throw a deprecation warning and then continue with
            // lenient locking to prevent deadlocks in user-managed threads.
            DeprecationLogger.nagUserOfDeprecatedBehaviour("The configuration " + identityPath.toString() + " was resolved without accessing the project in a safe manner.  This may happen when a configuration is resolved from a thread not managed by Gradle or from a different project.  See " + documentationRegistry.getDocumentationFor("troubleshooting_dependency_resolution", "configuration_resolution_constraints") + " for more details.");
            projectStateRegistry.withLenientState(new Runnable() {
                @Override
                public void run() {
                    resolveExclusively(requestedState);
                }
            });
        } else {
            resolveExclusively(requestedState);
        }
    }

    private void resolveExclusively(InternalState requestedState) {
        resolutionLock.withLock(new Runnable() {
            @Override
            public void run() {
                if (requestedState == GRAPH_RESOLVED || requestedState == ARTIFACTS_RESOLVED) {
                    resolveGraphIfRequired(requestedState);
                }
                if (requestedState == ARTIFACTS_RESOLVED) {
                    resolveArtifactsIfRequired();
                }
            }
        });
    }

    private void resolveGraphIfRequired(final InternalState requestedState) {
        if (resolvedState == ARTIFACTS_RESOLVED || resolvedState == GRAPH_RESOLVED) {
            if (dependenciesModified) {
                throw new InvalidUserDataException(String.format("Attempted to resolve %s that has been resolved previously.", getDisplayName()));
            }
            return;
        }

        buildOperationExecutor.run(new RunnableBuildOperation() {
            @Override
            public void run(BuildOperationContext context) {
                runDependencyActions();
                preventFromFurtherMutation();

                final ResolvableDependenciesInternal incoming = (ResolvableDependenciesInternal) getIncoming();
                performPreResolveActions(incoming);
                if (cachedResolverResults == null) {
                    cachedResolverResults = new DefaultResolverResults();
                }
                resolver.resolveGraph(DefaultConfiguration.this, cachedResolverResults);
                dependenciesModified = false;
                resolvedState = GRAPH_RESOLVED;

                // Mark all affected configurations as observed
                markParentsObserved(requestedState);
                markReferencedProjectConfigurationsObserved(requestedState);

                if (!cachedResolverResults.hasError()) {
                    dependencyResolutionListeners.getSource().afterResolve(incoming);
                    // Discard listeners
                    dependencyResolutionListeners.removeAll();
                }
                captureBuildOperationResult(context);
            }

            private void captureBuildOperationResult(BuildOperationContext context) {
                Throwable failure = cachedResolverResults.getFailure();
                if (failure != null) {
                    context.failed(failure);
                }
                // When dependency resolution has failed, we don't want the build operation listeners to fail as well
                // because:
                // 1. the `failed` method will have been called with the user facing error
                // 2. such an error may still lead to a valid dependency graph
                context.setResult(new ResolveConfigurationResolutionBuildOperationResult(cachedResolverResults.getResolutionResult()));
            }

            @Override
            public BuildOperationDescriptor.Builder description() {
                String displayName = "Resolve dependencies of " + identityPath;
                Path projectPath = domainObjectContext.getProjectPath();
                String projectPathString = domainObjectContext.isScript() ? null : (projectPath == null ? null : projectPath.getPath());
                return BuildOperationDescriptor.displayName(displayName)
                    .progressDisplayName(displayName)
                    .details(new ResolveConfigurationResolutionBuildOperationDetails(
                        getName(),
                        domainObjectContext.isScript(),
                        getDescription(),
                        domainObjectContext.getBuildPath().getPath(),
                        projectPathString,
                        isVisible(),
                        isTransitive(),
                        resolver.getRepositories()
                    ));
            }
        });
    }

    private void performPreResolveActions(ResolvableDependencies incoming) {
        DependencyResolutionListener dependencyResolutionListener = dependencyResolutionListeners.getSource();
        insideBeforeResolve = true;
        try {
            dependencyResolutionListener.beforeResolve(incoming);
        } finally {
            insideBeforeResolve = false;
        }
    }

    private void markReferencedProjectConfigurationsObserved(final InternalState requestedState) {
        for (ResolvedProjectConfiguration projectResult : cachedResolverResults.getResolvedLocalComponents().getResolvedProjectConfigurations()) {
            ProjectInternal project = projectFinder.getProject(projectResult.getId().getProjectPath());
            ConfigurationInternal targetConfig = (ConfigurationInternal) project.getConfigurations().getByName(projectResult.getTargetConfiguration());
            targetConfig.markAsObserved(requestedState);
        }
    }

    private void resolveArtifactsIfRequired() {
        if (resolvedState == ARTIFACTS_RESOLVED) {
            return;
        }
        if (resolvedState != GRAPH_RESOLVED) {
            throw new IllegalStateException("Cannot resolve artifacts before graph has been resolved.");
        }
        resolver.resolveArtifacts(DefaultConfiguration.this, cachedResolverResults);
        resolvedState = ARTIFACTS_RESOLVED;
    }

    public TaskDependency getBuildDependencies() {
        assertIsResolvable();
        return intrinsicFiles.getBuildDependencies();
    }

    /**
     * {@inheritDoc}
     */
    public TaskDependency getTaskDependencyFromProjectDependency(final boolean useDependedOn, final String taskName) {
        if (useDependedOn) {
            return new TasksFromProjectDependencies(taskName, getAllDependencies(), projectAccessListener);
        } else {
            return new TasksFromDependentProjects(taskName, getName());
        }
    }

    public DependencySet getDependencies() {
        return dependencies;
    }

    public DependencySet getAllDependencies() {
        if (allDependencies == null) {
            initAllDependencies();
        }
        return allDependencies;
    }

    private synchronized void initAllDependencies() {
        if (allDependencies != null) {
            return;
        }
        inheritedDependencies = CompositeDomainObjectSet.create(Dependency.class, ownDependencies);
        for (Configuration configuration : this.extendsFrom) {
            inheritedDependencies.addCollection(configuration.getAllDependencies());
        }
        allDependencies = new DefaultDependencySet(Describables.of(displayName, "all dependencies"), this, inheritedDependencies);
    }

    @Override
    public DependencyConstraintSet getDependencyConstraints() {
        return dependencyConstraints;
    }

    @Override
    public DependencyConstraintSet getAllDependencyConstraints() {
        if (allDependencyConstraints == null) {
            initAllDependencyConstraints();
        }
        return allDependencyConstraints;
    }

    private synchronized void initAllDependencyConstraints() {
        if (allDependencyConstraints != null) {
            return;
        }
        inheritedDependencyConstraints = CompositeDomainObjectSet.create(DependencyConstraint.class, ownDependencyConstraints);
        for (Configuration configuration : this.extendsFrom) {
            inheritedDependencyConstraints.addCollection(configuration.getAllDependencyConstraints());
        }
        allDependencyConstraints = new DefaultDependencyConstraintSet(Describables.of(displayName, "all dependency constraints"), inheritedDependencyConstraints);
    }

    public PublishArtifactSet getArtifacts() {
        return artifacts;
    }

    public PublishArtifactSet getAllArtifacts() {
        initAllArtifacts();
        return allArtifacts;
    }

    private synchronized void initAllArtifacts() {
        if (allArtifacts != null) {
            return;
        }
        DisplayName displayName = Describables.of(this.displayName, "all artifacts");

        if (!canBeMutated && extendsFrom.isEmpty()) {
            // No further mutation is allowed and there's no parent: the artifact set corresponds to this configuration own artifacts
            this.allArtifacts = new DefaultPublishArtifactSet(displayName, ownArtifacts, fileCollectionFactory);
            return;
        }

        if (canBeMutated) {
            // If the configuration can still be mutated, we need to create a composite
            inheritedArtifacts = CompositeDomainObjectSet.create(PublishArtifact.class, ownArtifacts);
        }
        for (Configuration configuration : this.extendsFrom) {
            PublishArtifactSet allArtifacts = configuration.getAllArtifacts();
            if (inheritedArtifacts!= null || !allArtifacts.isEmpty()) {
                if (inheritedArtifacts == null) {
                    // This configuration cannot be mutated, but some parent configurations provide artifacts
                    inheritedArtifacts = CompositeDomainObjectSet.create(PublishArtifact.class, ownArtifacts);
                }
                inheritedArtifacts.addCollection(allArtifacts);
            }
        }
        if (inheritedArtifacts != null) {
            this.allArtifacts = new DefaultPublishArtifactSet(displayName, inheritedArtifacts, fileCollectionFactory);
        } else {
            this.allArtifacts = new DefaultPublishArtifactSet(displayName, ownArtifacts, fileCollectionFactory);
        }
    }

    public Set<ExcludeRule> getExcludeRules() {
        initExcludeRules();
        return Collections.unmodifiableSet(parsedExcludeRules);
    }

    /**
     * Synchronize read access to excludes. Mutation does not need to be thread-safe.
     */
    private synchronized void initExcludeRules() {
        if (parsedExcludeRules == null) {
            NotationParser<Object, ExcludeRule> parser = ExcludeRuleNotationConverter.parser();
            parsedExcludeRules = Sets.newLinkedHashSet();
            for (Object excludeRule : excludeRules) {
                parsedExcludeRules.add(parser.parseNotation(excludeRule));
            }
        }
    }

    public void setExcludeRules(Set<ExcludeRule> excludeRules) {
        validateMutation(MutationType.DEPENDENCIES);
        parsedExcludeRules = null;
        this.excludeRules.clear();
        this.excludeRules.addAll(excludeRules);
    }

    public DefaultConfiguration exclude(Map<String, String> excludeRuleArgs) {
        validateMutation(MutationType.DEPENDENCIES);
        parsedExcludeRules = null;
        excludeRules.add(excludeRuleArgs);
        return this;
    }

    public String getUploadTaskName() {
        return Configurations.uploadTaskName(getName());
    }

    public String getDisplayName() {
        return displayName.getDisplayName();
    }

    public ResolvableDependencies getIncoming() {
        return resolvableDependencies;
    }

    @Override
    public ConfigurationPublications getOutgoing() {
        return outgoing;
    }

    @Override
    public OutgoingVariant convertToOutgoingVariant() {
        return outgoing.convertToOutgoingVariant();
    }

    @Override
    public void preventFromFurtherMutation() {
        // TODO This should use the same `MutationValidator` infrastructure that we use for other mutation types
        if (canBeMutated) {
            AttributeContainerInternal delegatee = configurationAttributes.asImmutable();
            configurationAttributes = new ImmutableAttributeContainerWithErrorMessage(delegatee, this.displayName);
            outgoing.preventFromFurtherMutation();
            canBeMutated = false;
        }
    }

    @Override
    public void outgoing(Action<? super ConfigurationPublications> action) {
        action.execute(outgoing);
    }

    public ConfigurationInternal copy() {
        return createCopy(getDependencies(), getDependencyConstraints(), false);
    }

    public Configuration copyRecursive() {
        return createCopy(getAllDependencies(), getAllDependencyConstraints(), true);
    }

    public Configuration copy(Spec<? super Dependency> dependencySpec) {
        return createCopy(CollectionUtils.filter(getDependencies(), dependencySpec), getDependencyConstraints(), false);
    }

    public Configuration copyRecursive(Spec<? super Dependency> dependencySpec) {
        return createCopy(CollectionUtils.filter(getAllDependencies(), dependencySpec), getAllDependencyConstraints(), true);
    }

    private DefaultConfiguration createCopy(Set<Dependency> dependencies, Set<DependencyConstraint> dependencyConstraints, boolean recursive) {
        DetachedConfigurationsProvider configurationsProvider = new DetachedConfigurationsProvider();
        RootComponentMetadataBuilder rootComponentMetadataBuilder = this.rootComponentMetadataBuilder.withConfigurationsProvider(configurationsProvider);
        String newName = name + "Copy";
        Factory<ResolutionStrategyInternal> childResolutionStrategy = resolutionStrategy != null ? Factories.constant(resolutionStrategy.copy()) : resolutionStrategyFactory;
        DefaultConfiguration copiedConfiguration = instantiator.newInstance(DefaultConfiguration.class, domainObjectContext, newName,
            configurationsProvider, resolver, listenerManager, metaDataProvider, childResolutionStrategy, projectAccessListener, projectFinder, fileCollectionFactory, buildOperationExecutor, instantiator, artifactNotationParser, capabilityNotationParser, attributesFactory,
            rootComponentMetadataBuilder, projectStateRegistry, documentationRegistry);
        configurationsProvider.setTheOnlyConfiguration(copiedConfiguration);
        // state, cachedResolvedConfiguration, and extendsFrom intentionally not copied - must re-resolve copy
        // copying extendsFrom could mess up dependencies when copy was re-resolved

        copiedConfiguration.visible = visible;
        copiedConfiguration.transitive = transitive;
        copiedConfiguration.description = description;

        copiedConfiguration.defaultDependencyActions = defaultDependencyActions;
        copiedConfiguration.dependencyResolutionListeners = dependencyResolutionListeners;

        copiedConfiguration.canBeConsumed = canBeConsumed;
        copiedConfiguration.canBeResolved = canBeResolved;

        copiedConfiguration.getArtifacts().addAll(getAllArtifacts());

        if (!configurationAttributes.isEmpty()) {
            for (Attribute<?> attribute : configurationAttributes.keySet()) {
                Object value = configurationAttributes.getAttribute(attribute);
                copiedConfiguration.getAttributes().attribute(Cast.<Attribute<Object>>uncheckedCast(attribute), value);
            }
        }

        // todo An ExcludeRule is a value object but we don't enforce immutability for DefaultExcludeRule as strong as we
        // should (we expose the Map). We should provide a better API for ExcludeRule (I don't want to use unmodifiable Map).
        // As soon as DefaultExcludeRule is truly immutable, we don't need to create a new instance of DefaultExcludeRule.
        Set<Configuration> excludeRuleSources = new LinkedHashSet<Configuration>();
        excludeRuleSources.add(this);
        if (recursive) {
            excludeRuleSources.addAll(getHierarchy());
        }

        for (Configuration excludeRuleSource : excludeRuleSources) {
            for (ExcludeRule excludeRule : excludeRuleSource.getExcludeRules()) {
                copiedConfiguration.excludeRules.add(new DefaultExcludeRule(excludeRule.getGroup(), excludeRule.getModule()));
            }
        }

        DomainObjectSet<Dependency> copiedDependencies = copiedConfiguration.getDependencies();
        for (Dependency dependency : dependencies) {
            copiedDependencies.add(dependency.copy());
        }
        DomainObjectSet<DependencyConstraint> copiedDependencyConstraints = copiedConfiguration.getDependencyConstraints();
        for (DependencyConstraint dependencyConstraint : dependencyConstraints) {
            copiedDependencyConstraints.add(((DefaultDependencyConstraint) dependencyConstraint).copy());
        }
        return copiedConfiguration;
    }

    public Configuration copy(Closure dependencySpec) {
        return copy(Specs.<Dependency>convertClosureToSpec(dependencySpec));
    }

    public Configuration copyRecursive(Closure dependencySpec) {
        return copyRecursive(Specs.<Dependency>convertClosureToSpec(dependencySpec));
    }

    public ResolutionStrategyInternal getResolutionStrategy() {
        if (resolutionStrategy == null) {
            resolutionStrategy = resolutionStrategyFactory.create();
            resolutionStrategy.setMutationValidator(this);
            resolutionStrategyFactory = null;
        }
        return resolutionStrategy;
    }

    public ComponentResolveMetadata toRootComponentMetaData() {
        return rootComponentMetadataBuilder.toRootComponentMetaData();
    }

    public String getPath() {
        return path.getPath();
    }

    @Override
    public Path getIdentityPath() {
        return identityPath;
    }

    @Override
    public Configuration resolutionStrategy(Closure closure) {
        configure(closure, getResolutionStrategy());
        return this;
    }

    @Override
    public Configuration resolutionStrategy(Action<? super ResolutionStrategy> action) {
        action.execute(getResolutionStrategy());
        return this;
    }

    @Override
    public void addMutationValidator(MutationValidator validator) {
        childMutationValidators.add(validator);
    }

    @Override
    public void removeMutationValidator(MutationValidator validator) {
        childMutationValidators.remove(validator);
    }

    private void validateParentMutation(MutationType type) {
        // Strategy changes in a parent configuration do not affect this configuration, or any of its children, in any way
        if (type == MutationType.STRATEGY) {
            return;
        }

        preventIllegalParentMutation(type);
        markAsModified(type);
        notifyChildren(type);
    }

    public void validateMutation(MutationType type) {
        preventIllegalMutation(type);
        markAsModified(type);
        notifyChildren(type);
    }

    private void preventIllegalParentMutation(MutationType type) {
        // TODO Deprecate and eventually prevent these mutations in parent when already resolved
        if (type == MutationType.DEPENDENCY_ATTRIBUTES) {
            return;
        }

        if (resolvedState == ARTIFACTS_RESOLVED) {
            throw new InvalidUserDataException(String.format("Cannot change %s of parent of %s after it has been resolved", type, getDisplayName()));
        } else if (resolvedState == GRAPH_RESOLVED) {
            if (type == MutationType.DEPENDENCIES) {
                throw new InvalidUserDataException(String.format("Cannot change %s of parent of %s after task dependencies have been resolved", type, getDisplayName()));
            }
        }
    }

    private void preventIllegalMutation(MutationType type) {
        // TODO: Deprecate and eventually prevent these mutations when already resolved
        if (type == MutationType.DEPENDENCY_ATTRIBUTES) {
            return;
        }

        if (resolvedState == ARTIFACTS_RESOLVED) {
            // The public result for the configuration has been calculated.
            // It is an error to change anything that would change the dependencies or artifacts
            throw new InvalidUserDataException(String.format("Cannot change %s of %s after it has been resolved.", type, getDisplayName()));
        } else if (resolvedState == GRAPH_RESOLVED) {
            // The task dependencies for the configuration have been calculated using Configuration.getBuildDependencies().
            throw new InvalidUserDataException(String.format("Cannot change %s of %s after task dependencies have been resolved", type, getDisplayName()));
        } else if (observedState == GRAPH_RESOLVED || observedState == ARTIFACTS_RESOLVED) {
            // The configuration has been used in a resolution, and it is an error for build logic to change any dependencies,
            // exclude rules or parent configurations (values that will affect the resolved graph).
            if (type != MutationType.STRATEGY) {
                String extraMessage = insideBeforeResolve ? " Use 'defaultDependencies' instead of 'beforeResolve' to specify default dependencies for a configuration." : "";
                throw new InvalidUserDataException(String.format("Cannot change %s of %s after it has been included in dependency resolution.%s", type, getDisplayName(), extraMessage));
            }
        }
    }

    private void markAsModified(MutationType type) {
        // TODO: Should not be ignoring DEPENDENCY_ATTRIBUTE modifications after resolve
        if (type == MutationType.DEPENDENCY_ATTRIBUTES) {
            return;
        }
        // Strategy mutations will not require a re-resolve
        if (type == MutationType.STRATEGY) {
            return;
        }
        dependenciesModified = true;
    }

    private void notifyChildren(MutationType type) {
        // Notify child configurations
        for (MutationValidator validator : childMutationValidators) {
            validator.validateMutation(type);
        }
    }

    private static class ConfigurationDescription implements Describable {
        private final Path identityPath;

        ConfigurationDescription(Path identityPath) {
            this.identityPath = identityPath;
        }

        @Override
        public String getDisplayName() {
            return "configuration '" + identityPath + "'";
        }
    }

    private class ConfigurationFileCollection extends AbstractFileCollection {
        private final Spec<? super Dependency> dependencySpec;
        private final AttributeContainerInternal viewAttributes;
        private final Spec<? super ComponentIdentifier> componentSpec;
        private final boolean lenient;
        private final boolean allowNoMatchingVariants;
        private SelectedArtifactSet selectedArtifacts;

        private ConfigurationFileCollection(Spec<? super Dependency> dependencySpec) {
            assertIsResolvable();
            this.dependencySpec = dependencySpec;
            this.viewAttributes = configurationAttributes;
            this.componentSpec = Specs.satisfyAll();
            lenient = false;
            allowNoMatchingVariants = false;
        }

        private ConfigurationFileCollection(Spec<? super Dependency> dependencySpec, AttributeContainerInternal viewAttributes,
                                            Spec<? super ComponentIdentifier> componentSpec, boolean lenient, boolean allowNoMatchingVariants) {
            this.dependencySpec = dependencySpec;
            this.viewAttributes = viewAttributes.asImmutable();
            this.componentSpec = componentSpec;
            this.lenient = lenient;
            this.allowNoMatchingVariants = allowNoMatchingVariants;
        }

        private ConfigurationFileCollection(Closure dependencySpecClosure) {
            this(Specs.convertClosureToSpec(dependencySpecClosure));
        }

        private ConfigurationFileCollection(final Set<Dependency> dependencies) {
            this(new Spec<Dependency>() {
                public boolean isSatisfiedBy(Dependency element) {
                    return dependencies.contains(element);
                }
            });
        }

        @Override
        public TaskDependency getBuildDependencies() {
            assertIsResolvable();
            return new ConfigurationTaskDependency(dependencySpec, viewAttributes, componentSpec, allowNoMatchingVariants, lenient);
        }

        public Spec<? super Dependency> getDependencySpec() {
            return dependencySpec;
        }

        public String getDisplayName() {
            return DefaultConfiguration.this.getDisplayName();
        }

        public Set<File> getFiles() {
            ResolvedFilesCollectingVisitor visitor = new ResolvedFilesCollectingVisitor();
            getSelectedArtifacts().visitArtifacts(visitor, lenient);

            if (!lenient) {
                rethrowFailure("files", visitor.getFailures());
            }

            return visitor.getFiles();
        }

        private SelectedArtifactSet getSelectedArtifacts() {
            if (selectedArtifacts == null) {
                resolveToStateOrLater(ARTIFACTS_RESOLVED);
                selectedArtifacts = cachedResolverResults.getVisitedArtifacts().select(dependencySpec, viewAttributes, componentSpec, allowNoMatchingVariants);
            }
            return selectedArtifacts;
        }
    }

    private void rethrowFailure(String type, Collection<Throwable> failures) {
        if (failures.isEmpty()) {
            return;
        }
        if (failures.size() == 1) {
            Throwable failure = failures.iterator().next();
            if (failure instanceof ResolveException) {
                throw UncheckedException.throwAsUncheckedException(failure);
            }
        }
        throw new DefaultLenientConfiguration.ArtifactResolveException(type, getIdentityPath().toString(), getDisplayName(), failures);
    }

    private boolean hasMutableProjectState() {
        if (domainObjectContext.getProjectPath() != null) {
            Project project = projectFinder.findProject(domainObjectContext.getProjectPath().getPath());
            if (project != null) {
                ProjectState projectState = projectStateRegistry.stateFor(project);
                if (!projectState.hasMutableState()) {
                    return false;
                }
            }
        }

        return true;
    }

    private void assertIsResolvable() {
        if (!canBeResolved) {
            throw new IllegalStateException("Resolving configuration '" + name + "' directly is not allowed");
        }
    }

    @Override
    public void registerWatchPoints(FileSystemSubset.Builder builder) {
        for (Dependency dependency : getAllDependencies()) {
            if (dependency instanceof FileCollectionDependency) {
                FileCollection files = ((FileCollectionDependency) dependency).getFiles();
                ((FileCollectionInternal) files).registerWatchPoints(builder);
            }
        }
        super.registerWatchPoints(builder);
    }

    @Override
    public AttributeContainerInternal getAttributes() {
        return configurationAttributes;
    }

    @Override
    public Configuration attributes(Action<? super AttributeContainer> action) {
        action.execute(configurationAttributes);
        return this;
    }

    @Override
    public boolean isCanBeConsumed() {
        return canBeConsumed;
    }

    @Override
    public void setCanBeConsumed(boolean allowed) {
        validateMutation(MutationType.ROLE);
        canBeConsumed = allowed;
    }

    @Override
    public boolean isCanBeResolved() {
        return canBeResolved;
    }

    @Override
    public void setCanBeResolved(boolean allowed) {
        validateMutation(MutationType.ROLE);
        canBeResolved = allowed;
    }

    /**
     * Print a formatted representation of a Configuration
     */
    public String dump() {
        StringBuilder reply = new StringBuilder();

        reply.append("\nConfiguration:");
        reply.append("  class='" + this.getClass() + "'");
        reply.append("  name='" + this.getName() + "'");
        reply.append("  hashcode='" + this.hashCode() + "'");

        reply.append("\nLocal Dependencies:");
        if (getDependencies().size() > 0) {
            for (Dependency d : getDependencies()) {
                reply.append("\n   " + d);
            }
        } else {
            reply.append("\n   none");
        }

        reply.append("\nLocal Artifacts:");
        if (getArtifacts().size() > 0) {
            for (PublishArtifact a : getArtifacts()) {
                reply.append("\n   " + a);
            }
        } else {
            reply.append("\n   none");
        }

        reply.append("\nAll Dependencies:");
        if (getAllDependencies().size() > 0) {
            for (Dependency d : getAllDependencies()) {
                reply.append("\n   " + d);
            }
        } else {
            reply.append("\n   none");
        }


        reply.append("\nAll Artifacts:");
        if (getAllArtifacts().size() > 0) {
            for (PublishArtifact a : getAllArtifacts()) {
                reply.append("\n   " + a);
            }
        } else {
            reply.append("\n   none");
        }

        return reply.toString();
    }

    public class ConfigurationResolvableDependencies implements ResolvableDependenciesInternal {
        public String getName() {
            return name;
        }

        public String getPath() {
            return path.getPath();
        }

        @Override
        public String toString() {
            return "dependencies '" + getIdentityPath() + "'";
        }

        public FileCollection getFiles() {
            return new ConfigurationFileCollection(Specs.<Dependency>satisfyAll());
        }

        public DependencySet getDependencies() {
            runDependencyActions();
            return getAllDependencies();
        }

        public DependencyConstraintSet getDependencyConstraints() {
            runDependencyActions();
            return getAllDependencyConstraints();
        }

        public void beforeResolve(Action<? super ResolvableDependencies> action) {
            dependencyResolutionListeners.add("beforeResolve", action);
        }

        public void beforeResolve(Closure action) {
            dependencyResolutionListeners.add(new ClosureBackedMethodInvocationDispatch("beforeResolve", action));
        }

        public void afterResolve(Action<? super ResolvableDependencies> action) {
            dependencyResolutionListeners.add("afterResolve", action);
        }

        public void afterResolve(Closure action) {
            dependencyResolutionListeners.add(new ClosureBackedMethodInvocationDispatch("afterResolve", action));
        }

        public ResolutionResult getResolutionResult() {
            return new LenientResolutionResult(DEFAULT_ERROR_HANDLER);
        }

        @Override
        public ArtifactCollection getArtifacts() {
            return new ConfigurationArtifactCollection();
        }

        @Override
        public ArtifactView artifactView(Action<? super ArtifactView.ViewConfiguration> configAction) {
            ArtifactViewConfiguration config = createArtifactViewConfiguration();
            configAction.execute(config);
            return createArtifactView(config);
        }

        private ArtifactView createArtifactView(ArtifactViewConfiguration config) {
            ImmutableAttributes viewAttributes = config.lockViewAttributes();
            // This is a little coincidental: if view attributes have not been accessed, don't allow no matching variants
            boolean allowNoMatchingVariants = config.attributesUsed;
            return new ConfigurationArtifactView(viewAttributes, config.lockComponentFilter(), config.lenient, allowNoMatchingVariants);
        }

        private DefaultConfiguration.ArtifactViewConfiguration createArtifactViewConfiguration() {
            return instantiator.newInstance(ArtifactViewConfiguration.class, attributesFactory, configurationAttributes);
        }

        @Override
        public AttributeContainer getAttributes() {
            return configurationAttributes;
        }

        @Override
        public ResolutionResult getResolutionResult(Action<? super Throwable> errorHandler) {
            return new LenientResolutionResult(errorHandler);
        }

        private class ConfigurationArtifactView implements ArtifactView {
            private final ImmutableAttributes viewAttributes;
            private final Spec<? super ComponentIdentifier> componentFilter;
            private final boolean lenient;
            private final boolean allowNoMatchingVariants;

            ConfigurationArtifactView(ImmutableAttributes viewAttributes, Spec<? super ComponentIdentifier> componentFilter, boolean lenient, boolean allowNoMatchingVariants) {
                this.viewAttributes = viewAttributes;
                this.componentFilter = componentFilter;
                this.lenient = lenient;
                this.allowNoMatchingVariants = allowNoMatchingVariants;
            }

            @Override
            public AttributeContainer getAttributes() {
                return viewAttributes;
            }

            @Override
            public ArtifactCollection getArtifacts() {
                return new ConfigurationArtifactCollection(viewAttributes, componentFilter, lenient, allowNoMatchingVariants);
            }

            @Override
            public FileCollection getFiles() {
                return new ConfigurationFileCollection(Specs.<Dependency>satisfyAll(), viewAttributes, componentFilter, lenient, allowNoMatchingVariants);
            }
        }

        private void assertArtifactsResolved() {
            DefaultConfiguration.this.resolveToStateOrLater(ARTIFACTS_RESOLVED);
        }

        private class LenientResolutionResult implements ResolutionResult {
            private final Action<? super Throwable> errorHandler;
            private volatile ResolutionResult delegate;

            private LenientResolutionResult(Action<? super Throwable> errorHandler) {
                this.errorHandler = errorHandler;
            }

            private void resolve() {
                if (delegate == null) {
                    synchronized (this) {
                        if (delegate == null) {
                            assertArtifactsResolved();
                            delegate = cachedResolverResults.getResolutionResult();
                            Throwable failure = cachedResolverResults.consumeNonFatalFailure();
                            if (failure != null) {
                                errorHandler.execute(failure);
                            }
                        }
                    }
                }
            }

            @Override
            public ResolvedComponentResult getRoot() {
                resolve();
                return delegate.getRoot();
            }

            @Override
            public Set<? extends DependencyResult> getAllDependencies() {
                resolve();
                return delegate.getAllDependencies();
            }

            @Override
            public void allDependencies(Action<? super DependencyResult> action) {
                resolve();
                delegate.allDependencies(action);
            }

            @Override
            public void allDependencies(Closure closure) {
                resolve();
                delegate.allDependencies(closure);
            }

            @Override
            public Set<ResolvedComponentResult> getAllComponents() {
                resolve();
                return delegate.getAllComponents();
            }

            @Override
            public void allComponents(Action<? super ResolvedComponentResult> action) {
                resolve();
                delegate.allComponents(action);
            }

            @Override
            public void allComponents(Closure closure) {
                resolve();
                delegate.allComponents(closure);
            }

            @Override
            public int hashCode() {
                resolve();
                return delegate.hashCode();
            }

            @Override
            public boolean equals(Object obj) {
                if (obj instanceof LenientResolutionResult) {
                    resolve();
                    return delegate.equals(((LenientResolutionResult) obj).delegate);
                }
                return false;
            }

            @Override
            public String toString() {
                return "lenient resolution result for " + delegate;
            }
        }
    }

    public static class ArtifactViewConfiguration implements ArtifactView.ViewConfiguration {
        private final ImmutableAttributesFactory attributesFactory;
        private final AttributeContainerInternal configurationAttributes;
        private AttributeContainerInternal viewAttributes;
        private Spec<? super ComponentIdentifier> componentFilter;
        private boolean lenient;
        private boolean attributesUsed;

        public ArtifactViewConfiguration(ImmutableAttributesFactory attributesFactory, AttributeContainerInternal configurationAttributes) {
            this.attributesFactory = attributesFactory;
            this.configurationAttributes = configurationAttributes;
        }

        @Override
        public AttributeContainer getAttributes() {
            if (viewAttributes == null) {
                viewAttributes = attributesFactory.mutable(configurationAttributes);
                attributesUsed = true;
            }
            return viewAttributes;
        }

        @Override
        public ArtifactViewConfiguration attributes(Action<? super AttributeContainer> action) {
            action.execute(getAttributes());
            return this;
        }

        @Override
        public ArtifactViewConfiguration componentFilter(Spec<? super ComponentIdentifier> componentFilter) {
            assertComponentFilterUnset();
            this.componentFilter = componentFilter;
            return this;
        }

        @Override
        public boolean isLenient() {
            return lenient;
        }

        @Override
        public void setLenient(boolean lenient) {
            this.lenient = lenient;
        }

        @Override
        public ArtifactViewConfiguration lenient(boolean lenient) {
            this.lenient = lenient;
            return this;
        }

        private void assertComponentFilterUnset() {
            if (componentFilter != null) {
                throw new IllegalStateException("The component filter can only be set once before the view was computed");
            }
        }

        private Spec<? super ComponentIdentifier> lockComponentFilter() {
            if (componentFilter == null) {
                componentFilter = Specs.satisfyAll();
            }
            return componentFilter;
        }

        private ImmutableAttributes lockViewAttributes() {
            if (viewAttributes == null) {
                viewAttributes = configurationAttributes.asImmutable();
            } else {
                viewAttributes = viewAttributes.asImmutable();
            }
            return viewAttributes.asImmutable();
        }
    }

    private class ConfigurationArtifactCollection implements ArtifactCollection {
        private final ConfigurationFileCollection fileCollection;
        private final AttributeContainerInternal viewAttributes;
        private final Spec<? super ComponentIdentifier> componentFilter;
        private final boolean lenient;
        private Set<ResolvedArtifactResult> artifactResults;
        private Set<Throwable> failures;

        ConfigurationArtifactCollection() {
            this(configurationAttributes, Specs.<ComponentIdentifier>satisfyAll(), false, false);
        }

        ConfigurationArtifactCollection(AttributeContainerInternal attributes, Spec<? super ComponentIdentifier> componentFilter, boolean lenient, boolean allowNoMatchingVariants) {
            assertIsResolvable();
            this.viewAttributes = attributes.asImmutable();
            this.componentFilter = componentFilter;
            this.fileCollection = new ConfigurationFileCollection(Specs.<Dependency>satisfyAll(), viewAttributes, this.componentFilter, lenient, allowNoMatchingVariants);
            this.lenient = lenient;
        }

        @Override
        public FileCollection getArtifactFiles() {
            return fileCollection;
        }

        @Override
        public Set<ResolvedArtifactResult> getArtifacts() {
            ensureResolved();
            return artifactResults;
        }

        @Override
        public Iterator<ResolvedArtifactResult> iterator() {
            ensureResolved();
            return artifactResults.iterator();
        }

        @Override
        public Collection<Throwable> getFailures() {
            ensureResolved();
            return failures;
        }

        private synchronized void ensureResolved() {
            if (artifactResults != null) {
                return;
            }

            ResolvedArtifactCollectingVisitor visitor = new ResolvedArtifactCollectingVisitor();
            fileCollection.getSelectedArtifacts().visitArtifacts(visitor, lenient);

            artifactResults = visitor.getArtifacts();
            failures = visitor.getFailures();

            if (!lenient) {
                rethrowFailure("artifacts", failures);
            }
        }
    }

    private class ConfigurationTaskDependency extends AbstractTaskDependency {
        private final Spec<? super Dependency> dependencySpec;
        private final AttributeContainerInternal requestedAttributes;
        private final Spec<? super ComponentIdentifier> componentIdentifierSpec;
        private final boolean lenient;
        private final boolean allowNoMatchingVariants;

        ConfigurationTaskDependency(Spec<? super Dependency> dependencySpec, AttributeContainerInternal requestedAttributes, Spec<? super ComponentIdentifier> componentIdentifierSpec, boolean allowNoMatchingVariants, boolean lenient) {
            this.dependencySpec = dependencySpec;
            this.requestedAttributes = requestedAttributes;
            this.componentIdentifierSpec = componentIdentifierSpec;
            this.allowNoMatchingVariants = allowNoMatchingVariants;
            this.lenient = lenient;
        }

        @Override
        public void visitDependencies(final TaskDependencyResolveContext context) {
            if (getResolutionStrategy().resolveGraphToDetermineTaskDependencies()) {
                // Force graph resolution as this is required to calculate build dependencies
                resolveToStateOrLater(GRAPH_RESOLVED);
            }
            ResolverResults results;
            if (getState() == State.UNRESOLVED) {
                // Traverse graph
                results = new DefaultResolverResults();
                resolver.resolveBuildDependencies(DefaultConfiguration.this, results);
            } else {
                // Otherwise, already have a result, so reuse it
                results = cachedResolverResults;
            }
            SelectedArtifactSet selected = results.getVisitedArtifacts().select(dependencySpec, requestedAttributes, componentIdentifierSpec, allowNoMatchingVariants);
            final Set<Throwable> failures = new LinkedHashSet<Throwable>();
            selected.collectBuildDependencies(new BuildDependenciesVisitor() {
                @Override
                public void visitFailure(Throwable failure) {
                    failures.add(failure);
                }

                @Override
                public void attachFinalizerTo(Task task, Action<? super Task> action) {
                    context.attachFinalizerTo(task, action);
                }

                @Override
                public void visitDependency(Object dep) {
                    context.add(dep);
                }
            });
            if (!lenient) {
                rethrowFailure("task dependencies", failures);
            }
        }
    }

    private class AllArtifactsProvider implements PublishArtifactSetProvider {

        @Override
        public PublishArtifactSet getPublishArtifactSet() {
            return getAllArtifacts();
        }
    }
}
