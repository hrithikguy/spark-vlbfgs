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

package org.apache.spark.ml.optim

import breeze.optimize.{DiffFunction, StepSizeUnderflow, StrongWolfeLineSearch}
import breeze.util.Implicits.scEnrichIterator
import org.apache.spark.internal.Logging
import org.apache.spark.ml.linalg.distributed.{DistributedVector, DistributedVectorPartitioner, DistributedVectors}
import org.apache.spark.ml.linalg.{BLAS, Vector}
import org.apache.spark.rdd.{RDD, VRDDFunctions}
import org.apache.spark.storage.StorageLevel

import scala.collection.mutable.ArrayBuffer

/**
 * Implements Vector-free LBFGS on Apache Spark
 *
 * Special note for LBFGS:
 *  If you use it in published work, you must cite one of:
 *  * J. Nocedal. Updating  Quasi-Newton  Matrices  with  Limited  Storage
 *    (1980), Mathematics of Computation 35, pp. 773-782.
 *  * D.C. Liu and J. Nocedal. On the  Limited  mem  Method  for  Large
 *    Scale  Optimization  (1989),  Mathematical  Programming  B,  45,  3,
 *    pp. 503-528.
 *
 * Vector-free LBFGS:
 *  * Weizhu Chen, Zhenghao Wang and Jingren Zhou. Large-scale L-BFGS using MapReduce.
 *  Neural Information Processing Systems (NIPS) 2014.
 *
 * @param maxIter max iteration number.
 * @param m correction number for LBFGS. 3 to 7 is usually sufficient.
 * @param tolerance the convergence tolerance of iterations.
 * @param checkpointInterval checkpoint interval
 * @param eagerPersist whether eagerly persist distributed vectors when calculating.
 */
class VLBFGS(
    maxIter: Int,
    m: Int = 7,
    tolerance: Double = 1E-9,
    checkpointInterval: Int = 15,
    eagerPersist: Boolean = true) extends Logging { VLBFGSOptimizer =>

  require(m > 0)

  require(checkpointInterval <= 0 || checkpointInterval - m >= 5,
    "checkpoint interval is illegal.")

  val fvalMemory: Int = 20
  val relativeTolerance: Boolean = true

  var currentCheckpointList: ArrayBuffer[DistributedVector] = new ArrayBuffer[DistributedVector]()
  var prevCheckpointList: ArrayBuffer[DistributedVector] = null

  // return Tuple(newValue, newAdjValue, newX, newGrad, newAdjGrad)
  protected def determineAndTakeStepSize(
      state: State,
      fn: VDiffFunction,
      direction: DistributedVector
    ): (Double, Double, DistributedVector, DistributedVector, DistributedVector) = {
    // using strong wolfe line search
    val grad = state.grad

    val lineSearchDiffFn = new VLBFGSLineSearchDiffFun(state, direction, fn, eagerPersist)
    val search = new StrongWolfeLineSearch(maxZoomIter = 10, maxLineSearchIter = 10)
    val alpha = search.minimize(lineSearchDiffFn,
      if (state.iter == 0.0) 1.0 / direction.norm else 1.0)

    if (alpha * grad.norm < 1E-10) {
      throw new StepSizeUnderflow
    }

    var newValue: Double = 0.0
    var newX: DistributedVector = null
    var newGrad: DistributedVector = null

    if (alpha == lineSearchDiffFn.lastAlpha) {
      newValue = lineSearchDiffFn.lastValue
      newX = lineSearchDiffFn.lastX
      newGrad = lineSearchDiffFn.lastGrad
    } else {
      // release unused RDDs
      lineSearchDiffFn.disposeLastResult()
      newX = takeStep(state, direction, alpha)
      assert(newX.isPersisted)
      val (_newValue, _newGrad) = fn.calculate(newX, state.checkAndMarkCheckpoint)
      newValue = _newValue
      newGrad = _newGrad
      assert(newGrad.isPersisted)
    }
    (newValue, newValue, newX, newGrad, newGrad)
  }

  protected def takeStep(
      state: this.State,
      dir: DistributedVector,
      stepSize: Double): DistributedVector = {
    val newX = state.x.addScaledVector(stepSize, dir)
      .persist(StorageLevel.MEMORY_AND_DISK, eager = eagerPersist)
    state.checkAndMarkCheckpoint(newX)
    newX
  }

  protected def adjust(
      state: this.State,
      newX: DistributedVector,
      newGrad: DistributedVector,
      newVal: Double): (Double, DistributedVector) = (newVal, newGrad)

  // convergence check here is exactly the same as the one in breeze LBFGS
  protected def checkConvergence(state: State): Int = {
    if (maxIter >= 0 && state.iter >= maxIter) {
      MaxIterationsReachedFlag
    } else if (
      state.fValHistory.length >= 2 &&
        (state.adjustedValue - state.fValHistory.max).abs
          <= tolerance * (if (relativeTolerance) state.initialAdjVal else 1.0)
    ) {
      FunctionValuesConvergedFlag
    } else if (
      state.adjustedGradient.norm <=
        math.max(tolerance * (if (relativeTolerance) state.adjustedValue else 1.0), 1E-8)
    ) {
      GradientConvergedFlag
    } else {
      NotConvergedFlag
    }
  }

  protected def initialState(
      fn: VDiffFunction, init: DistributedVector, checkpointInterval: Int): State = {
    val x = init
    val (value, grad) = fn.calculate(x, null)
    val (adjValue, adjGrad) = adjust(null, x, grad, value)
    new State(x, value, grad, adjValue, adjGrad, 0, adjValue,
      IndexedSeq(Double.PositiveInfinity), NotConvergedFlag, null)
  }

  // VOWLQN will override this method
  def chooseDescentDirection(history: this.History, state: this.State): DistributedVector = {
    val direction = history.computeDirection(state)
    direction.persist(StorageLevel.MEMORY_AND_DISK, eager = eagerPersist)
  }

  // the `fn` is responsible for output `grad` persist.
  // the `init` must be persisted before this interface called.
  def iterations(
      fn: VDiffFunction,
      init: DistributedVector): Iterator[State] = {

    val history: History = History(m, eagerPersist)

    val state0 = initialState(fn, init, checkpointInterval)

    val infiniteIterations = Iterator.iterate(state0) { state =>
      try {
        val dir = chooseDescentDirection(history, state)
        assert(dir.isPersisted)
        val (value, adjValue, x, grad, adjGrad) = determineAndTakeStepSize(state, fn, dir)

        val newState = new State(x, value, grad, adjValue, adjGrad, state.iter + 1, state.initialAdjVal,
          (state.fValHistory :+ value).takeRight(fvalMemory), NotConvergedFlag, dir)

        newState.convergenceFlag = checkConvergence(newState)

        // in order to recycle memory ASAP, now dispose last iteration state
        state.dispose(false)

        newState
      } catch {
        case x: Exception =>
          state.convergenceFlag = SearchFailedFlag
          logError(s"LBFGS search failed: ${x.toString}")
          x.printStackTrace(System.err)
          state
      }
    }

    infiniteIterations.takeUpToWhere { state =>
      val isFinished = state.convergenceFlag match {
        case NotConvergedFlag =>
          false
        case MaxIterationsReachedFlag =>
          logInfo("Vector-Free LBFGS reach max iterations.")
          true
        case FunctionValuesConvergedFlag =>
          logInfo("Vector-Free LBFGS function value converged.")
          true
        case GradientConvergedFlag =>
          logInfo("Vector-Free LBFGS gradient converged.")
          true
        case SearchFailedFlag =>
          logError("Vector-Free LBFGS search failed.")
          true
      }
      if (isFinished) history.dispose
      isFinished
    }
  }

  def dispose(): Unit = {
    currentCheckpointList.foreach(_.deleteCheckpoint())
    if (prevCheckpointList != null) {
      prevCheckpointList.foreach(_.deleteCheckpoint())
    }
  }

  // Line Search DiffFunction
  class VLBFGSLineSearchDiffFun(
      state: this.State,
      direction: DistributedVector,
      outer: VDiffFunction,
      eagerPersist: Boolean
    ) extends DiffFunction[Double]{

    // store last step size
    var lastAlpha: Double = Double.NegativeInfinity

    // store last fn value
    var lastValue: Double = 0.0

    // store last point vector
    var lastX: DistributedVector = null

    // store last gradient vector
    var lastGrad: DistributedVector = null

    // store last line search grad value
    var lastLineSearchGradValue: Double = 0.0

    // calculates the value at a point
    override def valueAt(alpha: Double): Double = calculate(alpha)._1

    // calculates the gradient at a point
    override def gradientAt(alpha: Double): Double = calculate(alpha)._2

    // Calculates both the value and the gradient at a point
    def calculate(alpha: Double): (Double, Double) = {

      if (alpha == 0.0) {
        state.value -> (state.grad dot direction)
      } else if (lastAlpha == alpha) {
        lastValue -> lastLineSearchGradValue
      } else {
        // release unused RDDs
        disposeLastResult()

        lastAlpha = alpha

        lastX = VLBFGSOptimizer.takeStep(state, direction, alpha)

        val (fnValue, grad) = outer.calculate(lastX, state.checkAndMarkCheckpoint)

        assert(grad.isPersisted)

        lastGrad = grad
        lastValue = fnValue
        lastLineSearchGradValue = grad dot direction

        lastValue -> lastLineSearchGradValue
      }
    }

    def disposeLastResult() = {
      // release last point vector
      if (lastX != null) {
        lastX.unpersist()
        lastX = null
      }

      // release last gradient vector
      if (lastGrad != null) {
        lastGrad.unpersist()
        lastGrad = null
      }
    }
  }

  val NotConvergedFlag = 0
  val MaxIterationsReachedFlag = 1
  val FunctionValuesConvergedFlag = 2
  val GradientConvergedFlag = 3
  val SearchFailedFlag = 4

  class State(
    var x: DistributedVector,
    val value: Double,
    var grad: DistributedVector,
    val adjustedValue: Double,
    var adjustedGradient: DistributedVector,
    val iter: Int,
    val initialAdjVal: Double,
    val fValHistory: IndexedSeq[Double],
    var convergenceFlag: Int,
    var direction: DistributedVector) {

    def shouldCheckpoint(): Boolean = {
      VLBFGSOptimizer.checkpointInterval > 0 && iter > 1 &&
        (iter % VLBFGSOptimizer.checkpointInterval) == 0
    }

    def markCheckpoint(vector: DistributedVector): Unit = {
      vector.checkpoint()
      VLBFGSOptimizer.currentCheckpointList += vector
    }

    val checkAndMarkCheckpoint = (vector: DistributedVector) => {
      if (shouldCheckpoint()) {
        markCheckpoint(vector)
      }
    }

    def dispose(iterationFinished: Boolean) = {
      // If iteration hasn't finished,
      // releasing `x` and `grad` should be postponed to next iteration
      if (iterationFinished) {
        x.unpersist()
        grad.unpersist()
      }
      if (adjustedGradient != grad) {
        adjustedGradient.unpersist()
      }
      x = null
      grad = null
      adjustedGradient = null
      if (direction != null) {
        direction.unpersist()
        direction = null
      }
    }
  }

  case class History(m: Int, eagerPersist: Boolean) {
    require(m > 0)

    private var k = 0

    val S: Array[DistributedVector] = new Array[DistributedVector](m)
    val Y: Array[DistributedVector] = new Array[DistributedVector](m)

    private val SSdot: Array[Array[Double]] = Array.ofDim[Double](m, m)
    private val YYdot: Array[Array[Double]] = Array.ofDim[Double](m, m)
    private val SYdot: Array[Array[Double]] = Array.ofDim[Double](m, m)

    var lastX: DistributedVector = null
    var lastGrad: DistributedVector = null

    def shouldCheckpointLBFGSSiYi(state: State): Boolean = {
      if (checkpointInterval > 0 && state.iter > 1) {
        val mod = state.iter % VLBFGSOptimizer.checkpointInterval
        (mod <= 1 || mod >= VLBFGSOptimizer.checkpointInterval - m + 2)
      } else false
    }

    def dispose = {
      for (i <- 0 until m) {
        if (S(i) != null) {
          S(i).unpersist()
          S(i) = null
        }
        if (Y(i) != null) {
          Y(i).unpersist()
          Y(i) = null
        }
      }
      if (lastX != null) {
        lastX.unpersist()
        lastX = null
      }
      if (lastGrad != null) {
        lastGrad.unpersist()
        lastGrad = null
      }
    }

    private def push(vv: Array[DistributedVector], v: DistributedVector): Unit = {
      val end = vv.length - 1
      if (vv(0) != null) vv(0).unpersist()
      for (i <- 0 until end) {
        vv(i) = vv(i + 1)
      }
      vv(end) = v
      // v.persist() do not need persist here because `v` has already persisted.
    }

    private def shift(VV: Array[Array[Double]]): Unit = {
      val end = VV.length - 1
      for (i <- 0 until end; j <- 0 until end) {
        VV(i)(j) = VV(i + 1)(j + 1)
      }
    }

    // In LBFGS newAdjGrad == newGrad, but in OWLQN, newAdjGrad contains L1 pseudo-gradient
    // Note: The approximate Hessian computed in LBFGS must use `grad` without L1 pseudo-gradient
    def computeDirection(state: State): DistributedVector = {
      val newX = state.x
      val newGrad = state.grad
      val newAdjGrad = state.adjustedGradient

      val dir = if (k == 0) {
        lastX = newX
        lastGrad = newGrad
        newAdjGrad.scale(-1)
      } else {
        val newSYTaskList = Seq("S", "Y")

        val newS: DistributedVector = newX.sub(lastX)
          .persist(StorageLevel.MEMORY_AND_DISK, eager = false)
        val newY: DistributedVector = newGrad.sub(lastGrad)
          .persist(StorageLevel.MEMORY_AND_DISK, eager = false)

        if (shouldCheckpointLBFGSSiYi(state)) {
          state.markCheckpoint(newS)
          state.markCheckpoint(newY)
        }

        // push `newS` and `newY` into LBFGS S & Y vector history.
        push(S, newS)
        push(Y, newY)

        // calculate dot products between all `S` and `Y` vectors
        shift(SSdot)
        shift(SYdot)
        shift(YYdot)

        val start = math.max(m - k, 0)
        val localM = m
        val mm1 = m - 1

        val rddList = Array.concat(S.filter(_ != null).map(_.values),
          Y.filter(_ != null).map(_.values), Array(newAdjGrad.values)).toList

        // calculate dot products between 2M + 1 distributed vectors
        // only calulate the dot products of new added`S`, `Y` and `adjGrad`
        // with `M - 1` history `S` and `Y` vectors
        // use `union` + `repartition` instead of launching multiple jobs of `RDD.zip`
        // such way can save IO cost because when calculating,
        // only need to load newest `S`, newest `Y` and `adjGrad` partitions once.
        val dotArr = newX.values.sparkContext.union(rddList.zipWithIndex.map { case (rdd: RDD[Vector], index: Int) =>
          rdd.mapPartitionsWithIndex { case (pid: Int, iter: Iterator[Vector]) =>
            iter.map(v => (pid, (index, v)))
          }
        }).partitionBy(new DistributedVectorPartitioner(newX.numPartitions)).map(_._2).glom()
          .map(list => list.sortBy(_._1).map(_._2)).map {
          vecList: Array[Vector] =>
            val SVecList = vecList.slice(0, localM - start)
            val YVecList = vecList.slice(localM - start, 2 * (localM - start))

            val adjGradVec = vecList(vecList.size - 1)
            val newSVec = SVecList(SVecList.size - 1)
            val newYVec = YVecList(YVecList.size - 1)

            val ssDot = new Array[Double](localM)
            val yyDot = new Array[Double](localM)
            val syDot = new Array[Double](localM)
            val ysDot = new Array[Double](localM)
            val sgDot = new Array[Double](localM)
            val ygDot = new Array[Double](localM)

            var i = 0
            while(i < SVecList.size - 1) {
              val SVec = SVecList(i)
              ssDot(start + i) = BLAS.dot(SVec, newSVec)
              syDot(start + i) = BLAS.dot(SVec, newYVec)
              sgDot(start + i) = BLAS.dot(SVec, adjGradVec)
              i += 1
            }
            i = 0
            while(i < YVecList.size - 1) {
              val YVec = YVecList(i)
              ysDot(start + i) = BLAS.dot(YVec, newSVec)
              yyDot(start + i) = BLAS.dot(YVec, newYVec)
              ygDot(start + i) = BLAS.dot(YVec, adjGradVec)
              i += 1
            }
            ssDot(mm1) = BLAS.dot(newSVec, newSVec)
            yyDot(mm1) = BLAS.dot(newYVec, newYVec)
            syDot(mm1) = BLAS.dot(newSVec, newYVec)
            ysDot(mm1) = syDot(mm1)
            sgDot(mm1) = BLAS.dot(newSVec, adjGradVec)
            ygDot(mm1) = BLAS.dot(newYVec, adjGradVec)

            Array.concat(ssDot, yyDot, syDot, ysDot, sgDot, ygDot)
        }.reduce((a1, a2) => a1.zip(a2).map(t => t._1 + t._2))

        val ssDot = dotArr.slice(0, m)
        val yyDot = dotArr.slice(m, 2 * m)
        val syDot = dotArr.slice(2 * m, 3 * m)
        val ysDot = dotArr.slice(3 * m, 4 * m)
        val SGdot = dotArr.slice(4 * m, 5 * m)
        val YGdot = dotArr.slice(5 * m, 6 * m)

        for (i <- start to mm1) {
          SSdot(i)(mm1) = ssDot(i)
          SSdot(mm1)(i) = ssDot(i)
          YYdot(i)(mm1) = yyDot(i)
          YYdot(mm1)(i) = yyDot(i)
          SYdot(i)(mm1) = syDot(i)
          SYdot(mm1)(i) = ysDot(i)
        }

        // After dot products between these vectors have been computed, we can make sure that
        // `newS` and `newY` computed and `lastX` and `lastGrad` won't be used,
        // we can unpersist `lastX` and `lastGrad`
        lastX.unpersist()
        lastGrad.unpersist()

        // check whether new round vectors have been all checkpointed.
        if (newX.isCheckpointed) {
          // make sure `newGrad`, `newAdjGrad`, last m `S` and last m `Y` vectors have been checkpointed.
          assert(newGrad.isCheckpointed)
          assert(newAdjGrad.isCheckpointed)
          S.foreach(v => assert(v.isCheckpointed))
          Y.foreach(v => assert(v.isCheckpointed))

          logInfo("new checkpoint round finished.")
          if (VLBFGSOptimizer.prevCheckpointList != null) {
            VLBFGSOptimizer.prevCheckpointList.foreach(_.deleteCheckpoint())
          }
          VLBFGSOptimizer.prevCheckpointList = VLBFGSOptimizer.currentCheckpointList
          VLBFGSOptimizer.currentCheckpointList = new ArrayBuffer[DistributedVector]()
        }

        lastX = newX
        lastGrad = newGrad

        val theta = Array.fill(m)(0.0)
        val tau = Array.fill(m)(0.0)
        var tauAdjGrad = -1.0

        val alpha = new Array[Double](m)
        for (i <- mm1 to start by (-1)) {
          var sum = 0.0
          for (j <- 0 until m) {
            sum +=
              (SSdot(i)(j) * theta(j) + SYdot(i)(j) * tau(j))
          }
          sum += SGdot(i) * tauAdjGrad
          val a = sum / SYdot(i)(i)
          assert(!a.isNaN)
          alpha(i) = a
          tau(i) -= a
        }

        val scale = SYdot(mm1)(mm1) / YYdot(mm1)(mm1)

        for (i <- 0 until m) {
          theta(i) *= scale
          tau(i) *= scale
        }
        tauAdjGrad *= scale
        for (i <- start to mm1) {
          var sum = 0.0
          for (j <- 0 until m) {
            sum +=
              (SYdot(j)(i) * theta(j) + YYdot(i)(j) * tau(j))
          }
          sum += YGdot(i) * tauAdjGrad
          val b = alpha(i) - sum / SYdot(i)(i)
          assert(!b.isNaN)
          theta(i) += b
        }

        DistributedVectors.combine(
          (theta.toSeq.zip(S) ++ tau.toSeq.zip(Y) ++ Seq((tauAdjGrad, newAdjGrad)))
            .filter(_._2 != null): _*)
      }
      k += 1
      dir
    }
  }
}

abstract class VDiffFunction(eagerPersist: Boolean = true) { outer =>

  // Calculates both the value and the gradient at a point
  def calculate(x: DistributedVector, checkAndMarkCheckpoint: DistributedVector => Unit):
    (Double, DistributedVector)
}
