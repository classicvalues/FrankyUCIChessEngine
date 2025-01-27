/*
 * MIT License
 *
 * Copyright (c) 2018 Frank Kopp
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */

package fko.FrankyEngine.Franky;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.IntStream;

/**
 * A cache for node results during AlphaBeta search.
 * <p>
 * Implementation uses a simple array of an TT_Entry class. The array indexes
 * are calculated by using the modulo of the max number of entries from the key.
 * <code>entries[key%maxNumberOfEntries]</code>. As long as key is randomly distributed
 * this works just fine.
 * <p>
 * The TT_Entry elements are tailored for small memory footprint and use primitive data types
 * for value (short), depth (byte), type (byte), age (byte).
 */
public class TranspositionTable {

  private static final Logger LOG = LoggerFactory.getLogger(TranspositionTable.class);

  private static final int KB = 1024;
  private static final int MB = KB * KB;

  private long sizeInByte;
  private int  maxNumberOfEntries;
  private int  numberOfEntries = 0;

  private long numberOfPuts       = 0L;
  private long numberOfCollisions = 0L;
  private long numberOfUpdates    = 0L;
  private long numberOfProbes     = 0L;
  private long numberOfHits       = 0L;
  private long numberOfMisses     = 0L;

  private final TT_Entry[] entries;

  /**
   * Creates a hash table with a approximated number of entries calculated by
   * the size in KB divided by the entry size.<br>
   * The hash function is very simple using the modulo of number of entries on the key
   *
   * @param size in MB (1024B^2)
   */
  public TranspositionTable(int size) {
    if (size < 1) {
      final String msg = "Hashtable must a least be 1 MB in size";
      IllegalArgumentException e = new IllegalArgumentException(msg);
      LOG.error(msg, e);
      throw e;
    }

    sizeInByte = (long) size * MB;

    // check available mem - add some head room
    System.gc();
    long usedMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    long freeMemory = (Runtime.getRuntime().maxMemory() - usedMemory);
    long ttMemory = (long) (freeMemory * 0.9);

    LOG.debug("{}", String.format("Max JVM memory:              %,5d MB",
                                  Runtime.getRuntime().maxMemory() / MB));
    LOG.debug("{}", String.format("Current total memory:        %,5d MB",
                                  Runtime.getRuntime().totalMemory() / MB));
    LOG.debug("{}", String.format("Current used memory:         %,5d MB", usedMemory / MB));
    LOG.debug("{}", String.format("Current free memory:         %,5d MB", freeMemory / MB));
    LOG.debug("{}", String.format("Memory available for TT:     %,5d MB", ttMemory / MB));

    if (ttMemory < sizeInByte) {
      LOG.warn("{}", String.format(
        "Not enough memory for a %,dMB transposition cache - reducing to %,dMB", sizeInByte / MB,
        (ttMemory) / MB));
      sizeInByte = (int) (ttMemory); // % of memory
    }

    // size in byte divided by entry size plus size for array bucket
    maxNumberOfEntries = (int) (sizeInByte / (TT_Entry.SIZE + Integer.BYTES));

    // create buckets for hash table
    entries = new TT_Entry[maxNumberOfEntries];
    // initialize to not create objects during usage
    for (int i = 0; i < maxNumberOfEntries; i++) {
      entries[i] = new TT_Entry();
    }

    LOG.info("{}", String.format("Transposition Table Size:    %,5d MB", sizeInByte / (KB * KB)));
    LOG.info("{}", String.format("Transposition Table Entries: %,d", maxNumberOfEntries));
  }

  /**
   * Stores the node value and the depth it has been calculated at.
   *
   * @param key
   * @param value
   * @param type
   * @param depth
   */
  public void put(final long key, final short value, final byte type, final byte depth) {
    put(key, value, type, depth, Move.NOMOVE, false);
  }

  /**
   * Stores the node value and the depth it has been calculated at.
   * @param key
   * @param value
   * @param type
   * @param depth
   * @param bestMove
   * @param mateThreat
   */
  public void put(final long key, final short value, final byte type, final byte depth,
                  final int bestMove, final boolean mateThreat) {

    assert depth >= 0;
    assert type > 0;
    assert value > Evaluation.NOVALUE;

    final TT_Entry ttEntry = entries[getHash(key)];

    numberOfPuts++;

    // New hash
    if (ttEntry.key == 0) {
      numberOfEntries++;

      ttEntry.key = key;
      //ttEntry.fen = position.toFENString();
      ttEntry.age = 1;
      ttEntry.mateThreat = mateThreat;
      ttEntry.value = value;
      ttEntry.type = type;
      ttEntry.depth = depth;
      ttEntry.bestMove = bestMove;
    }
    // Same hash but different position
    // overwrite if
    // - the new entry's depth is higher or equal
    // - the previous entry has not been used (is aged)
    // @formatter:off
    else if (key != ttEntry.key
             && depth >= ttEntry.depth
             && ttEntry.age > 0
    ) { // @formatter:on
      numberOfCollisions++;

      ttEntry.key = key;
      //ttEntry.fen = position.toFENString();
      ttEntry.age = 1;
      ttEntry.mateThreat = mateThreat;

      ttEntry.value = value;
      ttEntry.type = type;
      ttEntry.depth = depth;
      ttEntry.bestMove = bestMove;
    }
    // Same hash and same position -> update entry?
    else if (key == ttEntry.key) {

      // if from same depth only update when quality of new entry is better
      // e.g. don't replace EXACT with ALPHA or BETA
      if (depth == ttEntry.depth) {
        numberOfUpdates++;

        // ttEntry.fen = position.toFENString();
        ttEntry.age = 1;
        ttEntry.mateThreat = mateThreat;

        // old was not EXACT - update
        if (ttEntry.type != TT_EntryType.EXACT) {
          ttEntry.value = value;
          ttEntry.type = type;
          ttEntry.depth = depth;
        }
        // old entry was exact, the new entry is also EXACT -> assert that they are identical
        else assert type != TT_EntryType.EXACT || ttEntry.value == value;

        // overwrite bestMove only with a valid move
        if (bestMove != Move.NOMOVE) ttEntry.bestMove = bestMove;
      }
      // if depth is greater then update in any case
      else if (depth > ttEntry.depth) {
        numberOfUpdates++;

        // ttEntry.fen = position.toFENString();
        ttEntry.age = 1;
        ttEntry.mateThreat = mateThreat;
        ttEntry.value = value;
        ttEntry.type = type;
        ttEntry.depth = depth;

        // overwrite bestNive only with a valid move
        if (bestMove != Move.NOMOVE) ttEntry.bestMove = bestMove;
      } // overwrite bestMove if there wasn't any before
      else if (ttEntry.bestMove == Move.NOMOVE) ttEntry.bestMove = bestMove;
    }
  }

  /**
   * This retrieves the cached value of this node from cache if the
   * cached value has been calculated at a depth equal or deeper as the
   * depth value provided.
   *
   * @param key
   * @return value for key or <tt>Integer.MIN_VALUE</tt> if not found
   */
  public TT_Entry get(final long key) {
    numberOfProbes++;
    final TT_Entry ttEntry = entries[getHash(key)];
    if (ttEntry.key == key) { // hash hit
      numberOfHits++;
      // decrease age of entry until 0
      ttEntry.age = (byte) Math.max(ttEntry.age - 1, 0);
      return ttEntry;
    }
    else numberOfMisses++;
    // cache miss or collision
    return null;
  }

  /**
   * Clears all entry by resetting the to key=0 and
   * value=Integer-MIN_VALUE
   */
  public void clear() {
    // tests show for() is about 60% slower than lambda parallel()
    IntStream.range(0, entries.length).parallel().filter(i -> entries[i].key != 0).forEach(i -> {
      entries[i].key = 0L;
      //entries[i].fen = "";
      entries[i].value = Evaluation.NOVALUE;
      entries[i].depth = 0;
      entries[i].type = TT_EntryType.NONE;
      entries[i].bestMove = Move.NOMOVE;
      entries[i].age = 0;
      entries[i].mateThreat = false;
    });
    numberOfEntries = 0;
    numberOfPuts = 0;
    numberOfCollisions = 0;
    numberOfUpdates = 0;
    numberOfProbes = 0;
    numberOfMisses = 0;
    numberOfHits = 0;
  }

  /**
   * Mark all entries unused and clear for overwriting
   */
  public void ageEntries() {
    // tests show for() is about 60% slower than lambda parallel()
    IntStream.range(0, entries.length)
             .parallel()
             .filter(i -> entries[i].key != 0)
             .forEach(
               i -> entries[i].age = (byte) Math.min(entries[i].age + 1, Byte.MAX_VALUE - 1));
  }

  /**
   * @return the numberOfEntries
   */
  public int getNumberOfEntries() {
    return this.numberOfEntries;
  }

  /**
   * @return the size in KB
   */
  public long getSize() {
    return this.sizeInByte;
  }

  /**
   * @return the max_entries
   */
  public int getMaxEntries() {
    return this.maxNumberOfEntries;
  }

  /**
   * @return the numberOfCollisions
   */
  public long getNumberOfCollisions() {
    return numberOfCollisions;
  }

  /**
   * @return number of entry updates of same position but deeper search
   */
  public long getNumberOfUpdates() {
    return numberOfUpdates;
  }

  /**
   * @return number of queries
   */
  public long getNumberOfProbes() {
    return numberOfProbes;
  }

  /**
   * @return number of hits when queried
   */
  public long getNumberOfHits() {
    return numberOfHits;
  }

  /**
   * @return number of misses when queried
   */
  public long getNumberOfMisses() {
    return numberOfMisses;
  }

  @Override
  public String toString() {
    return String.format("TranspositionTable'{'" + "Size: %,d MB, max entries: %,d "
                         + "numberOfEntries: %,d (%,.1f%%), " + "numberOfPuts: %,d, "
                         + "numberOfCollisions: %,d (%,.1f%%), "
                         + "numberOfUpdates: %,d (%,.1f%%), " + "numberOfProbes: %,d, "
                         + "numberOfHits: %,d (%,.1f%%), numberOfMisses: %,d (%,.1f%%)" + "'}'",
                         sizeInByte / MB, maxNumberOfEntries, numberOfEntries,
                         maxNumberOfEntries == 0
                         ? 0
                         : 100 * ((double) numberOfEntries / maxNumberOfEntries), numberOfPuts,
                         numberOfCollisions,
                         numberOfPuts == 0 ? 0 : 100 * ((double) numberOfCollisions / numberOfPuts),
                         numberOfUpdates,
                         numberOfPuts == 0 ? 0 : 100 * ((double) numberOfUpdates / numberOfPuts),
                         numberOfProbes, numberOfHits,
                         numberOfHits == 0 ? 0 : 100 * ((double) numberOfHits / numberOfProbes),
                         numberOfMisses, numberOfMisses == 0
                                         ? 0
                                         : 100 * ((double) numberOfMisses / numberOfProbes));
  }

  /**
   * @param key
   * @return returns a hash key
   */
  private int getHash(long key) {
    return (int) (key % maxNumberOfEntries);
  }

  // @formatter:off
  /**
   * Entry for transposition table.
   * <pre>
   * fko.FrankyEngine.Franky.TranspositionTable$TT_Entry object internals:
   * OFFSET  SIZE      TYPE DESCRIPTION                               VALUE
   *      0    12           (object header)                           N/A
   *     12     4       int TT_Entry.bestMove                         N/A
   *     16     8      long TT_Entry.key                              N/A
   *     24     2     short TT_Entry.value                            N/A
   *     26     1      byte TT_Entry.depth                            N/A
   *     27     1      byte TT_Entry.type                             N/A
   *     28     1   boolean TT_Entry.age                              N/A
   *     29     3           (loss due to the next object alignment)
   * Instance size: 32 bytes
   * </pre>
   */
  // @formatter:on
  public static final class TT_Entry {

    static final int SIZE = 32;

    long    key        = 0L;
    byte    age        = 0;
    boolean mateThreat = false;
    short   value      = Evaluation.NOVALUE;
    byte    depth      = 0;
    byte    type       = TT_EntryType.NONE;
    int     bestMove   = Move.NOMOVE;
    //String fen = "";
  }

  /**
   * Defines the type of transposition table entry for alpha beta search.
   *
   * Byte is smaller than enum!
   */
  public static class TT_EntryType {
    public static final byte NONE  = 0;
    public static final byte EXACT = 1;
    public static final byte ALPHA = 2;
    public static final byte BETA  = 3;

    public static String toString(int type) {
      switch (type) {
        case EXACT:
          return "EXACT";
        case ALPHA:
          return "ALPHA";
        case BETA:
          return "BETA";
        default:
          return "NONE";
      }
    }
  }
}
