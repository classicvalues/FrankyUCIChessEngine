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

package fko.UCI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.Semaphore;

/**
 * UCIProtocolHandler
 *<p>
 * Handles all communication related to the UCI protocol and interacts with a IUCIEngine to
 * handle engine searches
 */
public class UCIProtocolHandler implements Runnable, IUCIProtocolHandler {

  private static final Logger LOG    = LoggerFactory.getLogger(UCIProtocolHandler.class);
  private static final Logger COMLOG = LoggerFactory.getLogger("ComLog");


  // back reference to the engine
  private final IUCIEngine uciEngine;

  // input and outputStream of command stream
  private final InputStream  inputStream;
  private final OutputStream outputStream;

  // -- the handler runs in a separate thread --
  private Thread myThread = null;
  private Semaphore startUpGate = new Semaphore(0, true);

  private Semaphore   externalSynchWaiter;
  private boolean     running = false;
  private PrintStream outputStreamPrinter;


  /**
   * Default contructor
   *
   * <p>Uses System.in and System.out as input and output streams.
   *
   * @param uciEngine
   */
  public UCIProtocolHandler(final IUCIEngine uciEngine) {
    this(uciEngine, System.in, System.out);
  }

  /**
   * Contructor for unit testing
   *
   * @param uciEngine
   * @param in
   * @param out
   */
  public UCIProtocolHandler(final IUCIEngine uciEngine, InputStream in, OutputStream out) {
    this.uciEngine = uciEngine;
    this.inputStream = in;
    this.outputStream = out;

    uciEngine.registerProtocolHandler(this);
    outputStreamPrinter = new PrintStream(outputStream);
  }

  /**
   * Start a new thread to handle protocol<br>
   * The thread then calls run() to actually do the work.
   */
  public void startHandler() {
    running = true;
    // Now start the thread
    if (myThread == null) {
      myThread = new Thread(this, "Protocol Handler");
      myThread.start();
    } else {
      throw new IllegalStateException("startHandler(): Handler thread already exists.");
    }
  }

  /**
   * Stops the playroom thread and the running game.<br>
   */
  public void stopHandler() {
    if (myThread == null || !myThread.isAlive() || !running) {
      throw new IllegalStateException("stopHandler(): Handler thread is not running");
    }
    running = false;
    myThread.interrupt();
  }

  /**
   * Loop for protocol scanning
   */
  @Override
  public void run() {

    final BufferedReader in = new BufferedReader(new InputStreamReader(inputStream));

    while (running) {

      try {

        LOG.debug("Listening...");

        // wait until a line is ready to be read
        final String readLine = in.readLine();

        // Input is gone - probably application quit
        if (readLine == null) {
          COMLOG.info(">> {}", "EOF");
          break;
        }

        COMLOG.info(">> {}", readLine);

        // Scanner parses the line to tokenize it
        Scanner scanner = new Scanner(readLine);
        if (scanner.hasNext()) {

          String command = scanner.next().toLowerCase();

          // interpreting the command
          switch (command) {
            case "uci":
              commandUCI(scanner);
              break;
            case "isready":
              commandIsReady(scanner);
              break;
            case "setoption":
              commandSetOption(scanner);
              break;
            case "ucinewgame":
              commandUciNewGame(scanner);
              break;
            case "position":
              commandPosition(scanner);
              break;
            case "go":
              commandGo(scanner);
              break;
            case "stop":
              commandStop(scanner);
              break;
            case "ponderhit":
              commandPonderhit(scanner);
              break;
            case "register":
              commandRegister(scanner);
              break;
            case "debug":
              commandDebug(scanner);
              break;
            case "quit":
              commandQuit(scanner);
              break;
            default:
              LOG.warn("UCI Protocol Unknown Command: " + command);
              break;
          }
        }
        waiterResume();
        LOG.debug("Finished processing.");
      } catch (IOException ex) {
        LOG.error("IO Exception at buffered read!!", ex);
        System.exit(-1);
      }
    }
    LOG.debug("Quitting");
  }

  private void commandUCI(final Scanner scanner) {
    send("id name " + uciEngine.getIDName());
    send("id author " + uciEngine.getIDAuthor());

    List<IUCIEngine.IUCIOption> uciOptionList = uciEngine.getOptions();
    for (IUCIEngine.IUCIOption option : uciOptionList) {
      String optionString = "option name " + option.getNameID();
      optionString += " type " + option.getOptionType().name();
      optionString += " default " + option.getDefaultValue();
      if (!option.getMinValue().equals("")) optionString += " min " + option.getMinValue();
      if (!option.getMaxValue().equals("")) optionString += " max " + option.getMaxValue();
      if (!option.getVarValue().equals("")) {
        for (String v : option.getVarValue().split(" ")) {
          optionString += " var " + v;
        }
      }
      send(optionString);
    }

    send("uciok");
  }

  private void commandIsReady(final Scanner scanner) {
    if (uciEngine.isReady()) send("readyok");
  }

  private void commandSetOption(final Scanner scanner) {
    // setoption name Hash value 32

    String token = "";
    String name;
    if (scanner.hasNext() && (token=scanner.next()).equals("name")) {
      if (scanner.hasNext()) {
        name = scanner.next();
      } else {
        LOG.error("Command setoption is malformed - expected name");
        return;
      }
    } else {
      LOG.error("Command setoption is malformed - expected token name received: " + token);
      return;
    }

    String value = "";
    if (scanner.hasNext()) {
      if ((token = scanner.next()).equals("value")) {
        if (scanner.hasNext()) {
          value = scanner.next();
        } else {
          LOG.error("Command setoption is malformed - expected value");
          return;
        }
      } else if (token.equals("Hash")) {
        name += " Hash";
      } else {
        LOG.error("Command setoption is malformed - expected token value received: " + token);
        return;
      }
    }

    uciEngine.setOption(name, value);
  }

  private void commandUciNewGame(final Scanner scanner) {
    uciEngine.newGame();
  }

  private void commandDebug(final Scanner scanner) {
    final String keyValue = scanner.next();
    if (keyValue.equals("on")) {
      uciEngine.setDebugMode(true);
    } else if (keyValue.equals("off")) {
      uciEngine.setDebugMode(false);
    } else {
      LOG.error("Command debug is malformed - expected key value received: " + keyValue);
    }
  }

  private void commandRegister(final Scanner scanner) {
    LOG.warn("UCI Protocol Command: register not implemented!");
  }

  private void commandPosition(final Scanner scanner) {
    String startFen = START_FEN;
    String token = scanner.next();
    if (token.equals("fen")) {
      startFen = "";
      while (scanner.hasNext() && !(token = scanner.next()).equals("moves")) {
        startFen += token + " ";
      }
    } else if (token.equals("startpos")) {
      startFen = START_FEN;
    }
    List<String> moves = new ArrayList<>();
    if (token.equals("moves") || (scanner.hasNext() && (token = scanner.next()).equals("moves"))) {
      while (scanner.hasNext()) {
        token = scanner.next();
        moves.add(token);
      }
    }
    uciEngine.setPosition(startFen);
    for (String move : moves) {
      uciEngine.doMove(move);
    }

  }

  private void commandGo(final Scanner scanner) {

    IUCISearchMode searchMode = new UCISearchMode();

    while (scanner.hasNext()) {
      String token = scanner.next();
      switch (token) {
        case "searchmoves": // needs to last token in command
          while (scanner.hasNext()) {
            token = scanner.next();
            searchMode.getMoves().add(token);
          }
          break;
        case "ponder":
          searchMode.setPonder(true);
          break;
        case "wtime":
          searchMode.setWhiteTime(Integer.valueOf(scanner.next()));
          break;
        case "btime":
          searchMode.setBlackTime(Integer.valueOf(scanner.next()));
          break;
        case "winc":
          searchMode.setWhiteInc(Integer.valueOf(scanner.next()));
          break;
        case "binc":
          searchMode.setBlackInc(Integer.valueOf(scanner.next()));
          break;
        case "movestogo":
          searchMode.setMovesToGo(Integer.valueOf(scanner.next()));
          break;
        case "depth":
          searchMode.setDepth(Integer.valueOf(scanner.next()));
          break;
        case "nodes":
          searchMode.setNodes(Long.valueOf(scanner.next()));
          break;
        case "mate":
          searchMode.setMate(Integer.valueOf(scanner.next()));
          break;
        case "movetime":
          searchMode.setMoveTime(Integer.valueOf(scanner.next()));
          break;
        case "infinite":
          searchMode.setInfinite(true);
          break;
        case "perft":
          searchMode.setPerft(true);
          searchMode.setDepth(Integer.valueOf(scanner.next()));
          break;
        default:
          LOG.error("Command go is malformed - expected known go subcommand but received " + token);
          break;
      }
    }

    uciEngine.startSearch(searchMode);
  }

  private void commandStop(final Scanner scanner) {
    uciEngine.stopSearch();
  }

  private void commandPonderhit(final Scanner scanner) {
    uciEngine.ponderHit();
  }

  private void commandQuit(final Scanner scanner) {
    LOG.info("Received quit command");
    stopHandler();
  }

  private void waiterResume() {
    if (externalSynchWaiter != null) {
      externalSynchWaiter.release();
    }
  }

  void setSemaphoreForSynchronization(Semaphore waiter) {
    this.externalSynchWaiter = waiter;
  }

  /**
   * @return true if handler is running the protocol loop
   */
  public boolean isRunning() {
    return running;
  }

  private void send(final String msg) {
    COMLOG.info("<< {}", msg);
    outputStreamPrinter.println(msg);
  }

  @Override
  public void sendInfoToUCI(String msg) {
    send("info " + msg);
  }

  @Override
  public void sendInfoStringToUCI(final String msg) {
    send("info string " + msg);
  }

  @Override
  public void sendResultToUCI(String result) {
    send("bestmove " + result);
  }

  @Override
  public void sendResultToUCI(String result, String ponder) {
    send("bestmove " + result + " ponder " + ponder);
  }
}
