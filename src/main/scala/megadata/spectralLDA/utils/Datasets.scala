package megadata.spectralLDA.utils

import java.nio.file.{Files, Paths, Path}
import breeze.linalg.{SparseVector => brSparseVector}
import org.apache.spark.mllib.linalg.{Vector => mlVector, Vectors => mlVectors}
import org.apache.spark.sql.SparkSession
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD


/** Utility functions to read various datasets */
object Datasets {

  // ------------ Common -------------

  /** Convert a Breeze Sparse Vector to Spark Mllib Vector */
  def breezeToMllib(v: brSparseVector[Double]): mlVector = {
    mlVectors.sparse(v.length, v.activeIterator.toSeq)
  }

  /** Convert a Spark Mllib Vector to Breeze Sparse Vector */
  def mllibToBreeze(v: mlVector): brSparseVector[Double] = {
    val vSparse = v.toSparse
    new brSparseVector[Double](vSparse.indices, vSparse.values, v.size)
  }

  /** Write vocabulary to file
    *
    * @param vocabulary     Array of vocabulary words
    * @param uri            URI to write the vocabulary to
    * @return               URI the vocabulary was written to
    */
  def writeVocabulary(vocabulary: Array[String], uri: String): Path = {
    import collection.JavaConverters._
    Files.write(Paths.get(uri), vocabulary.toBuffer.asJava)
  }

  // --------- Bag of Words ----------

  /** Print document statistics
    *
    * @param features       RDD[(doc-id, (word-id, count))] of Bag-of-words
    * @param probabilities  Quantile levels for the statistics
    * @param relativeError  Acceptable error when estimating the quantiles
    * @param spark          SparkSession
    */
  def printBowStatistics(features: RDD[(Long, (Int, Double))],
                         probabilities: Array[Double],
                         relativeError: Double,
                         spark: SparkSession) = {
    import spark.implicits._

    val numDocs = features.keys.countApproxDistinct(relativeError)
    println(s"# Documents: ~$numDocs")

    val quantilesDistinctTokensByDocument = features
      .mapValues(_ => 1)
      .reduceByKey(_ + _)
      .toDF()
      .stat.approxQuantile("_2", probabilities, relativeError)
    val msg1 = quantilesDistinctTokensByDocument.mkString(", ")
    println(s"Qtl Dist Tokens By Doc: $msg1")

    val quantilesDocumentLength = features
      .mapValues(_._2)
      .reduceByKey(_ + _)
      .toDF()
      .stat.approxQuantile("_2", probabilities, relativeError)
    val msg2 = quantilesDocumentLength.mkString(", ")
    println(s"Qtl Doc Length: $msg2")
  }

  /** Convert bag-of-word tuples to documents for TensorLDA
    *
    * @param features     RDD of (doc-id, (word-id, count))
    * @param maxFeatures  Max number of features
    * @return             RDD of (doc-id, Breeze Sparse Vector)
    */
  def bowFeaturesToBreeze(features: RDD[(Long, (Int, Double))],
                          maxFeatures: Int)
  : RDD[(Long, brSparseVector[Double])] = {
    features
      .groupByKey()
      .mapValues {
        x => brSparseVector[Double](maxFeatures)(x.toSeq: _*)
      }
  }

  /** Convert bag-of-word tuples to documents for Spark LDA
    *
    * @param features     RDD of (doc-id, (word-id, count))
    * @param maxFeatures  Max number of features
    * @return             RDD of (doc-id, Spark Mllib Vector)
    */
  def bowFeaturesToMllib(features: RDD[(Long, (Int, Double))],
                         maxFeatures: Int)
  : RDD[(Long, mlVector)] = {
    features
      .groupByKey()
      .mapValues {
        x => mlVectors.sparse(maxFeatures, x.toSeq)
      }
  }

  /** Pick documents with specified word id
    *
    * @param features   RDD[(doc-id, (word-id, count))] of bag-of-words
    * @param wordId     Retain documents with specified word id
    * @return           Filtered bag-of-words
    */
  def filterDocumentsWithWordId(features: RDD[(Long, (Int, Double))],
                                wordId: Int)
  : RDD[(Long, (Int, Double))] = {
    features
      .map {
        case (docid, (wid, _)) => (docid, wid == wordId)
      }
      .reduceByKey(_ || _)
      .filter(_._2)
      .join(features)
      .mapValues {
        case (_, x) => x
      }
  }

  /** Pick documents with specified word ids
    *
    * @param features   RDD[(doc-id, (word-id, count))] of bag-of-words
    * @param wordIds    Retain documents with specified word ids
    * @return           Filtered bag-of-words
    */
  def filterDocumentsWithWordId(features: RDD[(Long, (Int, Double))],
                                wordIds: Array[Int])
  : RDD[(Long, (Int, Double))] = {
    features
      .map {
        case (docid, (wid, _)) => (docid, wordIds contains wid)
      }
      .reduceByKey(_ || _)
      .filter(_._2)
      .join(features)
      .mapValues {
        case (_, x) => x
      }
  }


  // --- UCI Bag of Words Dataset ----

  /** Read UCI Bag of Words Dataset
    *
    * The output still remains in tuples (doc-id, (word-id, count)).
    * We have to call [[bowFeaturesToBreeze]] or [[bowFeaturesToMllib]]
    * to produce the documents for the topic learning class. In this way
    * the user can cache the output of this function and run the
    * Tensor LDA, Spark LDA in the same session.
    *
    * @param sc               SparkContext
    * @param docWordUri       docword file path
    * @param vocabFilePath    vocab file path
    * @param maxFeatures      Max number of features to retain
    *                         for the bag of words
    * @return                 RDD of (doc-id, (word-id, count)),
    *                         Array of vocabulary word
    */
  def readUciBagOfWords(sc: SparkContext,
                        docWordUri: String,
                        vocabFilePath: String,
                        maxFeatures: Int)
  : (RDD[(Long, (Int, Double))], Array[String]) = {
    val docWord = sc.textFile(docWordUri)

    // Stats of the doc-word dataset
    val docWordStats = docWord.take(3).map(_.toInt)
      match {
        case Array(docCount, vocabSize, nnz) => (docCount, vocabSize, nnz)
      }

    // Cast the doc-word tuples into integers
    // Change the 1-based word indexing to 0-based
    val docWordElements = docWord
      .map(_.split(" "))
      .collect {
        case Array(docid: String, wid: String, c: String)
        => (docid.toLong, wid.toInt - 1, c.toDouble)
      }

    // Document frequency of words
    val df = docWordElements
      .map {
        case (docid: Long, wid: Int, c: Double)
          => (wid, 1)
      }
      .reduceByKey(_ + _)

    // Sort DF and take the top maxFeatures words
    val oldToNewWordIDMap = df
      .sortBy(- _._2)
      .zipWithIndex
      .take(maxFeatures)
      .map {
        case ((wid, _), newWid) => (wid, newWid.toInt)
      }
      .toMap

    // Reindex the words in features
    val features = docWordElements
      .collect {
        case (docid, wid, c) if oldToNewWordIDMap contains wid =>
          (docid, (oldToNewWordIDMap(wid), c))
      }

    // Generate abridged vocabulary
    // Note the words need be sorted in the order of new word-id
    val vocab = sc.textFile(vocabFilePath)
      .zipWithIndex()
      .collect {
        case (w, wid) if oldToNewWordIDMap contains wid.toInt =>
          (w, oldToNewWordIDMap(wid.toInt))
      }
      .sortBy(_._2)
      .collect
      .map(_._1)

    (features, vocab)
  }

  // ------- Wiki Pages Articles dump --------

  /** Reads Wiki dump, outputs bag-of-words RDD and vocabulary
    *
    * @param spark          SparkSession
    * @param dumpUri        URI of the Wiki Pages Articles dump file
    * @param maxFeatures    Maximum number of features
    * @return               RDD of bag-of-words and the vocabulary
    */
  def readWikiPagesArticlesDump(spark: SparkSession,
                                dumpUri: String,
                                maxFeatures: Int)
  : (RDD[(Long, (Int, Double))], Array[String]) = {
    // As of v2.3.0, the spark ml Vector cannot be
    // extracted for missing Encoder class, we thus proceed by
    //
    // 1. Compute the vocabulary with the spark ml classes
    // 2. Process the document texts again with the vocabulary
    //    and map the tokens into their indices to get the RDD
    //    bag-of-words

    import org.apache.spark.sql.Row
    import org.apache.spark.ml.feature.{RegexTokenizer,
      StopWordsRemover, CountVectorizer}

    val df = spark.read
      .format("com.databricks.spark.xml")
      .option("rowTag", "revision")
      .load(dumpUri)
      .filter("text._VALUE is not null")

    val regexTokenized = new RegexTokenizer()
      .setInputCol("text_value")
      .setOutputCol("words")
      .setToLowercase(true)
      .setPattern("\\W+")
      .transform(df.select(df.col("text._VALUE").as( "text_value")))

    val stopWordsRemoved = new StopWordsRemover()
      .setInputCol("words")
      .setOutputCol("stop_words_removed")
      .transform(regexTokenized.select("words"))

    val wikiVectorizerModel = new CountVectorizer()
      .setInputCol("stop_words_removed")
      .setOutputCol("features")
      .setVocabSize(maxFeatures)
      .setMinDF(1)
      .fit(stopWordsRemoved)

    val vocab = wikiVectorizerModel.vocabulary
    val vocabToIndexMap = vocab.zipWithIndex
      .map {
        case (w, wid) => (w, wid.toInt)
      }
      .toMap

    val bow = df.select("text._VALUE")
      .rdd
      .map {
        case Row(documentText: String) =>
          documentText
            .toLowerCase()
            .split("\\W+")
            .collect {
              case w if vocabToIndexMap contains w => vocabToIndexMap(w)
            }
      }
      .zipWithIndex
      .flatMap {
        case (wids, docid) =>
          wids.map {
            case wid => ((docid, wid), 1.0)
          }
      }
      .reduceByKey(_ + _)
      .map {
        case ((docid, wid), c) => (docid, (wid, c))
      }

    (bow, vocab)
  }

}
