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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;

/**
 * SearchTreeSizeTest
 */
public class SearchTreeSizeTest {

  private static final Logger LOG = LoggerFactory.getLogger(SearchTreeSizeTest.class);
  private              int    HASH_SIZE;
  private              int    THREADS;

  class SingleTest {
    String name  = "";
    long   nodes = 0;
    int    nps   = 0;
    long   time  = 0;
    int    move  = Move.NOMOVE;
    int    value = Evaluation.NOVALUE;
    String pv    = "";
  }

  class Result {
    String           fen;
    List<SingleTest> tests = new ArrayList<>();

    public Result(String fen) {
      this.fen = fen;
    }
  }

  @BeforeEach
  void setUp() {

  }

  @Test
  @Disabled
  public void sizeOfSearchTreeTest() throws ExecutionException, InterruptedException {

    final int NO_OF_TESTS = 5; //  Integer.MAX_VALUE;
    final int START_NO = 0;
    final int DEPTH = 6;
    HASH_SIZE = 1024;
    THREADS = 1;

    LOG.info("Start SIZE Test for depth {}", DEPTH);
    List<String> fens = getFENs();
    List<Result> results = new ArrayList<>();

    // ForkJoinPool allows to limit the number of threads for lambda .parallel
    // to individual values.
    ForkJoinPool forkJoinPool = new ForkJoinPool(THREADS);
    forkJoinPool.submit(() -> fens.parallelStream().skip(START_NO)
                                  .limit(NO_OF_TESTS)
                                  .forEach(fen -> results.add(featureMeasurements(DEPTH, fen))))
                .get();

    // Print result
    System.out.println();
    System.out.printf("################## RESULTS for depth %d ##########################%n",
                      DEPTH);
    System.out.println();
    System.out.printf("%-12s | %6s | %8s | %15s | %12s | %12s | %s | %s %n", "Test Name", "Move",
                      "Value", "Nodes", "Nps", "Time", "PV", "Fen");
    System.out.println("-----------------------------------------------------------------------"
                       + "-----------------------------------------------------------------------");

    Map<String, Long> sumNodes = new LinkedHashMap<>();
    Map<String, Long> sumNps = new LinkedHashMap<>();
    Map<String, Long> sumTime = new LinkedHashMap<>();

    for (Result result : results) {
      int lastMove = Move.NOMOVE;
      long lastNodes = 0;
      for (SingleTest test : result.tests) {
        long oldNodes = sumNodes.get(test.name) == null ? 0 : sumNodes.get(test.name);
        long oldNps = sumNps.get(test.name) == null ? 0 : sumNps.get(test.name);
        long oldTime = sumTime.get(test.name) == null ? 0 : sumTime.get(test.name);
        sumNodes.put(test.name, oldNodes + test.nodes);
        sumNps.put(test.name, oldNps + test.nps);
        sumTime.put(test.name, oldTime + test.time);

        String changeFlagMove = "";
        if (lastMove != Move.NOMOVE && test.move != lastMove) changeFlagMove = ">";
        lastMove = test.move;

        String changeFlagNodes = "";
        if (lastNodes != 0) {
          if (test.nodes > lastNodes) changeFlagNodes = "^";
          if (test.nodes == lastNodes) changeFlagNodes = "=";
        }
        lastNodes = test.nodes;

        // @formatter:off
        System.out.printf("%-12s | %1s%5s | %8s | %1s%,14d | %,12d | %,12d | %s | %s %n",
                          test.name,
                          changeFlagMove,
                          Move.toSimpleString(test.move),
                          test.value,
                          changeFlagNodes,
                          test.nodes,
                          test.nps,
                          test.time,
                          test.pv,
                          result.fen);
        // @formatter:on
      }
      System.out.println();
    }
    System.out.println("-----------------------------------------------------------------------"
                       + "-----------------------------------------------------------------------");
    System.out.println();
    long lastNodes = 0;
    long lastTime = 0;
    for (String key : sumNodes.keySet()) {

      String changeFlagNodes = "";
      if (lastNodes != 0) {
        if (sumNodes.get(key) > lastNodes) changeFlagNodes = "^";
        else if (sumNodes.get(key) == lastNodes) changeFlagNodes = "=";
      }
      lastNodes = sumNodes.get(key);

      String changeFlagTime = "";
      if (lastTime != 0) {
        if (sumTime.get(key) > lastTime) changeFlagTime = "^";
        else if (sumTime.get(key) == lastTime) changeFlagTime = "=";
      }
      lastTime = sumTime.get(key);

      System.out.printf("Test: %-12s  Nodes: %1s%,15d  Nps: %,15d  Time: %1s%,15d %n", key,
                        changeFlagNodes, sumNodes.get(key), sumNps.get(key), changeFlagTime,
                        sumTime.get(key));
    }
    System.out.println();

  }

  private Result featureMeasurements(final int depth, final String fen) {

    FrankyEngine engine = new FrankyEngine();
    Search search = engine.getSearch();
    search.config.USE_BOOK = false;
    search.setHashSize(HASH_SIZE);

    Result result = new Result(fen);

    Position position = new Position(fen);
    SearchMode searchMode = new SearchMode(0, 0, 0, 0, 0, 0, 0, depth, 0, null, false, true, false);

    // turn off all optimizations to get a reference value of the search tree size
    search.config.USE_ALPHABETA_PRUNING = false;
    search.config.USE_PVS = false;
    search.config.USE_PVS_ORDERING = false;
    search.config.USE_KILLER_MOVES = false;
    search.config.USE_ASPIRATION_WINDOW = false;
    search.config.USE_MTDf = false;

    search.config.USE_TRANSPOSITION_TABLE = false;
    search.config.USE_TT_ROOT = false;

    search.config.USE_MDP = false;
    search.config.USE_MPP = false;

    search.config.USE_RFP = false;
    search.config.USE_NMP = false;
    search.config.USE_RAZOR_PRUNING = false;

    search.config.USE_IID = false;

    search.config.USE_EXTENSIONS = false;

    search.config.USE_LIMITED_RAZORING = false;
    search.config.USE_EXTENDED_FUTILITY_PRUNING = false;
    search.config.USE_FUTILITY_PRUNING = false;
    search.config.USE_LMP = false;
    search.config.USE_LMR = false;

    search.config.USE_QUIESCENCE = false;
    search.config.USE_QFUTILITY_PRUNING = false;

    // pure MiniMax
    search.config.USE_QUIESCENCE = true;
    //result.tests.add(measureTreeSize(position, searchMode, "MINIMAX+QS", true));

    // AlphaBeta with TT and SORT
    search.config.USE_ALPHABETA_PRUNING = true;
    result.tests.add(measureTreeSize(search, position, searchMode, "ALPHABETA", true));

    // MTDf - just for debugging for now
    //    search.config.USE_MTDf = true;
    //    search.config.MTDf_START_DEPTH = 2;
    //    result.tests.add(measureTreeSize(search, position, searchMode, "MTDf", true));
    //    search.config.USE_MTDf = false;

    // PVS
    search.config.USE_PVS = true;
    search.config.USE_PVS_ORDERING = true;
    search.config.USE_KILLER_MOVES = true;
    search.config.NO_KILLER_MOVES = 2;
    result.tests.add(measureTreeSize(search, position, searchMode, "PVS", true));

    search.config.USE_TRANSPOSITION_TABLE = true;
    search.config.USE_TT_ROOT = true;
    result.tests.add(measureTreeSize(search, position, searchMode, "TT", true));

    // Aspiration
    search.config.USE_ASPIRATION_WINDOW = true;
    search.config.ASPIRATION_START_DEPTH = 2;
    result.tests.add(measureTreeSize(search, position, searchMode, "ASPIRATION", true));

    // Minor Pruning
    search.config.USE_MDP = true;
    search.config.USE_MPP = true;
    result.tests.add(measureTreeSize(search, position, searchMode, "MDP_MPP", true));

    // Reverse Futility Pruning
    search.config.USE_RFP = true;
    search.config.RFP_MARGIN = 300;
    result.tests.add(measureTreeSize(search, position, searchMode, "RFP", true));

    // Null move pruning
    search.config.USE_NMP = true;
    search.config.NMP_DEPTH = 3;
    search.config.USE_VERIFY_NMP = true;
    search.config.NMP_VERIFICATION_DEPTH = 3;
    result.tests.add(measureTreeSize(search, position, searchMode, "NMP", true));

    // Razor reduction
    search.config.USE_RAZOR_PRUNING = true;
    search.config.RAZOR_DEPTH = 3;
    search.config.RAZOR_MARGIN = 600;
    result.tests.add(measureTreeSize(search, position, searchMode, "RAZOR", true));

    // Internal Iterative Deepening
    search.config.USE_IID = true;
    result.tests.add(measureTreeSize(search, position, searchMode, "IID", true));

    // Search extensions
    search.config.USE_EXTENSIONS = true;
    result.tests.add(measureTreeSize(search, position, searchMode, "EXTENSION", true));

    // Futility Pruning
    search.config.USE_FUTILITY_PRUNING = true;
    result.tests.add(measureTreeSize(search, position, searchMode, "FP", true));
    search.config.USE_QFUTILITY_PRUNING = true;
    result.tests.add(measureTreeSize(search, position, searchMode, "QFP", true));
    search.config.USE_LIMITED_RAZORING = true;
    result.tests.add(measureTreeSize(search, position, searchMode, "LR", true));
    search.config.USE_EXTENDED_FUTILITY_PRUNING = true;
    result.tests.add(measureTreeSize(search, position, searchMode, "EFP", true));

    // Late Move Pruning
    search.config.USE_LMP = true;
    search.config.LMP_MIN_DEPTH = 3;
    search.config.LMP_MIN_MOVES = 6;
    result.tests.add(measureTreeSize(search, position, searchMode, "LMP", true));

    // Late Move Reduction
    search.config.USE_LMR = true;
    search.config.LMR_MIN_DEPTH = 2;
    search.config.LMR_MIN_MOVES = 3;
    search.config.LMR_REDUCTION = 1;
    result.tests.add(measureTreeSize(search, position, searchMode, "LMR", true));

    return result;

  }

  private SingleTest measureTreeSize(Search search, final Position position,
                                     final SearchMode searchMode, final String feature,
                                     final boolean clearTT) {

    System.out.println("Testing. " + feature);
    if (clearTT) search.clearHashTables();
    search.startSearch(position, searchMode);
    search.waitWhileSearching();

    SingleTest test = new SingleTest();
    test.name = feature;
    test.nodes = search.getSearchCounter().nodesVisited;
    test.move = search.getLastSearchResult().bestMove;
    test.value = search.getLastSearchResult().resultValue;
    test.nps = (int) ((1e3 * search.getSearchCounter().nodesVisited) / (
      search.getSearchCounter().lastSearchTime + 1));
    test.time = search.getSearchCounter().lastSearchTime;
    test.pv = search.getPrincipalVariationString(0);

    return test;

  }

  ArrayList<String> getFENs() {

    ArrayList<String> fen = new ArrayList<>();

    fen.add(Position.STANDARD_BOARD_FEN);
    fen.add("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq -");
    fen.add("1r3rk1/1pnnq1bR/p1pp2B1/P2P1p2/1PP1pP2/2B3P1/5PK1/2Q4R w - -");
    fen.add("r1bq1rk1/pp2bppp/2n2n2/3p4/3P4/2N2N2/PPQ1BPPP/R1B2RK1 b - -");
    fen.add("1r1r2k1/2p1qp1p/6p1/ppQB1b2/5Pn1/2R1P1P1/PP5P/R1B3K1 b - -");
    fen.add("2q1r1k1/1ppb4/r2p1Pp1/p4n1p/2P1n3/5NPP/PP3Q1K/2BRRB2 w - -");
    fen.add("2r4k/pB4bp/6p1/6q1/1P1n4/2N5/P4PPP/2R1Q1K1 b - -");
    fen.add("6k1/1R4pp/2p5/8/P1rNp3/6Pb/4PK2/8 w - -");
    fen.add("6K1/n1P2N1p/6pr/b1pp3b/n2Bp1k1/1R2R1Pp/3p1P2/2qN1B2 w - -");
    fen.add("2b1rk2/r6p/1pP1p1p1/p2pNnR1/5Q2/P1B4q/1PP2P1P/1K4R1 w - -");
    fen.add("8/8/8/p7/8/8/R6p/2K2Rbk w - -");
    fen.add("8/8/8/4N3/2R5/4k3/8/5K2 w - -");
    fen.add("2r1r1k1/pb1n1pp1/1p1qpn1p/4N1B1/2PP4/3B4/P2Q1PPP/3RR1K1 w - - ");
    fen.add("r3k2r/1ppn3p/2q1q1n1/4P3/2q1Pp2/B5R1/pbp2PPP/1R4K1 b kq e3");
    fen.add("R6R/3Q4/1Q4Q1/4Q3/2Q4Q/Q4Q2/pp1Q4/kBNN1KB1 w - - 0 1"); // 218 moves to make

    fen.add("r3k2r/1ppn3p/2q1q1n1/4P3/2q1Pp2/6R1/pbp2PPP/1R4K1 b kq e3");
    fen.add("r3k2r/1ppn3p/2q1q1n1/4P3/2q1Pp2/6R1/pbp2PPP/1R4K1 w kq -");
    fen.add("8/1P6/6k1/8/8/8/p1K5/8 w - -");
    fen.add("1r3rk1/1pnnq1bR/p1pp2B1/P2P1p2/1PP1pP2/2B3P1/5PK1/2Q4R w - -");
    fen.add("4rk2/p5p1/1p2P2N/7R/nP5P/5PQ1/b6K/q7 w - -");
    fen.add("4k2r/1q1p1pp1/p3p3/1pb1P3/2r3P1/P1N1P2p/1PP1Q2P/2R1R1K1 b k -");
    fen.add("r2r1n2/pp2bk2/2p1p2p/3q4/3PN1QP/2P3R1/P4PP1/5RK1 w - -");
    fen.add("1kr4r/ppp2bq1/4n3/4P1pp/1NP2p2/2PP2PP/5Q1K/4R2R w - -");
    fen.add("1k1r4/pp1b1R2/3q2pp/4p3/2B5/4Q3/PPP2B2/2K5 b - -");
    fen.add("8/5k2/8/8/2N2N2/2B5/2K5/8 w - -");
    fen.add("8/8/6k1/8/8/8/P1K5/8 w - -");
    fen.add("8/5k2/8/8/8/8/1BK5/1B6 w - -");
    fen.add("5r1k/4Qpq1/4p3/1p1p2P1/2p2P2/1p2P3/1K1P4/B7 w - -");
    fen.add("1k1r4/pp1b1R2/3q2pp/4p3/2B5/4Q3/PPP2B2/2K5 b - -");
    fen.add("3r1k2/4npp1/1ppr3p/p6P/P2PPPP1/1NR5/5K2/2R5 w - -");
    fen.add("2q1rr1k/3bbnnp/p2p1pp1/2pPp3/PpP1P1P1/1P2BNNP/2BQ1PRK/7R b - -");
    fen.add("rnbqkb1r/p3pppp/1p6/2ppP3/3N4/2P5/PPP1QPPP/R1B1KB1R w KQkq -");
    fen.add("r1b2rk1/2q1b1pp/p2ppn2/1p6/3QP3/1BN1B3/PPP3PP/R4RK1 w - -");
    fen.add("2r3k1/pppR1pp1/4p3/4P1P1/5P2/1P4K1/P1P5/8 w - -");
    fen.add("1nk1r1r1/pp2n1pp/4p3/q2pPp1N/b1pP1P2/B1P2R2/2P1B1PP/R2Q2K1 w - -");
    fen.add("4b3/p3kp2/6p1/3pP2p/2pP1P2/4K1P1/P3N2P/8 w - -");
    fen.add("2kr1bnr/pbpq4/2n1pp2/3p3p/3P1P1B/2N2N1Q/PPP3PP/2KR1B1R w - -");
    fen.add("3rr1k1/pp3pp1/1qn2np1/8/3p4/PP1R1P2/2P1NQPP/R1B3K1 b - -");
    fen.add("2r1nrk1/p2q1ppp/bp1p4/n1pPp3/P1P1P3/2PBB1N1/4QPPP/R4RK1 w - -");
    fen.add("r3r1k1/ppqb1ppp/8/4p1NQ/8/2P5/PP3PPP/R3R1K1 b - -");
    fen.add("r2q1rk1/4bppp/p2p4/2pP4/3pP3/3Q4/PP1B1PPP/R3R1K1 w - -");
    fen.add("rnb2r1k/pp2p2p/2pp2p1/q2P1p2/8/1Pb2NP1/PB2PPBP/R2Q1RK1 w - -");
    fen.add("2r3k1/1p2q1pp/2b1pr2/p1pp4/6Q1/1P1PP1R1/P1PN2PP/5RK1 w - -");
    fen.add("r1bqkb1r/4npp1/p1p4p/1p1pP1B1/8/1B6/PPPN1PPP/R2Q1RK1 w kq -");
    fen.add("r2q1rk1/1ppnbppp/p2p1nb1/3Pp3/2P1P1P1/2N2N1P/PPB1QP2/R1B2RK1 b - -");
    fen.add("r1bq1rk1/pp2ppbp/2np2p1/2n5/P3PP2/N1P2N2/1PB3PP/R1B1QRK1 b - -");
    fen.add("3rr3/2pq2pk/p2p1pnp/8/2QBPP2/1P6/P5PP/4RRK1 b - -");
    fen.add("r4k2/pb2bp1r/1p1qp2p/3pNp2/3P1P2/2N3P1/PPP1Q2P/2KRR3 w - -");
    fen.add("3rn2k/ppb2rpp/2ppqp2/5N2/2P1P3/1P5Q/PB3PPP/3RR1K1 w - -");
    fen.add("2r2rk1/1bqnbpp1/1p1ppn1p/pP6/N1P1P3/P2B1N1P/1B2QPP1/R2R2K1 b - -");
    fen.add("r1bqk2r/pp2bppp/2p5/3pP3/P2Q1P2/2N1B3/1PP3PP/R4RK1 b kq -");
    fen.add("r2qnrnk/p2b2b1/1p1p2pp/2pPpp2/1PP1P3/PRNBB3/3QNPPP/5RK1 w - -");
    fen.add("r3qb1k/1b4p1/p2pr2p/3n4/Pnp1N1N1/6RP/1B3PP1/1B1QR1K1 w - -");
    // Crafty test EPDs
    fen.add("rn2kb1r/pp3ppp/4pn2/2pq4/3P2b1/2P2N2/PP2BPPP/RNBQK2R w KQkq -");
    fen.add("r3k2r/pp2qppp/2n1pn2/bN5b/3P4/P3BN1P/1P2BPP1/R2Q1RK1 w kq -");
    fen.add("2rr2k1/1p2qp1p/1pn1pp2/1N6/3P4/P6P/1P2QPP1/2R2RK1 w - -");
    fen.add("6rk/1p3p1p/2n2q2/1NQ2p2/3p4/PP5P/5PP1/2R3K1 w - -");
    fen.add("7k/2R2p1p/3N1q2/3Q4/3p4/PP3pPP/5n1K/4r3 w - -");
    fen.add("r1bqkb1r/pp3ppp/2n1pn2/2p5/2pP4/5NP1/PP2PPBP/RNBQ1RK1 w kq -");
    fen.add("1r1q1rk1/p2b1ppp/3bpn2/4N3/3p4/5QP1/PP2PPBP/R1B2RK1 w - -");
    fen.add("1q3rk1/p4p1p/1r3p2/4p3/Qb1p4/6P1/P3PPBP/1R3RK1 w - -");
    fen.add("1r6/2q2pkp/5p2/4p3/3pB3/2bQ2P1/P3PP1P/5RK1 w - -");
    fen.add("5k1q/5p2/5p2/4p3/3pB1QP/6P1/4PP2/b5K1 w - -");
    fen.add("2q5/4kp2/5p2/4p3/2Bp3P/2b2QP1/4PP2/6K1 w - -");
    fen.add("8/4kp2/3q4/4pp2/2Bp3P/2b3P1/Q3PP2/6K1 w - -");
    fen.add("1Q6/4k3/5q2/1B3p2/3pp2P/6P1/3bPP2/6K1 w - -");
    fen.add("4k3/2Q5/5q2/5p2/2Bp1P1P/6P1/3b4/5K2 w - -");
    fen.add("1k6/4Q3/2q5/5B2/3p1P1P/4b1P1/8/5K2 w - -");
    fen.add("rn2kb1r/pp3ppp/4pn2/2pq4/3P2b1/2P2N2/PP2BPPP/RNBQK2R w KQkq -");
    fen.add("r3k2r/pp3ppp/2nqpn2/4N3/3P4/P1b1B3/1P2QPPP/R4RK1 w kq -");
    fen.add("4k2r/p4ppp/1p2pn2/8/2rP1B2/P1P5/5PPP/RR4K1 w k -");
    fen.add("r5k1/5ppp/p1RBpn2/1p6/r2P4/P1P5/5PPP/1R3K2 w - -");
    fen.add("8/3r1pk1/p1R2p2/1p5p/r2p4/PRP1K1P1/5P1P/8 w - -");
    fen.add("r1bqk2r/pp1n1ppp/2pbpn2/3p4/2PP4/3BPN2/PP1N1PPP/R1BQK2R w KQkq -");
    fen.add("r1bq1rk1/pp1n1pp1/2p4p/2b5/2PQ4/5N2/PPB2PPP/R1B1R1K1 w - -");
    fen.add("r1q1r1k1/1p3pp1/2p1bn1p/p3N3/2P2P2/P1Q1R3/1PB3PP/4R1K1 w - -");
    fen.add("r5k1/3q1pp1/2p4p/p2nQP2/2R5/P6P/1PB3P1/6K1 w - -");
    fen.add("1q1r4/6pk/2B2p1p/p2n1P2/2p1R3/P6P/1P3QP1/7K w - -");
    fen.add("3r4/5qpk/5p1p/R3nP2/8/5BQP/6P1/7K w - -");
    fen.add("r6k/2Q3p1/6Bp/5P2/3q4/7P/6PK/8 w - -");
    fen.add("r1bqk2r/p1pp1ppp/2p2n2/8/1b2P3/2N5/PPP2PPP/R1BQKB1R w KQkq -");
    fen.add("r1bqr1k1/p3bpp1/2p2n1p/3p2B1/8/3B1Q2/PPP1NPPP/4RRK1 w - -");
    fen.add("4r1k1/p2b1pp1/1q3n1p/3p4/3N1Q2/3B4/PP3PPP/5RK1 w - -");
    fen.add("3r2k1/p4bp1/1q5p/8/3Npp2/1PQ5/P4PPP/3R2K1 w - -");
    fen.add("8/p4bpk/7p/3rq3/3N1P2/PPQR1P2/6KP/4q3 w - -");
    fen.add("8/p6k/7p/4P1p1/1Pb5/P3RP2/3r1K1P/8 w - -");
    fen.add("r1bqkb1r/pp3ppp/2n1pn2/2pp4/2PP4/1P2PN2/P2N1PPP/R1BQKB1R w Kqkq -");
    fen.add("r2q1rk1/pp1b1ppp/2nbp3/3p4/2PP1n2/1P3N2/PB1N1PPP/1BRQR1K1 w - -");
    fen.add("2rr2k1/pp1qnppp/2n1p3/b2p4/2PP3P/PP2RNP1/1B3P2/1BRQ2K1 w - -");
    fen.add("2r1r3/ppbqnpk1/4p1p1/1PPp1n1p/3P3P/P2Q1NP1/3BRP2/1BR3K1 w - -");
    fen.add("rb6/1p2rpk1/pP2p1p1/P1PpPn1p/q6P/2BQ1NP1/4RP2/2R3K1 w - -");
    fen.add("rbr5/3q1p2/pPp1pBpk/P1QpP2p/7P/6P1/4RP2/2R3K1 w - -");
    fen.add("r2qkb1r/pppnpppp/8/3p4/4nP2/1P2Pb2/PBPPB1PP/RN1QK2R w KQkq -");
    fen.add("2kr1b1r/ppp3pp/8/2n2p2/4pP1q/1PN1P3/PBPP3P/R2Q1R1K w - -");
    fen.add("2k4r/1pp3Rp/p7/2n2r2/5P2/1P2Pp2/P1PP3P/R6K w - -");
    fen.add("8/1pk4p/p7/4r3/5r2/1P1P1p2/P1PR1K1P/8 w - -");
    fen.add("rn2kb1r/pp2pppp/2p2n2/q7/2BP2b1/2N2N2/PPP2PPP/R1BQK2R w KQkq -");
    fen.add("2kr1b1r/ppqn1Bpp/2p2nb1/6N1/3p2P1/2N4P/PPPBQP2/2KR3R w - -");
    fen.add("2k1rb1r/p2n2pp/2pqN3/1pN1n1P1/3p2Q1/7P/PPPB1P2/2KRR3 w - -");
    fen.add("rn2kb1r/pp2pppp/2p2n2/q7/2BP2b1/2N2N2/PPP2PPP/R1BQK2R w KQkq -");
    fen.add("r3k2r/ppqn1ppp/2pbp3/3n2P1/2BP4/2N2Q1P/PPPB1P2/2KR3R w kq -");
    fen.add("r4rk1/5ppp/1np1p3/p2n2P1/2BP2p1/6PP/PP1B4/2KR3R w - -");
    fen.add("2r3k1/5ppp/4p3/1rnn2P1/p7/1P3BPP/P2B4/1KR4R w - -");
    fen.add("5k2/5ppp/8/3p2P1/8/pP4PP/P1K5/8 w - -");
    fen.add("r1b1kb1r/pp2pppp/2n2n2/3q4/3p4/2P2N2/PP2BPPP/RNBQK2R w KQkq -");
    fen.add("2rq1rk1/pb2bppp/1pn1pn2/4N3/P1BP4/2N1B3/1P3PPP/R2Q1RK1 w - -");
    fen.add("q4rk1/pb2bppp/1p2p3/3nN3/P2P4/1B1QBP2/1P4PP/2R3K1 w - -");
    fen.add("2q3k1/1br1b1pp/pp2pp2/3n1P2/P1BP2N1/1P1Q2P1/3B3P/2R3K1 w - -");
    fen.add("q6k/2r1b1pp/5p2/1p3R2/3P4/1P1Q2P1/3B3P/6K1 w - -");
    fen.add("rn1qkb1r/ppp2ppp/4pn2/8/2PP2b1/5N2/PP2BPPP/RNBQK2R w KQkq -");
    fen.add("r2q1rk1/pp1nbppp/2p1pnb1/8/2PP2PN/P3B2P/1P1NBP2/R2Q1RK1 w - -");
    fen.add("r2q1rk1/ppbn1pp1/4p1p1/2P3P1/1p1P1P2/P3B2P/4B3/R2Q1RK1 w - -");
    fen.add("1rr3k1/1Qbnqpp1/6p1/2p1P1P1/1PBP4/4B2P/8/R4RK1 w - -");
    fen.add("r1bqkb1r/ppp1p1pp/1nnpp3/8/2PP4/5N2/PP3PPP/RNBQKB1R w KQkq -");
    fen.add("r1b2k1r/pp2p3/1n1qp1p1/2pp1n2/2P5/1P1B4/P2N1PPP/R1BQ1RK1 w - -");
    fen.add("r4k2/pp1bp2r/1q2p1p1/2p1R3/2Pp2Pn/1P1B2B1/P4P1P/R2Q2K1 w - -");
    fen.add("2r2k2/pp2p2B/6q1/2p1p2p/2Pp3B/1P3P1b/P6P/4R1K1 w - -");
    fen.add("5k2/1p2p3/1r6/p1p4B/P1P2B2/1P1p1P1b/7P/4R1K1 w - -");
    fen.add("4Br2/1p4k1/8/p5B1/P1P5/7b/3p3P/3R2K1 w - -");
    fen.add("8/8/6k1/p7/P2r4/5B1b/3B3P/3R2K1 w - -");
    fen.add("8/8/2R5/3B4/5kbP/2B5/4r3/6K1 w - -");
    fen.add("rnb1kb1r/pp3ppp/2p2n2/3q4/3Np3/6P1/PP1PPPBP/RNBQK2R w KQkq -");
    fen.add("rn3rk1/p4ppp/2p2n2/1p3b1q/4p2P/2N1b1P1/PPQPPPB1/R1B1K2R w KQ -");
    fen.add("3r2k1/p2n1ppp/2p1r1b1/3n3q/PpN1p2P/1P2P1P1/1BQ1PPB1/R3K2R w KQ -");
    fen.add("6k1/p1r2pp1/2p1rn2/3n1q2/PpNBp1pP/1P2PP2/1KQ1P3/3R3R w - -");
    fen.add("6k1/p2Q1pB1/8/2p1Nq2/Pp2p1rP/1P6/1K2P3/3n4 w - -");
    fen.add("7k/p7/6p1/2pq3P/Pp2p3/1P2r3/1K2P2Q/8 w - -");
    fen.add("r1bqkb1r/pp1ppppp/1nn5/4P3/2Bp4/2P2N2/PP3PPP/RNBQK2R w KQkq -");
    fen.add("r1bq1rk1/pp2bppp/2n1p3/3n4/3P4/1BN2NP1/PP3P1P/R1BQR1K1 w - -");
    fen.add("2rqr1k1/pb2bp1p/1p2p1p1/n7/3P4/P1PQ1NP1/2BB1P1P/R3R1K1 w - -");
    fen.add("4r1k1/pb2b3/1p6/3qpppp/2nP2P1/P1P2N1P/5PK1/R1BQR3 w - -");
    fen.add("5r2/p3b2k/1p6/4n2P/3P2p1/P1P2b2/3B1P2/R5K1 w - -");
    fen.add("r1bqkb1r/ppp1pppp/1nn5/4P3/2PP4/8/PP4PP/RNBQKBNR w KQkq -");
    fen.add("r3kb1r/pppn1ppp/2n1p3/2P1P3/3P1q2/2N2P2/PP2BB1P/R2QK2R w KQkq -");
    fen.add("2kr3r/pppn3p/2n1p3/2P1Ppb1/3P4/2N2B2/PP3B1P/3RK2R w K f6");
    fen.add("2kr2r1/1pnnb2p/p1p1p3/2P1Pp2/PP1P4/2N2B2/4RB1P/3R3K w - -");
    fen.add("Rnk3r1/1p4rp/4p3/1PPpPpb1/3P4/5B2/5B1P/R6K w - -");
    fen.add("Rnkb2r1/1p4r1/1P2p3/2PpP2p/B2P1p2/4B3/7P/1R5K w - -");
    fen.add("rnbqk2r/pp2nppp/2pb4/3P4/3P1p2/2N2N2/PPP3PP/R1BQKB1R w KQkq -");
    fen.add("r1b2rk1/p3nppp/3b4/q1nP4/5p2/PBN2N2/1PP3PP/R1BQK2R w KQ -");
    fen.add("r3r1k1/p4ppp/b7/2bn4/2PN1p2/P7/B3N1PP/R1B1K2R w KQ -");
    fen.add("3r2k1/p4ppp/b7/2b5/2PN4/P4r2/BB1KN1nP/6R1 w - -");
    fen.add("pr4k1/p4p1p/6p1/2b5/P1PN1R2/8/BB1rN2P/2K5 w - -");
    fen.add("8/p5kp/6p1/2bP1p2/P7/1r6/2NK3P/5R2 w - -");
    fen.add("r1bq1rk1/ppp1bppp/2n1pn2/3p4/3P1B2/4PN1P/PPPN1PP1/R2QKB1R w KQ -");
    fen.add("r1bq1rk1/pp5p/3bpnp1/2ppNp2/2PP4/4PN1P/PP2BPP1/R2Q1RK1 w - -");
    fen.add("3r1r1k/2q4p/1p1bpnp1/p1pbNp2/Q2P4/P3PN1P/1PR1BPP1/3R2K1 w - -");
    fen.add("3r3k/2qN2r1/1p2p1p1/pBpbNp1p/Q2Pn2b/P3P2P/1PR2PP1/4R1K1 w - -");
    fen.add("3r3k/2qNb1r1/1p2p3/pBpnNp2/Q2PbPp1/P3P3/1P1R2P1/2R2K2 w - -");
    fen.add("3r4/3N2kr/1p6/pBpn1p2/Q2PR1p1/P7/1P4P1/2q3K1 w - -");
    fen.add("rn1qkb1r/pp3ppp/2p1pnb1/3p2B1/3P4/2NBPN2/PPP2PPP/R2QK2R w KQkq -");
    fen.add("r3k2r/pp1n1pp1/2pqpnp1/8/3Pp3/2NQ1N2/PPP2PPP/R3R1K1 w kq -");
    fen.add("1k1r3r/pp3pp1/1np1p1p1/4q3/1P1P4/5N2/P1P1RPPP/4R1K1 w - -");
    fen.add("3r1r2/1pk2pp1/1pp1p1p1/8/1P1P3P/2P3P1/P1R2P2/4R1K1 w - -");
    fen.add("r7/1p4p1/2p1ppp1/1p1k4/1P1P1P1P/r1PK2P1/PR2R3/8 w - -");
    fen.add("r7/4r1p1/1ppkppp1/1p6/1P1P1P1P/2PK2P1/P2RR3/8 w - -");
    fen.add("r2qkb1r/pp1n1ppp/2p1bn2/3pp1B1/8/2NP1NP1/PPP1PPBP/R2QK2R w KQkq -");
    fen.add("2kr3r/pp1n1p2/2pbbq1p/4p1p1/4P3/2PP2P1/P2NNPBP/R2Q1RK1 w - -");
    fen.add("1k1r3r/ppb2p2/1np1b2p/4p1pq/1Q1PP3/2P3P1/1R1NNPBP/R5K1 w - -");
    fen.add("1k1rr3/ppb2p2/1np4p/2N3p1/1Q1PP3/2N2qP1/1R3P1P/R5K1 w - -");
    fen.add("k7/1pRN1p2/p3r2p/Q2N2p1/3PPP2/8/5P1P/R5K1 w - -");
    fen.add("r2qkb1r/pb1p1ppp/1pn1pn2/2p5/3PP3/2PBB3/PP1N1PPP/R2QK1NR w KQkq -");
    fen.add("2r2rk1/pb1q1pbp/1pnppnp1/8/1P1PP3/P2BBQ2/3NNPPP/2R2RK1 w - -");
    fen.add("3q1rk1/pb2npb1/1p1np1pp/1B1p4/1P1PP3/P3BPQP/3NN1P1/2R3K1 w - -");
    fen.add("b1nqr1k1/5pb1/p2Bp1pp/1pR5/1PpPP3/P4PQP/3NN1P1/6K1 w - -");
    fen.add("b7/3q1pk1/p2nr1pp/1pR5/PPp1PQ1P/5P2/3NN1P1/6K1 w - -");
    fen.add("8/3q1pk1/6pp/1pRb4/1r5P/2N5/5QP1/6K1 w - -");
    fen.add("r2qkb1r/pp1npppp/2p2n2/5b2/2QP4/2N2N2/PP2PPPP/R1B1KB1R w KQkq -");
    fen.add("r3kb1r/pp2pppp/2p1b3/5q2/3Pn3/2P2NP1/PBQ1PPBP/R3K2R w KQkq -");
    fen.add("2kr1b1r/1p2pb1p/pQp2p2/5qp1/2PPn3/5NP1/PB2PPBP/2R1R1K1 w - -");
    fen.add("3k1b1r/1r5p/p1Q1bp2/2p2qp1/2P5/2n3P1/P2NPP1P/1R2R1K1 w - -");
    fen.add("4kr2/4q2p/p7/2p3p1/2PbN3/3Q2P1/P3PP1P/4R1K1 w - -");
    fen.add("rnbqk1nr/pp3ppp/3b4/3p4/2pP4/2P1BN2/PP3PPP/RN1QKB1R w KQkq -");
    fen.add("rb3rk1/pp1qnppp/2n5/1N1p1b2/R1PP4/1P1BBN2/5PPP/3QK2R w K -");
    fen.add("3rr1k1/1p1qnppp/p1n5/b1Pp4/R2P3N/1PNQB3/5PPP/4R1K1 w - -");
    fen.add("4r1k1/1p4p1/p1n1rp2/2Pp1q1p/3P4/1PQ1B2P/4RPP1/4R1K1 w - -");
    fen.add("6k1/1p2r3/p3rp2/1nPp1q1p/1P1P2pP/4B3/4RPP1/2Q1R1K1 w - -");
    fen.add("8/1p2rk2/2P5/pP1p1p1p/2nPr1pP/1Q2BqP1/4RP1K/4R3 w - -");
    fen.add("8/4rk2/1P6/p2p3p/2nP1P1P/4r3/5R2/3R2K1 w - -");
    fen.add("r2qkbnr/pp2pppp/2p5/3Pn3/2p1P1b1/2N2N2/PP3PPP/R1BQKB1R w KQkq -");
    fen.add("r3kbnr/pp2pppp/5q2/1N1P4/2BQ4/4Bb2/PP3P1P/R3K2R w KQkq -");
    fen.add("r3k1nr/p4ppp/2p1p3/3P4/1b6/5Q2/PP3P1P/R3K2R w KQkq -");
    fen.add("r4k1r/1R3pp1/3bpn1p/p2p4/Q7/8/PP2KP1P/2R5 w - -");
    fen.add("1Q5r/5ppk/3np2p/P2p4/8/8/P3KP2/8 w - -");
    fen.add("rnbqk1nr/pp2bppp/3pp3/8/2B1P3/2N2N2/PP3PPP/R1BQK2R w KQkq -");
    fen.add("r1b2rk1/pp2bppp/2n1p3/8/4BB2/5N2/PP1q1PPP/2R2RK1 w - -");
    fen.add("2rrb1k1/pp3p2/2n1pb1p/6p1/P3BB2/1P3N1P/5PP1/3RR1K1 w - -");
    fen.add("2rb2k1/p7/1pb1p2p/n5p1/P4p2/1P1B1N1P/1B3PP1/3R2K1 w - -");
    fen.add("8/pB3k2/1p1rp2p/2b3p1/P4p2/2B4P/5PP1/2R3K1 w - -");
    fen.add("8/p3k1B1/1p1bp2p/6pB/P3Rp2/7P/5PPK/2r5 w - -");
    fen.add("8/p7/1p1k3p/6p1/P2bR1P1/1B3p1P/5P1K/5r2 w - -");
    fen.add("8/5B2/p6p/1p4p1/P2k2P1/4RK1P/5P2/1r6 w - -");
    fen.add("1r6/8/R7/6p1/2B3P1/p3K2P/1k3P2/8 w - -");
    fen.add("8/8/8/R5p1/2B3P1/p6r/2K2P2/k7 w - -");
    fen.add("rnbqk2r/ppp1n1pp/3p4/5p2/2PPp3/2N2N2/PP2PPPP/R2QKB1R w KQkq -");
    fen.add("r3k2r/pp2nbpp/1q1p1n2/2pP1p2/2P5/1NN1PP2/PP2B2P/R2QK2R w KQkq -");
    fen.add("5k1r/1p2n1pp/rq1p2b1/p1pP1p2/Q1P2N2/2NnPP2/PP5P/R4RK1 w - -");
    fen.add("5qkr/rpNbn2p/3p2p1/p1pP1p2/2P2N2/4PP2/PPQ4P/R5RK w - -");
    fen.add("r6r/4nk1p/3pNqp1/p1pP1p2/8/3QPP2/PP4RP/6RK w - -");
    fen.add("6kr/1rN1n2p/3p1qp1/p1pP1p2/4P3/P4P2/1P1Q2RP/4R2K w - -");
    fen.add("6kr/8/6p1/p2n4/2N5/P3pP2/1P4RP/7K w - -");
    fen.add("2r5/7k/8/8/2N2n1P/Pp3P2/4p3/4R2K w - -");
    fen.add("rn1qkb1r/ppp2ppp/4pn2/7b/2BP4/4PN1P/PP3PP1/RNBQK2R w KQkq -");
    fen.add("r3kb1r/pppnqpp1/3np3/7p/3P2P1/1B2PN1P/PP1B1P2/R2QK2R w KQkq -");
    fen.add("2kr4/2pnbpp1/1p1qp3/p7/3P2P1/1B2PN1r/PPQ2P2/2KR2R1 w - -");
    fen.add("1n6/1kq1bp2/1pp1p3/p5p1/B2PN1P1/4P2r/PPQ2P2/1KR5 w - -");
    fen.add("6r1/1k1qb3/1pp1pp2/p2n2p1/3PN1P1/PB2P1Q1/1P3P2/1KR5 w - -");
    fen.add("8/1k4r1/1pp1qbQ1/p2n1pp1/3P4/PB2P3/1P1N1P2/1K5R w - -");
    fen.add("7Q/1k2b3/1pp1q3/p2nNp2/3P2p1/PB2P3/1P1K1P2/8 w - -");
    fen.add("8/1kn5/1p6/p7/5PQ1/PB6/1P3q2/2K5 w - -");
    fen.add("8/8/1p6/3k4/p4P2/P2K4/1P6/8 w - -");
    fen.add("rnbqk2r/ppp1p1b1/3p1n1p/5pp1/3P4/2P1P1B1/PP3PPP/RN1QKBNR w KQkq -");
    fen.add("r1bq1rk1/ppp3b1/2n4p/3p2pn/3pPp2/1QPB1P2/PP1N1BPP/R3K1NR w KQ -");
    fen.add("r4rk1/ppp5/1q5p/n4bpn/4Np2/2NB1P2/PPQ2KPP/R6R w - -");
    fen.add("6k1/ppp5/1q5p/n2nr1p1/4Np2/5P2/PP2B1PP/3Q1K1R w - -");
    fen.add("8/ppQ1n1k1/2nN3p/6p1/5p2/5P2/P3rKPP/7q w - -");
    fen.add("rnbq1rk1/1pp1ppbp/p2p1np1/8/2PPP3/2N1B3/PP2BPPP/R2QK1NR w KQ -");
    fen.add("rnbq1rk1/2n2pbp/p3p1p1/1p1pP3/3P1P2/2N1BN2/PP2B1PP/2R1QRK1 w - -");
    fen.add("n1bq1rk1/r2n1pbp/2RQp1p1/p2pP3/1p1P1P2/5N2/PP1BBNPP/5RK1 w - -");
    fen.add("n1r2bk1/rb3p1p/1n2p1p1/p2pP3/1p1P1P2/3BBN2/PPR2NPP/2R3K1 w - -");
    fen.add("6k1/1bn2p1p/1n2pPp1/p2pN3/1p1P1P2/P2B4/1P4PP/2B3K1 w - -");
    fen.add("7k/1bn4p/5PpN/3pp3/1B1P1P2/3B4/1n4PP/6K1 w - -");
    fen.add("rnbqkb1r/pp2p3/2pp1n1p/5pp1/3PPB2/2P5/PP1N1PPP/R2QKBNR w KQkq -");
    fen.add("rn1q1br1/ppk1p3/2ppb2p/4P1n1/3P1Q2/2P1NN2/PP3PPP/R3KB1R w KQ -");
    fen.add("rk3br1/pp2q3/2npn2p/1N1p4/5Q1P/2P5/PP2BPP1/R3K2R w KQ -");
    fen.add("r1bqk1nr/pp1nppbp/3p2p1/8/2PpP3/2N2N2/PP2BPPP/R1BQK2R w KQkq -");
    fen.add("r2qr1k1/1p1bppbp/p1np1np1/8/2PNPP2/2N1B2P/PP1QB1P1/R4RK1 w - -");
    fen.add("1r2r1k1/1q1bppbp/ppnp2p1/4P2n/1PP2P2/P1NBBN1P/5QP1/2R2RK1 w - -");
    fen.add("1r1nr1kb/3qpp2/1p1p2p1/1P2P1N1/5Pb1/P1NBB3/5Q2/2RR2K1 w - -");
    fen.add("rnbqk2r/pp2p1bp/2p2ppn/3pP3/3P1P2/2P5/PP1N2PP/R1BQKBNR w KQkq -");
    fen.add("r1b2rk1/1p2p1bp/1qn3pn/p2pP3/3P4/1N3N2/PP2B1PP/R1BQ1R1K w - -");
    fen.add("r4rk1/1p2p1bp/2n3pn/p3P3/P2Pp3/q4N2/3BB1PP/1R1Q1R1K w - -");
    fen.add("5r2/rp2p1kp/2n3p1/p3P3/P1BP4/1R3P2/7P/3R3K w - -");
    fen.add("8/3rp1kp/1rp3p1/p1R1P3/P2P4/5P2/6KP/3R4 w - -");
    fen.add("6k1/4R2p/4p1p1/p2pP3/3P4/r3KP2/7P/8 w - -");
    fen.add("8/5k2/R5pp/3pP3/p2r1PKP/8/8/8 w - -");
    fen.add("5k2/3R4/4P2p/3p1K1p/p1r2P2/8/8/8 w - -");
    fen.add("6k1/8/4P3/4KP1R/2r5/p6p/8/8 w - -");
    fen.add("rnbqk2r/ppp3bp/3p1np1/4pp2/2P5/2NP1NP1/PP2PPBP/R1BQK2R w KQkq -");
    fen.add("r2q1rk1/1pp3bp/2npbnp1/4ppB1/1PP5/2NP1NP1/3QPPBP/1R3RK1 w - -");
    fen.add("5rk1/1pq1n1bp/2ppbnp1/1P2ppB1/N1P5/3P2P1/2NQPPBP/1R4K1 w - -");
    fen.add("6k1/r2nn1b1/2ppb1pp/5p2/2P2p2/2NP2P1/2NBP1BP/1R4K1 w - -");
    fen.add("6k1/4n3/1n2bbpp/5p2/1N1p1P2/3P4/4PKBP/2BN4 w - -");
    fen.add("8/5bk1/5bpp/5p2/N2p1P2/3Pn3/3BP1BP/5K2 w - -");
    fen.add("8/6k1/7p/2bB1pp1/5P2/4p3/4P1KP/8 w - -");
    fen.add("r1bq1rk1/pp1pppbp/2n2np1/1Bp5/4P3/2P2N2/PP1P1PPP/RNBQR1K1 w - -");
    fen.add("r2q1rk1/p3ppbp/2p3p1/3pPb2/3P4/2P2N1P/P4PP1/R1BQR1K1 w - -");
    fen.add("1r4k1/prq1ppbp/2p3p1/2BpP3/b2P3N/2P1QP1P/P5P1/R1R3K1 w - -");
    fen.add("1r6/pr2pk1p/2p2b1Q/1bBp1p2/3P4/2P2NqP/P5P1/R3R1K1 w - -");
    fen.add("1r1k4/pr2p3/2R5/1b1pqQ2/3P4/2P4P/P5P1/R5K1 w - -");
    fen.add("8/p1k1pQ2/1rb5/3p4/3P2P1/2P4P/P6K/8 w - -");
    fen.add("2k5/p2b4/8/8/1QPP2P1/6KP/P3r3/8 w - -");
    fen.add("rnbqk2r/ppp2pp1/4pb1p/3p4/2PP4/2N2N2/PP2PPPP/R2QKB1R w KQkq -");
    fen.add("r1bq1rk1/1pp2pp1/p4b1p/3Ppn2/2B1N3/4PN2/PP1Q1PPP/R4RK1 w - -");
    fen.add("2r1r1k1/1p3pp1/pQ1n2qp/3P4/4p1b1/1B2P3/PP1N1PPP/1R3RK1 w - -");
    fen.add("2r3k1/1p3pp1/pQ1n2qp/3P4/4pPr1/1B2P3/PP4PP/1R2R1K1 w - -");
    fen.add("6k1/1p4p1/p2n2r1/3P1p1p/3QBPq1/4P1P1/PP5P/1R4K1 w - -");
    fen.add("8/1p2Q1pk/6rq/p2P1p2/5P1p/3BP1nP/PP3K2/1R6 w - -");
    fen.add("8/6pk/5r2/2QP1p2/5P1p/1P1BP1nP/P5q1/1R2K3 w - -");
    fen.add("8/3P2pk/8/2Q2p2/4qP1p/1P1r3P/PK6/2R5 w - -");
    fen.add("3Q4/2R3pk/8/q4p2/5P1p/1PK5/3Q4/7r w - -");
    fen.add("r1b1kbnr/p2pqppp/1pn5/2pQ4/2B5/4PN2/PPP2PPP/RNB1K2R w KQkq -");
    fen.add("rkb3nr/p2p2p1/1p4n1/2p1q2p/2B1P2P/2N2Q2/PPP2PP1/R1B1K2R w KQ -");
    fen.add("rkb2r2/3p2p1/pp2n3/2p1q1BB/3nP2P/2N1Q1P1/PPP2P2/3RK2R w K -");
    fen.add("r4r2/kb3qpR/p2p4/1pp3P1/3nPPB1/2N1Q1P1/PPPK4/7R w - -");
    fen.add("4rr2/k5pR/p2p4/2p3P1/5PB1/1nPQ2P1/P1K3q1/7R w - -");
    fen.add("5r2/6R1/p2R4/n1p3P1/k4PB1/2P3P1/2K5/4r3 w - -");
    fen.add("rnbq1rk1/ppp1bppp/3p1n2/4p3/2PP4/P1N1P3/1P2NPPP/R1BQKB1R w KQ -");
    fen.add("2rqbrk1/pp1nbppp/3p1n2/3Pp3/8/P1N1PPN1/1P2B1PP/R1BQ1R1K w - -");
    fen.add("2rqbr1k/1p1nbpp1/p2p1n1p/P2PpN2/1P2P3/2N1BP2/4B1PP/2RQ1R1K w - -");
    fen.add("q3bbrk/1pB2pp1/p2p3p/P2PpN1n/1P2P3/5P2/3QB1PP/2R4K w - -");
    fen.add("q1r4k/1pB2bp1/1Q3p1p/Pp1Pp2n/4P3/5PP1/4B2P/2R4K w - -");
    fen.add("rn1qkb1r/pp1b1ppp/5n2/1Bpp4/3P4/5N2/PPPN1PPP/R1BQK2R w KQkq -");
    fen.add("r2q1rk1/pp3pp1/5n1p/2b3B1/3p4/3Q1N2/PPP2PPP/R4RK1 w - -");
    fen.add("3rr1k1/pp3p2/7p/2b2p2/3p4/5N2/PPP2PPP/2RR1K2 w - -");
    fen.add("3r4/2r2pk1/1p5p/pRb1Np2/3p4/P7/1PP2PPP/2R2K2 w - -");
    fen.add("2r5/5p2/1p3k1p/1RbR1p2/p2pr3/P1PN2P1/1P3P1P/5K2 w - -");
    fen.add("8/5p2/1p5p/r2k1p2/PR1b4/1P1N2P1/5P1P/5K2 w - -");
    fen.add("8/8/5p1p/1P1k1p1P/1b3P2/1P4P1/1N2K3/8 w - -");
    fen.add("r1bq1rk1/ppp2ppp/2p2n2/4p3/1b2P3/2N2N2/PPPP1PPP/R1BQ1RK1 w - -");
    fen.add("r4rk1/ppp1qppp/2p1b3/2b1p3/4P1P1/3P1N1P/PPP3PK/R1BQ1R2 w - -");
    fen.add("r4rk1/p1p3p1/1p2bp1p/q1p1p3/P3P1P1/1P1PQNKP/2P2RP1/R7 w - -");
    fen.add("5rk1/p1p3p1/1p6/2p1p1p1/P3P3/1Pq1rNK1/4QRP1/3R4 w - -");
    fen.add("2r3k1/p1pR2p1/1p6/4R3/r1p1P1K1/5N2/6P1/8 w - -");
    fen.add("3r3k/p1R4r/1p6/4P1N1/2p3K1/6P1/8/8 w - -");
    fen.add("rnbqkb1r/1p2pp1p/p2p1np1/8/P2NP3/2N5/1PP2PPP/R1BQKB1R w KQkq -");
    fen.add("2rqr1k1/1p2ppbp/p1npbnp1/8/P3PP2/RNN1B3/1PP1B1PP/3Q1R1K w - -");
    fen.add("2r1r3/1p1qppk1/p2p1bp1/P2Pn3/8/1RP1B3/1P2B1PP/3Q1R1K w - -");
    fen.add("8/1prqppk1/p5p1/P2Pp3/7b/1QP5/1P2B1PP/5RK1 w - -");
    fen.add("8/2q1p1k1/pb4p1/4p3/1Q6/2P2B2/1P4PP/7K w - -");
    fen.add("7k/4p2B/1b6/p3p1p1/8/2P5/1P3qPP/1Q5K w - -");
    fen.add("7k/4p3/8/p1b1pBp1/2P5/1P6/5qPP/1Q5K w - -");
    fen.add("r1b1kbnr/1pqp1ppp/p1n1p3/8/3NP3/2N5/PPP1BPPP/R1BQK2R w KQkq -");
    fen.add("1r3rk1/2qp1ppp/B1p1pn2/8/1b2P3/4B3/PPP2PPP/R2Q1RK1 w - -");
    fen.add("1r3rk1/5ppp/2p5/3nq3/5p2/1P1B1Q2/P1P3PP/R4RK1 w - -");
    fen.add("5rk1/5p1p/6q1/3Q2p1/P4R2/1P6/2r3PP/4R1K1 w - -");
    fen.add("8/7p/2q2pk1/p4rp1/1P1Q4/8/2rR2PP/1R4K1 w - -");
    fen.add("8/7p/P4p1k/6p1/1P6/8/1q3rPP/R5QK w - -");
    fen.add("8/7k/4Qp1p/6p1/1q6/8/7P/5R1K w - -");
    fen.add("r1b1kbnr/1pqp1ppp/p1n1p3/8/3NP3/2N5/PPP1BPPP/R1BQK2R w KQkq -");
    fen.add("r1b1k1nr/2B2pbp/p1p1p3/3p4/4P3/2N5/PPP1BPP1/R4R1K w kq -");
    fen.add("2b2rk1/1r3pbp/p1p1p1n1/3p4/N3P3/2P3B1/PP2BPP1/3R1R1K w - -");
    fen.add("2b3k1/5rbp/pBr1p1n1/3p1p2/N7/2P5/PP2BPP1/3RR2K w - -");
    fen.add("1r3b2/1b2rk1p/pN2p1n1/B4p1B/2p5/1P6/P4PP1/3RRK2 w - -");
    fen.add("1r6/4k2p/2b1p1n1/p4p1B/R7/1P6/5PP1/4RK2 w - -");
    fen.add("8/4k3/4p2R/1b3pnB/8/1r4P1/5PK1/4R3 w - -");
    fen.add("8/4k3/4p2R/3b1p2/4n3/6P1/1r2BP2/2R1K3 w - -");
    fen.add("8/4k3/2n1p2R/8/8/1b1B2P1/5P2/1r3K2 w - -");
    fen.add("8/5n2/2b2k2/4p3/5PPR/4K3/8/1B6 w - -");
    fen.add("r1bqk2r/pp1n1ppp/2pb1n2/3pp3/8/3P1NP1/PPPNPPBP/R1BQ1RK1 w kq -");
    fen.add("r2r2k1/pp3ppp/1qpbbn2/2n1p3/4P2N/5PP1/PPP3BP/R1BQRN1K w - -");
    fen.add("r2r2k1/pp3pp1/2p1bn1p/q3p1B1/4P1PN/2b2P2/P1N1Q1BP/R3R2K w - -");
    fen.add("4r1k1/p4pp1/1ppr1n2/4pPB1/q7/4NP2/P3Q1BP/4R2K w - -");
    fen.add("4rk2/p4pp1/1pp5/4pP2/3r1q2/4NP2/P5BP/4Q1RK w - -");
    fen.add("4r3/p1k2pp1/1p4q1/2p5/3r1p2/4N3/P4QBP/6RK w - -");
    fen.add("8/p1kq1Q2/1p6/2p5/1r3p2/4r3/P5BP/6RK w - -");
    fen.add("8/p1Q5/1p6/2p3k1/3q4/4r3/r5BP/5R1K w - -");
    fen.add("rnbqk2r/ppp2ppp/5n2/8/1bBP4/2N5/PP3PPP/R1BQK1NR w KQkq -");
    fen.add("r2q1rk1/pppn1ppp/6b1/4P3/1bB3P1/2N1B2P/PP3P2/R2Q1RK1 w - -");
    fen.add("r4r2/pp3pkp/1np5/4q3/1b4P1/1BN1BQ1P/PP6/5RK1 w - -");
    fen.add("5r1k/Bp5p/5p1N/3pq3/1br3P1/7P/PP3Q2/5RK1 w - -");
    fen.add("7k/Bp2r2p/7N/3pp3/6P1/P6P/1P4K1/4b3 w - -");
    fen.add("8/1p4kp/8/4N1P1/3pp3/bP5P/8/5K2 w - -");
    fen.add("r1bqkb1r/pp3ppp/2nppn2/6B1/3NP3/2N5/PPP2PPP/R2QKB1R w KQkq -");
    fen.add("r2qk2r/1p3pp1/p1b1pn1p/b2p4/4PB2/P1N2P2/1PP3PP/2KRQB1R w kq -");
    fen.add("r2qr1k1/5pp1/2b2n1p/pp6/2Bp3Q/P4P2/NPPR2PP/2K4R w - -");
    fen.add("1r4k1/5pp1/2bR1n1p/p7/8/4rP2/NKP3PP/5B1R w - -");
    fen.add("r5k1/5pp1/2R2n1p/8/p7/5PP1/2P1K2P/7B w - -");
    fen.add("6k1/5pp1/7p/8/8/2K2PP1/2P3BP/6r1 w - -");
    fen.add("6k1/5p2/8/8/4B2p/3K1P2/2P2r2/8 w - -");
    fen.add("6k1/6r1/8/5p2/5P2/8/2P2KBp/8 w - -");
    fen.add("r2qkb1r/pp1npppp/2p2n2/5b2/2QP4/2N2N2/PP2PPPP/R1B1KB1R w KQkq -");
    fen.add("3r1rk1/pp2bppp/1qp1pn2/5b2/3Pn3/2N1P1P1/PP2QPBP/R1BRN1K1 w - -");
    fen.add("2nr1rk1/pp2bppp/1qp1p1b1/8/P2PP3/1P1R1PP1/2N1Q1BP/R1B3K1 w - -");
    fen.add("5rk1/pp2nppp/1q4b1/4p3/PP2P3/3QbPP1/2N3BP/3R2K1 w - -");
    fen.add("8/p3nkpp/1p3p2/4p3/PP1qP3/2Q1NPP1/7P/6K1 w - -");
    fen.add("5k2/p3n1p1/1p2qp2/4p3/PP2P1p1/5P1K/3Q2NP/8 w - -");
    fen.add("5k2/p3n1p1/1p3p2/8/PP1p2P1/4N3/6KP/8 w - -");
    fen.add("8/p3k1p1/1p1N1p2/1P6/P7/3K1n2/8/8 w - -");
    fen.add("rn1qk2r/pbp1bppp/1p2pn2/3p2B1/2PP4/P1N2N2/1P2PPPP/R2QKB1R w KQkq -");
    fen.add("r2q1rk1/1b2bppp/pp3n2/2pp4/3PnB2/P1NBPN2/1PQ2PPP/2R2RK1 w - -");
    fen.add("2r1qrk1/1b2bppp/p4n2/1p1p4/1PnN1B2/P1NBP3/1Q3PPP/2RR2K1 w - -");
    fen.add("3rqrk1/1b3pp1/p4b1p/1p3B2/1PnP1N2/PQN5/5PPP/2RR2K1 w - -");
    fen.add("3r2k1/5pp1/p2r3p/1p6/1PnN1q2/P1Q5/5PPP/3RR1K1 w - -");
    fen.add("6k1/6p1/4p2p/1p6/1qn5/6N1/5PPP/2Q3K1 w - -");
    fen.add("8/1Q4pk/4p2p/1p1q4/2n5/7P/5PP1/5NK1 w - -");
    fen.add("8/6p1/4p1kp/1p3n2/3q4/3QN2P/5PP1/6K1 w - -");
    fen.add("rnbqk2r/1p2ppbp/p1p2np1/2Pp4/3P1B2/2N2N2/PP2PPPP/R2QKB1R w KQkq -");
    fen.add("r1bqnrk1/1p4bp/p1p3p1/2nPp3/5B2/2NB1N1P/PP3PP1/2RQK2R w K -");
    fen.add("r2q1rk1/1p4bp/p1p3p1/8/6Q1/4Bb1P/PP3PP1/2R2RK1 w - -");
    fen.add("6k1/1p3rbp/p1p3p1/8/2Q5/3RBP1P/1q3P2/6K1 w - -");
    fen.add("3R1bk1/1p3r1p/p1p1Q1pB/8/5P1P/q7/5PK1/8 w - -");
    fen.add("r1bqk2r/pp2ppbp/2np1np1/2p5/8/2NP1NP1/PPP1PPBP/R1BQ1RK1 w kq -");
    fen.add("1r1q1r2/3bppkp/2np2p1/ppp3N1/4n3/P2P2P1/1PPQPPBP/R4RK1 w - -");
    fen.add("5r2/2q1ppk1/Rrnp2pp/2p5/1p2N1b1/3PP1P1/1PP2PBP/2R1Q1K1 w - -");
    fen.add("2r3k1/1b2pp2/1qn3pp/2pp4/1p2N3/1P1PP1P1/R1P2PBP/Q5K1 w - -");
    fen.add("r2q2k1/1b3p2/2n3p1/2ppp2p/1p6/1P1PPNPP/R1P2PB1/Q5K1 w - -");
    fen.add("b7/3n1k2/5pp1/2ppp1P1/1p4N1/1P1PP3/2P2PB1/6K1 w - -");
    fen.add("b7/8/5k2/2pp2p1/1p3P2/1P1P2KB/2P5/8 w - -");
    fen.add("r1bqk2r/pp2bppp/2np1n2/2p1p3/Q3P3/2PP1N2/PP1N1PPP/R1B1KB1R w KQkq -");
    fen.add("2rq1rk1/1p1bbppp/p2p1n2/3Pp3/4P3/5N2/PPQN1PPP/R1B2RK1 w - -");
    fen.add("2rq2k1/1p4pp/3p1b2/pbnPpr2/8/Q1R2N1P/PP1N1PP1/R1B3K1 w - -");
    fen.add("2q3k1/1p4pp/3p1b2/pb1Pp3/8/1P1nQr1P/P2N1PK1/R1B5 w - -");
    fen.add("6k1/1p1b2pp/3p4/p2Pbq2/P7/1P3p1P/5P1K/4R1N1 w - -");
    fen.add("r1b1kb1r/pp3ppp/2n1pn2/2pq4/3P4/2P2N2/PP2BPPP/RNBQK2R w KQkq -");
    fen.add("r1bq1rk1/1p2bppp/p1n1p3/3n4/3P4/2N2NB1/PP2BPPP/R2Q1RK1 w - -");
    fen.add("2rqr1k1/1b2bppp/p1n1pn2/1p6/1P1P1B2/P1N2N2/Q3BPPP/2RR2K1 w - -");
    fen.add("b3r1k1/3R1ppp/p1n5/1p6/1P6/4BN2/4rPPP/2R3K1 w - -");
    fen.add("4r1k1/3R1pp1/p3b2p/1p6/1n6/4P3/3N1KPP/1R6 w - -");
    fen.add("3R4/1b3ppk/p6p/1pn5/8/4P1P1/3NK2P/8 w - -");
    fen.add("8/6pk/3R3p/1p3p2/4n3/4K1Pb/3N3P/8 w - -");
    fen.add("8/6k1/1R5p/6p1/4p3/4KbP1/7P/8 w - -");
    fen.add("r1bqkb1r/pp3ppp/2np1n2/1N2p3/4P3/2N5/PPP2PPP/R1BQKB1R w KQkq -");
    fen.add("r2qkbr1/5p1p/p1npb3/1p1Np2Q/4Pp2/N2B4/PPP2PPP/R4RK1 w q -");
    fen.add("2r1kb2/5p1p/p1npb2q/1p1Np3/2P1P3/N2B1pP1/PP3P1P/R5RK w - -");
    fen.add("4kb2/5p1p/n2p4/3rpB2/8/5NP1/PP3q1P/R5RK w - -");
    fen.add("3k1b2/7p/2Bp3q/2n1pp2/8/P4NP1/1P5P/4R2K w - -");
    fen.add("r1bqk2r/pp1nbppp/2p1pn2/3p4/2PP4/2N1P3/PPQB1PPP/R3KBNR w KQkq -");
    fen.add("r2q1rk1/pb1n1pp1/1p2pn1p/2b5/8/2N1PN2/PPQBBPPP/R2R2K1 w - -");
    fen.add("rqr3k1/1b3pp1/p3pn1p/1p2b3/7Q/P1N1P3/1P1BBPPP/2RR2K1 w - -");
    fen.add("r1r5/5kp1/p3p2p/1p1b1p2/3BnP2/P3P3/1P2B1PP/2RR2K1 w - -");
    fen.add("8/4nkp1/p3p2p/1b2Bp2/5P2/1P1BP3/6PP/6K1 w - -");
    fen.add("8/2B2k2/2n1p1p1/5p1p/4PP2/1b5P/5KP1/1B6 w - -");
    fen.add("r1bqk2r/ppp2p1p/2np1np1/4p3/1bP5/2NPPN2/PP1B1PPP/R2QKB1R w KQkq -");
    fen.add("r2q1rk1/ppp2p2/2np1npp/3b4/2P5/P1B1PB2/1P3PPP/R2Q1RK1 w - -");
    fen.add("2r2rk1/p3qp2/2pp1npp/4n3/4P3/P1B2P2/1P2B1PP/R2Q1RK1 w - -");
    fen.add("rnbq1rk1/p1pp1ppp/1p2pn2/8/2PP4/P1Q5/1P2PPPP/R1B1KBNR w KQ -");
    fen.add("2rq1rk1/p2n1pp1/bp1ppn1p/8/2Pp3B/P1QBPP2/1P2N1PP/3RK2R w K -");
    fen.add("2rr4/p4pk1/bp2p2p/8/2p1P3/PP2P3/4N1PP/3RK2R w K -");
    fen.add("8/5pk1/p3p2p/1p6/4r3/PR2P3/4K1PP/8 w - -");
    fen.add("8/8/p3p3/1p3p1p/r1k5/P2RPKPP/8/8 w - -");
    fen.add("8/8/8/pR3p1p/7P/1p1KP1P1/1k6/r7 w - -");
    fen.add("r1bqkb1r/pp3ppp/2n2n2/2pp4/3P4/5NP1/PP2PPBP/RNBQK2R w KQkq -");
    fen.add("r4rk1/pp3ppp/1qn2b2/3b4/8/1N4P1/PP2PPBP/R2Q1RK1 w - -");
    fen.add("2rr2k1/ppq1npp1/7p/5Q1P/8/bN2P1P1/P4PB1/1R1R2K1 w - -");
    fen.add("6k1/2r1npp1/pb1r3p/1p5P/3NB3/3RP1P1/P4P2/3R2K1 w - -");
    fen.add("5k2/2rR1pp1/1b5p/p2B3P/1p6/4P1P1/P4PK1/8 w - -");
    fen.add("3b1k2/8/6p1/p6p/1p2PPP1/1B3K2/P7/8 w - -");
    fen.add("8/8/2B2P2/p1b1P1kp/1p2K3/8/P7/8 w - -");
    fen.add("4B3/5K2/4PP2/p5k1/1p1b4/7p/P7/8 w - -");
    // repeat the first
    fen.add("r3k2r/1ppn3p/2q1q1n1/4P3/2q1Pp2/6R1/pbp2PPP/1R4K1 b kq e3");
    return fen;
  }

}
