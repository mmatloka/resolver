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
package org.jboss.shrinkwrap.resolver.impl.maven;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.jboss.shrinkwrap.resolver.api.NoResolvedResultException;
import org.jboss.shrinkwrap.resolver.api.NonUniqueResultException;
import org.jboss.shrinkwrap.resolver.api.formatprocessor.FileFormatProcessor;
import org.jboss.shrinkwrap.resolver.api.formatprocessor.FormatProcessor;
import org.jboss.shrinkwrap.resolver.api.formatprocessor.InputStreamFormatProcessor;
import org.jboss.shrinkwrap.resolver.api.maven.MavenFormatStage;
import org.jboss.shrinkwrap.resolver.api.maven.ResolvedArtifactInfo;
import org.jboss.shrinkwrap.resolver.impl.maven.util.IOUtil;
import org.jboss.shrinkwrap.resolver.impl.maven.util.Validate;
import org.sonatype.aether.artifact.Artifact;

/**
 * Implementation of {@link MavenFormatStage}
 *
 * @author <a href="mailto:kpiwko@redhat.com">Karel Piwko</a>
 */
public class MavenFormatStageImpl implements MavenFormatStage {
    private static final Logger log = Logger.getLogger(MavenFormatStageImpl.class.getName());

    private static final ArtifactMapper REACTOR_MAPPER = new ArtifactMapper() {

        @Override
        public File map(final Artifact artifact) throws IllegalArgumentException {
            Validate.notNull(artifact, "ArtifactResult must not be null");
            if (!isMappable(artifact)) {
                throw new IllegalArgumentException(MessageFormat.format("Artifact {0} cannot be mapped to a file.",
                    artifact));
            }
            // FIXME: this is not a safe assumption, file can have a different name
            if ("pom.xml".equals(artifact.getFile().getName())) {

                String artifactId = artifact.getArtifactId();
                String extension = artifact.getExtension();

                File root = new File(artifact.getFile().getParentFile(), "target/classes");
                try {
                    File archive = File.createTempFile(artifactId + "-", "." + extension);
                    archive.deleteOnExit();
                    PackageDirHelper.packageDirectories(archive, root);
                    return archive;
                } catch (IOException e) {
                    throw new IllegalArgumentException("Unable to get artifact " + artifactId + " from the classpath",
                        e);
                }

            } else {
                return artifact.getFile();
            }
        }

        @Override
        public boolean isMappable(final Artifact artifactResult) throws IllegalArgumentException {

            return true;

            // FIXME this differs for ResolvedArtifactInfo
            /*
             * Artifact a = artifactResult.getArtifact(); // skip all pom artifacts if ("pom".equals(a.getExtension()))
             * { return false; }
             */

        }
    };

    private final Collection<Artifact> artifacts;

    public MavenFormatStageImpl(final Collection<Artifact> artifacts) {
        assert artifacts != null : "Artifacts are required";
        this.artifacts = artifacts;
    }

    @Override
    public final File[] as(final Class<File> type) throws IllegalArgumentException {
        return as(File.class, FileFormatProcessor.INSTANCE);
    }

    @Override
    public final InputStream[] as(final Class<InputStream> type) throws IllegalArgumentException {
        return as(InputStream.class, InputStreamFormatProcessor.INSTANCE);
    }

    @Override
    public final File asSingle(final Class<File> type) throws IllegalArgumentException, NonUniqueResultException,
        NoResolvedResultException {
        return asSingle(File.class, FileFormatProcessor.INSTANCE);
    }

    @Override
    public final InputStream asSingle(final Class<InputStream> type) throws IllegalArgumentException,
        NonUniqueResultException, NoResolvedResultException {
        return asSingle(InputStream.class, InputStreamFormatProcessor.INSTANCE);
    }

    @Override
    public final <RETURNTYPE> RETURNTYPE[] as(final Class<RETURNTYPE> type, final FormatProcessor<RETURNTYPE> processor)
        throws IllegalArgumentException {

        final List<RETURNTYPE> list = new ArrayList<RETURNTYPE>();

        for (final Artifact artifact : artifacts) {
            if (REACTOR_MAPPER.isMappable(artifact)) {
                list.add(processor.process(REACTOR_MAPPER.map(artifact)));
            } else {
                log.log(Level.INFO, "Removed artifact {0} from archive, it cannot be mapped to a file", artifact);
            }
        }

        // we need to convert to an array of specified return type
        // due to generics this is the only way
        @SuppressWarnings("unchecked")
        final RETURNTYPE[] array = (RETURNTYPE[]) Array.newInstance(type, list.size());
        return list.toArray(array);
    }

    @Override
    public final <RETURNTYPE> RETURNTYPE asSingle(final Class<RETURNTYPE> type,
        final FormatProcessor<RETURNTYPE> processor) throws IllegalArgumentException, NonUniqueResultException,
        NoResolvedResultException {

        final Collection<RETURNTYPE> collection = new ArrayList<RETURNTYPE>();

        for (final Artifact artifact : artifacts) {
            if (REACTOR_MAPPER.isMappable(artifact)) {
                collection.add(processor.process(REACTOR_MAPPER.map(artifact)));
            } else {
                log.log(Level.INFO, "Removed artifact {0} from archive, it cannot be mapped to a file", artifact);
            }
        }

        if (collection.isEmpty()) {
            throw new NoResolvedResultException("Unable to resolve dependencies, none of them were found.");
        }
        if (collection.size() != 1) {

            StringBuilder sb = new StringBuilder();
            for (RETURNTYPE artifact : collection) {
                sb.append(artifact).append("\n");
            }
            // delete last two characters
            if (sb.lastIndexOf("\n") != -1) {
                sb.deleteCharAt(sb.length() - 1);
            }

            throw new NonUniqueResultException(
                MessageFormat
                    .format(
                        "Resolution resolved more than a single artifact ({0} artifact(s)), unable to determine which one should used.\nComplete list of resolved artifacts:\n{1}",
                        collection.size(), sb));
        }

        return collection.iterator().next();
    }

    @Override
    public final ResolvedArtifactInfo[] as(final Class<ResolvedArtifactInfo> type) throws IllegalArgumentException {
        throw new UnsupportedOperationException();
    }

    @Override
    public final ResolvedArtifactInfo asSingle(final Class<ResolvedArtifactInfo> type) throws IllegalArgumentException,
        NonUniqueResultException, NoResolvedResultException {
        throw new UnsupportedOperationException();
    }

    /**
     * Maps an artifact to a file. This allows ShrinkWrap Maven resolver to package reactor releted dependencies.
     *
     * @author <a href="mailto:kpiwko@redhat.com">Karel Piwko</a>
     *
     */
    private interface ArtifactMapper {

        boolean isMappable(Artifact artifactResult) throws IllegalArgumentException;

        File map(Artifact artifactResult) throws IllegalArgumentException;
    }

    /**
     * I/O Utilities needed by the enclosing class
     *
     * @author <a href="mailto:alr@jboss.org">Andrew Lee Rubinger</a>
     */
    private static class PackageDirHelper {
        private PackageDirHelper() {
            throw new UnsupportedOperationException("No instances should be created; stateless class");
        }

        private static void safelyClose(final Closeable closeable) {
            if (closeable != null) {
                try {
                    closeable.close();
                } catch (final IOException ignore) {
                    if (log.isLoggable(Level.FINER)) {
                        log.finer("Could not close stream due to: " + ignore.getMessage() + "; ignoring");
                    }
                }
            }
        }

        static void packageDirectories(final File outputFile, final File... directories) throws IOException {

            Validate.notNullAndNoNullValues(directories, "Directories to be packaged must be specified");

            final ZipOutputStream zipFile = new ZipOutputStream(new FileOutputStream(outputFile));

            for (File directory : directories) {
                for (String entry : fileListing(directory)) {
                    FileInputStream fis = null;
                    try {
                        fis = new FileInputStream(new File(directory, entry));
                        zipFile.putNextEntry(new ZipEntry(entry));
                        IOUtil.copy(fis, zipFile);
                    } finally {
                        safelyClose(fis);
                    }
                }
            }
            safelyClose(zipFile);
        }

        private static List<String> fileListing(final File directory) {
            final List<String> list = new ArrayList<String>();
            generateFileList(list, directory, directory);
            return list;
        }

        private static void generateFileList(final List<String> list, final File root, final File file) {
            if (file.isFile()) {
                list.add(file.getAbsolutePath().substring(root.getAbsolutePath().length() + 1));
            } else if (file.isDirectory()) {
                for (File next : file.listFiles()) {
                    generateFileList(list, root, next);
                }
            }
        }
    }

}
