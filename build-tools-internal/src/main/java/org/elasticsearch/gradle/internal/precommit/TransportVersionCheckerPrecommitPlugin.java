/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.gradle.internal.precommit;

import org.elasticsearch.gradle.internal.conventions.precommit.PrecommitPlugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;

public class TransportVersionCheckerPrecommitPlugin extends PrecommitPlugin {
    @Override
    public TaskProvider<? extends Task> createTask(Project project) {
        Configuration transportVersionConfig = project.getConfigurations().create("transportVersionPlugin");
        // this makes it easier to test by not requiring this project to be always available in our
        // test sample projects
        if (project.findProject(":test:transport-version-usage") != null) {
            project.getDependencies().add("transportVersionPlugin", project.project(":test:transport-version-usage"));
        }
        TaskProvider<TransportVersionTask> transportVersion = project.getTasks()
            .register("transportVersionCheck", TransportVersionTask.class);

        SourceSetContainer sourceSets = project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets();
        sourceSets.matching(
            sourceSet -> sourceSet.getName().equals(SourceSet.MAIN_SOURCE_SET_NAME)
                || sourceSet.getName().equals(SourceSet.TEST_SOURCE_SET_NAME)
        ).all(sourceSet -> transportVersion.configure(t -> t.addSourceSet(sourceSet)));

        transportVersion.configure(
            t -> t.setClasspath(transportVersionConfig)

        );
        return transportVersion;
    }
}
