// Copyright (C) 2018  Neil Marchant
//
// Author: Neil Marchant
//
// This file is part of dblink.
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <https://www.gnu.org/licenses/>.

package com.github.cleanzr.dblink

import SimilarityFn.{ConstantSimilarityFn, LevenshteinSimilarityFn}
import com.github.cleanzr.dblink.analysis.Clusters
import com.github.cleanzr.dblink.analysis.Clusters
import partitioning.{KDTreePartitioner, PartitionFunction}
import com.typesafe.config.{Config, ConfigException, ConfigObject}
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.functions.{array, col}
import org.apache.spark.sql.{DataFrame, SparkSession}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.Try

/** An entity resolution project
  *
  * @param dataPath path to source records (in CSV format)
  * @param outputPath path to project directory
  * @param checkpointPath path for saving Spark checkpoints
  * @param recIdAttribute name of record identifier column in dataFrame (must be unique across all files)
  * @param fileIdAttribute name of file identifier column in dataFrame (optional)
  * @param entIdAttribute name of entity identifier column in dataFrame (optional: if ground truth is available)
  * @param matchingAttributes attribute specifications to use for matching
  * @param partitionFunction partition function (determines how entities are partitioned across executors)
  * @param randomSeed random seed
  * @param populationSize size of the latent population
  * @param expectedMaxClusterSize expected size of the largest record cluster (used as a hint to improve precaching)
  * @param dataFrame data frame containing source records
  */
case class Project(dataPath: String, outputPath: String, checkpointPath: String,
                   recIdAttribute: String, fileIdAttribute: Option[String],
                   entIdAttribute: Option[String], matchingAttributes: IndexedSeq[Attribute],
                   partitionFunction: PartitionFunction[ValueId], randomSeed: Long, populationSize: Option[Long],
                   expectedMaxClusterSize: Int, dataFrame: DataFrame) extends Logging {
  require(expectedMaxClusterSize >= 0, "expectedMaxClusterSize must be non-negative")

  def sparkContext: SparkContext = dataFrame.sparkSession.sparkContext

  def mkString: String = {
    val lines = mutable.ArrayBuffer.empty[String]
    lines += "Data settings"
    lines += "-------------"
    lines += s"  * Using data files located at '$dataPath'"
    lines += s"  * The record identifier attribute is '$recIdAttribute'"
    fileIdAttribute match {
      case Some(fId) => lines += s"  * The file identifier attribute is '$fId'"
      case None => lines += "  * There is no file identifier"
    }
    entIdAttribute match {
      case Some(eId) => lines += s"  * The entity identifier attribute is '$eId'"
      case None => lines += "  * There is no entity identifier"
    }
    lines += s"  * The matching attributes are ${matchingAttributes.map("'" + _.name + "'").mkString(", ")}"
    lines += ""

    lines += "Hyperparameter settings"
    lines += "-----------------------"
    lines ++= matchingAttributes.zipWithIndex.map { case (attribute, attributeId) =>
      s"  * '${attribute.name}' (id=$attributeId) with ${attribute.similarityFn.mkString} and ${attribute.distortionPrior.mkString}"
    }
    lines += s"  * Size of latent population is ${populationSize.getOrElse(recordsRDD.count())}"
    lines += ""

    lines += "Partition function settings"
    lines += "---------------------------"
    lines += "  * " + partitionFunction.mkString
    lines += ""

    lines += "Project settings"
    lines += "----------------"
    lines += s"  * Using randomSeed=$randomSeed"
    lines += s"  * Using expectedMaxClusterSize=$expectedMaxClusterSize"
    lines += s"  * Saving Markov chain and complete final state to '$outputPath'"
    lines += s"  * Saving Spark checkpoints to '$checkpointPath'"

    lines.mkString("","\n","\n")
  }

  def sharedMostProbableClustersOnDisk: Boolean = {
    val hdfs = FileSystem.get(sparkContext.hadoopConfiguration)
    val fSMPC = new Path(outputPath + "shared-most-probable-clusters.csv")
    hdfs.exists(fSMPC)
  }

  def savedStateOnDisk: Boolean = {
    val hdfs = FileSystem.get(sparkContext.hadoopConfiguration)
    val fDriverState = new Path(outputPath + "driver-state")
    val fPartitionState = new Path(outputPath + "partitions-state.parquet")
    hdfs.exists(fDriverState) && hdfs.exists(fPartitionState)
  }

  def linkageChainOnDisk: Boolean = {
    val hdfs = FileSystem.get(sparkContext.hadoopConfiguration)
    val file = new Path(outputPath + "linkage-chain.parquet")
    hdfs.exists(file)
  }

  /** Transform a source DataFrame into an RDD of `Records` (the format required by dblink).
    */
  def recordsRDD: RDD[Record[String]] = {
    val spark = dataFrame.sparkSession
    import spark.implicits._

    fileIdAttribute match {
      case Some(fIdCol) =>
        dataFrame.select(
          col(recIdAttribute),
          col(fIdCol),
          array(matchingAttributes.map(_.name) map col: _*)
        ).map(r =>
          Record(r.getString(0), r.getString(1), r.getSeq[String](2).toArray)
        ).rdd
      case None =>
        dataFrame.select(
          col(recIdAttribute),
          array(matchingAttributes.map(_.name) map col: _*)
        ).map(r =>
          Record(r.getString(0), "0", r.getSeq[String](1).toArray)
        ).rdd
    }
  }

  /** Loads the ground truth cluster membership for each record. */
  def membershipRDD: Option[RDD[(RecordId, EntityId)]] = {
    entIdAttribute match {
      case Some(eId) =>
        val spark = dataFrame.sparkSession
        val recIdName = recIdAttribute
        import spark.implicits._
        Some(dataFrame.map(r => (r.getAs[RecordId](recIdName), r.getAs[EntityId](eId))).rdd)
      case _ => None
    }
  }

  def getSavedLinkageChain(lowerIterationCutoff: Int = 0): Option[RDD[LinkageState]] = {
    if (linkageChainOnDisk) {
      val chain = if (lowerIterationCutoff == 0) LinkageChain.read(outputPath)
        else LinkageChain.read(outputPath).filter(_.iteration >= lowerIterationCutoff)
      if (chain.isEmpty()) None
      else Some(chain)
    } else None
  }

  def getSavedState: Option[State] = {
    if (savedStateOnDisk) {
      Some(State.read(outputPath))
    } else None
  }

  def generateInitialState: State = {
    info("Generating new initial state")
    val parameters = Parameters(
      populationSize = populationSize.getOrElse(recordsRDD.count()),
      maxClusterSize = expectedMaxClusterSize
    )
    State.deterministic(
      records = recordsRDD,
      attributeSpecs = matchingAttributes,
      parameters = parameters,
      partitionFunction = partitionFunction,
      randomSeed = randomSeed
    )
  }

  def getSavedSharedMostProbableClusters: Option[RDD[Cluster]] = {
    if (sharedMostProbableClustersOnDisk) {
      Some(Clusters.readCsv(outputPath + "shared-most-probable-clusters.csv"))
    } else None
  }

  def getTrueClusters: Option[RDD[Cluster]] = {
    membershipRDD match {
      case Some(membership) => Some(Clusters(membership))
      case _ => None
    }
  }
}

object Project {
  def apply(config: Config): Project = {
    val dataPath = config.getString("dblink.data.path")

    val dataFrame: DataFrame = {
      val spark = SparkSession.builder().getOrCreate()
      spark.read.format("csv")
        .option("header", "true")
        .option("mode", "DROPMALFORMED")
        .option("nullValue", config.getString("dblink.data.nullValue"))
        .load(dataPath)
    }

    val matchingAttributes =
      parseMatchingAttributes(config.getObjectList("dblink.data.matchingAttributes"))

    Project(
      dataPath = dataPath,
      outputPath = config.getString("dblink.outputPath"),
      checkpointPath = config.getString("dblink.checkpointPath"),
      recIdAttribute = config.getString("dblink.data.recordIdentifier"),
      fileIdAttribute = Try {Some(config.getString("dblink.data.fileIdentifier"))} getOrElse None,
      entIdAttribute = Try {Some(config.getString("dblink.data.entityIdentifier"))} getOrElse None,
      matchingAttributes = matchingAttributes,
      partitionFunction = parsePartitioner(config.getConfig("dblink.partitioner"), matchingAttributes.map(_.name)),
      randomSeed = config.getLong("dblink.randomSeed"),
      populationSize = Try {Some(config.getLong("dblink.populationSize"))} getOrElse None,
      expectedMaxClusterSize = Try {config.getInt("dblink.expectedMaxClusterSize")} getOrElse 10,
      dataFrame = dataFrame
    )
  }

  implicit def toConfigTraversable[T <: ConfigObject](objectList: java.util.List[T]): Traversable[Config] = objectList.asScala.map(_.toConfig)

  private def parseMatchingAttributes(configList: Traversable[Config]): Array[Attribute] = {
    configList.map { c =>
      val simFn = c.getString("similarityFunction.name") match {
        case "ConstantSimilarityFn" => ConstantSimilarityFn
        case "LevenshteinSimilarityFn" =>
          LevenshteinSimilarityFn(c.getDouble("similarityFunction.parameters.threshold"), c.getDouble("similarityFunction.parameters.maxSimilarity"))
        case _ => throw new ConfigException.BadValue(c.origin(), "similarityFunction.name", "unsupported value")
      }
      val distortionPrior = BetaShapeParameters(
        c.getDouble("distortionPrior.alpha"),
        c.getDouble("distortionPrior.beta")
      )
      Attribute(c.getString("name"), simFn, distortionPrior)
    }.toArray
  }

  private def parsePartitioner(config: Config, attributeNames: Seq[String]): KDTreePartitioner[ValueId] = {
    if (config.getString("name") == "KDTreePartitioner") {
      val numLevels = config.getInt("parameters.numLevels")
      val attributeIds = config.getStringList("parameters.matchingAttributes").asScala.map( n =>
        attributeNames.indexOf(n)
      )
      KDTreePartitioner[ValueId](numLevels, attributeIds)
    } else {
      throw new ConfigException.BadValue(config.origin(), "name", "unsupported value")
    }
  }
}