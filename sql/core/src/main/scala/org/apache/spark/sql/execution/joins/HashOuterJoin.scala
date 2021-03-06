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

package org.apache.spark.sql.execution.joins

import java.util.{HashMap => JavaHashMap}

import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.physical.{Partitioning, UnknownPartitioning}
import org.apache.spark.sql.catalyst.plans.{FullOuter, JoinType, LeftOuter, RightOuter}
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.util.collection.CompactBuffer

@DeveloperApi
trait HashOuterJoin {
  self: SparkPlan =>

  val leftKeys: Seq[Expression]
  val rightKeys: Seq[Expression]
  val joinType: JoinType
  val condition: Option[Expression]
  val left: SparkPlan
  val right: SparkPlan

override def outputPartitioning: Partitioning = joinType match {
    case LeftOuter => left.outputPartitioning
    case RightOuter => right.outputPartitioning
    case FullOuter => UnknownPartitioning(left.outputPartitioning.numPartitions)
    case x =>
      throw new IllegalArgumentException(s"HashOuterJoin should not take $x as the JoinType")
  }

  override def output: Seq[Attribute] = {
    joinType match {
      case LeftOuter =>
        left.output ++ right.output.map(_.withNullability(true))
      case RightOuter =>
        left.output.map(_.withNullability(true)) ++ right.output
      case FullOuter =>
        left.output.map(_.withNullability(true)) ++ right.output.map(_.withNullability(true))
      case x =>
        throw new IllegalArgumentException(s"HashOuterJoin should not take $x as the JoinType")
    }
  }

  @transient private[this] lazy val DUMMY_LIST = CompactBuffer[InternalRow](null)
  @transient protected[this] lazy val EMPTY_LIST = CompactBuffer[InternalRow]()

  @transient private[this] lazy val leftNullRow = new GenericInternalRow(left.output.length)
  @transient private[this] lazy val rightNullRow = new GenericInternalRow(right.output.length)
  @transient private[this] lazy val boundCondition =
    condition.map(
      newPredicate(_, left.output ++ right.output)).getOrElse((row: InternalRow) => true)

  // TODO we need to rewrite all of the iterators with our own implementation instead of the Scala
  // iterator for performance purpose.

  protected[this] def leftOuterIterator(
      key: InternalRow,
      joinedRow: JoinedRow,
      rightIter: Iterable[InternalRow]): Iterator[InternalRow] = {
    val ret: Iterable[InternalRow] = {
      if (!key.anyNull) {
        val temp = rightIter.collect {
          case r if boundCondition(joinedRow.withRight(r)) => joinedRow.copy()
        }
        if (temp.isEmpty) {
          joinedRow.withRight(rightNullRow).copy :: Nil
        } else {
          temp
        }
      } else {
        joinedRow.withRight(rightNullRow).copy :: Nil
      }
    }
    ret.iterator
  }

  protected[this] def rightOuterIterator(
      key: InternalRow,
      leftIter: Iterable[InternalRow],
      joinedRow: JoinedRow): Iterator[InternalRow] = {
    val ret: Iterable[InternalRow] = {
      if (!key.anyNull) {
        val temp = leftIter.collect {
          case l if boundCondition(joinedRow.withLeft(l)) =>
            joinedRow.copy()
        }
        if (temp.isEmpty) {
          joinedRow.withLeft(leftNullRow).copy :: Nil
        } else {
          temp
        }
      } else {
        joinedRow.withLeft(leftNullRow).copy :: Nil
      }
    }
    ret.iterator
  }

  protected[this] def fullOuterIterator(
      key: InternalRow, leftIter: Iterable[InternalRow], rightIter: Iterable[InternalRow],
      joinedRow: JoinedRow): Iterator[InternalRow] = {
    if (!key.anyNull) {
      // Store the positions of records in right, if one of its associated row satisfy
      // the join condition.
      val rightMatchedSet = scala.collection.mutable.Set[Int]()
      leftIter.iterator.flatMap[InternalRow] { l =>
        joinedRow.withLeft(l)
        var matched = false
        rightIter.zipWithIndex.collect {
          // 1. For those matched (satisfy the join condition) records with both sides filled,
          //    append them directly

          case (r, idx) if boundCondition(joinedRow.withRight(r)) =>
            matched = true
            // if the row satisfy the join condition, add its index into the matched set
            rightMatchedSet.add(idx)
            joinedRow.copy()

        } ++ DUMMY_LIST.filter(_ => !matched).map( _ => {
          // 2. For those unmatched records in left, append additional records with empty right.

          // DUMMY_LIST.filter(_ => !matched) is a tricky way to add additional row,
          // as we don't know whether we need to append it until finish iterating all
          // of the records in right side.
          // If we didn't get any proper row, then append a single row with empty right.
          joinedRow.withRight(rightNullRow).copy()
        })
      } ++ rightIter.zipWithIndex.collect {
        // 3. For those unmatched records in right, append additional records with empty left.

        // Re-visiting the records in right, and append additional row with empty left, if its not
        // in the matched set.
        case (r, idx) if !rightMatchedSet.contains(idx) =>
          joinedRow(leftNullRow, r).copy()
      }
    } else {
      leftIter.iterator.map[InternalRow] { l =>
        joinedRow(l, rightNullRow).copy()
      } ++ rightIter.iterator.map[InternalRow] { r =>
        joinedRow(leftNullRow, r).copy()
      }
    }
  }

  protected[this] def buildHashTable(
      iter: Iterator[InternalRow],
      keyGenerator: Projection): JavaHashMap[InternalRow, CompactBuffer[InternalRow]] = {
    val hashTable = new JavaHashMap[InternalRow, CompactBuffer[InternalRow]]()
    while (iter.hasNext) {
      val currentRow = iter.next()
      val rowKey = keyGenerator(currentRow)

      var existingMatchList = hashTable.get(rowKey)
      if (existingMatchList == null) {
        existingMatchList = new CompactBuffer[InternalRow]()
        hashTable.put(rowKey, existingMatchList)
      }

      existingMatchList += currentRow.copy()
    }

    hashTable
  }
}
