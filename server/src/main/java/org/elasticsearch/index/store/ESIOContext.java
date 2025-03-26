/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.store;

import org.apache.lucene.store.FlushInfo;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.MergeInfo;
import org.apache.lucene.store.ReadAdvice;

public class ESIOContext implements IOContext {
    private final IOContext ioContext;
    private final boolean forceDirectIO;

    public ESIOContext(IOContext context, boolean forceDirectIO) {
        this.ioContext = context;
        this.forceDirectIO = forceDirectIO;
    }

    @Override
    public Context context() {
        return ioContext.context();
    }

    @Override
    public MergeInfo mergeInfo() {
        return ioContext.mergeInfo();
    }

    @Override
    public FlushInfo flushInfo() {
        return ioContext.flushInfo();
    }

    @Override
    public ReadAdvice readAdvice() {
        return ioContext.readAdvice();
    }

    @Override
    public IOContext withReadAdvice(ReadAdvice advice) {
        return new ESIOContext(ioContext.withReadAdvice(advice), forceDirectIO);
    }

    public boolean forceDirectIO() {
        return forceDirectIO;
    }
}
