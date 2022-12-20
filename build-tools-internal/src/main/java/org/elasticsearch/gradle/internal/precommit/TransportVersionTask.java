/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.gradle.internal.precommit;

import org.elasticsearch.gradle.LoggedExec;
import org.elasticsearch.gradle.internal.conventions.precommit.PrecommitTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.*;
import org.gradle.process.ExecOperations;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;

import javax.inject.Inject;
import java.io.File;

/**
 * Runs TransportVersionCheck on a set of directories.
 */
@CacheableTask
public abstract class TransportVersionTask extends PrecommitTask {

    private FileCollection classpath;

    private final ListProperty<FileCollection> classesDirs;

    private final ObjectFactory objectFactory;

    @Inject
    public TransportVersionTask(ObjectFactory objectFactory) {
        this.classesDirs = objectFactory.listProperty(FileCollection.class);
        this.objectFactory = objectFactory;
        setDescription("Runs TransportVersionCheck on output directories of all source sets");
    }

    @Inject
    public abstract WorkerExecutor getWorkerExecutor();

    @TaskAction
    public void runTransportVersionTask() {
        WorkQueue workQueue = getWorkerExecutor().noIsolation();
        workQueue.submit(TransportVersionWorkAction.class, parameters -> {
            parameters.getClasspath().setFrom(getClasspath());
            parameters.getClassDirectories().setFrom(getClassDirectories());
        });
    }

    @Classpath
    public FileCollection getClasspath() {
        return classpath;
    }

    public void setClasspath(FileCollection classpath) {
        this.classpath = classpath;
    }

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    @SkipWhenEmpty
    public FileCollection getClassDirectories() {
        return classesDirs.get().stream().reduce(FileCollection::plus).orElse(objectFactory.fileCollection()).filter(File::exists);
    }

    public void addSourceSet(SourceSet sourceSet) {
        classesDirs.add(sourceSet.getOutput().getClassesDirs());
    }

    abstract static class TransportVersionWorkAction implements WorkAction<Parameters> {

        private final ExecOperations execOperations;

        @Inject
        public TransportVersionWorkAction(ExecOperations execOperations) {
            this.execOperations = execOperations;
        }

        @Override
        public void execute() {
            LoggedExec.javaexec(execOperations, spec -> {
                spec.getMainClass().set("org.elasticsearch.test.transportversion.ESTransportVersionChecker");
                spec.classpath(getParameters().getClasspath());
                getParameters().getClassDirectories().forEach(spec::args);
            });
        }
    }

    interface Parameters extends WorkParameters {
        ConfigurableFileCollection getClassDirectories();

        ConfigurableFileCollection getClasspath();
    }

}
