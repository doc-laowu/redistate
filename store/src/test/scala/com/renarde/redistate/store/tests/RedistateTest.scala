package com.renarde.redistate.store.tests

import java.nio.file.Files

import com.renarde.redistate.tests.{RedisSupport, SparkSupport}
import org.apache.spark.internal.Logging
import org.apache.spark.sql.execution.streaming.MemoryStream
import org.apache.spark.sql.streaming.{GroupState, GroupStateTimeout, OutputMode, StreamingQuery}
import org.apache.spark.sql.{Dataset, SQLContext}
import org.scalatest.FunSuite

class RedistateTest extends FunSuite with SparkSupport with RedisSupport with Logging {


  lazy val checkpointLocation: String = Files.createTempDirectory("redistate-test").toString
  var query: StreamingQuery = _

  test("execute stateful operation") {

    implicit val sqlCtx: SQLContext = spark.sqlContext
    import sqlCtx.implicits._

    val visitsStream = MemoryStream[PageVisit]

    val pageVisitsTypedStream: Dataset[PageVisit] = visitsStream.toDS()

    val initialBatch = Seq(
      generateEvent(1),
      generateEvent(1),
      generateEvent(1),
      generateEvent(1),
      generateEvent(2),
    )

    visitsStream.addData(initialBatch)

    val noTimeout = GroupStateTimeout.NoTimeout
    val userStatisticsStream = pageVisitsTypedStream
      .groupByKey(_.id)
      .mapGroupsWithState(noTimeout)(updateUserStatistics)

    query = userStatisticsStream.writeStream
      .outputMode(OutputMode.Update())
      .option("checkpointLocation", checkpointLocation)
      .foreachBatch(printBatch _)
      .start()

    processDataWithLock(query)
  }

  def generateEvent(id: Int): PageVisit = {
    PageVisit(
      id = id,
      url = s"https://www.my-service.org/${generateBetween(100, 200)}"
    )
  }

  def generateBetween(start: Int = 0, end: Int = 100): Int = {
    start + random.nextInt((end - start) + 1)
  }

  def updateUserStatistics(
                            id: Int,
                            newEvents: Iterator[PageVisit],
                            oldState: GroupState[UserStatistics]): UserStatistics = {

    var state: UserStatistics = if (oldState.exists) oldState.get else UserStatistics(id, Seq.empty, 0)

    for (event <- newEvents) {
      state = state.copy(visits = state.visits ++ Seq(event), totalVisits = state.totalVisits + 1)
      oldState.update(state)
    }
    state
  }

  def printBatch(batchData: Dataset[UserStatistics], batchId: Long): Unit = {
    log.info(s"Started working with batch id $batchId")
    log.info(s"Successfully finished working with batch id $batchId, dataset size: ${batchData.count()}")
  }

  def processDataWithLock(query: StreamingQuery): Unit = {
    query.processAllAvailable()
    while (query.status.message != "Waiting for data to arrive") {
      log.info(s"Waiting for the query to finish processing, current status is ${query.status.message}")
      Thread.sleep(1)
    }
    log.info("Locking the thread for another 5 seconds for state operations cleanup")
    Thread.sleep(5000)
  }

}