/*
 * Copyright (C) 2018-2019 Lightbend Inc. <http://www.lightbend.com>
 */

package com.lightbend.lagom.javadsl.persistence;

import akka.Done;
import akka.stream.alpakka.couchbase.javadsl.CouchbaseSession;
import com.couchbase.client.java.document.JsonDocument;
import com.couchbase.client.java.document.json.JsonObject;
import com.lightbend.lagom.javadsl.persistence.couchbase.CouchbaseReadSide;
import org.pcollections.PSequence;

import javax.inject.Inject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class TestEntityReadSide {

  private final CouchbaseSession session;

  @Inject
  public TestEntityReadSide(CouchbaseSession session) {
    this.session = session;
  }

  public CompletionStage<Long> getAppendCount(String entityId) {
    return getCount(session, entityId);
  }

  private static CompletionStage<Long> getCount(CouchbaseSession session, String entityId) {
    return session
        .get("count-" + entityId)
        .thenApply(v -> v.isPresent() ? v.get().content().getLong("count") : 0);
  }

  public static class TestEntityReadSideProcessor extends ReadSideProcessor<TestEntity.Evt> {
    private final CouchbaseReadSide readSide;
    private volatile boolean prepared;

    @Inject
    public TestEntityReadSideProcessor(CouchbaseReadSide readSide) {
      this.readSide = readSide;
    }

    @Override
    public ReadSideProcessor.ReadSideHandler<TestEntity.Evt> buildHandler() {
      return readSide
          .<TestEntity.Evt>builder("testoffsets")
          .setGlobalPrepare(session -> CompletableFuture.completedFuture(Done.getInstance()))
          .setPrepare(
              (session, tag) ->
                  CompletableFuture.supplyAsync(
                      () -> {
                        prepared = true;
                        return Done.getInstance();
                      }))
          .setEventHandler(TestEntity.Appended.class, this::updateCount)
          .build();
    }

    private CompletionStage<Done> updateCount(
        CouchbaseSession session, TestEntity.Appended event, Offset offset) {
      return getCount(session, event.getEntityId())
          .thenComposeAsync(
              count -> {
                if (!prepared) {
                  throw new IllegalStateException("Prepare handler hasn't been called");
                }
                JsonObject content = JsonObject.create().put("count", count + 1);
                return session
                    .upsert(JsonDocument.create("count-" + event.getEntityId(), content))
                    .thenApply(d -> Done.getInstance());
              });
    }

    @Override
    public PSequence<AggregateEventTag<TestEntity.Evt>> aggregateTags() {
      return TestEntity.Evt.AGGREGATE_EVENT_SHARDS.allTags();
    }
  }
}
