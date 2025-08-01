// Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
// or more contributor license agreements. Licensed under the Elastic License
// 2.0; you may not use this file except in compliance with the Elastic License
// 2.0.
package org.elasticsearch.xpack.esql.expression.function.scalar.math;

import java.lang.IllegalArgumentException;
import java.lang.Override;
import java.lang.String;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.DoubleBlock;
import org.elasticsearch.compute.data.DoubleVector;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.compute.operator.EvalOperator;
import org.elasticsearch.compute.operator.Warnings;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.xpack.esql.core.tree.Source;

/**
 * {@link EvalOperator.ExpressionEvaluator} implementation for {@link RoundToDouble}.
 * This class is generated. Edit {@code EvaluatorImplementer} instead.
 */
public final class RoundToDouble6Evaluator implements EvalOperator.ExpressionEvaluator {
  private final Source source;

  private final EvalOperator.ExpressionEvaluator field;

  private final double p0;

  private final double p1;

  private final double p2;

  private final double p3;

  private final double p4;

  private final double p5;

  private final DriverContext driverContext;

  private Warnings warnings;

  public RoundToDouble6Evaluator(Source source, EvalOperator.ExpressionEvaluator field, double p0,
      double p1, double p2, double p3, double p4, double p5, DriverContext driverContext) {
    this.source = source;
    this.field = field;
    this.p0 = p0;
    this.p1 = p1;
    this.p2 = p2;
    this.p3 = p3;
    this.p4 = p4;
    this.p5 = p5;
    this.driverContext = driverContext;
  }

  @Override
  public Block eval(Page page) {
    try (DoubleBlock fieldBlock = (DoubleBlock) field.eval(page)) {
      DoubleVector fieldVector = fieldBlock.asVector();
      if (fieldVector == null) {
        return eval(page.getPositionCount(), fieldBlock);
      }
      return eval(page.getPositionCount(), fieldVector).asBlock();
    }
  }

  public DoubleBlock eval(int positionCount, DoubleBlock fieldBlock) {
    try(DoubleBlock.Builder result = driverContext.blockFactory().newDoubleBlockBuilder(positionCount)) {
      position: for (int p = 0; p < positionCount; p++) {
        if (fieldBlock.isNull(p)) {
          result.appendNull();
          continue position;
        }
        if (fieldBlock.getValueCount(p) != 1) {
          if (fieldBlock.getValueCount(p) > 1) {
            warnings().registerException(new IllegalArgumentException("single-value function encountered multi-value"));
          }
          result.appendNull();
          continue position;
        }
        result.appendDouble(RoundToDouble.process(fieldBlock.getDouble(fieldBlock.getFirstValueIndex(p)), this.p0, this.p1, this.p2, this.p3, this.p4, this.p5));
      }
      return result.build();
    }
  }

  public DoubleVector eval(int positionCount, DoubleVector fieldVector) {
    try(DoubleVector.FixedBuilder result = driverContext.blockFactory().newDoubleVectorFixedBuilder(positionCount)) {
      position: for (int p = 0; p < positionCount; p++) {
        result.appendDouble(p, RoundToDouble.process(fieldVector.getDouble(p), this.p0, this.p1, this.p2, this.p3, this.p4, this.p5));
      }
      return result.build();
    }
  }

  @Override
  public String toString() {
    return "RoundToDouble6Evaluator[" + "field=" + field + ", p0=" + p0 + ", p1=" + p1 + ", p2=" + p2 + ", p3=" + p3 + ", p4=" + p4 + ", p5=" + p5 + "]";
  }

  @Override
  public void close() {
    Releasables.closeExpectNoException(field);
  }

  private Warnings warnings() {
    if (warnings == null) {
      this.warnings = Warnings.createWarnings(
              driverContext.warningsMode(),
              source.source().getLineNumber(),
              source.source().getColumnNumber(),
              source.text()
          );
    }
    return warnings;
  }

  static class Factory implements EvalOperator.ExpressionEvaluator.Factory {
    private final Source source;

    private final EvalOperator.ExpressionEvaluator.Factory field;

    private final double p0;

    private final double p1;

    private final double p2;

    private final double p3;

    private final double p4;

    private final double p5;

    public Factory(Source source, EvalOperator.ExpressionEvaluator.Factory field, double p0,
        double p1, double p2, double p3, double p4, double p5) {
      this.source = source;
      this.field = field;
      this.p0 = p0;
      this.p1 = p1;
      this.p2 = p2;
      this.p3 = p3;
      this.p4 = p4;
      this.p5 = p5;
    }

    @Override
    public RoundToDouble6Evaluator get(DriverContext context) {
      return new RoundToDouble6Evaluator(source, field.get(context), p0, p1, p2, p3, p4, p5, context);
    }

    @Override
    public String toString() {
      return "RoundToDouble6Evaluator[" + "field=" + field + ", p0=" + p0 + ", p1=" + p1 + ", p2=" + p2 + ", p3=" + p3 + ", p4=" + p4 + ", p5=" + p5 + "]";
    }
  }
}
