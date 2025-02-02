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

import fko.FrankyEngine.Franky.TranspositionTable.TT_Entry;
import fko.FrankyEngine.Franky.TranspositionTable.TT_EntryType;
import fko.FrankyEngine.Franky.openingbook.OpeningBook;
import fko.FrankyEngine.Franky.openingbook.OpeningBookImpl;
import fko.UCI.IUCIEngine;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

/**
 * Search implements the actual search for best move of a given position.
 * <p>
 * Search runs in a separate thread when the actual search is started. When
 * the search is finished it calls <code>engine.sendResult</code> ith the best move and a ponder
 * move if it has one.
 * <p>
 *
 * TODO:
 *  Testing / Bug fixing
 *  --------------------------------
 * <p>
 * TODO
 *  Performance
 *  --------------------------------
 *
 * <p>
 * TODO
 *  Features
 *  --------------------------------
 * TODO: History Heuristic / http://www.frayn.net/beowulf/theory.html#history
 * TODO: SEE (https://www.chessprogramming.org/Static_Exchange_Evaluation)
 * TODO: Lazy SMP
 *
 */
public class Search implements Runnable {

  private static final Logger LOG = LoggerFactory.getLogger(Search.class);

  // how often shall an update of the search be send to UCI in ms
  private static final int UCI_UPDATE_INTERVAL = 500;

  /** Maximum depth this search can go. */
  public static final int MAX_SEARCH_DEPTH = Byte.MAX_VALUE;

  /** Configuration object for direct manipulation */
  public final Configuration config;

  // Readability constants
  private              boolean PERFT            = false;
  private static final boolean DO_NULL          = true;
  private static final boolean NO_NULL          = false;
  private static final boolean PV_NODE          = true;
  private static final boolean NPV_NODE         = false;
  private static final int     DEPTH_NONE       = 0;
  private static final int     ROOT_PLY         = 0;
  private static final int     LEAF             = 0;
  private static final int     FRONTIER         = 1;
  private static final int     PRE_FRONTIER     = 2;
  private static final int     PRE_PRE_FRONTIER = 3;
  private static final int     MAX_MOVES        = 128;

  // search counters
  private final SearchCounter searchCounter;

  // back reference to the engine
  private final IUCIEngine engine;

  // the thread in which we will do the actual search
  private Thread searchThread = null;

  // used to wait for move from search
  private CountDownLatch waitForInitializationLatch;

  // flag to indicate to stop the search - can be called externally or via the timer clock.
  private boolean stopSearch = true;

  // opening book
  private final OpeningBook book;

  // Position Evaluator
  private final Evaluation evaluator;

  // running search global variables
  private final RootMoveList rootMoves;

  // current variation of the search
  private final MoveList currentVariation;

  // Move Generators - each depth in search gets it own to avoid object creation during search
  private final MoveGenerator[] moveGenerators;
  // to store the best move or principal variation we need to generate the move sequence backwards
  // in the recursion. This field stores the pv for each ply so far
  private final MoveList[]      pv;
  // killer move lists per ply
  private       MoveList[]      killerMoves;
  // remember if there have been mate threads in a ply
  private       boolean[]       mateThreat;
  // remember if this ply only has one move to play
  private       boolean[]       singleReply;

  // hash tables
  private TranspositionTable transpositionTable;

  // time variables
  private long startTime;
  private long stopTime;
  private long hardTimeLimit;
  private long softTimeLimit;
  private long extraTime;

  // search state - valid for one call to startSearch
  private Position     currentPosition;
  private int          currentBestRootMove  = Move.NOMOVE;
  private int          currentBestRootValue = Evaluation.NOVALUE;
  private Color        myColor;
  private SearchMode   searchMode;
  private SearchResult lastSearchResult;
  private long         uciUpdateTicker;
  private boolean      hadBookMove          = false;

  /**
   * Creates a search object and stores a back reference to the engine object and also the
   * used configuration instance.<br>
   * The engine object can be null. Info and search result will only be written to
   * log.
   *
   * @param engine
   * @param config
   */
  public Search(IUCIEngine engine, Configuration config) {
    this.engine = engine;
    this.config = config;

    waitForInitializationLatch = new CountDownLatch(1);

    // initialize ply variables
    currentVariation = new MoveList(MAX_SEARCH_DEPTH);
    moveGenerators = new MoveGenerator[MAX_SEARCH_DEPTH];
    pv = new MoveList[MAX_SEARCH_DEPTH];
    killerMoves = new MoveList[MAX_SEARCH_DEPTH];
    mateThreat = new boolean[MAX_SEARCH_DEPTH];
    singleReply = new boolean[MAX_SEARCH_DEPTH];

    // initialize search variables
    rootMoves = new RootMoveList();
    searchCounter = new SearchCounter();
    evaluator = new Evaluation();

    // set opening book - will be initialized in each search
    this.book = new OpeningBookImpl(config.OB_FolderPath + config.OB_fileNamePlain, config.OB_Mode);

    // set hash sizes
    setHashSize(this.config.HASH_SIZE);
  }

  /**
   * Start the search in a separate thread.<br>
   * The search will stop when it has reach the configured conditions. Either
   * reached a certain depth oder used up the time or found a move.<br>
   * The search also can be stopped by calling stop at any time. The
   * search will stop gracefully by storing the best move so far.
   * <p>
   * The finished search calls <code>Engine.sendResult(searchResult);</code> to
   * store the result is it has found one. After storing the result
   * the search is ended and the thread terminated.<br>
   *
   * @param position
   * @param searchMode
   */
  public void startSearch(Position position, SearchMode searchMode) {
    if (searchThread != null && searchThread.isAlive()) {
      final String s = "Search already running - can only be started once";
      IllegalStateException e = new IllegalStateException(s);
      LOG.error(s, e);
    }
    if (position == null) {
      final String s = "Null for position is not allowed";
      IllegalArgumentException e = new IllegalArgumentException(s);
      LOG.error(s, e);
    }
    if (searchMode == null) {
      final String s = "Null for searchMode is not allowed";
      IllegalArgumentException e = new IllegalArgumentException(s);
      LOG.error(s, e);
    }

    // create a deep copy of the position to not change
    // the original position given
    this.currentPosition = new Position(position);

    this.myColor = currentPosition.getNextPlayer();
    this.searchMode = searchMode;

    // setup latch - used to wait until run() has finished initialization
    waitForInitializationLatch = new CountDownLatch(1);

    // reset the stop search flag
    stopSearch = false;

    // create new search thread and start it
    String threadName = "Engine: " + myColor.toString();
    if (this.searchMode.isPonder()) {
      threadName += " (Pondering)";
    }
    searchThread = new Thread(this, threadName);
    searchThread.setDaemon(true);
    searchThread.setUncaughtExceptionHandler((t, e) -> {
      LOG.error("Caught uncaught exception", e);
      System.exit(1);
    });
    searchThread.start();

    // Wait for initialization in run() before returning from call
    try {
      waitForInitializationLatch.await();
    } catch (InterruptedException ignored) {
    }
  }

  /**
   * Stops a current search. If no search is running it does nothing.<br>
   * The search will stop gracefully by sending the best move so far.
   */
  public void stopSearch() {
    // return if no search is running
    if (searchThread == null) return;

    // stop pondering if we are
    if (searchMode.isPonder()) {
      if (searchThread == null || !searchThread.isAlive()) {
        // Ponder search has finished before we stopped it
        // Per UCI protocoll we need to send the result anyway although a miss
        LOG.info(
          "Pondering has been stopped after ponder search has finished. " + "Send obsolete result");
        LOG.info("Search result was: {} PV {}", lastSearchResult.toString(),
                 pv[ROOT_PLY].toNotationString());
        sendUCIBestMove();
      }
      else {
        LOG.info("Pondering has been stopped. Ponder Miss!");
      }
      searchMode.ponderStop();
    }

    // set stop flag - search needs to check regularly and stop accordingly
    stopSearch = true;

    // Wait for the thread to die
    try {
      this.searchThread.join();
    } catch (InterruptedException ignored) {
    }

    // clear thread
    searchThread = null;

    LOG.info("Search thread has been stopped");
  }

  /**
   * Called when the new search thread is started.
   * Initializes the search and checks the opening book if required.
   * Calls <code>iterativeDeepening()</code> when search is initialized.
   * <p>
   * The finished search calls <code>Engine.sendResult(searchResult)</code> to
   * store/hand over the result. After storing the result the search is ended
   * and the thread terminated.<br>
   */
  @Override
  public void run() {

    if (Thread.currentThread() != searchThread) {
      final String s = "run() cannot be called directly!";
      UnsupportedOperationException e = new UnsupportedOperationException(s);
      LOG.error(s, e);
    }

    // Initialize for new search
    lastSearchResult = null;
    searchCounter.resetCounter();
    softTimeLimit = hardTimeLimit = extraTime = 0;

    // Initialize ply based data
    // Each depth in search gets it own global field to avoid object creation
    // during search.
    for (int i = 0; i < MAX_SEARCH_DEPTH; i++) {
      moveGenerators[i] = new MoveGenerator();
      moveGenerators[i].SORT_MOVES = config.USE_SORT_ALL_MOVES;
      // prepare principal variation lists
      pv[i] = new MoveList(MAX_SEARCH_DEPTH);
      // init killer moves
      killerMoves[i] = new MoveList(config.NO_KILLER_MOVES + 1);
      // mateThreads
      mateThreat[i] = false;
      // singleReply
      singleReply[i] = false;
    }

    // age TT entries
    transpositionTable.ageEntries();

    // print info about search mode
    assert searchMode != null : "Null for searchMode is not allowed";
    if (config.PERFT || searchMode.isPerft()) {
      LOG.info("****** PERFT SEARCH (" + searchMode.getMaxDepth() + ") *******");
      PERFT = true;
    }
    else {
      PERFT = false;
    }
    if (searchMode.isTimeControl() && searchMode.getMate() > 0) {
      LOG.info("****** TIMED MATE SEARCH *******");
    }
    if (searchMode.isTimeControl() && searchMode.getMate() <= 0) {
      LOG.info("****** TIMED SEARCH *******");
    }
    if (searchMode.isPonder()) {
      LOG.info("****** PONDER SEARCH *******");
    }
    if (searchMode.isInfinite()) {
      LOG.info("****** INFINITE SEARCH *******");
    }
    if (searchMode.getMate() > 0) {
      LOG.info("****** MATE SEARCH (time: {} max depth {}) ) *******", searchMode.getMoveTime(),
               searchMode.getMaxDepth());
    }

    // release latch so the caller can continue
    waitForInitializationLatch.countDown();

    // ###########################################
    // ### SEARCH

    // try to get book move
    lastSearchResult = getBookMove(currentPosition);

    // if we didn't get a book move start the search
    if (lastSearchResult == null) lastSearchResult = iterativeDeepening(currentPosition);
    assert lastSearchResult != null;

    // if the mode still is ponder at this point we finished the ponder
    // search early before a miss or hit has been signaled. We need to
    // wait with sending the result until we get a miss (stop) or a hit.
    if (searchMode.isPonder()) {
      LOG.info("Ponder Search finished! Waiting for Ponderhit to send result");
      return;
    }

    // ### SEARCH
    // ###########################################

    LOG.info("Search result was: {} PV {} ", lastSearchResult.toString(),
             pv[ROOT_PLY].toNotationString());

    // send result to engine
    sendUCIBestMove();
  }

  /**
   * Generates root moves and starts the actual iterative search by calling the
   * root moves search <code>rootMovesSearch()</code>.
   * <p>
   * Detects mate if started on a mate position.
   *
   * @param position
   * @return search result
   */
  private SearchResult iterativeDeepening(Position position) {

    // remember the start time of the search
    startTime = System.currentTimeMillis();
    uciUpdateTicker = System.currentTimeMillis();

    // max window search - preparation for aspiration window search
    int alpha = Evaluation.MIN;
    int beta = Evaluation.MAX;

    // init best move and value
    currentBestRootMove = Move.NOMOVE;
    currentBestRootValue = Evaluation.NOVALUE;

    // clear principal Variation for root depth
    pv[ROOT_PLY].clear();

    // prepare search result
    SearchResult searchResult = new SearchResult();

    // no legal root moves - game already ended!
    if (!moveGenerators[ROOT_PLY].hasLegalMove(position)) {
      if (position.hasCheck()) searchResult.resultValue = -Evaluation.CHECKMATE;
      else searchResult.resultValue = Evaluation.DRAW;
      return searchResult;
    }

    // start depth from searchMode
    int depth = searchMode.getStartDepth();

    // if time based game setup the soft and hard time limits
    if (searchMode.isTimeControl()) configureTimeLimits();

    // add some extra time for the move after the last book move
    if (hadBookMove) {
      double extraTimeFactor = 2;
      LOG.debug("Last book move detected. Adding some extra time. Before: {} After: {}",
                hardTimeLimit, (long) (extraTimeFactor * hardTimeLimit));
      hadBookMove = false;
      addExtraTime(extraTimeFactor);
    }

    // current search depth
    searchCounter.currentSearchDepth = ROOT_PLY;
    searchCounter.currentExtraSearchDepth = ROOT_PLY;

    // Do a TT lookup to try to find a first best move for this position
    if (config.USE_TT_ROOT && config.USE_TRANSPOSITION_TABLE && !PERFT) {

      TT_Entry ttEntry = transpositionTable.get(position.getZobristKey());
      if (ttEntry != null) {
        searchCounter.tt_Hits++;

        // mate thread flag
        mateThreat[ROOT_PLY] = ttEntry.mateThreat;

        // get best move and PV from TT
        if (ttEntry.bestMove != Move.NOMOVE) {
          currentBestRootMove = ttEntry.bestMove;
          getPVLine(position, ttEntry.depth, pv[ROOT_PLY]);
          assert pv[ROOT_PLY].getFirst() == currentBestRootMove;
        }

        // use value only if tt depth was equal or deeper
        if (ttEntry.depth >= depth) {
          assert (int) ttEntry.value != Evaluation.NOVALUE;
          // set best move value from TT
          currentBestRootValue = (int) ttEntry.value;

          // skip lower depths in next search
          // commented out as other programs don't do this.
          // if (ttEntry.depth >= depth) {
          //   depth = ttEntry.depth + 1;
          //   LOG.debug("TT cached result of depth {}. Start depth is now {}", ttEntry.depth, depth);
          //   // send info to UCI to let the user know that we have a result for the cached depth
          //   engine.sendInfoToUCI(String.format("depth %d %s time %d pv %s", ttEntry.depth,
          //                                      getScoreString(currentBestRootValue), elapsedTime(),
          //                                      pv[ROOT_PLY].toNotationString()));
          // }
        }
      }
      else searchCounter.tt_Misses++;
    }

    // generate all legal root moves, and set pv move if we got one from TT
    generateRootMoves(position);

    // if we did not get a bestMove and PV from the TT set a temporary bestMove
    // and PV use the first move from the generated moves as this is likely to
    // be the best anyway due to move sorting.
    if (currentBestRootMove == Move.NOMOVE) {
      assert pv[ROOT_PLY].empty() : "if we have no TT move we should not have a pv";
      currentBestRootMove = rootMoves.getMove(0);
      pv[ROOT_PLY].add(currentBestRootMove);
    }

    // single reply in root
    if (rootMoves.size() == 1) {
      singleReply[ROOT_PLY] = true;
      // add time for this move as this is a special situation (forced moved?)
      if (searchMode.isTimeControl()) addExtraTime(1.5);
    }
    else {
      singleReply[ROOT_PLY] = false;
    }

    // print search setup for debugging
    if (LOG.isDebugEnabled()) {
      LOG.debug("{}", config.toString());
      LOG.debug("Searching in Position: {}", position.toFENString());
      LOG.debug("Searching these moves: {}", rootMoves.toString());
      LOG.debug("Search Mode: {}", searchMode.toString());
      LOG.debug("Time Management: {} soft: {} ms hard: {} ms",
                (searchMode.isTimeControl() ? "ON" : "OFF"), String.format("%,d", softTimeLimit),
                String.format("%,d", hardTimeLimit));
      LOG.debug("Start Depth: {}", depth);
      LOG.debug("Max Depth: {}", searchMode.getMaxDepth());
      LOG.debug("Start iterative deepening now");
    }

    // check search requirements
    assert rootMoves.size() > 0 : "No root moves to search";
    assert currentBestRootMove != Move.NOMOVE : "No initial best root move";
    assert !pv[ROOT_PLY].empty() : "No initial root PV ";
    assert depth > 0 : "depth <= 0";
    assert position != null : "Position == null";
    assert config.ASPIRATION_START_DEPTH > 1 : "ASPIRATION_START_DEPTH must be > 1";
    assert config.MTDf_START_DEPTH > 1 : "MTDf_START_DEPTH must be > 1";

    // ###########################################
    // ### BEGIN Iterative Deepening
    do {
      assert currentBestRootMove != Move.NOMOVE;

      searchCounter.currentIterationDepth = depth;
      searchCounter.bestMoveChanges = 0;
      // root node is always first searched node
      searchCounter.nodesVisited++;

      int value;

      // ###########################################
      // ### CALL SEARCH for depth    @formatter:off
      // ###
      // MTDf - just for debugging for now
      // https://www.chessprogramming.org/Debugging
      if (config.USE_MTDf
          && depth >= config.MTDf_START_DEPTH
          && !PERFT
          && currentBestRootValue != Evaluation.NOVALUE
      ) {
        assert !config.USE_PVS : "If using MTDf PVS should turned off";
        value = mtdf_search(position, depth, currentBestRootValue );
      }
      // ASPIRATION - not yet very efficient due to search oscillation
      else if (config.USE_ASPIRATION_WINDOW
          && depth >= config.ASPIRATION_START_DEPTH
          && !PERFT
          && currentBestRootValue != Evaluation.NOVALUE
      ) {
        assert !config.USE_MTDf : "If using Aspiration MTDf should be turned off";
        value = aspiration_search(position, depth, currentBestRootValue);
      }
      // ALPHA_BETA
      else {
        value = search(position, depth, ROOT_PLY,alpha, beta, PV_NODE, DO_NULL);
      }
      // ### @formatter:on
      // ###########################################

      assert PERFT || value != Evaluation.MIN || stopSearch : "MIN value without STOPSEARCH";

      // we can only use the value if there has not been a stop
      if (!stopSearch) {
        currentBestRootValue = value;
        rootMoves.pushToHead(pv[ROOT_PLY].getFirst());
      }

      // check after search conditions
      assert currentBestRootMove != Move.NOMOVE : "We should have a best move here";
      assert !pv[ROOT_PLY].empty() : "PV should not be empty";
      assert currentBestRootMove == pv[ROOT_PLY].getFirst() : "best move is different from pv";
      assert PERFT || currentBestRootValue != Evaluation.MIN
        : "Best root value is MIN, should be >MIN";
      assert
        PERFT || (currentBestRootValue >= Evaluation.MIN && currentBestRootValue <= Evaluation.MAX)
        : "Best root value out of MIN/MAX window";

      // update the UCI current best move and value
      sendUCIIterationEndInfo();

      // if the last iteration had many bestMoveChanges extend time
      // TODO: if (depth > 4 && searchCounter.bestMoveChanges > (depth / 2) + 1) addExtraTime(1.4);

      // check if we need to stop search - could be external or time.
      if (stopSearch || softTimeLimitReached() || hardTimeLimitReached()) break;

    } while (++depth <= searchMode.getMaxDepth());
    // ### ENDOF Iterative Deepening
    // ###########################################

    // create searchResult here
    searchResult.bestMove = currentBestRootMove;
    searchResult.resultValue = currentBestRootValue;
    searchResult.depth = searchCounter.currentSearchDepth;
    searchResult.extraDepth = searchCounter.currentExtraSearchDepth;

    // retrieve ponder move from pv
    searchResult.ponderMove = Move.NOMOVE;
    if (pv[ROOT_PLY].size() > 1 && (pv[ROOT_PLY].get(1)) != Move.NOMOVE) {
      searchResult.ponderMove = pv[ROOT_PLY].get(1);
    }

    // search is finished - stop timer
    stopTime = System.currentTimeMillis();
    searchCounter.lastSearchTime = elapsedTime(stopTime);

    // print result of the search
    printSearchResultInfo();

    return searchResult;
  }

  /**
   * Generates root moves and stores them in rootMoves. UCI move filter is applied and moves are
   * sorted best guess first.
   * @param position
   */
  private void generateRootMoves(Position position) {
    moveGenerators[ROOT_PLY].setPosition(position);
    if (config.USE_PVS_ORDERING) {
      moveGenerators[ROOT_PLY].setPVMove(currentBestRootMove);
    }
    MoveList legalMoves = moveGenerators[ROOT_PLY].getLegalMoves(true);

    // filter the root move list according to the given UCI moves
    rootMoves.clear();
    for (int i = 0; i < legalMoves.size(); i++) {
      if (searchMode.getMoves().isEmpty()) {
        rootMoves.add(legalMoves.get(i), Evaluation.NOVALUE);
      }
      else if (searchMode.getMoves().contains(Move.toUCINotation(position, legalMoves.get(i)))) {
        rootMoves.add(legalMoves.get(i), Evaluation.NOVALUE);
      }
    }
  }

  /**
   * MTDf Search
   * https://askeplaat.wordpress.com/534-2/mtdf-algorithm/
   *
   * This is for testing only - evaluation per iteration is changing
   * too much for this to be useful (yet).
   *
   * @param position
   * @param depth
   * @param f
   * @return bestValue
   */
  private int mtdf_search(Position position, int depth, int f) {
    int mtdf_searches = 0;
    int beta;
    int g = f;
    int upperbound = Evaluation.MAX;
    int lowerbound = Evaluation.MIN;
    LOG.debug("Start MDTf with value={}", f);
    do {
      if (g == lowerbound) beta = g + 1;
      else beta = g;
      g = search(position, depth, ROOT_PLY, beta - 1, beta, PV_NODE, DO_NULL);
      if (g < beta) upperbound = g;
      else lowerbound = g;
      mtdf_searches++;
    } while (lowerbound < upperbound);
    LOG.debug("MDTf value {} researches: {}", g, mtdf_searches);
    return g;
  }

  /**
   * Aspiration search works with the assumption that the value from previous
   * searches will not change too much and therefore the search can be tried
   * with a narrow window for alpha and beta around the previous value to cause
   * more cut offs. If the result is at the edge or outside(not possible in
   * fail-hard) of our window, we try another search with a wider window. If
   * this also fails we fall back to a full window search.
   *
   * @param position
   * @param depth
   * @param bestValue
   */
  private int aspiration_search(Position position, int depth, final int bestValue) {
    // need to have a good guess for the score of the best move
    assert bestValue != Evaluation.NOVALUE;

    // ##########################################################
    // 1st aspiration
    int alpha = Math.max(Evaluation.MIN, bestValue - 30);
    int beta = Math.min(Evaluation.MAX, bestValue + 30);
    int value = search(position, depth, ROOT_PLY, alpha, beta, PV_NODE, DO_NULL);
    // ##########################################################

    // if search has been stopped and value has missed window return current best value
    if (stopSearch && (value <= alpha || value >= beta)) return bestValue;

    // ##########################################################
    // 2nd aspiration
    // FAIL LOW - decrease lower bound
    if (value <= alpha) {
      sendUCIAspirationResearchInfo(" upperbound");
      searchCounter.aspirationResearches++;
      // add some extra time because of fail low - we might have found strong opponents move
      addExtraTime(1.3);
      alpha = Math.max(Evaluation.MIN, bestValue - 200);
      value = search(position, depth, ROOT_PLY, alpha, beta, PV_NODE, DO_NULL);
    }
    // FAIL HIGH - increase upper bound
    else if (value >= beta) {
      sendUCIAspirationResearchInfo(" lowerbound");
      searchCounter.aspirationResearches++;
      beta = Math.min(Evaluation.MAX, bestValue + 200);
      value = search(position, depth, ROOT_PLY, alpha, beta, PV_NODE, DO_NULL);
    }
    // ##########################################################

    // if search has been stopped and value has missed window return current best value
    if (stopSearch && (value <= alpha || value >= beta)) return bestValue;

    // ##########################################################
    // 3rd aspiration
    // FAIL - full window search
    if (value <= alpha || value >= beta) {
      if (value <= alpha) sendUCIAspirationResearchInfo(" lowerbound");
      else sendUCIAspirationResearchInfo(" upperbound");
      searchCounter.aspirationResearches++;
      // add some extra time because of fail low - we might have found strong opponents move
      if (value <= alpha) addExtraTime(1.3);
      alpha = Evaluation.MIN;
      beta = Evaluation.MAX;
      value = search(position, depth, ROOT_PLY, alpha, beta, PV_NODE, DO_NULL);
    }
    // ##########################################################

    return stopSearch ? bestValue : value;
  }

  /**
   * Main move search for all depths. Root ply is included as special case.
   *
   * @param position
   * @param depth
   * @param ply
   * @param alpha
   * @param beta
   * @param pvNode
   * @param doNullMove
   */
  private int search(final Position position, final int depth, final int ply, int alpha, int beta,
                     final boolean pvNode, final boolean doNullMove) {

    // is this the root node?
    final boolean ROOT = ply == ROOT_PLY;

    assert depth <= MAX_SEARCH_DEPTH;
    assert alpha >= Evaluation.MIN && beta <= Evaluation.MAX;
    assert pvNode || alpha == beta - 1;

    // update current search depth stats
    searchCounter.currentSearchDepth = Math.max(searchCounter.currentSearchDepth, ply);
    searchCounter.currentExtraSearchDepth = Math.max(searchCounter.currentExtraSearchDepth, ply);

    // on leaf node call qsearch
    // also go into quiescence when depth is 1 deeper than current
    // iteration to avoid search explosion through extensions
    if (depth <= LEAF || ply >= MAX_SEARCH_DEPTH - 1
        || ply - 1 >= searchCounter.currentIterationDepth) {
      return qsearch(position, ply, alpha, beta, pvNode);
    }

    // Check if we need to stop search - could be external or time or
    // max allowed nodes.
    // @formatter:off
    if (stopSearch
        || hardTimeLimitReached()
        || checkMaxNodes()
    ) {
      stopSearch = true;
      return Evaluation.MIN; // value does not matter because of top flag
    }
    // @formatter:on

    // ###############################################
    // DRAW by REPETITION
    // Check draw through 50-moves-rule, 3-fold-repetition
    // In non root nodes we evaluate each repetition as draw within
    // the search tree - this way we detect repetition
    // earlier - this should not weaken the search
    if (!PERFT) {
      if (ROOT) {
        if (position.check50Moves() || position.checkRepetitions(2)) {
          return Evaluation.DRAW;
        }
      }
      else {
        if (position.check50Moves() || position.checkRepetitions(1)) {
          return contempt(position);
        }
      }
    }
    // ###############################################

    // ###############################################
    // Mate Distance Pruning            @formatter:off
    // Did we already find a shorter mate then ignore
    // this one.
    if (config.USE_MDP && !PERFT
        && !ROOT
    ) {
      alpha = Math.max(-Evaluation.CHECKMATE + ply, alpha);
      beta = Math.min(Evaluation.CHECKMATE - ply, beta);
      if (alpha >= beta) {
        assert isCheckMateValue(alpha);
        searchCounter.mateDistancePrunings++;
        return alpha;
      }
    } // @formatter:on
    // ###############################################

    // ###############################################
    // TT Lookup
    int ttMove = Move.NOMOVE;
    if (config.USE_TRANSPOSITION_TABLE && !PERFT) {

      TT_Entry ttEntry = transpositionTable.get(position.getZobristKey());
      if (ttEntry != null) {
        searchCounter.tt_Hits++;

        // independent from tt entry depth
        ttMove = ttEntry.bestMove;
        mateThreat[ply] = ttEntry.mateThreat;

        // use value only if tt depth was equal or deeper
        if (ttEntry.depth >= depth) {
          int value = ttEntry.value;
          assert value != Evaluation.NOVALUE;
          // correct the mate value as this has been recorded
          // relative to a different ply
          if (isCheckMateValue(value)) value = value > 0 ? value - ply : value + ply;
          // in PV node only return ttHit if it was an exact hit
          boolean cut = false;
          if (ttEntry.type == TT_EntryType.EXACT) cut = true;
          else if (!pvNode && ttEntry.type == TT_EntryType.ALPHA && value <= alpha) cut = true;
          else if (!pvNode && ttEntry.type == TT_EntryType.BETA && value >= beta) cut = true;
          if (cut) {
            searchCounter.tt_Cuts++;
            return value;
          }
        }
        searchCounter.tt_Ignored++;
      }
      else searchCounter.tt_Misses++;
    }
    // End TT Lookup
    // ###############################################

    // Initialization
    int numberOfSearchedMoves = 0;
    byte ttType = TT_EntryType.ALPHA;
    int bestNodeValue = Evaluation.MIN;
    int bestNodeMove;
    if (ROOT) {
      bestNodeMove = currentBestRootMove;
    }
    else {
      bestNodeMove = ttMove;
      pv[ply].clear();
    }

    // ###############################################
    // FORWARD PRUNING BETA             @formatter:off
    // Pruning which return a beta value and not just
    // skip moves.
    // - Static Eval
    // - RFP
    // - NMP
    // - RAZOR
    if (!PERFT
        && !ROOT
        && !pvNode
        && !position.hasCheck()
        && doNullMove
    ) {

      // get an evaluation for the position
      int staticEval = evaluate(position, ply, alpha, beta);

      // ###############################################
      // Reverse Futility Pruning, (RFP, Static Null Move Pruning)
      // https://www.chessprogramming.org/Reverse_Futility_Pruning
      // Anticipate likely alpha low in the next ply by a beta cut
      // off before making and evaluating the move
      if (config.USE_RFP
          && depth == FRONTIER
      ) {
        final int evalMargin = config.RFP_MARGIN * depth;
        if (staticEval - evalMargin >= beta ){
          searchCounter.rfpPrunings++;
          storeTT(position, staticEval, TT_EntryType.BETA, depth, bestNodeMove, mateThreat[ply]);
          return staticEval - evalMargin; // fail-hard: beta / fail-soft: staticEval - evalMargin;
        }
      }
      // ###############################################

      // ###############################################
      // NULL MOVE PRUNING
      // https://www.chessprogramming.org/Null_Move_Pruning
      // If the next player would skip a move and would still be ahead (>beta)
      // we can prune this move. It also detects mate threats by
      // assuming the opponent could do two move in a row.
      if (config.USE_NMP
          && depth >= config.NMP_DEPTH
          && bigPiecePresent(position)
          && !mateThreat[ply]
          && staticEval >= beta
      ) {
        // reduce more on higher depths
        int r = depth > 6 ? 3 : 2;
        if (config.USE_VERIFY_NMP) r++;

        position.makeNullMove();
        int nullValue = -search(position, depth - r, ply + 1, -beta, -beta + 1, NPV_NODE, NO_NULL);
        position.undoNullMove();

        // Check for mate threat
        if (isCheckMateValue(nullValue)) mateThreat[ply] = true;

        // Verify on fail high
        if (config.USE_VERIFY_NMP
            && depth > config.NMP_VERIFICATION_DEPTH
            && nullValue >= beta
        ) {
          searchCounter.nullMoveVerifications++;
          nullValue =
            search(position, depth - config.NMP_VERIFICATION_DEPTH, ply, alpha, beta, NPV_NODE,
                   NO_NULL);
        }

        // pruning
        if (nullValue >= beta) {
          searchCounter.nullMovePrunings++;
          storeTT(position, nullValue, TT_EntryType.BETA, depth, bestNodeMove, mateThreat[ply]);
          return nullValue; // fail-hard: beta / fail-soft: nullValue;
        }
      }
      // ###############################################

      // ###############################################
      // RAZORING
      // If this position is already weaker as alpha (<alpha)
      // by a large margin we jump into qsearch to see if there
      // are any capturing moves which might improve the situation
      if(config.USE_RAZOR_PRUNING
          && depth <= config.RAZOR_DEPTH
          && !mateThreat[ply]
          && !isCheckMateValue(alpha)
          && staticEval + config.RAZOR_MARGIN <= alpha
      ){
          searchCounter.razorReductions++;
          return qsearch(position, ply, alpha, beta, NPV_NODE);
        }
      // ###############################################

    } // @formatter:on
    // ###############################################

    // ###############################################
    // INTERNAL ITERATIVE DEEPENING
    // If we didn't get a best move from the TT to play
    // first (PV) then do a shallow search to find
    // one. This is most effective with bad move ordering.
    // If move ordering is quite good this might be
    // a waste of search time.
    // @formatter:off
    if (config.USE_IID && !PERFT
        && pvNode
        && bestNodeMove == Move.NOMOVE
    ) { // @formatter:on
      searchCounter.iidSearches++;
      int iidDepth = depth - config.IID_REDUCTION;
      // do the iterative search which will eventually
      // fill the pv list and the TT
      search(position, iidDepth, ply, alpha, beta, PV_NODE, DO_NULL);
      // no we look in the pv list if we have a best move
      bestNodeMove = pv[ply].empty() ? Move.NOMOVE : pv[ply].getFirst();
    }
    // ###############################################

    // ###############################################
    // MOVE GENERATION
    // We could not prune until now so we need to prepare the
    // move generator and the search all child nodes.
    // We set position, killers and TT move.
    // Root moves have been generated in iterativeDeepening()
    // and are in field rootMoves
    if (!ROOT) {
      moveGenerators[ply].setPosition(position);
      if (config.USE_KILLER_MOVES && !killerMoves[ply].empty()) {
        moveGenerators[ply].setKillerMoves(killerMoves[ply]);
      }
      if (config.USE_PVS_ORDERING && bestNodeMove != Move.NOMOVE) {
        moveGenerators[ply].setPVMove(bestNodeMove);
      }
    }
    int legalMovesSize = 0; // used only for tracing
    // ###############################################

    // Check if we need to stop search again - there is a lot happening since last check
    if (stopSearch) {
      return Evaluation.MIN; // value does not matter because of top flag
    }

    // ###############################################
    // MOVE LOOP
    // Search all generated moves using the onDemand move generator.
    int move;
    int i = 0;
    int movesSize = ROOT ? rootMoves.size() : legalMovesSize;
    move = getNextMove(ply, i++);
    while (move != Move.NOMOVE) {

      // compute if this move gives chess
      final boolean givesCheck = position.givesCheck(move);

      if (ROOT) {
        // store the current move
        searchCounter.currentRootMoveNumber = i;
        searchCounter.currentRootMove = move;
      }

      // ###############################################
      // Minor Promotion Pruning
      // Skip non queen or knight promotion as they are
      // redundant. Exception would be stale mate situations
      // which we ignore.
      if (config.USE_MPP && !PERFT) {
        //@formatter:off
        if (Move.getMoveType(move) == MoveType.PROMOTION
          && Move.getPromotion(move).getType() != PieceType.QUEEN
          && Move.getPromotion(move).getType() != PieceType.KNIGHT) {
          searchCounter.minorPromotionPrunings++;
          move = getNextMove(ply, i++);
          continue;
        } // @formatter:on
      }
      // ###############################################

      // prepare new search depth
      int newDepth = depth - 1;

      // ###############################################
      // EXTENSIONS PRE MOVE
      // Some positions should be searched to a higher
      // depth or at least they should not be reduced.
      int extension = 0;
      if (config.USE_EXTENSIONS && !PERFT) {
        // @formatter:off
        if (mateThreat[ply]
            || Move.getMoveType(move) == MoveType.PROMOTION
            || (Move.getPiece(move).getType() == PieceType.PAWN
                && (position.getNextPlayer().isWhite()
                ? Move.getEnd(move).getRank() == Square.Rank.r7
                : Move.getEnd(move).getRank() == Square.Rank.r2))
            || Move.getMoveType(move) == MoveType.CASTLING
            //|| position.hasCheck()
            || givesCheck
        ) {
          extension = 1;
          newDepth += extension;
        } // @formatter:on
      }
      // ###############################################

      // ###############################################
      // FORWARD PRUNING ALPHA            @formatter:off
      // Avoid making the move on the position if we can
      // deduct that it is not worth examining.
      // Will not be done when in a pvNode search or when
      // already any search extensions has been determined.
      // Also not when in check.
      if (!PERFT
          && !pvNode
          && extension == 0
          && !position.hasCheck()
      ) {

        final int materialEval
          = position.getMaterial(myColor) - position.getMaterial(myColor.getInverseColor());
        final int moveGain = Move.getTarget(move).getType().getValue();

        // ###############################################
        // Limited Razoring
        // http://people.csail.mit.edu/heinz/dt/node29.html
        if (config.USE_LIMITED_RAZORING
            && depth == PRE_PRE_FRONTIER
        ) {
          final int razorMargin = PieceType.QUEEN.getValue();
          if (materialEval + moveGain + razorMargin <= alpha) {
            searchCounter.lrReductions++;
            newDepth = PRE_FRONTIER; // reduction by 1
          }
        }
        // ###############################################

        // ###############################################
        // Extended Futility Pruning
        // http://people.csail.mit.edu/heinz/dt/node25.html
        if (config.USE_EXTENDED_FUTILITY_PRUNING
            && depth == PRE_FRONTIER
        ) {
          final int extFutilityMargin = PieceType.ROOK.getValue();
          if (materialEval + moveGain + extFutilityMargin <= alpha) {
            searchCounter.efpPrunings++;
            move = getNextMove(ply, i++);
            continue;
          }
        }
        // ###############################################

        // ###############################################
        // Futility Pruning
        // http://people.csail.mit.edu/heinz/dt/node23.html
        // Predicts stand-pat cat offs in qsearch before
        // executing the move at frontier node (depth==1)
        // Futilitymargin is the margin by that a move can
        // increase the value of a position by positional
        // evaluations only (without material difference)
        if (config.USE_FUTILITY_PRUNING
            && depth == FRONTIER
        ) {
          final int futilityMargin = 3 * PieceType.PAWN.getValue();
          if (materialEval + moveGain + futilityMargin <= alpha) {
            if (materialEval + moveGain > bestNodeValue) bestNodeValue = materialEval + moveGain;
            searchCounter.fpPrunings++;
            move = getNextMove(ply, i++);
            continue;
          }
        }
        // ###############################################

        // ###############################################
        // Late Move Pruning (Move Count Based Pruning)
        // TODO: DANGER - more testing needed
        // if (config.USE_LMP
        //     && depth < config.LMP_MIN_DEPTH
        //     && numberOfSearchedMoves >= config.LMP_MIN_MOVES
        //     && !ROOT
        // ) {
        //   searchCounter.lmpPrunings++;
        //   if (TRACE) trace("%sSearch in ply %d for depth %d: LMP CUT", getSpaces(ply), ply, depth);
        //   move = getNextMove(ply, i++);
        //   continue;
        // }
        // ###############################################

        // ###############################################
        // Late Move Reduction
        if (config.USE_LMR
            && depth >= config.LMR_MIN_DEPTH
            && numberOfSearchedMoves >= config.LMR_MIN_MOVES
        ) {
          searchCounter.lmrReductions++;
          newDepth -= config.LMR_REDUCTION;
        }
        // ###############################################
      } // @formatter:on
      // ###############################################

      // ###############################################
      // MAKE MOVE and skip illegal moves
      // Root moves are always legal.
      position.makeMove(move);
      if (!ROOT && wasIllegalMove(position)) {
        position.undoMove();
        move = getNextMove(ply, i++);
        continue;
      }
      searchCounter.nodesVisited++;
      currentVariation.add(move);
      sendUCIUpdate(position);
      // ###############################################

      // Check if our givesCheck(move) works correctly
      assert position.hasCheck() == givesCheck
        : "Position check after move not the same as before the move";

      // ###############################################
      // ### START PVS SEARCH
      int value;
      if (!config.USE_PVS || PERFT || numberOfSearchedMoves == 0) {
        value = -search(position, newDepth, ply + 1, -beta, -alpha, pvNode, DO_NULL);
      }
      else {
        value = -search(position, newDepth, ply + 1, -alpha - 1, -alpha, NPV_NODE, DO_NULL);
        if (value > alpha && value < beta && !stopSearch) {
          if (ROOT) searchCounter.pvs_root_researches++;
          else searchCounter.pvs_researches++;
          value = -search(position, newDepth, ply + 1, -beta, -alpha, PV_NODE, DO_NULL);
        }
        else {
          if (ROOT) searchCounter.pvs_root_cutoffs++;
          else searchCounter.pvs_cutoffs++;
        }
      }
      // ### END PVS ROOT_PLY SEARCH
      // ###############################################

      // ###############################################
      // UNDO MOVE
      numberOfSearchedMoves++;
      currentVariation.removeLast();
      position.undoMove();
      // ###############################################

      // In PERFT we can ignore values and pruning
      if (PERFT) {
        move = getNextMove(ply, i++);
        continue;
      }

      // End a stopped search here as the value from this is not reliable.
      // If we already have searched moves and found a better alpha then we
      // still use this better move.
      if (stopSearch) break;

      // write the value back to the root moves list
      if (ROOT) rootMoves.set(i - 1, move, value);

      // Did we find a better move for this node?
      // For the first move this is always the case.
      if (value > bestNodeValue) {

        bestNodeValue = value;
        bestNodeMove = move;

        // If we found a move that is better or equal than beta this means that the
        // opponent can/will avoid this position altogether so we can stop search
        // this node
        if (value >= beta && config.USE_ALPHABETA_PRUNING) { // fail-high

          // save killer moves so they will be search earlier on following nodes
          if (config.USE_KILLER_MOVES && Move.getTarget(move) == Piece.NOPIECE) {
            if (!killerMoves[ply].pushToHeadStable(move)) {
              killerMoves[ply].addFront(move);
              while (killerMoves[ply].size() > config.NO_KILLER_MOVES) {
                killerMoves[ply].removeLast(); // keep size stable
              }
            }
          }

          searchCounter.prunings++;
          if (i < MAX_MOVES) searchCounter.betaCutOffs[i - 1]++;
          // store the bestNodeMove any way as this is the a refutation and
          // should be checked in other nodes very early
          ttType = TT_EntryType.BETA;
          break; // get out of loop and return the value at the end
        }

        // Did we find a better move than in previous nodes then this is our new
        // PV and best move for this ply.
        // If we never find a better alpha we do have a best move for this node
        // but not for the ply. We will return alpha and store a alpha node in
        // TT.
        if (value > alpha) { // NEW ALPHA => NEW PV NODE
          MoveList.savePV(move, pv[ply + 1], pv[ply]);
          ttType = TT_EntryType.EXACT;
          alpha = value;
          if (ROOT) {
            currentBestRootMove = move;
            searchCounter.bestMoveChanges++;
          }
        }
      }

      // check if we need to stop search - could be external or time.
      if (ROOT && (stopSearch || softTimeLimitReached() || hardTimeLimitReached())) {
        break;
      }

      // get the new move
      move = getNextMove(ply, i++);

    } // end iteration over all moves
    // ##### Iterate through all available moves
    // ##########################################################

    // if we did not have a legal move then we have a mate
    if (!ROOT && numberOfSearchedMoves == 0 && !stopSearch) {
      searchCounter.nonLeafPositionsEvaluated++;
      if (position.hasCheck()) {
        // We have a check mate. Return a -CHECKMATE.
        bestNodeValue = -Evaluation.CHECKMATE + ply;
      }
      else {
        // We have a stale mate. Return the draw value.
        bestNodeValue = Evaluation.DRAW;
      }
      assert ttType == TT_EntryType.ALPHA;
    }

    // store the best alpha
    storeTT(position, bestNodeValue, ttType, depth, bestNodeMove, mateThreat[ply]);
    return bestNodeValue; // fail-hard: alpha / fail.soft: bestValue
  }

  /**
   * After the normal search has reached its intended depth the search is extended for certain
   * moves. Typically this are moves which are capturing or checking. All other moves are called
   * "quiet" moves and therefore the term quiescence search (qsearch).
   * <p>
   * A quiescence search is a special und usually simpler version of the normal search where only
   * certain moves are generated and searched deeper.
   *
   * @param position
   * @param ply
   * @param alpha
   * @param beta
   * @param pvNode
   * @return evaluation
   */
  private int qsearch(final Position position, final int ply, int alpha, int beta,
                      final boolean pvNode) {

    assert ply >= 1;
    assert alpha >= Evaluation.MIN && beta <= Evaluation.MAX;
    assert pvNode || alpha == beta - 1;

    // update current search depth stats
    searchCounter.currentExtraSearchDepth = Math.max(searchCounter.currentExtraSearchDepth, ply);

    // if PERFT return with eval to count all captures etc.
    if (PERFT) return evaluate(position, ply, alpha, beta);

    // check draw through 50-moves-rule, 3-fold-repetition
    // we evaluate ech repetition as draw within the search tree - this weay we detect repetition
    // earlier - this should not weeken the search
    if (position.check50Moves() || position.checkRepetitions(1)) {
      return contempt(position);
    }

    // If quiescence is turned off or we reach max depth return evaluation
    if (!config.USE_QUIESCENCE || ply >= MAX_SEARCH_DEPTH - 1) {
      return evaluate(position, ply, alpha, beta);
    }

    // Check if we need to stop search - could be external or time or
    // max allowed nodes.
    // @formatter:off
    if (stopSearch
        || hardTimeLimitReached()
        || checkMaxNodes()
    ) {
      stopSearch = true;
      return Evaluation.MIN; // value does ont matter because of top flag
    }
    // @formatter:on

    // ###############################################
    // ## Mate Distance Pruning
    // ## Did we already find a shorter mate then ignore this one
    if (config.USE_MDP && !PERFT) {
      alpha = Math.max(-Evaluation.CHECKMATE + ply, alpha);
      beta = Math.min(Evaluation.CHECKMATE - ply, beta);
      if (alpha >= beta) {
        assert isCheckMateValue(alpha);
        searchCounter.mateDistancePrunings++;
        return alpha;
      }
    }
    // ## ENDOF Mate Distance Pruning
    // ###############################################

    // ###############################################
    // TT Lookup
    int ttMove = Move.NOMOVE;
    if (config.USE_TRANSPOSITION_TABLE && !PERFT) {

      TT_Entry ttEntry = transpositionTable.get(position.getZobristKey());
      if (ttEntry != null) {
        searchCounter.tt_Hits++;

        // independend from tt entry depth
        ttMove = ttEntry.bestMove;
        mateThreat[ply] = ttEntry.mateThreat;

        // use value only if tt depth was equal or deeper
        //if (ttEntry.depth >= DEPTH_NONE) {
        int value = ttEntry.value;
        assert value != Evaluation.NOVALUE;
        // correct the mate value as this has been recorded
        // relative to a different ply
        if (isCheckMateValue(value)) value = value > 0 ? value - ply : value + ply;
        // in PV node only return ttHit if it was an exact hit
        boolean cut = false;
        if (ttEntry.type == TT_EntryType.EXACT) cut = true;
        else if (!pvNode && ttEntry.type == TT_EntryType.ALPHA && value <= alpha) cut = true;
        else if (!pvNode && ttEntry.type == TT_EntryType.BETA && value >= beta) cut = true;
        if (cut) {
          searchCounter.tt_Cuts++;
          return value;
        }
        //}
        searchCounter.tt_Ignored++;
      }
      else searchCounter.tt_Misses++;
    }
    // End TT Lookup
    // ###############################################

    // Prepare hash type
    byte ttType = TT_EntryType.ALPHA;

    // Initialize best values
    int bestNodeValue = Evaluation.MIN;
    int bestNodeMove = ttMove;

    // needed to remember if we even had a legal move
    int numberOfSearchedMoves = 0;

    // clear principal Variation for this depth
    pv[ply].clear();

    // ###############################################
    // StandPat
    // Use evaluation as a standing pat (lower bound)
    // https://www.chessprogramming.org/Quiescence_Search#Standing_Pat
    // Assuption is that there is at least on move which would improve the
    // current position. So if we are already >beta we don't need to look at it.
    if (!position.hasCheck()) {
      int standPat = evaluate(position, ply, alpha, beta);
      bestNodeValue = standPat;
      if (standPat >= beta) {
        storeTT(position, standPat, TT_EntryType.BETA, DEPTH_NONE, Move.NOMOVE, mateThreat[ply]);
        return standPat; // fail-hard: beta, fail-soft: statEval
      }
      if (standPat > alpha) alpha = standPat;
    }
    // ###############################################

    // Prepare move generator - set position, killers and TT move and generate
    // all PseudoLegalMoves for QSearch. Usually only capture moves and check
    // evasions will be determined in move generator
    moveGenerators[ply].setPosition(position);
    if (config.USE_PVS_ORDERING && bestNodeMove != Move.NOMOVE) {
      moveGenerators[ply].setPVMove(bestNodeMove);
    }
    MoveList moves = moveGenerators[ply].getPseudoLegalQSearchMoves();
    searchCounter.movesGenerated += moves.size();

    // ###############################################
    // moves to search recursively
    int value;
    for (int i = 0; i < moves.size(); i++) {
      int move = moves.get(i);

      // ###############################################
      // Minor Promotion Pruning
      // Skip non queen or knight promotion as they are
      // redundant. Exception would be stale mate situations
      // which we ignore.
      if (config.USE_MPP && !PERFT) {
        // @formatter:off
        if (Move.getMoveType(move) == MoveType.PROMOTION
            && Move.getPromotion(move).getType() != PieceType.QUEEN
            && Move.getPromotion(move).getType() != PieceType.KNIGHT) {
          searchCounter.minorPromotionPrunings++;
          continue;
        }
        // @formatter:on
      }
      // ###############################################

      // ###############################################
      // QUIESCENCE FUTILITY PRUNING      @formatter:off
      // Avoid making the move on the position if we can
      // deduct that it is not worth examining.
      if (config.USE_QFUTILITY_PRUNING && !PERFT
          && !pvNode
          && !position.hasCheck()
          && Move.getMoveType(move) != MoveType.PROMOTION
          && !(Move.getPiece(move).getType() == PieceType.PAWN
                && (position.getNextPlayer().isWhite()
                ? Move.getEnd(move).getRank() == Square.Rank.r7
                : Move.getEnd(move).getRank() == Square.Rank.r2))
          && bigPiecePresent(position)
          && !position.givesCheck(move)
      ) {
        final int materialEval = position.getMaterial(myColor)
                                   - position.getMaterial(myColor.getInverseColor());
        final int moveGain = Move.getTarget(move).getType().getValue();
        final int deltaMargin = 2 * PieceType.PAWN.getValue();
        value = materialEval + moveGain + deltaMargin;
        if (value <= alpha) {
          searchCounter.qfpPrunings++;
          if (value > bestNodeValue) bestNodeValue = value;
          continue;
        }
      } // @formatter:on
      // ###############################################

      // TODO: SEE test - skip loosing moves

      // ###############################################
      // Make the move and skip illegal moves
      position.makeMove(move);
      if (wasIllegalMove(position)) {
        position.undoMove();
        continue;
      }
      // keep track of current variation
      currentVariation.add(move);
      // update nodes visited and count as non quiet board
      searchCounter.nodesVisited++;
      searchCounter.positionsNonQuiet++;
      // update UCI
      sendUCIUpdate(position);
      // ###############################################

      // ###############################################
      // go one ply deeper into the search tree
      value = -qsearch(position, ply + 1, -beta, -alpha, pvNode);
      // needed to remember if we even had a legal move
      numberOfSearchedMoves++;
      currentVariation.removeLast();
      position.undoMove();
      // ###############################################

      assert stopSearch || (value > Evaluation.MIN && value < Evaluation.MAX);

      // End a stopped search here as the value from this is not reliable.
      // If we already have searched moves and found a better alpha then we
      // still use this better move.
      if (stopSearch) break;

      // Did we find a better move for this node?
      // For the PV move this is always the case.
      if (value > bestNodeValue) {

        bestNodeValue = value;
        bestNodeMove = move;

        // If we found a move that is better or equal than beta this mean that the
        // opponent can/will avoid this move altogether so we can stop search this node
        // fail-high
        if (value >= beta && config.USE_ALPHABETA_PRUNING && !PERFT) {
          searchCounter.prunings++;
          if (i < MAX_MOVES) searchCounter.betaCutOffs[i]++;
          ttType = TT_EntryType.BETA;
          break; // get out of loop and return the value at the end
        }

        // Did we find a better move than in previous nodes then this is our new
        // PV and best move for this ply.
        if (value > alpha) { // NEW ALPHA => NEW PV NODE
          MoveList.savePV(move, pv[ply + 1], pv[ply]);
          ttType = TT_EntryType.EXACT;
          alpha = value;
        }
      }
      // PRUNING END
    } // iteration over all qmoves
    // ###############################################

    // if we did not have a legal move then we might have a mate or only quiet moves
    if (numberOfSearchedMoves == 0 && position.hasCheck() && !stopSearch) {
      // as we will not enter evaluation we count it here
      searchCounter.nonLeafPositionsEvaluated++;
      // We have a check mate. Return a -CHECKMATE.
      bestNodeValue = -Evaluation.CHECKMATE + ply;
      assert ttType == TT_EntryType.ALPHA;
    }

    assert stopSearch || (bestNodeValue > Evaluation.MIN && bestNodeValue < Evaluation.MAX);

    storeTT(position, bestNodeValue, ttType, 0, bestNodeMove, mateThreat[ply]);
    return bestNodeValue; /// fail-hard: alpha / fail-soft: bestvalue
  }

  /**
   * Call the Evaluator evaluation and updates some statistics
   *
   * @param position
   * @param ply
   * @param alpha
   * @param beta
   * @return the evaluation value of the position
   */
  private int evaluate(Position position, int ply, int alpha, int beta) {

    // count all leaf nodes evaluated
    searchCounter.leafPositionsEvaluated++;

    // PERFT stats
    if (PERFT) {
      final int lastMove = position.getLastMove();
      if (Move.getTarget(lastMove) != Piece.NOPIECE) searchCounter.captureCounter++;
      if (Move.getMoveType(lastMove) == MoveType.ENPASSANT) searchCounter.enPassantCounter++;
      if (position.hasCheck()) searchCounter.checkCounter++;
      if (position.hasCheckMate()) searchCounter.checkMateCounter++;
      return 1;
    }

    // do evaluation
    return evaluator.evaluate(position);
  }

  /**
   * Stores position values and node best moves into the transposition table.
   *
   * @param position
   * @param value
   * @param ttType
   * @param depth
   * @param bestMove
   * @param mateThreat
   */
  private void storeTT(final Position position, final int value, final byte ttType, final int depth,
                       int bestMove, boolean mateThreat) {

    if (!config.USE_TRANSPOSITION_TABLE || PERFT || stopSearch) return;

    assert depth >= 0 && depth <= MAX_SEARCH_DEPTH;
    assert (value >= Evaluation.MIN && value <= Evaluation.MAX);
    transpositionTable.put(position.getZobristKey(), (short) value, ttType, (byte) depth, bestMove,
                           mateThreat);
  }

  /**
   * Returns the next move for the search distinguishing between root and non-root.
   * @param ply
   * @param i
   * @return next from either rootMove list (when ply==ROOT_PLY) or onDemand from move generator
   */
  private int getNextMove(int ply, int i) {
    int move;
    if (ply == ROOT_PLY) move = i < rootMoves.size() ? rootMoves.getMove(i) : Move.NOMOVE;
    else move = moveGenerators[ply].getNextPseudoLegalMove(false);
    return move;
  }

  /**
   * Checks if the maximum number of nodes has been searched
   * @return true if maximum number of searched nodes is reached
   */
  private boolean checkMaxNodes() {
    return searchMode.getNodes() > 0 && searchCounter.nodesVisited >= searchMode.getNodes();
  }

  /**
   * Retrieves the PV line from the transposition table in root search.
   *
   * @param position
   * @param depth
   * @param pv
   */
  private void getPVLine(final Position position, final byte depth, final MoveList pv) {
    if (depth < 0) return;
    TT_Entry ttEntry = transpositionTable.get(position.getZobristKey());
    if (ttEntry != null && ttEntry.bestMove != Move.NOMOVE) {
      pv.add(ttEntry.bestMove);
      position.makeMove(ttEntry.bestMove);
      getPVLine(position, (byte) (depth - 1), pv);
      position.undoMove();
    }
  }

  /**
   * Probes the openbook for the given position and returns a move from
   * the opening book or null if no move was found.
   *
   * @param position
   * @return move from opening book or null if no move was found
   */
  private SearchResult getBookMove(Position position) {
    // prepare search result
    SearchResult searchResult = new SearchResult();

    // Look for a possible opening book move and send it as result
    if (config.USE_BOOK && !PERFT) {
      if (searchMode.isTimeControl()) {
        LOG.info("Time controlled search => Using book");
        // initialize book - only happens the first time
        book.initialize();
        // retrieve a move from the book
        int bookMove = book.getBookMove(position.toFENString());
        if (bookMove != Move.NOMOVE && Move.isValid(bookMove)) {
          LOG.info("Book move found: {}", Move.toString(bookMove));
          hadBookMove = true;
          searchResult.bestMove = bookMove;
          searchResult.ponderMove = Move.NOMOVE;
          return searchResult;
        }
        else {
          LOG.info("No Book move found");
        }
      }
      else {
        LOG.info("Non time controlled search => not using book");
      }
    }
    return null;
  }

  /**
   * Returns true if at least on non pawn/king piece is on the
   * board for the moving side.
   *
   * @param position
   * @return true if at least one officer is on the board, false otherwise.
   */
  private static boolean bigPiecePresent(Position position) {
    final int activePlayer = position.getNextPlayer().ordinal();
    return !(position.getKnightSquares()[activePlayer].isEmpty()
             && position.getBishopSquares()[activePlayer].isEmpty()
             && position.getRookSquares()[activePlayer].isEmpty()
             && position.getQueenSquares()[activePlayer].isEmpty());
  }

  /**
   * @param value
   * @return true if absolute value is a mate value, false otherwise
   */
  private static boolean isCheckMateValue(int value) {
    final int abs = Math.abs(value);
    return abs >= Evaluation.CHECKMATE_THRESHOLD && abs <= Evaluation.CHECKMATE;
  }

  /**
   * @param value
   * @return a UCI compatible string for th score in cp or in mate in ply
   */
  private static String getScoreString(int value) {
    String scoreString;
    if (isCheckMateValue(value)) {
      scoreString = "score mate ";
      scoreString += value < 0 ? "-" : "";
      scoreString += (Evaluation.CHECKMATE - Math.abs(value) + 1) / 2;
    }
    else {
      scoreString = "score cp " + value;
    }
    return scoreString;
  }

  /**
   * @param position
   * @return value depending on game phase to avoid easy draws
   */
  private int contempt(Position position) {
    return (int) (-position.getGamePhaseFactor() * EvaluationConfig.CONTEMPT_FACTOR);
  }

  /**
   * @param position
   * @return true it last move made on poistion was illegal (left the king in check)
   */
  private boolean wasIllegalMove(final Position position) {
    return position.isAttacked(position.getNextPlayer(),
                               position.getKingSquares()[position.getOpponent().ordinal()]);
  }

  /**
   * Configure time limits-
   * <p>
   * Chooses if search mode is time per move or remaining time
   * and set time limits accordingly
   */
  private void configureTimeLimits() {

    if (searchMode.getMoveTime().toMillis() > 0) { // mode time per move

      hardTimeLimit = searchMode.getMoveTime().toMillis();
      softTimeLimit = hardTimeLimit;

    }
    else { // remaining time - estimated time per move

      // retrieve time left from search mode
      long timeLeft = searchMode.getRemainingTime(myColor).toMillis();

      // Give some overhead time so that in games with very low available time we do not run out
      // of time
      timeLeft -= 1000; // this should do

      // when we know the move to go (until next time control) use them otherwise assume 40
      int movesLeft = searchMode.getMovesToGo() > 0 ? searchMode.getMovesToGo() : 40;

      // when we have a time increase per move we estimate the additional time we should have
      if (myColor.isWhite()) {
        timeLeft += 40 * searchMode.getWhiteInc().toMillis();
      }
      else if (myColor.isBlack()) {
        timeLeft += 40 * searchMode.getBlackInc().toMillis();
      }

      // for timed games with remaining time
      hardTimeLimit = Duration.ofMillis((long) ((timeLeft / movesLeft) * 1.0f)).toMillis();
      softTimeLimit = (long) (hardTimeLimit * 0.8);
    }

    // limits for very short available time
    if (hardTimeLimit < 100) {
      addExtraTime(0.9);
    }

  }

  /**
   * Changes the time limit by the given direction and also sets the soft time limit
   * to 0.8 of the hard time limit.
   * Factor 1 is neutral. <1 shortens the time, >1 adds time<br/>
   * Example: direction 0.8 is 20% less time. Factor 1.2 is 20% additional time
   * Always calculated from the initial time budget.
   * *
   *
   * @param factor direction for changing the time for the current search
   */
  private void addExtraTime(double factor) {

    if (searchMode.getMoveTime().toMillis() == 0) {
      extraTime += hardTimeLimit * (factor - 1);
      LOG.debug(String.format("Time added %,d ms to %,d ms", (long) (hardTimeLimit * (factor - 1)),
                              hardTimeLimit + extraTime));
    }
  }

  /**
   * Soft time limit is used in iterative deepening to decide if an new depth should even be started.
   *
   * @return true if soft time limit is reached, false otherwise
   */
  private boolean softTimeLimitReached() {
    if (!searchMode.isTimeControl()) return false;
    stopSearch = elapsedTime() >= softTimeLimit + (extraTime * 0.8);
    return stopSearch;
  }

  /**
   * Hard time limit is used to check time regularily in the search to stop the search when
   * time is out
   *
   * TODO instead of checking this regulary we could use a timer thread to set stopSearch to true.
   *
   * @return true if hard time limit is reached, false otherwise
   */
  private boolean hardTimeLimitReached() {
    if (!searchMode.isTimeControl()) return false;
    stopSearch = elapsedTime() >= hardTimeLimit + extraTime;
    return stopSearch;
  }

  /**
   * @return the elapsed time in ms since the start of the search
   */
  private long elapsedTime() {
    return System.currentTimeMillis() - startTime;
  }

  /**
   * @param t
   * @return the elapsed time from the start of the search to the given t
   */
  private long elapsedTime(final long t) {
    return t - startTime;
  }

  /**
   * Print log info after search is finish.
   */
  private void printSearchResultInfo() {
    if (LOG.isInfoEnabled()) {
      LOG.info(searchCounter.toString());
      LOG.info(transpositionTable.toString());
      LOG.info(String.format(
        "TT Stats: Nodes visited: %,d TT Hits %,d TT Misses %,d TT Cuts %,d TT Ignored %,d",
        searchCounter.nodesVisited, searchCounter.tt_Hits, searchCounter.tt_Misses,
        searchCounter.tt_Cuts, searchCounter.tt_Ignored));
      LOG.info("{}", String.format(
        "Search complete. Nodes visited: %,d Boards Evaluated: %,d (+%,d) re-pvs-root=%d re-asp=%d betaCutOffs=%s",
        searchCounter.nodesVisited, searchCounter.leafPositionsEvaluated,
        searchCounter.nonLeafPositionsEvaluated, searchCounter.pvs_root_researches,
        searchCounter.aspirationResearches, Arrays.toString(searchCounter.betaCutOffs)));

      LOG.info(searchCounter.toString());
      LOG.info("Search Depth was {} ({})", searchCounter.currentIterationDepth,
               searchCounter.currentExtraSearchDepth);
      LOG.info("Search took {}", DurationFormatUtils.formatDurationHMS(elapsedTime(stopTime)));
      if (hardTimeLimit > 0) {
        LOG.info("Initial time budget was {} ({}%)",
                 DurationFormatUtils.formatDurationHMS(hardTimeLimit),
                 (100 * searchCounter.lastSearchTime) / hardTimeLimit);
      }
      LOG.info("Speed: {}", String.format("%,d nps", 1000 * (searchCounter.nodesVisited / (
        elapsedTime(stopTime) + 2L))));
    }
  }

  /**
   * Send UCI info after each iteration of search depth
   */
  private void sendUCIIterationEndInfo() {
    final String infoString =
      String.format("depth %d seldepth %d multipv 1 %s nodes %d nps %d time %d pv %s",
                    searchCounter.currentIterationDepth, searchCounter.currentExtraSearchDepth,
                    getScoreString(currentBestRootValue), searchCounter.nodesVisited,
                    1000 * (searchCounter.nodesVisited / (elapsedTime() + 2L)), elapsedTime(),
                    pv[ROOT_PLY].toNotationString());

    if (engine == null) LOG.info(">> {}", infoString);
    else engine.sendInfoToUCI(infoString);
  }

  /**
   * Send UCI info after each iteration of search depth
   */
  private void sendUCIAspirationResearchInfo(String bound) {
    final String infoString =
      String.format("depth %d seldepth %d multipv 1 %s%s nodes %d nps %d time %d pv %s",
                    searchCounter.currentIterationDepth, searchCounter.currentExtraSearchDepth,
                    getScoreString(currentBestRootValue), bound, searchCounter.nodesVisited,
                    1000 * (searchCounter.nodesVisited / (elapsedTime() + 2L)), elapsedTime(),
                    pv[ROOT_PLY].toNotationString());

    if (engine == null) LOG.info(">> {}", infoString);
    else engine.sendInfoToUCI(infoString);
  }

  /**
   * Send the UCI info command line to the UI. Uses a ticker interval to avoid
   * flooding the protocol. <code>UCI_UPDATE_INTERVAL</code> is used as a time
   * interval in ms
   *
   * @param position
   */
  private void sendUCIUpdate(final Position position) {
    // send current root move info to UCI every x milli seconds
    if (System.currentTimeMillis() - uciUpdateTicker >= UCI_UPDATE_INTERVAL) {

      String infoString = String.format("depth %d seldepth %d nodes %d nps %d time %d hashfull %d",
                                        searchCounter.currentIterationDepth,
                                        searchCounter.currentExtraSearchDepth,
                                        searchCounter.nodesVisited,
                                        1000 * searchCounter.nodesVisited / (1 + elapsedTime()),
                                        elapsedTime(), (int) (1000 * (
          (float) transpositionTable.getNumberOfEntries() / transpositionTable.getMaxEntries())));

      if (engine == null) LOG.info(">> {}", infoString);
      else engine.sendInfoToUCI(infoString);

      infoString = String.format("currmove %s currmovenumber %d",
                                 Move.toUCINotation(position, searchCounter.currentRootMove),
                                 searchCounter.currentRootMoveNumber);

      if (engine == null) LOG.info(">> {}", infoString);
      else engine.sendInfoToUCI(infoString);

      if (config.UCI_ShowCurrLine) {
        infoString = String.format("currline %s", currentVariation.toNotationString());
        if (engine == null) LOG.info(">> {}", infoString);
        else engine.sendInfoToUCI(infoString);

      }

      LOG.debug(searchCounter.toString());
      LOG.info(transpositionTable.toString());
      LOG.info(String.format(
        "TT Stats: Nodes visited: %,d TT Hits %,d TT Misses %,d TT Cuts %,d TT Ignored %,d",
        searchCounter.nodesVisited, searchCounter.tt_Hits, searchCounter.tt_Misses,
        searchCounter.tt_Cuts, searchCounter.tt_Ignored));

      uciUpdateTicker = System.currentTimeMillis();
    }
  }

  /**
   * Sends the lastSearchResult to the UCI UI via the engine
   */
  private void sendUCIBestMove() {
    if (!Move.isValid(lastSearchResult.bestMove)) {
      LOG.error("Engine Best Move is invalid move!" + Move.toString(lastSearchResult.bestMove));
      LOG.error("Position: " + currentPosition.toFENString());
      LOG.error("Last Move: " + currentPosition.getLastMove());
    }

    if (engine == null) LOG.info(
      "Engine got Best Move: " + Move.toSimpleString(lastSearchResult.bestMove) + " [Ponder " + Move
        .toSimpleString(lastSearchResult.ponderMove) + "]");
    else engine.sendResult(lastSearchResult.bestMove, lastSearchResult.ponderMove);
  }

  /**
   * Is called when our last ponder suggestion has been executed by opponent.
   * If we are already pondering just continue the search but switch to time control.
   */
  public void ponderHit() {
    if (searchMode.isPonder()) {
      LOG.info("****** PONDERHIT *******");
      if (isSearching()) {
        LOG.info("Ponderhit when ponder search still running. Continue searching.");
        startTime = System.currentTimeMillis();
        searchMode.ponderHit();
        String threadName = "Engine: " + myColor.toString();
        threadName += " (PHit)";
        searchThread.setName(threadName);
        // if time based game setup the time soft and hard time limits
        if (searchMode.isTimeControl()) {
          configureTimeLimits();
        }
      }
      else {
        LOG.info("Ponderhit when ponder search already ended. Sending result.");
        LOG.info("Search result was: {} PV {}", lastSearchResult.toString(),
                 pv[ROOT_PLY].toNotationString());

        sendUCIBestMove();
      }

    }
    else {
      LOG.warn("Ponderhit when not pondering!");
    }
  }

  /**
   * @return true if previous search is still running
   */
  public boolean isSearching() {
    return searchThread != null && searchThread.isAlive();
  }

  /**
   * Called when the state of this search is no longer valid as the last call to startSearch is
   * not from
   * the same game as the next.
   */
  public void newGame() {
    clearHashTables();
  }

  /**
   * @return Search result of the last search. Has NOMOVE if no result available.
   */
  public SearchResult getLastSearchResult() {
    return lastSearchResult;
  }

  /**
   * Returns a clone of the last principal variation MoveList
   * @param ply
   * @return Clone of PV MoveList
   */
  public MoveList getPrincipalVariation(int ply) {
    return pv[ply].clone();
  }

  /**
   * Returns a String of the last principal variation MoveList
   * @param ply
   * @return String of PV MoveList
   */
  public String getPrincipalVariationString(int ply) {
    return pv[ply].toNotationString();
  }

  /**
   * @return a wrapper for all search counters
   */
  public SearchCounter getSearchCounter() {
    return searchCounter;
  }

  /**
   * @return
   */
  public TranspositionTable getTranspositionTable() {
    return transpositionTable;
  }

  /**
   * Called by engine whenever hash size changes.
   * Initially set in constructor
   *
   * @param hashSize
   */
  public void setHashSize(int hashSize) {
    transpositionTable = new TranspositionTable(hashSize);
  }

  /**
   * Clears the hashtables
   */
  public void clearHashTables() {
    transpositionTable.clear();
  }

  /**
   * Pauses the calling thread while in search.
   * <p>
   * Uses join() on the search thread.
   */
  public void waitWhileSearching() {
    while (isSearching()) {
      try {
        searchThread.join();
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
  }

  /**
   * Parameter class for the search result
   */
  public static final class SearchResult {

    public int  bestMove    = Move.NOMOVE;
    public int  ponderMove  = Move.NOMOVE;
    public int  resultValue = Evaluation.NOVALUE;
    public long time        = -1;
    public int  depth       = 0;
    public int  extraDepth  = 0;

    @Override
    public String toString() {
      return "Best Move: " + Move.toString(bestMove) + " (" + getScoreString(resultValue) + ") "
             + " Ponder Move: " + Move.toString(ponderMove) + " Depth: " + depth + "/" + extraDepth;
    }
  }

  /**
   * Simple data structure for return value when probing the transposition table.
   */
  class TTHit {
    int  value    = Evaluation.NOVALUE;
    byte type     = TT_EntryType.NONE;
    byte depth    = 0;
    int  bestMove = Move.NOMOVE;
    public boolean mateThreat;
  }

  /**
   * Convenience Wrapper class for all search data and counters
   */
  public class SearchCounter {

    // counter for cut off to measure quality of move ordering
    long[] betaCutOffs = new long[MAX_MOVES];

    // Info values
    int  currentIterationDepth   = 0;
    int  currentSearchDepth      = 0;
    int  currentExtraSearchDepth = 0;
    int  currentRootMove         = 0;
    int  currentRootMoveNumber   = 0;
    long lastSearchTime          = 0;
    int  bestMoveChanges         = 0;

    // PERFT Values
    long leafPositionsEvaluated    = 0;
    long nonLeafPositionsEvaluated = 0;
    long checkCounter              = 0;
    long checkMateCounter          = 0;
    long captureCounter            = 0;
    long enPassantCounter          = 0;

    // Optimization Values
    int  aspirationResearches   = 0;
    long prunings               = 0;
    long pvs_root_researches    = 0;
    long pvs_root_cutoffs       = 0;
    long pvs_researches         = 0;
    long pvs_cutoffs            = 0;
    long positionsNonQuiet      = 0;
    long tt_Hits                = 0;
    long tt_Misses              = 0;
    long tt_Cuts                = 0;
    long tt_Ignored             = 0;
    long movesGenerated         = 0;
    long nodesVisited           = 0;
    int  minorPromotionPrunings = 0;
    int  mateDistancePrunings   = 0;
    int  rfpPrunings            = 0;
    int  nullMovePrunings       = 0;
    int  nullMoveVerifications  = 0;
    int  razorReductions        = 0;
    int  iidSearches            = 0;
    int  lrReductions           = 0;
    int  efpPrunings            = 0;
    int  fpPrunings             = 0;
    int  qfpPrunings            = 0;
    int  lmpPrunings            = 0;
    int  lmrReductions          = 0;
    int  deltaPrunings          = 0;

    public SearchCounter() {
      // init betaCutOff counter
      for (int i = 0; i < MAX_MOVES; i++) betaCutOffs[i] = 0;
    }

    private void resetCounter() {
      // init betaCutOff counter
      for (int i = 0; i < MAX_MOVES; i++) betaCutOffs[i] = 0;

      currentIterationDepth = 0;
      currentSearchDepth = 0;
      currentExtraSearchDepth = 0;
      currentRootMove = 0;
      currentRootMoveNumber = 0;
      bestMoveChanges = 0;
      nodesVisited = 0;
      leafPositionsEvaluated = 0;
      nonLeafPositionsEvaluated = 0;
      positionsNonQuiet = 0;
      prunings = 0;
      pvs_root_researches = 0;
      pvs_root_cutoffs = 0;
      pvs_researches = 0;
      pvs_cutoffs = 0;
      tt_Hits = 0;
      tt_Misses = 0;
      tt_Cuts = 0;
      tt_Ignored = 0;
      movesGenerated = 0;
      checkCounter = 0;
      checkMateCounter = 0;
      captureCounter = 0;
      enPassantCounter = 0;
      lastSearchTime = 0;
      mateDistancePrunings = 0;
      minorPromotionPrunings = 0;
      rfpPrunings = 0;
      nullMovePrunings = 0;
      nullMoveVerifications = 0;
      razorReductions = 0;
      lrReductions = 0;
      efpPrunings = 0;
      fpPrunings = 0;
      qfpPrunings = 0;
      lmpPrunings = 0;
      lmrReductions = 0;
      aspirationResearches = 0;
      deltaPrunings = 0;
    }

    @Override
    public String toString() {
      // @formatter:off
      return "SearchCounter{" +
             "nodesVisited=" + nodesVisited +
             ", lastSearchTime=" + DurationFormatUtils.formatDurationHMS(lastSearchTime) +
             ", currentIterationDepth=" + currentIterationDepth +
             ", currentSearchDepth=" + currentSearchDepth +
             ", currentExtraSearchDepth=" + currentExtraSearchDepth +
             ", bestMoveChanges=" + bestMoveChanges +
             ", currentRootMove=" + currentRootMove +
             ", currentRootMoveNumber=" + currentRootMoveNumber +
             ", leafPositionsEvaluated=" + leafPositionsEvaluated +
             ", nonLeafPositionsEvaluated=" + nonLeafPositionsEvaluated +
             ", checkCounter=" + checkCounter +
             ", checkMateCounter=" + checkMateCounter +
             ", captureCounter=" + captureCounter +
             ", enPassantCounter=" + enPassantCounter +
             ", aspirationResearches=" + aspirationResearches +
             ", tt_Hits=" + tt_Hits +
             ", tt_Misses=" + tt_Misses +
             ", tt_Cuts=" + tt_Cuts +
             ", tt_Ignored=" + tt_Ignored +
             ", movesGenerated=" + movesGenerated +
             ", positionsNonQuiet=" + positionsNonQuiet +
             ", pvs_root_researches=" + pvs_root_researches +
             ", pvs_root_cutoffs=" + pvs_root_cutoffs +
             ", pvs_researches=" + pvs_researches +
             ", pvs_cutoffs=" + pvs_cutoffs +
             ", mateDistancePrunings=" + mateDistancePrunings +
             ", minorPromotionPrunings=" + minorPromotionPrunings +
             ", rfpPrunings=" + rfpPrunings +
             ", nullMovePrunings=" + nullMovePrunings +
             ", nullMoveVerifications=" + nullMoveVerifications +
             ", razorReductions=" + razorReductions +
             ", iidSearches=" + iidSearches +
             ", lrReductions=" + lrReductions +
             ", efpPrunings=" + efpPrunings +
             ", fpPrunings=" + fpPrunings +
             ", qfpPrunings=" + qfpPrunings +
             ", lmpPrunings=" + lmpPrunings +
             ", lmrReductions=" + lmrReductions +
             ", deltaPrunings" + deltaPrunings +
             '}';
      // @formatter:on
    }
  }
}
