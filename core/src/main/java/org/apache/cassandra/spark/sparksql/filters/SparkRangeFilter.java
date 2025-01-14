package org.apache.cassandra.spark.sparksql.filters;

import java.io.Serializable;
import java.math.BigInteger;
import java.nio.ByteBuffer;

import com.google.common.base.Preconditions;
import com.google.common.collect.Range;

import org.apache.cassandra.spark.reader.SparkSSTableReader;
import org.jetbrains.annotations.NotNull;

/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

public class SparkRangeFilter implements CustomFilter, Serializable
{
    private final Range<BigInteger> tokenRange;

    private SparkRangeFilter(@NotNull final Range<BigInteger> tokenRange)
    {
        this.tokenRange = tokenRange;
    }

    public Range<BigInteger> tokenRange()
    {
        return tokenRange;
    }

    @Override
    public boolean overlaps(final Range<BigInteger> tokenRange)
    {
        return this.tokenRange.isConnected(tokenRange);
    }

    @Override
    public boolean skipPartition(final ByteBuffer key, final BigInteger token)
    {
        return !this.tokenRange.contains(token);
    }

    @Override
    public boolean canFilterByKey()
    {
        return false;
    }

    @Override
    public boolean filter(final ByteBuffer key)
    {
        return true;
    }

    @Override
    public boolean filter(final SparkSSTableReader reader)
    {
        return SparkSSTableReader.overlaps(reader, this.tokenRange);
    }

    @Override
    public boolean isSpecificRange()
    {
        return false;
    }

    public static SparkRangeFilter create(final Range<BigInteger> tokenRange)
    {
        Preconditions.checkArgument(tokenRange.hasLowerBound() && tokenRange.hasUpperBound());
        return new SparkRangeFilter(tokenRange);
    }
}
