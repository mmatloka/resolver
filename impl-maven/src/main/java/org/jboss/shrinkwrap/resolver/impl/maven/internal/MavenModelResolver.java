/*
 * JBoss, Home of Professional Open Source
 * Copyright 2012, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.shrinkwrap.resolver.impl.maven.internal;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.model.Repository;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.resolution.InvalidRepositoryException;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.jboss.shrinkwrap.resolver.impl.maven.bootstrap.MavenRepositorySystem;
import org.jboss.shrinkwrap.resolver.impl.maven.convert.MavenConverter;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;

/**
 * Resolves an artifact even from remote repository during resolution of the model.
 *
 * The repositories are added to the resolution chain as found during processing of the POM file. Repository is added
 * only if there is no other repository with same id already defined.
 *
 * @author <a href="mailto:kpiwko@redhat.com">Karel Piwko</a>
 */
public class MavenModelResolver implements ModelResolver {

    private final List<RemoteRepository> repositories;
    private final Set<String> repositoryIds;

    private final MavenRepositorySystem system;
    private final RepositorySystemSession session;

    /**
     * Creates a new Maven repository resolver. This resolver uses service available to Maven to create an artifact
     * resolution chain
     *
     * @param system
     *            the Maven based implementation of the {@link RepositorySystem}
     * @param session
     *            the current Maven execution session
     * @param remoteRepositories
     *            the list of available Maven repositories
     */
    public MavenModelResolver(MavenRepositorySystem system, RepositorySystemSession session,
        List<RemoteRepository> remoteRepositories) {
        this.system = system;
        this.session = session;

        // RemoteRepository is mutable
        this.repositories = new ArrayList<RemoteRepository>(remoteRepositories.size());
        for (final RemoteRepository remoteRepository : remoteRepositories) {
            this.repositories.add(new RemoteRepository.Builder(remoteRepository).build());
        }

        this.repositoryIds = new HashSet<String>(repositories.size());

        for (final RemoteRepository repository : repositories) {
            repositoryIds.add(repository.getId());
        }
    }

    /**
     * Cloning constructor
     *
     * @param origin
     */
    private MavenModelResolver(MavenModelResolver origin) {
        this(origin.system, origin.session, origin.repositories);
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.maven.model.resolution.ModelResolver#addRepository(org.apache.maven.model.Repository)
     */
    @Override
    public void addRepository(Repository repository) throws InvalidRepositoryException {
        if (repositoryIds.contains(repository.getId())) {
            return;
        }

        repositoryIds.add(repository.getId());
        repositories.add(MavenConverter.asRemoteRepository(repository));
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.maven.model.resolution.ModelResolver#newCopy()
     */
    @Override
    public ModelResolver newCopy() {
        return new MavenModelResolver(this);
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.maven.model.resolution.ModelResolver#resolveModel(java.lang.String, java.lang.String,
     * java.lang.String)
     */
    @Override
    public ModelSource resolveModel(String groupId, String artifactId, String version)
        throws UnresolvableModelException {
        Artifact pomArtifact = new DefaultArtifact(groupId, artifactId, "", "pom", version);
        try {
            final ArtifactRequest request = new ArtifactRequest(pomArtifact, repositories, null);
            pomArtifact = system.resolveArtifact(session, request).getArtifact();

        } catch (ArtifactResolutionException e) {
            throw new UnresolvableModelException("Failed to resolve POM for " + groupId + ":" + artifactId + ":"
                + version + " due to " + e.getMessage(), groupId, artifactId, version, e);
        }

        final File pomFile = pomArtifact.getFile();

        return new FileModelSource(pomFile);

    }
}
