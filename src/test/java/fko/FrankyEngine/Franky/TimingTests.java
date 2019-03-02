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

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.function.Function;

/** @author Frank */
@SuppressWarnings("SameParameterValue")
public class TimingTests {

  int[] intList = new int[512];

  /**
   * Is creating a new array fester than resetting all values in a loop?
   * ==> Loop is faster than creating new
   */
  @Test
  @Disabled
  public void testTimingNewVsInit() {
    Function f1 = o -> {
      for (int i = 0, intListLength = intList.length; i < intListLength; i++) {
        intList[i] = 0;
      }
      return null;
    };
    Function f2 = o -> {
      intList = new int[512];
      return null;
    };
    timingTest(5, 50, 100_000, f1, f2);
  }

  /**
   * Testing two versions of {@link Position#isAttacked(Color, Square)}
   * ==> New is faster (2 is slower)
   */
  @Test
  @Disabled
  public void testTimingIsAttacked() {
    Position position = new Position();

    Function f1 = o -> {
      position.isAttacked(Color.BLACK, Square.e8);
      return null;
    };
    Function f2 = o -> {
      position.isAttacked2(Color.BLACK, Square.e8);
      return null;
    };
    timingTest(5, 50, 20_000_000, f1, f2);
  }

  @Test
  @Disabled
  public void testTimingBytevsInt() {
    Position position = new Position();

    Function f1 = o -> {
      int[] test = new int[1024];
      return null;
    };
    Function f2 = o -> {
      byte[] test = new byte[1024];
      return null;
    };
    timingTest(5, 50, 1_000_000, f1, f2);
  }

  @Test
  @Disabled
  public void testTimingMoveGen() {
    Position position = new Position("r3k2r/1ppn3p/2q1q1n1/4P3/2q1Pp2/B5R1/pbp2PPP/1R4K1 b kq e3");
    final MoveGenerator mg = new MoveGenerator();
    Function f1 = o -> {
      mg.setPosition(position);
      mg.getPseudoLegalMoves();
      return null;
    };
    Function f2 = o -> {
      mg.setPosition(position);
      int move;
      while ((move = mg.getNextPseudoLegalMove(false)) != Move.NOMOVE) {
        // nothing
      }
      return null;
    };
    timingTest(5, 50, 100_000, f1, f2);
  }

  @Test
  @Disabled
  public void testTimingMoveLoop() {
    Position position = new Position("r3k2r/1ppn3p/2q1q1n1/4P3/2q1Pp2/B5R1/pbp2PPP/1R4K1 b kq e3");

    SquareList sql = position.getPawnSquares()[1];
    long pawnBB = position.getPiecesBitboards(1, PieceType.PAWN);

    Function f1 = o -> {
      // iterate over all squares where we have a pawn
      for (int i = 0, size = sql.size();
           i < size;
           i++) {
        final Square square = sql.get(i);
        assert position.getPiece(square).getType() == PieceType.PAWN;
      }
      return null;
    };

    Function f2 = o -> {
      // iterate over all squares where we have a pawn
      int i = 0, size = sql.size();
      while (i < size) {
        final Square square = sql.get(i);
        assert position.getPiece(square).getType() == PieceType.PAWN;
        i++;
      }
      return null;
    };

    Function f3 = o -> {
      int sqx;
      long tmpBB = pawnBB;
      while ((sqx = Square.getFirstSquareIndex(tmpBB)) != 64) {
        final Square square = Square.index64Map[sqx];
        assert position.getPiece(square).getType() == PieceType.PAWN;
        tmpBB = Square.removeFirstSquare(tmpBB, sqx);
      }
      return null;
    };
    timingTest(5, 50, 3_000_000, f1, f2, f3);
  }

  private void timingTest(final int rounds, final int iterations, final int repetitions,
                          Function... functions) {

    ArrayList<String> result = new ArrayList<>();

    for (int round = 1; round <= rounds; round++) {
      long start, end, sum;

      System.out.printf("Running round %d of Timing Test%n", round);

      int testNr = 0;
      for (Function f : functions) {
        System.gc();
        int i = 0;
        sum = 0;
        while (++i <= iterations) {
          start = System.nanoTime();
          for (int j = 0; j < repetitions; j++) {
            f.apply(null);
          }
          end = System.nanoTime();
          sum += end - start;
        }
        float avg1 = ((float) sum / iterations) / 1e9f;
        result.add(String.format("Round %d Test %d avg: %,.6f sec", round, testNr++, avg1));
      }
    }

    System.out.println();
    for (String s : result) {
      System.out.println(s);
    }
  }
}
