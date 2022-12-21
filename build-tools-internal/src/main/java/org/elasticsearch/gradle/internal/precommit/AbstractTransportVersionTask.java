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
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;

import javax.inject.Inject;
import java.io.File;
import java.io.OutputStream;

public abstract class AbstractTransportVersionTask extends PrecommitTask {

    private FileCollection classpath;

    private final ListProperty<FileCollection> classesDirs;

    private final ObjectFactory objectFactory;

    public AbstractTransportVersionTask(ObjectFactory objectFactory) {
        this.classesDirs = objectFactory.listProperty(FileCollection.class);
        this.objectFactory = objectFactory;
    }

    protected File getTransportFile() {
        return new File(getProjectLayout().getProjectDirectory().getAsFile(), "transport.txt");
    }

    @Inject
    public abstract WorkerExecutor getWorkerExecutor();

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


}
