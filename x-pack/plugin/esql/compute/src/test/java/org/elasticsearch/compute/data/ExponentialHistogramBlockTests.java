/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.data;

import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.compute.test.BlockTestUtils;
import org.elasticsearch.compute.test.ComputeTestCase;
import org.elasticsearch.compute.test.RandomBlock;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.exponentialhistogram.ExponentialHistogram;
import org.elasticsearch.exponentialhistogram.ExponentialHistogramCircuitBreaker;

import java.io.IOException;
import java.util.List;

import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.Matchers.equalTo;

public class ExponentialHistogramBlockTests extends ComputeTestCase {

    public void testPopulatedBlockSerialization() throws IOException {
        int elementCount = randomIntBetween(1, 100);
        ExponentialHistogramBlockBuilder builder = blockFactory().newExponentialHistogramBlockBuilder(elementCount);
        for (int i = 0; i < elementCount; i++) {
            if (randomBoolean()) {
                builder.appendNull();
            } else {
                builder.append(BlockTestUtils.randomExponentialHistogram());
            }
        }
        ExponentialHistogramBlock block = builder.build();
        Block deserializedBlock = serializationRoundTrip(block);
        assertThat(deserializedBlock, equalTo(block));
        Releasables.close(block, deserializedBlock);
    }

    public void testNullSerialization() throws IOException {
        // sub-blocks can be constant null, those should serialize correctly too
        int elementCount = randomIntBetween(1, 100);

        Block block = new ExponentialHistogramArrayBlock(
            (DoubleBlock) blockFactory().newConstantNullBlock(elementCount),
            (DoubleBlock) blockFactory().newConstantNullBlock(elementCount),
            (DoubleBlock) blockFactory().newConstantNullBlock(elementCount),
            (LongBlock) blockFactory().newConstantNullBlock(elementCount),
            (DoubleBlock) blockFactory().newConstantNullBlock(elementCount),
            (BytesRefBlock) blockFactory().newConstantNullBlock(elementCount)
        );

        Block deserializedBlock = serializationRoundTrip(block);
        assertThat(deserializedBlock, equalTo(block));
        Releasables.close(block, deserializedBlock);
    }

    private Block serializationRoundTrip(Block block) throws IOException {
        BytesStreamOutput out = new BytesStreamOutput();
        Block.writeTypedBlock(block, out);
        try (BlockStreamInput input = new BlockStreamInput(out.bytes().streamInput(), blockFactory())) {
            return Block.readTypedBlock(input);
        }
    }

    public void testEmptyBlockEquality() {
        List<Block> blocks = List.of(
            blockFactory().newConstantNullBlock(0),
            blockFactory().newExponentialHistogramBlockBuilder(0).build(),
            filterAndRelease(blockFactory().newExponentialHistogramBlockBuilder(0).appendNull().build()),
            filterAndRelease(blockFactory().newExponentialHistogramBlockBuilder(0).append(ExponentialHistogram.empty()).build())
        );
        for (Block a : blocks) {
            for (Block b : blocks) {
                assertThat(a, equalTo(b));
                assertThat(a.hashCode(), equalTo(b.hashCode()));
            }
        }
        Releasables.close(blocks);
    }

    public void testNullValuesEquality() {
        List<Block> blocks = List.of(
            blockFactory().newConstantNullBlock(2),
            blockFactory().newExponentialHistogramBlockBuilder(0).appendNull().appendNull().build()
        );
        for (Block a : blocks) {
            for (Block b : blocks) {
                assertThat(a, equalTo(b));
                assertThat(a.hashCode(), equalTo(b.hashCode()));
            }
        }
        Releasables.close(blocks);
    }

    public void testFilteredBlockEquality() {
        ExponentialHistogram histo1 = ExponentialHistogram.create(4, ExponentialHistogramCircuitBreaker.noop(), 1, 2, 3, 4, 5);
        ExponentialHistogram histo2 = ExponentialHistogram.empty();
        Block block1 = blockFactory().newExponentialHistogramBlockBuilder(0).append(histo1).append(histo1).append(histo2).build();

        Block block2 = blockFactory().newExponentialHistogramBlockBuilder(0).append(histo1).append(histo2).append(histo2).build();

        Block block1Filtered = block1.filter(1, 2);
        Block block2Filtered = block2.filter(0, 1);

        assertThat(block1, not(equalTo(block2)));
        assertThat(block1, not(equalTo(block1Filtered)));
        assertThat(block1, not(equalTo(block2Filtered)));
        assertThat(block2, not(equalTo(block1)));
        assertThat(block2, not(equalTo(block1Filtered)));
        assertThat(block2, not(equalTo(block2Filtered)));

        assertThat(block1Filtered, equalTo(block2Filtered));
        assertThat(block1Filtered.hashCode(), equalTo(block2Filtered.hashCode()));

        Releasables.close(block1, block2, block1Filtered, block2Filtered);
    }

    public void testRandomBlockEquality() {
        int positionCount = randomIntBetween(0, 10_000);
        Block expHistoBlock = RandomBlock.randomBlock(blockFactory(), ElementType.EXPONENTIAL_HISTOGRAM, positionCount, true, 1, 10, 0, 10)
            .block();
        Block copy = BlockUtils.deepCopyOf(expHistoBlock, blockFactory());

        assertThat(expHistoBlock, equalTo(copy));
        assertThat(expHistoBlock.hashCode(), equalTo(copy.hashCode()));

        Releasables.close(expHistoBlock, copy);
    }

    private static Block filterAndRelease(Block toFilterAndRelease) {
        Block filtered = toFilterAndRelease.filter();
        toFilterAndRelease.close();
        return filtered;
    }
}
