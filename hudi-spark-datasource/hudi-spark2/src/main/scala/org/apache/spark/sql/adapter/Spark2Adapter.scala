/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.adapter

import org.apache.avro.Schema
import org.apache.hudi.Spark2RowSerDe
import org.apache.hudi.client.utils.SparkRowSerDe
import org.apache.spark.sql.avro.{HoodieAvroDeserializerTrait, HoodieAvroSerializerTrait, Spark2HoodieAvroDeserializer, HoodieAvroSerializer}
import org.apache.spark.sql.catalyst.analysis.UnresolvedRelation
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import org.apache.spark.sql.catalyst.expressions.{Expression, Like}
import org.apache.spark.sql.catalyst.parser.ParserInterface
import org.apache.spark.sql.catalyst.plans.JoinType
import org.apache.spark.sql.catalyst.plans.logical.{InsertIntoTable, Join, LogicalPlan}
import org.apache.spark.sql.catalyst.{AliasIdentifier, TableIdentifier}
import org.apache.spark.sql.execution.datasources.{FilePartition, PartitionedFile, Spark2ParsePartitionUtil, SparkParsePartitionUtil}
import org.apache.spark.sql.hudi.SparkAdapter
import org.apache.spark.sql.hudi.parser.HoodieSpark2ExtendedSqlParser
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.DataType
import org.apache.spark.sql.{Row, SparkSession}

import scala.collection.mutable.ArrayBuffer

/**
 * The adapter for spark2.
 */
class Spark2Adapter extends SparkAdapter {

  def createAvroSerializer(rootCatalystType: DataType, rootAvroType: Schema, nullable: Boolean): HoodieAvroSerializerTrait =
    new HoodieAvroSerializer(rootCatalystType, rootAvroType, nullable)

  def createAvroDeserializer(rootAvroType: Schema, rootCatalystType: DataType): HoodieAvroDeserializerTrait =
    new Spark2HoodieAvroDeserializer(rootAvroType, rootCatalystType)

  override def createSparkRowSerDe(encoder: ExpressionEncoder[Row]): SparkRowSerDe = {
    new Spark2RowSerDe(encoder)
  }

  override def toTableIdentifier(aliasId: AliasIdentifier): TableIdentifier = {
    TableIdentifier(aliasId.identifier, aliasId.database)
  }

  override def toTableIdentifier(relation: UnresolvedRelation): TableIdentifier = {
    relation.tableIdentifier
  }

  override def createJoin(left: LogicalPlan, right: LogicalPlan, joinType: JoinType): Join = {
    Join(left, right, joinType, None)
  }

  override def isInsertInto(plan: LogicalPlan): Boolean = {
    plan.isInstanceOf[InsertIntoTable]
  }

  override def getInsertIntoChildren(plan: LogicalPlan):
    Option[(LogicalPlan, Map[String, Option[String]], LogicalPlan, Boolean, Boolean)] = {
    plan match {
      case InsertIntoTable(table, partition, query, overwrite, ifPartitionNotExists) =>
        Some((table, partition, query, overwrite, ifPartitionNotExists))
      case _=> None
    }
  }

  override def createInsertInto(table: LogicalPlan, partition: Map[String, Option[String]],
      query: LogicalPlan, overwrite: Boolean, ifPartitionNotExists: Boolean): LogicalPlan = {
    InsertIntoTable(table, partition, query, overwrite, ifPartitionNotExists)
  }

  override def createExtendedSparkParser: Option[(SparkSession, ParserInterface) => ParserInterface] = {
    Some(
      (spark: SparkSession, delegate: ParserInterface) => new HoodieSpark2ExtendedSqlParser(spark, delegate)
    )
  }

  override def createSparkParsePartitionUtil(conf: SQLConf): SparkParsePartitionUtil = new Spark2ParsePartitionUtil

  override def createLike(left: Expression, right: Expression): Expression = {
    Like(left, right)
  }

  override def parseMultipartIdentifier(parser: ParserInterface, sqlText: String): Seq[String] = {
    throw new IllegalStateException(s"Should not call ParserInterface#parseMultipartIdentifier for spark2")
  }

  /**
   * Combine [[PartitionedFile]] to [[FilePartition]] according to `maxSplitBytes`.
   *
   * This is a copy of org.apache.spark.sql.execution.datasources.FilePartition#getFilePartitions from Spark 3.2.
   * And this will be called only in Spark 2.
   */
  override def getFilePartitions(
      sparkSession: SparkSession,
      partitionedFiles: Seq[PartitionedFile],
      maxSplitBytes: Long): Seq[FilePartition] = {

    val partitions = new ArrayBuffer[FilePartition]
    val currentFiles = new ArrayBuffer[PartitionedFile]
    var currentSize = 0L

    /** Close the current partition and move to the next. */
    def closePartition(): Unit = {
      if (currentFiles.nonEmpty) {
        // Copy to a new Array.
        val newPartition = FilePartition(partitions.size, currentFiles.toArray)
        partitions += newPartition
      }
      currentFiles.clear()
      currentSize = 0
    }

    val openCostInBytes = sparkSession.sessionState.conf.filesOpenCostInBytes
    // Assign files to partitions using "Next Fit Decreasing"
    partitionedFiles.foreach { file =>
      if (currentSize + file.length > maxSplitBytes) {
        closePartition()
      }
      // Add the given file to the current partition.
      currentSize += file.length + openCostInBytes
      currentFiles += file
    }
    closePartition()
    partitions.toSeq
  }
}
