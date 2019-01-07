/*
 * Copyright (C) 2018 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.alpakka.couchbase.scaladsl

import akka.annotation.DoNotInherit
import akka.stream.alpakka.couchbase.impl.{CouchbaseSessionImpl, RxUtilities}
import akka.stream.alpakka.couchbase.javadsl.{CouchbaseSession => JavaDslCouchbaseSession}
import akka.stream.alpakka.couchbase.{CouchbaseSessionSettings, CouchbaseWriteSettings}
import akka.stream.scaladsl.Source
import akka.{Done, NotUsed}
import com.couchbase.client.java._
import com.couchbase.client.java.document.json.JsonObject
import com.couchbase.client.java.document.{Document, JsonDocument}
import com.couchbase.client.java.query._
import com.couchbase.client.java.query.util.IndexInfo

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration

object CouchbaseSession {

  /**
   * Create a session against the given bucket. The couchbase client used to connect will be created and then closed when
   * the session is closed.
   */
  def apply(settings: CouchbaseSessionSettings,
            bucketName: String)(implicit ec: ExecutionContext): Future[CouchbaseSession] = {
    //wrap CouchbaseAsyncCluster.create up in the Future because it's blocking
    val asyncCluster: Future[AsyncCluster] =
      Future(settings.environment match {
        case Some(environment) =>
          CouchbaseAsyncCluster.create(environment, settings.nodes: _*)
        case None =>
          CouchbaseAsyncCluster.create(settings.nodes: _*)
      }).map(_.authenticate(settings.username, settings.password))

    asyncCluster
      .flatMap(c => RxUtilities.singleObservableToFuture(c.openBucket(bucketName), "").map((c, _)))
      .map { case (c, bucket) => new CouchbaseSessionImpl(bucket, Some(c)) }
  }

  /**
   * Create a session against the given bucket. You are responsible for managing the lifecycle of the couchbase client
   * that the bucket was created with.
   */
  def apply(bucket: Bucket): CouchbaseSession =
    new CouchbaseSessionImpl(bucket.async(), None)

}

/**
 * Scala API: A session allowing querying and interacting with a specific couchbase bucket
 *
 * Not for user extension
 */
@DoNotInherit
trait CouchbaseSession {

  def underlying: AsyncBucket

  def asJava: JavaDslCouchbaseSession

  /**
   * Insert a JSON document using the default write settings.
   *
   * For inserting other types of documents see `insertDoc`.
   *
   * @return A future that completes with the written document when the write completes
   */
  def insert(document: JsonDocument): Future[JsonDocument]

  /**
   * Insert any type of document using the default write settings. Separate from `insert` to make the most common
   * case smoother with the type inference
   *
   * @return A future that completes with the written document when the write completes
   */
  def insertDoc[T <: Document[_]](document: T): Future[T]

  /**
   * Insert a JSON document using the given write settings.
   *
   * For inserting other types of documents see `insertDoc`.
   */
  def insert(document: JsonDocument, writeSettings: CouchbaseWriteSettings): Future[JsonDocument]

  /**
   * Insert any type of document using the given write settings. Separate from `insert` to make the most common
   * case smoother with the type inference
   *
   * @return A future that completes with the written document when the write completes
   */
  def insertDoc[T <: Document[_]](document: T, writeSettings: CouchbaseWriteSettings): Future[T]

  /**
   * @return A document if found or none if there is no document for the id
   */
  def get(id: String): Future[Option[JsonDocument]]

  /**
   * @return A document of the given type if found or none if there is no document for the id
   */
  def get[T <: Document[_]](id: String, documentClass: Class[T]): Future[Option[T]]

  /**
   * @param timeout fail the returned future with a TimeoutException if it takes longer than this
   * @return A document if found or none if there is no document for the id
   */
  def get(id: String, timeout: FiniteDuration): Future[Option[JsonDocument]]

  /**
   * @return A document of the given type if found or none if there is no document for the id
   */
  def get[T <: Document[_]](id: String, timeout: FiniteDuration, documentClass: Class[T]): Future[Option[T]]

  /**
   * Upsert using the default write settings.
   *
   * For upserting other types of documents see `upsertDoc`.
   *
   * @return a future that completes when the upsert is done
   */
  def upsert(document: JsonDocument): Future[JsonDocument]

  /**
   * Upsert using the default write settings.
   *
   * Separate from `upsert` to make the most common case smoother with the type inference
   *
   * @return a future that completes when the upsert is done
   */
  def upsertDoc[T <: Document[_]](document: T): Future[T]

  /**
   * Upsert using the given write settings
   *
   * For upserting other types of documents see `upsertDoc`.
   *
   * @return a future that completes when the upsert is done
   */
  def upsert(document: JsonDocument, writeSettings: CouchbaseWriteSettings): Future[JsonDocument]

  /**
   * Upsert using the given write settings
   *
   * Separate from `upsert` to make the most common case smoother with the type inference
   *
   * @return a future that completes when the upsert is done
   */
  def upsertDoc[T <: Document[_]](document: T, writeSettings: CouchbaseWriteSettings): Future[T]

  /**
   * Remove a document by id using the default write settings.
   *
   * @return Future that completes when the document has been removed, if there is no such document
   *         the future is failed with a `DocumentDoesNotExistException`
   */
  def remove(id: String): Future[Done]

  /**
   * Remove a document by id using the default write settings.
   *
   * @return Future that completes when the document has been removed, if there is no such document
   *         the future is failed with a `DocumentDoesNotExistException`
   */
  def remove(id: String, writeSettings: CouchbaseWriteSettings): Future[Done]

  def streamedQuery(query: N1qlQuery): Source[JsonObject, NotUsed]
  def streamedQuery(query: Statement): Source[JsonObject, NotUsed]
  def singleResponseQuery(query: Statement): Future[Option[JsonObject]]
  def singleResponseQuery(query: N1qlQuery): Future[Option[JsonObject]]

  /**
   * Create or increment a counter
   *
   * @param id What counter document id
   * @param delta Value to increase the counter with if it does exist
   * @param initial Value to start from if the counter does not exist
   * @return The value of the counter after applying the delta
   */
  def counter(id: String, delta: Long, initial: Long): Future[Long]

  /**
   * Create or increment a counter
   *
   * @param id What counter document id
   * @param delta Value to increase the counter with if it does exist
   * @param initial Value to start from if the counter does not exist
   * @return The value of the counter after applying the delta
   */
  def counter(id: String, delta: Long, initial: Long, writeSettings: CouchbaseWriteSettings): Future[Long]

  /**
   * Close the session and release all resources it holds. Subsequent calls to other methods will likely fail.
   */
  def close(): Future[Done]

  /**
   * Create a secondary index for the current bucket.
   *
   * @param indexName the name of the index.
   * @param ignoreIfExist if a secondary index already exists with that name, an exception will be thrown unless this
   *                      is set to true.
   * @param fields the JSON fields to index - each can be either `String` or [com.couchbase.client.java.query.dsl.Expression]
   * @return an {@link scala.concurrent.Future} of true if the index was/will be effectively created, false
   *      if the index existed and ignoreIfExist is true. Completion of the future does not guarantee the index is online
   *      and ready to be used.
   */
  def createIndex(indexName: String, ignoreIfExist: Boolean, fields: AnyRef*): Future[Boolean]

  /**
   * List the existing secondary indexes for the bucket
   */
  def listIndexes(): Source[IndexInfo, NotUsed]
}
