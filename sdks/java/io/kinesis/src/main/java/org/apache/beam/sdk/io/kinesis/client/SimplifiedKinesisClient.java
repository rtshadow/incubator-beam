/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.io.kinesis.client;


import org.apache.beam.sdk.io.kinesis.client.response.GetKinesisRecordsResult;
import com.google.common.collect.Lists;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.kinesis.AmazonKinesis;
import com.amazonaws.services.kinesis.clientlibrary.types.UserRecord;
import com.amazonaws.services.kinesis.model.ExpiredIteratorException;
import com.amazonaws.services.kinesis.model.GetRecordsRequest;
import com.amazonaws.services.kinesis.model.GetRecordsResult;
import com.amazonaws.services.kinesis.model.GetShardIteratorRequest;
import com.amazonaws.services.kinesis.model.LimitExceededException;
import com.amazonaws.services.kinesis.model.ProvisionedThroughputExceededException;
import com.amazonaws.services.kinesis.model.Shard;
import com.amazonaws.services.kinesis.model.ShardIteratorType;
import com.amazonaws.services.kinesis.model.StreamDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;

/***
 * Wraps {@link AmazonKinesis} class providing much simpler interface and
 * proper error handling.
 */
public class SimplifiedKinesisClient {
    private static final Logger LOG = LoggerFactory.getLogger(SimplifiedKinesisClient.class);

    private final AmazonKinesis kinesis;

    public SimplifiedKinesisClient(AmazonKinesis kinesis) {
        this.kinesis = kinesis;
    }

    public static SimplifiedKinesisClient from(KinesisClientProvider provider) {
        return new SimplifiedKinesisClient(provider.get());
    }

    public String getShardIterator(final String streamName, final String shardId,
                                   final ShardIteratorType shardIteratorType,
                                   final String startingSequenceNumber, final Date timestamp)
            throws TransientKinesisException {
        return wrapExceptions(new Callable<String>() {
            @Override
            public String call() throws Exception {
                return kinesis.getShardIterator(new GetShardIteratorRequest()
                        .withStreamName(streamName)
                        .withShardId(shardId)
                        .withShardIteratorType(shardIteratorType)
                        .withStartingSequenceNumber(startingSequenceNumber)
                        .withTimestamp(timestamp)
                ).getShardIterator();
            }
        });
    }

    public List<Shard> listShards(final String streamName) throws TransientKinesisException {
        return wrapExceptions(new Callable<List<Shard>>() {
            @Override
            public List<Shard> call() throws Exception {
                List<Shard> shards = Lists.newArrayList();
                String lastShardId = null;

                StreamDescription description;
                do {
                    description = kinesis.describeStream(streamName, lastShardId)
                            .getStreamDescription();

                    shards.addAll(description.getShards());
                    lastShardId = shards.get(shards.size() - 1).getShardId();
                } while (description.getHasMoreShards());

                return shards;
            }
        });
    }

    /***
     * Gets records from Kinesis and deaggregates them if needed.
     *
     * @return list of deaggregated records
     * @throws IOException - in case of recoverable situation
     */
    public GetKinesisRecordsResult getRecords(String shardIterator, String streamName,
                                              String shardId) throws TransientKinesisException {
        return getRecords(shardIterator, streamName, shardId, null);
    }

    /***
     * Gets records from Kinesis and deaggregates them if needed.
     *
     * @return list of deaggregated records
     * @throws IOException - in case of recoverable situation
     */
    public GetKinesisRecordsResult getRecords(final String shardIterator, final String streamName,
                                              final String shardId, final Integer limit)
            throws
            TransientKinesisException {
        return wrapExceptions(new Callable<GetKinesisRecordsResult>() {
            @Override
            public GetKinesisRecordsResult call() throws Exception {
                GetRecordsResult response = kinesis.getRecords(new GetRecordsRequest()
                        .withShardIterator(shardIterator)
                        .withLimit(limit));
                return new GetKinesisRecordsResult(
                        UserRecord.deaggregate(response.getRecords()),
                        response.getNextShardIterator(),
                        streamName, shardId);
            }
        });
    }

    /***
     * Wraps Amazon specific exceptions into more friendly format.
     *
     * @throws IOException              - in case of recoverable situation, i.e.
     *                                  the request rate is too high, Kinesis remote service
     *                                  failed, network issue, etc.
     * @throws ExpiredIteratorException - if iterator needs to be refreshed
     * @throws RuntimeException         - in all other cases
     */
    private <T> T wrapExceptions(Callable<T> callable) throws TransientKinesisException {
        try {
            return callable.call();
        } catch (ExpiredIteratorException e) {
            throw e;
        } catch (LimitExceededException | ProvisionedThroughputExceededException e) {
            LOG.warn("Too many requests to Kinesis", e);
            throw new TransientKinesisException(e);
        } catch (AmazonServiceException e) {
            if (e.getErrorType() == AmazonServiceException.ErrorType.Service) {
                LOG.warn("Kinesis backend failed", e);
                throw new TransientKinesisException(e);
            }
            LOG.error("Client side failure", e);
            throw new RuntimeException(e);
        } catch (Exception e) {
            LOG.error("Unknown failure", e);
            throw new RuntimeException(e);
        }
    }

}
