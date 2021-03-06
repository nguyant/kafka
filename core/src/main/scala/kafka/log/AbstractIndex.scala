/**
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

package kafka.log

import java.io.{File, RandomAccessFile}
import java.nio.{ByteBuffer, MappedByteBuffer}
import java.nio.channels.FileChannel
import java.util.concurrent.locks.{Lock, ReentrantLock}

import kafka.log.IndexSearchType.IndexSearchEntity
import kafka.utils.CoreUtils.inLock
import kafka.utils.{CoreUtils, Logging}
import org.apache.kafka.common.utils.{OperatingSystem, Utils}
import sun.nio.ch.DirectBuffer

import scala.math.ceil

/**
 * The abstract index class which holds entry format agnostic methods.
 *
 * @param _file The index file
 * @param baseOffset the base offset of the segment that this index is corresponding to.
 * @param maxIndexSize The maximum index size in bytes.
 */
abstract class AbstractIndex[K, V](@volatile private var _file: File, val baseOffset: Long,
                                   val maxIndexSize: Int = -1, val writable: Boolean) {
  import AbstractIndex._

  def file: File = {
    if (!initialized) {
      inLock(lock) {
        if (!initialized) {
          initializeMmapAndLength
        }
      }
    }
    _file
  }

  def fileName: String = {
    _file.getName
  }

  def file_=(f: File) {
    _file = f
  }

  // Length of the index file
  @volatile
  private var _length: Option[Long] = None

  protected def entrySize: Int

  protected val lock = new ReentrantLock

  @volatile protected var _mmap: MappedByteBuffer = null

  @volatile var initialized = false

  private def initializeMmapAndLength: Unit = {
    val newlyCreated = _file.createNewFile()
    val raf = if (writable) new RandomAccessFile(_file, "rw") else new RandomAccessFile(_file, "r")
    try {
      /* pre-allocate the file if necessary */
      if(newlyCreated) {
        if(maxIndexSize < entrySize)
          throw new IllegalArgumentException("Invalid max index size: " + maxIndexSize)
        raf.setLength(roundDownToExactMultiple(maxIndexSize, entrySize))
      }

      /* memory-map the file */
      _length = Some(raf.length())
      val idx = {
        if (writable)
          raf.getChannel.map(FileChannel.MapMode.READ_WRITE, 0, _length.get)
        else
          raf.getChannel.map(FileChannel.MapMode.READ_ONLY, 0, _length.get)
      }
      /* set the position in the index for the next entry */
      if(newlyCreated)
        idx.position(0)
      else
      // if this is a pre-existing index, assume it is valid and set position to last entry
        idx.position(roundDownToExactMultiple(idx.limit(), entrySize))
      _mmap = idx
    } finally {
      initialized = true
      CoreUtils.swallow(raf.close())
    }
  }

  protected def mmap: MappedByteBuffer = {
    if (!initialized) {
      inLock(lock) {
        if (!initialized) {
          initializeMmapAndLength
        }
      }
    }
    _mmap
  }

  /**
   * The maximum number of entries this index can hold
   */
  @volatile
  private[this] var _maxEntries: Option[Int] = None

  /** The number of entries in this index */
  @volatile
  protected var _entries: Option[Int] = None

  /**
   * True iff there are no more slots available in this index
   */
  def isFull: Boolean = entries >= maxEntries

  // This can trigger IO operation.
  def maxEntries: Int = {
    if (_maxEntries.isEmpty) {
      inLock(lock) {
        if (_maxEntries.isEmpty)
          _maxEntries = Some(mmap.limit() / entrySize)
      }
    }
    _maxEntries.get
  }

  // This can trigger IO operation.
  def entries: Int = {
    if (_entries.isEmpty) {
      inLock(lock) {
        if (_entries.isEmpty)
          _entries = Some(mmap.position() / entrySize)
      }
    }
    _entries.get
  }

  // This can trigger IO operation.
  def length: Long = {
    if (!initialized) {
      inLock(lock) {
        if (!initialized)
          initializeMmapAndLength
      }
    }
    _length.get
  }

  /**
   * Reset the size of the memory map and the underneath file. This is used in two kinds of cases: (1) in
   * trimToValidSize() which is called at closing the segment or new segment being rolled; (2) at
   * loading segments from disk or truncating back to an old segment where a new log segment became active;
   * we want to reset the index size to maximum index size to avoid rolling new segment.
   *
   * @param newSize new size of the index file
   * @return a boolean indicating whether the size of the memory map and the underneath file is changed or not.
   */
  def resize(newSize: Int): Boolean = {
    inLock(lock) {
      val roundedNewSize = roundDownToExactMultiple(newSize, entrySize)
      if (length == roundedNewSize) {
        false
      } else {
        val raf = new RandomAccessFile(file, "rw")
        val position = mmap.position()

        /* Windows won't let us modify the file length while the file is mmapped :-( */
        if (OperatingSystem.IS_WINDOWS)
          forceUnmap(mmap)
        try {
          raf.setLength(roundedNewSize)
          _length = Some(roundedNewSize)
          _mmap = {
            if (writable)
              raf.getChannel.map(FileChannel.MapMode.READ_WRITE, 0, roundedNewSize)
            else
              raf.getChannel.map(FileChannel.MapMode.READ_ONLY, 0, roundedNewSize)
          }
          _maxEntries = Some(mmap.limit() / entrySize)
          mmap.position(position)
          true
        } finally {
          CoreUtils.swallow(raf.close())
        }
      }
    }
  }

  /**
   * Rename the file that backs this offset index
   *
   * @throws IOException if rename fails
   */
  def renameTo(f: File) {
    try Utils.atomicMoveWithFallback(file.toPath, f.toPath)
    finally file = f
  }

  /**
   * Flush the data in the index to disk
   */
  def flush() {
    inLock(lock) {
      mmap.force()
    }
  }

  /**
   * Delete this index file
   */
  def delete(): Boolean = {
    info(s"Deleting index ${file.getAbsolutePath}")
    inLock(lock) {
      // On JVM, a memory mapping is typically unmapped by garbage collector.
      // However, in some cases it can pause application threads(STW) for a long moment reading metadata from a physical disk.
      // To prevent this, we forcefully cleanup memory mapping within proper execution which never affects API responsiveness.
      // See https://issues.apache.org/jira/browse/KAFKA-4614 for the details.
      if (initialized)
        CoreUtils.swallow(forceUnmap(mmap))
      // Accessing unmapped mmap crashes JVM by SEGV.
      // Accessing it after this method called sounds like a bug but for safety, assign null and do not allow later access.
      _mmap = null
    }
    file.delete()
  }

  /**
   * Trim this segment to fit just the valid entries, deleting all trailing unwritten bytes from
   * the file.
   */
  def trimToValidSize() {
    inLock(lock) {
      resize(entrySize * entries)
    }
  }

  /**
   * The number of bytes actually used by this index
   */
  def sizeInBytes = entrySize * entries

  /** Close the index */
  def close() {
    trimToValidSize()
  }

  def closeHandler() = {
    inLock(lock) {
      // File handler of the index field will be closed after the mmap is garbage collected
      CoreUtils.swallow(forceUnmap(mmap))
      _mmap = null
    }
  }

  /**
   * Do a basic sanity check on this index to detect obvious problems
   *
   * @throws IllegalArgumentException if any problems are found
   */
  def sanityCheck(): Unit

  /**
   * Remove all the entries from the index.
   */
  def truncate(): Unit

  /**
   * Remove all entries from the index which have an offset greater than or equal to the given offset.
   * Truncating to an offset larger than the largest in the index has no effect.
   */
  def truncateTo(offset: Long): Unit

  /**
   * Forcefully free the buffer's mmap.
   */
  protected def forceUnmap(m: MappedByteBuffer) {
    try {
      m match {
        case buffer: DirectBuffer =>
          val bufferCleaner = buffer.cleaner()
          /* cleaner can be null if the mapped region has size 0 */
          if (bufferCleaner != null)
            bufferCleaner.clean()
        case _ =>
      }
    } catch {
      case t: Throwable => error("Error when freeing index buffer", t)
    }
  }

  /**
   * Execute the given function in a lock only if we are running on windows. We do this
   * because Windows won't let us resize a file while it is mmapped. As a result we have to force unmap it
   * and this requires synchronizing reads.
   */
  protected def maybeLock[T](lock: Lock)(fun: => T): T = {
    if (OperatingSystem.IS_WINDOWS)
      lock.lock()
    try fun
    finally {
      if (OperatingSystem.IS_WINDOWS)
        lock.unlock()
    }
  }

  /**
   * To parse an entry in the index.
   *
   * @param buffer the buffer of this memory mapped index.
   * @param n the slot
   * @return the index entry stored in the given slot.
   */
  protected def parseEntry(buffer: ByteBuffer, n: Int): IndexEntry

  /**
   * Find the slot in which the largest entry less than or equal to the given target key or value is stored.
   * The comparison is made using the `IndexEntry.compareTo()` method.
   *
   * @param idx The index buffer
   * @param target The index key to look for
   * @return The slot found or -1 if the least entry in the index is larger than the target key or the index is empty
   */
  protected def largestLowerBoundSlotFor(idx: ByteBuffer, target: Long, searchEntity: IndexSearchEntity): Int =
    indexSlotRangeFor(idx, target, searchEntity)._1

  /**
   * Find the smallest entry greater than or equal the target key or value. If none can be found, -1 is returned.
   */
  protected def smallestUpperBoundSlotFor(idx: ByteBuffer, target: Long, searchEntity: IndexSearchEntity): Int =
    indexSlotRangeFor(idx, target, searchEntity)._2

  /**
   * Lookup lower and upper bounds for the given target.
   */
  private def indexSlotRangeFor(idx: ByteBuffer, target: Long, searchEntity: IndexSearchEntity): (Int, Int) = {
    // check if the index is empty
    if(entries == 0)
      return (-1, -1)

    // check if the target offset is smaller than the least offset
    if(compareIndexEntry(parseEntry(idx, 0), target, searchEntity) > 0)
      return (-1, 0)

    // binary search for the entry
    var lo = 0
    var hi = entries - 1
    while(lo < hi) {
      val mid = ceil(hi/2.0 + lo/2.0).toInt
      val found = parseEntry(idx, mid)
      val compareResult = compareIndexEntry(found, target, searchEntity)
      if(compareResult > 0)
        hi = mid - 1
      else if(compareResult < 0)
        lo = mid
      else
        return (mid, mid)
    }

    (lo, if (lo == entries - 1) -1 else lo + 1)
  }

  private def compareIndexEntry(indexEntry: IndexEntry, target: Long, searchEntity: IndexSearchEntity): Int = {
    searchEntity match {
      case IndexSearchType.KEY => indexEntry.indexKey.compareTo(target)
      case IndexSearchType.VALUE => indexEntry.indexValue.compareTo(target)
    }
  }

  /**
   * Round a number to the greatest exact multiple of the given factor less than the given number.
   * E.g. roundDownToExactMultiple(67, 8) == 64
   */
  private def roundDownToExactMultiple(number: Int, factor: Int) = factor * (number / factor)

}

object AbstractIndex extends Logging {
}

object IndexSearchType extends Enumeration {
  type IndexSearchEntity = Value
  val KEY, VALUE = Value
}
