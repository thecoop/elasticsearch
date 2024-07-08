/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.upgrades;

import com.carrotsearch.randomizedtesting.annotations.Name;

import org.elasticsearch.client.Request;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.core.SuppressForbidden;
import org.elasticsearch.features.FeatureService;
import org.elasticsearch.test.cluster.ElasticsearchCluster;
import org.elasticsearch.test.cluster.FeatureFlag;
import org.elasticsearch.test.cluster.local.distribution.DistributionType;
import org.elasticsearch.test.junit.annotations.TestIssueLogging;
import org.elasticsearch.test.junit.annotations.TestLogging;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestRule;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;

@TestIssueLogging(value = "org.elasticsearch.cluster.coordination.NodeJoinExecutor:DEBUG", issueUrl = "https://github.com/elastic/elasticsearch/issues/109254")
public class ClusterFeatureMigrationIT extends ParameterizedRollingUpgradeTestCase {

    @Before
    public void checkMigrationVersion() {
        assumeFalse(
            "This checks migrations from before cluster features were introduced",
            oldClusterHasFeature(FeatureService.FEATURES_SUPPORTED)
        );
    }

    private static final TemporaryFolder repoDirectory = new TemporaryFolder();

    private static final ElasticsearchCluster cluster = ElasticsearchCluster.local()
        .distribution(DistributionType.DEFAULT)
        .version(getOldClusterTestVersion())
        .nodes(NODE_NUM)
        .node(0, s -> s.name("non-master1").setting("node.roles", "data"))
        .setting("path.repo", new Supplier<>() {
            @Override
            @SuppressForbidden(reason = "TemporaryFolder only has io.File methods, not nio.File")
            public String get() {
                return repoDirectory.getRoot().getPath();
            }
        })
        .setting("xpack.security.enabled", "false")
        .feature(FeatureFlag.TIME_SERIES_MODE)
        .build();

    @ClassRule
    public static TestRule ruleChain = RuleChain.outerRule(repoDirectory).around(cluster);

    public ClusterFeatureMigrationIT(@Name("upgradedNodes") int upgradedNodes) {
        super(upgradedNodes);
    }

    @Override
    protected ElasticsearchCluster getUpgradeCluster() {
        return cluster;
    }

    public void testClusterFeatureMigration() throws IOException {
        if (isUpgradedCluster()) {
            // check the nodes all have a feature in their cluster state (there should always be features_supported)
            var response = entityAsMap(adminClient().performRequest(new Request("GET", "/_cluster/state/nodes")));
            List<?> nodeFeatures = (List<?>) XContentMapValues.extractValue("nodes_features", response);
            assertThat(nodeFeatures, hasSize(adminClient().getNodes().size()));

            Map<String, List<?>> features = nodeFeatures.stream()
                .map(o -> (Map<?, ?>) o)
                .collect(Collectors.toMap(m -> (String) m.get("node_id"), m -> (List<?>) m.get("features")));

            Set<String> missing = features.entrySet()
                .stream()
                .filter(e -> e.getValue().contains(FeatureService.FEATURES_SUPPORTED.id()) == false)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
            assertThat(missing + " out of " + features.keySet() + " does not have the required feature", missing, empty());
        }
    }
}
