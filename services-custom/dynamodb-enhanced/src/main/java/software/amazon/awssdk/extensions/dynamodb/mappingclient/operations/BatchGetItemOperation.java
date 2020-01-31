/*
 * Copyright 2010-2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.extensions.dynamodb.mappingclient.operations;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.core.async.SdkPublisher;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.extensions.dynamodb.mappingclient.BatchableReadOperation;
import software.amazon.awssdk.extensions.dynamodb.mappingclient.MapperExtension;
import software.amazon.awssdk.extensions.dynamodb.mappingclient.PaginatedDatabaseOperation;
import software.amazon.awssdk.extensions.dynamodb.mappingclient.TableMetadata;
import software.amazon.awssdk.extensions.dynamodb.mappingclient.model.BatchGetItemEnhancedRequest;
import software.amazon.awssdk.extensions.dynamodb.mappingclient.model.BatchGetResultPage;
import software.amazon.awssdk.extensions.dynamodb.mappingclient.model.ReadBatch;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;

@SdkInternalApi
public class BatchGetItemOperation
    implements PaginatedDatabaseOperation<BatchGetItemRequest, BatchGetItemResponse, BatchGetResultPage> {

    private final BatchGetItemEnhancedRequest request;

    private BatchGetItemOperation(BatchGetItemEnhancedRequest request) {
        this.request = request;
    }

    public static BatchGetItemOperation create(BatchGetItemEnhancedRequest request) {
        return new BatchGetItemOperation(request);
    }

    @Override
    public BatchGetItemRequest generateRequest(MapperExtension mapperExtension) {
        Map<String, KeysAndAttributes> requestItems = new HashMap<>();
        request.readBatches().forEach(readBatch -> addReadRequestsToMap(readBatch, requestItems));

        return BatchGetItemRequest.builder()
                                  .requestItems(Collections.unmodifiableMap(requestItems))
                                  .build();
    }

    @Override
    public BatchGetResultPage transformResponse(BatchGetItemResponse response, MapperExtension mapperExtension) {
        return BatchGetResultPage.builder().batchGetItemResponse(response).mapperExtension(mapperExtension).build();
    }

    @Override
    public Function<BatchGetItemRequest, SdkIterable<BatchGetItemResponse>> serviceCall(DynamoDbClient dynamoDbClient) {
        return dynamoDbClient::batchGetItemPaginator;
    }

    @Override
    public Function<BatchGetItemRequest, SdkPublisher<BatchGetItemResponse>> asyncServiceCall(
        DynamoDbAsyncClient dynamoDbAsyncClient) {

        return dynamoDbAsyncClient::batchGetItemPaginator;
    }

    private void addReadRequestsToMap(ReadBatch readBatch, Map<String, KeysAndAttributes> readRequestMap) {
        String tableName = readBatch.mappedTableResource().tableName();

        KeysAndAttributes newKeysAndAttributes = generateKeysAndAttributes(readBatch);
        KeysAndAttributes existingKeysAndAttributes = readRequestMap.get(tableName);

        if (existingKeysAndAttributes == null) {
            readRequestMap.put(tableName, newKeysAndAttributes);
            return;
        }

        KeysAndAttributes mergedKeysAndAttributes = mergeKeysAndAttributes(existingKeysAndAttributes,
                                                                           newKeysAndAttributes);

        readRequestMap.put(tableName, mergedKeysAndAttributes);
    }

    // DynamoDB requires all component GetItem requests in a BatchGetItem to have the same consistentRead setting
    // for any given table. The logic here uses the setting of the first getItem in a table batch and then checks
    // the rest are identical or throws an exception.
    private KeysAndAttributes generateKeysAndAttributes(ReadBatch readBatch) {
        Collection<BatchableReadOperation> readOperations = readBatch.readOperations();

        AtomicReference<Boolean> consistentRead = new AtomicReference<>();
        AtomicBoolean firstRecord = new AtomicBoolean(true);

        List<Map<String, AttributeValue>> keys =
            readOperations.stream()
                          .peek(operation -> {
                              if (firstRecord.getAndSet(false)) {
                                  consistentRead.set(operation.consistentRead());
                              } else {
                                  if (!compareNullableBooleans(consistentRead.get(), operation.consistentRead())) {
                                      throw new IllegalArgumentException("All batchable read requests for the same "
                                                                         + "table must have the same 'consistentRead' "
                                                                         + "setting.");
                                  }
                              }
                          })
                          .map(BatchableReadOperation::key)
                          .map(key -> key.keyMap(readBatch.mappedTableResource().tableSchema(),
                                                 TableMetadata.primaryIndexName()))
                          .collect(Collectors.toList());

        return KeysAndAttributes.builder()
                                .keys(keys)
                                .consistentRead(consistentRead.get())
                                .build();
    }

    private static KeysAndAttributes mergeKeysAndAttributes(KeysAndAttributes first, KeysAndAttributes second) {
        if (!compareNullableBooleans(first.consistentRead(), second.consistentRead())) {
            throw new IllegalArgumentException("All batchable read requests for the same table must have the "
                                               + "same 'consistentRead' setting.");
        }

        Boolean consistentRead = first.consistentRead() == null ? second.consistentRead() : first.consistentRead();
        List<Map<String, AttributeValue>> keys =
            Stream.concat(first.keys().stream(), second.keys().stream()).collect(Collectors.toList());

        return KeysAndAttributes.builder()
                                .keys(keys)
                                .consistentRead(consistentRead)
                                .build();
    }

    private static boolean compareNullableBooleans(Boolean one, Boolean two) {
        if (one == null && two == null) {
            return true;
        }

        if (one != null) {
            return one.equals(two);
        } else {
            return false;
        }
    }

}