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
import org.gradle.api.tasks.*;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutionException;
import org.gradle.workers.WorkerExecutor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

import javax.inject.Inject;

/**
 * Runs TransportVersionCheck on a set of directories.
 */
@CacheableTask
public abstract class TransportVersionCheckTask extends AbstractTransportVersionTask {

    @Inject
    public TransportVersionCheckTask(ObjectFactory objectFactory) {
        super(objectFactory);
        setDescription("Checks transport usage file of all source sets");
    }

    @TaskAction
    public void runTransportVersionCheck() {
        WorkQueue workQueue = getWorkerExecutor().noIsolation();
        workQueue.submit(TransportVersionCheckAction.class, parameters -> {
            parameters.getTransportFile().set(getTransportFile());
            parameters.getClasspath().setFrom(getClasspath());
            parameters.getClassDirectories().setFrom(getClassDirectories());
        });
    }


    interface Parameters extends WorkParameters {
        Property<File> getTransportFile();
        ConfigurableFileCollection getClassDirectories();

        ConfigurableFileCollection getClasspath();
    }

    abstract static class TransportVersionCheckAction implements WorkAction<Parameters> {

        private final ExecOperations execOperations;

        @Inject
        public TransportVersionCheckAction(ExecOperations execOperations) {
            this.execOperations = execOperations;
        }

        @Override
        public void execute() {
            File transport = getParameters().getTransportFile().get();
            if (transport.exists() == false) {
                throw new WorkerExecutionException("Transport file does not exist, generate it first");
            }

            ByteArrayOutputStream expected = new ByteArrayOutputStream();
            ExecResult result = LoggedExec.javaexec(execOperations, spec -> {
                spec.getMainClass().set("org.elasticsearch.test.transportversion.ESTransportVersionReader");
                spec.setStandardOutput(expected);
                spec.classpath(getParameters().getClasspath());
                getParameters().getClassDirectories().forEach(spec::args);
            });

            // TODO: actually useful error message
            byte[] actual;
            try (FileInputStream read = new FileInputStream(transport)) {
                actual = read.readAllBytes();
            }
            catch (IOException e) {
                throw new WorkerExecutionException("Could not read transport file", e);
            }

            if (Arrays.mismatch(expected.toByteArray(), actual) >= 0) {
                throw new WorkerExecutionException("Transport version usage mismatch found");
            }
        }
    }
}
