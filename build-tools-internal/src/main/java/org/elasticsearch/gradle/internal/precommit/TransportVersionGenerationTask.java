/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.gradle.internal.precommit;

import org.elasticsearch.gradle.LoggedExec;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutionException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import javax.inject.Inject;

/**
 * Generates a transport version file for a set of directories.
 */
public abstract class TransportVersionGenerationTask extends AbstractTransportVersionTask {

    @OutputFile
    public File getOutputFile() {
        return getTransportFile();
    }

    @Inject
    public TransportVersionGenerationTask(ObjectFactory objectFactory) {
        super(objectFactory);
        setDescription("Generates transport usage file for all source sets");
    }

    @TaskAction
    public void runTransportVersionGeneration() {
        WorkQueue workQueue = getWorkerExecutor().noIsolation();
        workQueue.submit(TransportVersionGenerationAction.class, parameters -> {
            parameters.getOutputFile().set(getOutputFile());
            parameters.getClasspath().setFrom(getClasspath());
            parameters.getClassDirectories().setFrom(getClassDirectories());
        });
    }

    interface Parameters extends WorkParameters {
        Property<File> getOutputFile();

        ConfigurableFileCollection getClassDirectories();

        ConfigurableFileCollection getClasspath();
    }

    abstract static class TransportVersionGenerationAction implements WorkAction<Parameters> {

        private final ExecOperations execOperations;

        @Inject
        public TransportVersionGenerationAction(ExecOperations execOperations) {
            this.execOperations = execOperations;
        }

        @Override
        public void execute() {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ExecResult result = LoggedExec.javaexec(execOperations, spec -> {
                spec.getMainClass().set("org.elasticsearch.test.transportversion.ESTransportVersionReader");
                spec.setStandardOutput(output);
                spec.classpath(getParameters().getClasspath());
                getParameters().getClassDirectories().forEach(spec::args);
            });

            result.assertNormalExitValue();
            try (FileOutputStream file = new FileOutputStream(getParameters().getOutputFile().get())) {
                output.writeTo(file);
            } catch (IOException e) {
                throw new WorkerExecutionException("Could not write output file", e);
            }
        }
    }
}
