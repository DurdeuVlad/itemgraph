package com.itemgraph.api;

import java.util.concurrent.CompletableFuture;

public interface ItemGraphService {
    ApiVersion apiVersion();

    CompletableFuture<RegistrationResult> registerSource(SourceRegistration registration);

    CompletableFuture<SubmissionResult> submitObservation(
            SourceHandle source,
            DirectObservation observation);

    CompletableFuture<QueryResult> traceItem(ItemQuery query, QueryOptions options);
    CompletableFuture<QueryResult> tracePlayer(PlayerQuery query, QueryOptions options);
    CompletableFuture<QueryResult> traceContainer(ContainerQuery query, QueryOptions options);
    CompletableFuture<QueryResult> traceExternalInventory(
            ExternalInventoryEndpoint inventory,
            QueryOptions options);
}
