package org.openinfralabs.caerus.cache.client

import scala.util.hashing.byteswap32
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.execution.datasources.{HadoopFsRelation, LogicalRelation}
import org.openinfralabs.caerus.cache.common.plans.{CaerusPlan, CaerusSourceLoad}
import org.openinfralabs.caerus.cache.common.{BasicReadSizeInfo, Caching, Candidate, FileSkippingIndexing, ReadSizeInfo, Repartitioning, SizeInfo}

import java.nio.ByteBuffer
import java.util.{Random => JavaRandom}
import scala.util.hashing.MurmurHash3
import scala.util.Random
import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag


/**
 * Size estimator for Spark's Semantic Cache Client. SamplingSizeEstimator should create relatively small samples based
 * on a customized value (@ratio). Then it should instantiate the candidate for this sample.
 *
 * The writeSizeInfo should be derived from the size of the specific candidate instantiation
 * ((sample candidate size/sample source input size) * real source input size).
 *
 * The readSizeInfo should be derived differently for each technique.
 *
 * For repartitioning, the SamplingSizeEstimator should observe how many partitions are used for a specific query and
 * return:
 * (nrPartitionsUsed/nrPartitions) * real source input size
 * For example if you have repartitions for temperature [0-9], [10,19], [20-29] and a query asks for an estimation for
 * temperatures in [5,10]. Then there should be only two partitions selected. So, the answer should be:
 * 2/3*real source input size
 *
 * For file-skipping, the SamplingSizeEstimator should observe how many partitions are used for a specific query and
 * return:
 * (nrPartitionsUsed/nrPartitions) * real source input size
 * For example if you have files for temperature [0-15], [8,20], [12-30] and a query asks for an estimation for
 * temperatures in [5,10]. Then there should be only two files selected. So, the answer should be:
 * 2/3*real source input size
 *
 * For caching, the SamplingSizeEstimator should return the same value as the writeSizeInfo no matter what is the query.
 */
case class SamplingSizeEstimator(spark: SparkSession, sampleSize: Int) extends SizeEstimator {
  private var sourceSize: Long = 0L


  private def detectSources(inputPlan: LogicalPlan, plan: CaerusPlan): Seq[RDD[InternalRow]] = {
    plan match {
      case caerusSourceLoad: CaerusSourceLoad =>
        assert(inputPlan.isInstanceOf[LogicalRelation])
        val logicalRelation: LogicalRelation = inputPlan.asInstanceOf[LogicalRelation]
        assert(logicalRelation.relation.isInstanceOf[HadoopFsRelation])
        val hadoopFsRelation = logicalRelation.relation.asInstanceOf[HadoopFsRelation]
        val loadDF: DataFrame = spark.read
          .format(caerusSourceLoad.format)
          .options(hadoopFsRelation.options)
          .schema(hadoopFsRelation.dataSchema)
          .load(caerusSourceLoad.sources.map(source => source.path):_*)
        Seq(loadDF.queryExecution.toRdd)
      case _ =>
        plan.children.indices.flatMap(i => detectSources(inputPlan.children(i), plan.children(i)))
    }
  }

  /**
   * Estimate and update read and write sizes for specific candidate.
   *
   * @param candidate Candidate to estimate and updates sizes for.
   */
  override def estimateSize(inputPlan: LogicalPlan, candidate: Candidate): Unit = {
    candidate match {
      case Repartitioning(caerusSourceLoad, _, _) =>
        assert(inputPlan.isInstanceOf[LogicalRelation])
        val logicalRelation: LogicalRelation = inputPlan.asInstanceOf[LogicalRelation]
        assert(logicalRelation.relation.isInstanceOf[HadoopFsRelation])
        val hadoopFsRelation = logicalRelation.relation.asInstanceOf[HadoopFsRelation]
        val loadDF: DataFrame = spark.read
          .format(caerusSourceLoad.format)
          .options(hadoopFsRelation.options)
          .schema(hadoopFsRelation.dataSchema)
          .load(caerusSourceLoad.sources.map(source => source.path):_*)
        val rdd: RDD[InternalRow] = loadDF.queryExecution.toRdd
        val (_, sketched) = sketch(rdd,sampleSize)
        sourceSize = caerusSourceLoad.sources.size
        val candidates = ArrayBuffer.empty[Float]
        sketched.foreach{ case (idx,n,sample) =>
          val probability = (sample.length / n.toFloat)
            candidates +=  probability

        }
        val writeSize: Long = (candidates.sum/candidates.size * sourceSize).toLong
        val readSizeInfo: ReadSizeInfo = BasicReadSizeInfo(sourceSize / 10)
        val sizeInfo: SizeInfo = SizeInfo(writeSize, readSizeInfo)
        candidate.sizeInfo = Some(sizeInfo)

      case FileSkippingIndexing(caerusSourceLoad, _, _) =>
        assert(inputPlan.isInstanceOf[LogicalRelation])
        val logicalRelation: LogicalRelation = inputPlan.asInstanceOf[LogicalRelation]
        assert(logicalRelation.relation.isInstanceOf[HadoopFsRelation])
        val hadoopFsRelation = logicalRelation.relation.asInstanceOf[HadoopFsRelation]
        val loadDF: DataFrame = spark.read
          .format(caerusSourceLoad.format)
          .options(hadoopFsRelation.options)
          .schema(hadoopFsRelation.dataSchema)
          .load(caerusSourceLoad.sources.map(source => source.path):_*)
        val rdd: RDD[InternalRow] = loadDF.queryExecution.toRdd
        val (_, sketched) = sketch(rdd,sampleSize)
        sourceSize = caerusSourceLoad.sources.size
        val candidates = ArrayBuffer.empty[Float]
        sketched.foreach{ case (idx,n,sample) =>
          val probability = (sample.length / n.toFloat)
            candidates +=  probability
        }
        val writeSize: Long = (candidates.sum/candidates.size * sourceSize).toLong
        val readSizeInfo: ReadSizeInfo = BasicReadSizeInfo(sourceSize / 2)
        val sizeInfo: SizeInfo = SizeInfo(writeSize, readSizeInfo)
        candidate.sizeInfo = Some(sizeInfo)

      case Caching(plan, cachingSizeInfo) =>
        detectSources(inputPlan, plan)

    }
  }
  private def sketch[K: ClassTag](rdd: RDD[K], sampleSize : Int) : (Long, Array[(Int,Long, Array[K])]) = {
    val shift = rdd.id
    val sketched = rdd.mapPartitionsWithIndex ({ (idx, iter) =>
      val seed = byteswap32(idx ^ (shift << 16))
      val (sample, n) = reservoirSampleAndCount(
        iter, sampleSize, seed)
      Iterator((idx, n, sample))
    }).collect()
    val numItems = sketched.map(_._2).sum
    (numItems, sketched)
  }

  /**
   * Reservoir sampling implementation that also returns the input size.
   *
   * @param input input size
   * @param k reservoir size
   * @param seed random seed
   * @return (samples, input size)
   */
  private def reservoirSampleAndCount[T: ClassTag](
                                            input: Iterator[T],
                                            k: Int,
                                            seed: Long = Random.nextLong())
  : (Array[T], Long) = {
    val reservoir = new Array[T](k)
    // Put the first k elements in the reservoir.
    var i = 0
    while (i < k && input.hasNext) {
      val item = input.next()
      reservoir(i) = item
      i += 1
    }

    // If we have consumed all the elements, return them. Otherwise do the replacement.
    if (i < k) {
      // If input size < k, trim the array to return only an array of input size.
      val trimReservoir = new Array[T](i)
      System.arraycopy(reservoir, 0, trimReservoir, 0, i)
      (trimReservoir, i)
    } else {
      // If input size > k, continue the sampling process.
      val rand = new XORShiftRandom(seed)
      while (input.hasNext) {
        val item = input.next()
        val replacementIndex = rand.nextInt(i)
        if (replacementIndex < k) {
          reservoir(replacementIndex) = item
        }
        i += 1
      }
      (reservoir, i)
    }
  }


  /**
   * This class implements a XORShift random number generator algorithm
   * Source:
   * Marsaglia, G. (2003). Xorshift RNGs. Journal of Statistical Software, Vol. 8, Issue 14.
   * @see <a href="http://www.jstatsoft.org/v08/i14/paper">Paper</a>
   * This implementation is approximately 3.5 times faster than
   * {@link java.util.Random java.util.Random}, partly because of the algorithm, but also due
   * to renouncing thread safety. JDK's implementation uses an AtomicLong seed, this class
   * uses a regular Long. We can forgo thread safety since we use a new instance of the RNG
   * for each thread.
   */
  private class XORShiftRandom(init: Long) extends JavaRandom(init) {

    def this() = this(System.nanoTime)

    private var seed = XORShiftRandom.hashSeed(init)

    // we need to just override next - this will be called by nextInt, nextDouble,
    // nextGaussian, nextLong, etc.
    override protected def next(bits: Int): Int = {
      var nextSeed = seed ^ (seed << 21)
      nextSeed ^= (nextSeed >>> 35)
      nextSeed ^= (nextSeed << 4)
      seed = nextSeed
      (nextSeed & ((1L << bits) -1)).asInstanceOf[Int]
    }

    override def setSeed(s: Long): Unit = {
      seed = XORShiftRandom.hashSeed(s)
    }
  }

  /** Contains benchmark method and main method to run benchmark of the RNG */
  private object XORShiftRandom {

    /** Hash seeds to have 0/1 bits throughout. */
    private def hashSeed(seed: Long): Long = {
      val bytes = ByteBuffer.allocate(java.lang.Long.BYTES).putLong(seed).array()
      val lowBits = MurmurHash3.bytesHash(bytes, MurmurHash3.arraySeed)
      val highBits = MurmurHash3.bytesHash(bytes, lowBits)
      (highBits.toLong << 32) | (lowBits.toLong & 0xFFFFFFFFL)
    }
  }

}
