package artisynth.tools.batchsim;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import com.illposed.osc.OSCListener;
import com.illposed.osc.OSCMessage;
import com.illposed.osc.OSCPortIn;
import com.illposed.osc.OSCPortOut;

import argparser.ArgParseException;
import argparser.ArgParser;
import argparser.BooleanHolder;
import argparser.IntHolder;
import argparser.StringHolder;
import artisynth.core.driver.Main;
import artisynth.core.modelbase.ModelComponent;
import artisynth.core.probes.InputProbe;
import artisynth.core.probes.OutputProbe;
import artisynth.core.probes.WayPoint;
import artisynth.core.probes.WayPointProbe;
import artisynth.core.util.ArtisynthIO;
import artisynth.core.workspace.RootModel;
import artisynth.tools.batchsim.conditions.Condition;
import artisynth.tools.batchsim.conditions.StopConditionMonitor;
import artisynth.tools.batchsim.manager.BatchManager;
import maspack.matrix.NumericalException;
import maspack.properties.Property;
import maspack.properties.PropertyInfo;
import maspack.util.IndentingPrintWriter;
import maspack.util.NumberFormat;
import maspack.util.ReaderTokenizer;

/**
 * {@code BatchWorkerBase} is an abstract class that does most of the
 * heavy-lifting required of a worker subclass. Specifically, this class handles
 * the network communication of a worker client with a {@link BatchManager}
 * server. It also handles, upon receiving a simulation task from the manager,
 * the setting of the corresponding {@link Property} or of the corresponding
 * {@link ModelComponent}'s {@code Property} of the currently-loaded
 * {@link RootModel}.
 * <p>
 * 
 * <p>
 * {@code BatchWorkerBase} implements {@link Runnable}, and so a typical usage
 * pattern is:
 * 
 * <pre>
 * <b>class</b> MyConcreteBatchWorker <b>extends</b> BatchWorkerBase {
 *    // ...
 * }
 * 
 * // ...
 * 
 * String[] argsForMyWorker;
 * // ...
 * <b>new</b> MyConcreteBatchWorker (argsForMyWorker).run ();
 * </pre>
 * 
 * The abstract {@code BatchWorkerBase} class uses the
 * <a href="https://en.wikipedia.org/wiki/Template_method_pattern">template
 * method pattern</a>, which means it leaves "holes" in its methods by calling
 * abstract methods. Concrete (i.e. non-abstract) subclasses should override
 * these methods to provide additional custom behavior to the worker. These
 * methods are, in alphabetical order:
 * 
 * <pre>
 * <b>void</b> {@link #addLogEntry()};               // In run method.
 * <b>void</b> {@link #otherParserOptions()};        // In arg parsing method.
 * <b>void</b> {@link #postSim()};                   // In run method.
 * <b>void</b> {@link #preSim()};                    // In run method.
 * <b>void</b> {@link #recordSimResults()};          // In run method.
 * <b>void</b> {@link #setUpStopConditionMonitor()}; // In run method.
 * <b>void</b> {@link #startLogSession()};           // In run method.
 * </pre>
 * 
 * The {@link #run()} method is where most of these abstract methods get called.
 * Since the {@code run()} method is quite complex, a pseudo-code of the
 * implementation is provided here, so that the order in which these abstract
 * methods get called, and what happens between those method calls, is made very
 * clear. The abstract methods to override are in bold to visually distinguish
 * them from the other pseudo-code.
 * 
 * <pre>
 * run():
 *    <b>startLogSession()</b> // For subclass to override.
 *    <b>setUpStopConditionMonitor()</b> // For subclass to override.
 *    rootModel.addMonitor(stopConditionMonitor)
 *    while true:
 *       requestSimTask()
 *       if currentTask.size() == 0:
 *          break
 *       endIf
 *       success = false
 *       numSimsTilSuccessful = 0
 *       initial = stepSize = RootModel.getMaxStepSize()
 *       while !success && numSimsTilSuccessful < 2:
 *          numSimsTilSuccessful++
 *          main.reset()
 *          main.setMaxStep(stepSize)
 *          setPropVals()
 *          <b>preSim()</b> // For subclass to override.
 *          main.play()
 *          main.waitForStop()
 *          <b>postSim()</b> // For subclass to override.
 *          if stopConditionMonitor.hasAnyConditionBeenMet():
 *             success = true
 *          else:
 *             stepSize /= 10
 *          endIf
 *       endWhile
 *       // By now, tried simulation up to 2 times
 *       main.setMaxStep(initial)
 *       currentTaskSuccessful = success
 *       if !currentTaskSuccessful: // If both attempts failed
 *          stepSize *= 10          // Record the value of the last attempt
 *          numSimsTilSuccess = -1  // Mark as unsuccessful
 *       endIf
 *       <b>recordSimResults()</b> // For subclass to override.
 *       <b>addLogEntry()</b> // For subclass to override.
 *   endWhile
 *   endLogSession()
 * endMethod
 * </pre>
 * 
 * Since these methods are declared <b>{@code abstract}</b> in the abstract
 * {@code BatchWorkerBase} class, a concrete subclass must provide an
 * implementation of these methods (or else the Java compiler will complain).
 * However, most of these methods are not necessary for the proper functioning
 * of {@code BatchWorkerBase} methods, and so can be implemented with a empty
 * body. For example, {@code addLogEnty()} can be implemented like this:
 * 
 * <pre>
 * <b>class</b> MyConcreteBatchWorker <b>extends</b> BatchWorkerBase {
 *    // ...
 *    
 *    {@literal @}Override
 *    <b>public</b> <b>void</b> addLogEntry() {
 *       // Empty method.
 *    }
 *    
 *    // ...
 * }
 * </pre>
 * 
 * The <u>only</u> {@code BatchWorkerBase} abstract method that <b>must</b> have
 * a non-empty body in order for {@code BatchWorkerBase} methods to function
 * properly is {@code setUpStopConditionMonitor()}. When {@code BatchWorkerBase}
 * starts a simulation, it doesn't inherently know when the simulation should
 * stop. Therefore, it simply blocks, waiting for the simulation to stop.
 * However, two subtle issues present themselves here. First, some simulations
 * never stop, because the model being simulated lacks a mechanism (e.g. a
 * breakpoint) to stop it at a certain time. Second, if an error occurs,
 * simulations may stop before they reach their ending breakpoint. For example,
 * a {@link NullPointerException} or an inverted elements
 * {@link NumericalException} may be thrown, causing the simulation to stop
 * prematurely.
 * <p>
 * The solution to these issues comes in the form of {@link Condition
 * Conditions} and a {@link StopConditionMonitor}. Adding a temporary
 * {@code StopConditionMonitor} with one or more appropriate {@code Conditions}
 * to a {@code RootModel} during simulations ensures that all simulations will
 * eventually terminate. It also enables the {@code BatchWorkerBase} to query
 * the {@code StopConditionMonitor} in order to determine if any one particular
 * simulation ended because it reached an appropriate stop {@code Condition}, or
 * if the simulation stopped due to an error (such as an exception being
 * thrown). In the latter case, the simulation will be deemed a failure, and it
 * will automatically be retried with a smaller time step. If this second trial
 * is again deemed a failure, the {@code BatchWorkerBase} will mark that entire
 * simulation task a failure, and proceed to request a new simulation task from
 * the manager.
 * <p>
 * It is therefore crucial that concrete worker subclasses add appropriate stop
 * {@code Conditions} to the {@code BatchWorkerBase}'s
 * {@code StopConditionMonitor}, as otherwise a simulation may either never
 * stop, or else may be deemed a failure once it does stop. Refer to the
 * {@link artisynth.models.batchsim.conditions} package for details on adding
 * {@code Conditions} to a {@code StopConditionMonitor}.
 * <p>
 * Although the {@code BatchWorkerBase} can take command-line arguments to set
 * certain instance variable values, these variables all have useful defaults
 * such that passing <b>{@code null}</b> or an empty <b>{@code String[]}</b> to
 * the {@code BatchWorkerBase} constructor should be sufficient for most use
 * cases. Running the {@code BatchWorkerBase} with the "-help" argument will
 * cause it to print a list of all accepted command-line arguments, as well as a
 * description of what each argument does.
 * 
 * @author Francois Roewer-Despres
 * @version 1.0
 */
public abstract class BatchWorkerBase implements Runnable {

   /**
    * The default base reply port to which a {@link BatchWorkerBase} will try to
    * bind a socket. If the port cannot be bound, the port number is incremented
    * by one, and the process repeats until a port can be bound to a socket.
    */
   public static final int DEFAULT_BASE_REPLY_PORT = 23159;

   /**
    * The name of the directory to which output files will be written by
    * default.
    */
   public static final String DEFAULT_OUTPUT_DIRECTORY_NAME =
      System.getProperty ("user.dir");

   /* Instance variables set either by default, or by command line arguments. */
   protected String myName;
   protected String myOutputDirName;
   protected LinkedList<Double> myRerunList;
   protected PrintWriter myLogFileWriter;
   protected PrintWriter myPropValFileWriter;
   protected int myReplyPort;
   protected String myManagerHostName;
   protected int myManagerRequestPort;

   /* Other instance variables. */
   protected ArgParser myParser;
   protected RootModel myRootModel;
   protected AtomicInteger myReceivedPing;
   public StopConditionMonitor myStopConditionMonitor;
   protected ArrayBlockingQueue<List<String[]>> myTaskQueue;
   protected HashMap<Long,HashMap<Integer,String[]>> myIncomingMsgs;
   protected OSCPortOut myRequestChannel;
   protected OSCPortIn myReplyChannel;
   protected String myHostName;

   /**
    * Creates and sets up a new {@link BatchWorkerBase} ready to start running
    * simulations once its {@link #run()} method is called. Command-line
    * arguments should be passed to this constructor for parsing.
    * 
    * @param args
    * the command-line arguments
    * @throws IllegalStateException
    * if anything goes wrong in the setup that leaves the
    * {@code BatchWorkerBase} unable to {@code run()} properly.
    */
   public BatchWorkerBase (String[] args) throws IllegalStateException {
      try {
         myRerunList = new LinkedList<> ();
         parseArgsAndSetInstanceVars (args);
         myRootModel = Main.getMain ().getRootModel ();
         myStopConditionMonitor = new StopConditionMonitor ();
         myTaskQueue = new ArrayBlockingQueue<> (1);
         myReceivedPing = new AtomicInteger ();
         myIncomingMsgs = new HashMap<> ();
         try {
            myHostName = InetAddress.getLocalHost ().getCanonicalHostName ();
         }
         catch (UnknownHostException e) {
            myHostName =
               InetAddress.getLoopbackAddress ().getCanonicalHostName ();
         }
         requestChannelInit ();
         replyChannelInit ();
      }
      catch (Exception e) {
         e.printStackTrace ();
         throw new IllegalStateException ("BatchWorker: " + e.getMessage ());
      }
   }

   /**
    * This method is called by this {@link BatchWorkerBase} after adding its
    * default options to its {@link ArgParser}, but before said
    * {@code ArgParser} actually begins parsing the provided arguments.
    * <p>
    * Subclasses can <b>optionally</b> override this method to add additional
    * command-line arguments that the {@code BatchWorkerBase} accepts.
    * 
    * @see #parseArgsAndSetInstanceVars(String[])
    */
   protected abstract void otherParserOptions ();

   /**
    * Adds parser options to the {@link ArgParser}, parses the given arguments,
    * and sets all the instance variables that can be set through command-line
    * arguments. In the process, finds a free port and binds a socket to it.
    * <p>
    * Subclasses should set their own instance variables in their constructor
    * right after the call to their super class's constructor returns, as this
    * method is called in {@link BatchWorkerBase}'s constructor.
    * 
    * @param args
    * the command-line arguments for this {@code BatchWorkerBase}
    * @throws ArgParseException
    * if there is an error while parsing {@code args}
    * @throws IllegalStateException
    * if there is an error while setting any of the instance variables
    */
   protected void parseArgsAndSetInstanceVars (String[] args)
      throws ArgParseException, IllegalStateException {
      BooleanHolder helpHolder = new BooleanHolder (false);
      StringHolder myNameHolder = new StringHolder ();
      StringHolder myOutputDirNameHolder =
         new StringHolder (DEFAULT_OUTPUT_DIRECTORY_NAME);
      StringHolder myRerunListHolder = new StringHolder ();
      StringHolder myLogFileNameHolder = new StringHolder ();
      StringHolder myPropValFileNameHolder = new StringHolder ();
      IntHolder myReplyPortHolder = new IntHolder (DEFAULT_BASE_REPLY_PORT);
      StringHolder myManagerHostNameHolder =
         new StringHolder (BatchManager.HOST_NAME);
      IntHolder myManagerRequestPortHolder =
         new IntHolder (BatchManager.DEFAULT_REQUEST_PORT);

      myParser =
         new ArgParser (
            "artisynth -script <some_batch_driver.py> "
            + "[ <script_option_destined_for_batch_worker> ]", false);
      myParser.addOption ("-h, -help %v #print this help message", helpHolder);
      myParser.addOption (
         "-n, -workerName %s #<NAME>#set BatchWorker name to NAME; NAME "
         + "defaults to \"worker<PORT>\", where PORT is supplied by the`-p' "
         + "option (or its default runtime value)", myNameHolder);
      myParser.addOption (
         "-d, -outputDirName %s #<DIRNAME>#set the BatchWorker's "
         + "output directory to DIRNAME; DIRNAME defaults to the current "
         + "working directory", myOutputDirNameHolder);
      myParser.addOption ( // Can't use -s as option b/c ArtiSynth grabs it.
         "-e, -rerunSims %s #<LIST>#if simulations fail, rerun them using the "
         + "time steps listed in LIST; LIST should be in the form of "
         + "\"<double>[[,...],<double>]\" (a comma-separated list of doubles,"
         + "all as a single string); LIST defaults to the empty list, meaning"
         + "simulations are not rerun", myRerunListHolder);
      myParser.addOption (
         "-l, -logFileName %s #<FILENAME>#set the BatchWorker's log file "
         + "name to FILENAME; FILENAME defaults to \"<worker_name>_log.txt\", "
         + "where worker_name is supplied by the `-n' option",
         myLogFileNameHolder);
      myParser.addOption (
         "-f, -propValFileName %s #<FILENAME>#set the BatchWorker's property "
         + "value file name to FILENAME; FILENAME defaults to "
         + "\"<worker_name>_propVal.txt\", where worker_name is supplied by "
         + "the `-n' option", myPropValFileNameHolder);
      myParser.addOption (
         "-p, workerReplyPort %d {[1100," + (Short.MAX_VALUE * 2 - 1)
         + "]}#<PORT>#set the BatchWorker's 'base' reply channel port to "
         + "PORT; note that the actual port used may be different if the port "
         + "PORT is already in use; PORT defaults to "
         + DEFAULT_BASE_REPLY_PORT, myReplyPortHolder);
      myParser.addOption (
         "-m, managerHostName %s #<HOSTNAME>#tell this BatchWorker what the "
         + "BatchManager's host machine's name is by having it set to "
         + "HOSTNAME; HOSTNAME defaults to this BatchWorker's localhost name "
         + "or loopback address name, as it assumes the network connection is "
         + "local", myManagerHostNameHolder);
      myParser.addOption (
         "-r, managerRequestPort %d #<PORT>#tell this BatchWorker what the "
         + "BatchManager's listening-for-requests channel port number is by "
         + "having it set to PORT; the default value is "
         + BatchManager.DEFAULT_REQUEST_PORT, myManagerRequestPortHolder);
      otherParserOptions (); // For subclasses to add their own options.

      if (args != null && args.length > 0) {
         int idx = 0;
         while (idx < args.length) {
            try {
               idx = myParser.matchArg (args, idx);
               String unmatched;
               if ((unmatched = myParser.getUnmatchedArgument ()) != null) {
                  System.out
                     .println ("ignoring unrecognized argument: " + unmatched);
               }
            }
            catch (ArgParseException e) {
               throw new ArgParseException (
                  "error parsing options: " + e.getMessage () + "\n"
                  + myParser.getHelpMessage ());
            }
         }

         if (helpHolder.value) {
            System.out.println (myParser.getHelpMessage ());
         }
      }

      myName = myNameHolder.value;
      myReplyPort = myReplyPortHolder.value;
      myOutputDirName = myOutputDirNameHolder.value;
      myManagerHostName = myManagerHostNameHolder.value;
      myManagerRequestPort = myManagerRequestPortHolder.value;

      if (myRerunListHolder.value != null) {
         for (String num : myRerunListHolder.value.split (",")) {
            try {
               myRerunList.add (Double.valueOf (num));
            }
            catch (NumberFormatException e) {
               throw new ArgParseException (
                  "error parsing rerun list: " + e.getMessage () + "\n"
                  + myParser.getHelpMessage ());
            }
         }
      }

      findAndBindFreePort ();

      if (myName == null) {
         myName = "worker" + myReplyPort;
      }
      if (myLogFileNameHolder.value == null) {
         myLogFileNameHolder.value = myName + "_log.txt";
      }
      if (myPropValFileNameHolder.value == null) {
         myPropValFileNameHolder.value = myName + "_propVal.txt";
      }

      try {
         myLogFileWriter =
            new PrintWriter (
               new BufferedWriter (
                  new FileWriter (
                     new File (myOutputDirName, myLogFileNameHolder.value),
                     true)),
               true);
         myPropValFileWriter =
            new PrintWriter (
               new BufferedWriter (
                  new FileWriter (
                     new File (myOutputDirName, myPropValFileNameHolder.value),
                     false)),
               true);
      }
      catch (IOException e1) {
         throw new IllegalStateException (
            "file error: couldn't open file \"" + myOutputDirName
            + myLogFileNameHolder.value + "\"");
      }
   }

   /**
    * Tries to find a free port to which to bind the reply channel socket. Uses
    * the value of {@link #myReplyPort} as a "base" or "hint" from which to
    * start looking until a free port is found and bound to. Then,
    * {@link #myReplyPort} gets updated to that final bound port.
    * 
    * @throws IllegalStateException
    * if the socket cannot be bound to any port
    */
   protected void findAndBindFreePort () throws IllegalStateException {
      boolean init = false;
      for (int i = myReplyPort; !init && i < Short.MAX_VALUE * 2; i++) {
         try {
            myReplyChannel = new OSCPortIn (i);

            // Only reach here if no exception was thrown.
            init = true;
            myReplyPort = i;
         }
         catch (SocketException e) {
            if (i == Short.MAX_VALUE * 2 - 1) {
               throw new IllegalStateException (
                  "reply channel setup failed: " + e.getMessage ());
            }
            else {
               // Ignore. Try another port.
            }
         }
      }
   }

   /**
    * Sets up the channel from which requests for simulation tasks are sent to
    * the {@link BatchManager}.
    * 
    * @throws RuntimeException
    * if the channel cannot be set up
    */
   protected void requestChannelInit () throws RuntimeException {
      try {
         myRequestChannel =
            new OSCPortOut (
               InetAddress.getByName (myManagerHostName), myManagerRequestPort);
      }
      catch (SocketException | UnknownHostException e) {
         throw new RuntimeException (
            "request channel setup failed: " + e.getMessage ());
      }
   }

   /**
    * Sets up the reply channel for receiving replies (simulation tasks) from
    * the {@link BatchManager}.
    */
   protected void replyChannelInit () {
      OSCListener myListener = new OSCListener () {

         @Override
         public void acceptMessage (Date date, OSCMessage msg) {
            List<String[]> task = new LinkedList<> ();
            boolean enqueue = false;
            if (msg.getAddress ().equals ("reply")) {
               Object[] args = msg.getArguments ();

               // Format of a simulation task message:
               // 0 Task Number
               // 1 Number of property-value pairs in the task
               // 2 Position of the current prop-value pair in the task
               // 3 Property path
               // 4 Current value to which to set the property
               // We have one such message per prop-value pair in the
               // task. The reason we have to break up the task into
               // many small messages is that a Java varargs cannot
               // be very long, and some large tasks may exceed this
               // inherent limit, causing the algorithm to break.
               long taskNo = Long.valueOf ((String)args[0]);
               int taskSize = Integer.valueOf ((String)args[1]);
               int posInTask = Integer.valueOf ((String)args[2]);
               String[] propValPair =
                  new String[] { (String)args[3], (String)args[4] };

               // Since OSC uses UDP/IP, we may get the individual prop-value
               // pairs of a task out of order, and may even receive prop-value
               // pairs from different tasks altogether, although that is
               // unlikely. Just in case, we store the currently received
               // prop-value pairs of a particular task in a taskMap, which maps
               // the position in the task to the prop-value pair. We store the
               // taskMaps of different tasks in another Map, that maps the task
               // number to a taskMap.
               HashMap<Integer,String[]> taskMap = myIncomingMsgs.get (taskNo);
               if (taskMap == null) { // If this is a new task with a new number
                  taskMap = new HashMap<> ();
                  myIncomingMsgs.put (taskNo, taskMap);
               }
               taskMap.put (posInTask, propValPair);

               // If we have received every prop-value pair for this task, we
               // can create a new task from the taskMap, and get rid of the
               // taskMap to free up space in the HashMap.
               if (taskMap.size () == taskSize) {
                  for (int i = 0; i < taskSize; i++) {
                     task.add (taskMap.get (i));
                  }
                  enqueue = true;

                  // Prepend with the task number for future reference.
                  task.add (0, new String[] { String.valueOf (taskNo) });

                  myIncomingMsgs.remove (taskNo);
               }
            }
            else if (msg.getAddress ().equals ("DONE")) {
               // The task variable created above is already the empty task,
               // so there is no need to do anything here, except:
               enqueue = true;
            }
            else if (msg.getAddress ().equals ("ping")) {
               myReceivedPing.getAndIncrement ();
            }
            else { // Shouldn't happen.
               throw new IllegalArgumentException (
                  "unhandled OSCMessage address: " + msg.getAddress ());
            }

            if (enqueue) {
               try {
                  myTaskQueue.put (task);
               }
               catch (InterruptedException e) {
                  // TODO Auto-generated catch block
                  e.printStackTrace ();
               }
            }
         }
      };

      myReplyChannel.addListener ("reply", myListener);
      myReplyChannel.addListener ("DONE", myListener);
      myReplyChannel.addListener ("ping", myListener);
      myReplyChannel.startListening (); // Implicitly in a new thread.

      OSCMessage msg = new OSCMessage ("ping");
      msg.addArgument (myName);
      msg.addArgument (myHostName);
      msg.addArgument (myReplyPort);
      try {
         int counter = 0;
         do {
            counter++;
            myRequestChannel.send (msg);
            try {
               Thread.sleep (10);
            }
            catch (InterruptedException e) {
               // TODO Auto-generated catch block
               e.printStackTrace ();
            }
            if (counter == 100) {
               System.out.print (
                  "Pinging BatchManager at "
                  + InetAddress.getByName (myManagerHostName) + ":"
                  + myManagerRequestPort);
            }
            else if (counter % 100 == 0) {
               System.out.print (".");
            }
         }
         while (myReceivedPing.get () == 0);
         if (counter >= 100) {
            System.out.println ("OK");
         }
      }
      catch (IOException e) {
         throw new RuntimeException ("ping sending failed: " + e.getMessage ());
      }
   }

   /* For subclass to override. */
   /**
    * The default comment character that may appear in a file. Subclasses use
    * this value directly, or may chose to override it.
    */
   protected static String myLogComment = "#";

   /* For subclass to override. */
   /**
    * The default field-separator that may appear in a file. Subclasses use this
    * value directly, or may chose to override it.
    */
   protected static String mySeparator = ",";

   /**
    * This method is called <i>once</i> by this {@link BatchWorkerBase} at the
    * very beginning of its {@link #run()} method, before any simulations are
    * done.
    * <p>
    * Subclasses can <b>optionally</b> override this method to add additional
    * custom set up of the log file or log file writer <i>beyond simply creating
    * either object</i> (as that is already done by this {@code BatchWorkerBase}
    * ) in {@link #parseArgsAndSetInstanceVars(String[])}.
    * 
    * @see #run()
    * @see #parseArgsAndSetInstanceVars(String[])
    */
   protected abstract void startLogSession ();

   /**
    * This method is called <i>once</i> by this {@link BatchWorkerBase} at the
    * very beginning of its {@link #run()} method, before any simulations are
    * done.
    * <p>
    * Subclasses <b>must</b> override this method to add additional custom set
    * up of its {@link StopConditionMonitor} <i>beyond simply creating the
    * object and adding it to the currently-loaded {@link RootModel}</i> (as
    * that is already done by this {@code BatchWorkerBase}).
    * <p>
    * Stop {@link Condition}s can also be dynamically added or removed to its
    * {@code StopConditionMonitor} on a per-simulation basis in the
    * {@link #preSim()} and {@link #postSim()} methods.
    * 
    * 
    * @see #run()
    * @see artisynth.models.batchsim.conditions
    * @see #preSim()
    * @see #postSim()
    */
   protected abstract void setUpStopConditionMonitor ();

   /**
    * Requests a new simulation task from the {@link BatchManager}, and places
    * it in {@link #myCurrentTask} once the reply is received.
    * 
    * @throws RuntimeException
    * if the request fails
    * 
    * @see #run()
    */
   protected void requestSimTask () throws RuntimeException {
      if (myTaskQueue.isEmpty ()) {
         OSCMessage msg = new OSCMessage ("request");
         msg.addArgument (myName);
         msg.addArgument (myHostName);
         msg.addArgument (myReplyPort);
         try {
            myRequestChannel.send (msg);
         }
         catch (IOException e) {
            throw new RuntimeException ("request failed: " + e.getMessage ());
         }
      }

      try {
         myCurrentTask = myTaskQueue.take ();
      }
      catch (InterruptedException e) {
         // TODO Auto-generated catch block
         e.printStackTrace ();
      }

      if (myCurrentTask.isEmpty ()) {
         myTaskCounter = -1;
      }
      else {
         myTaskCounter = Long.valueOf (myCurrentTask.remove (0)[0]);
      }
   }

   /**
    * For each property-value pair in the current task, sets the
    * {@link Property} of the {@link ModelComponent} to the given value.
    * 
    * @throws IllegalStateException
    * if the named {@code ModelComponent} or its {@code Property} does not exit;
    * or if the {@code Property} value is not in the correct format for the
    * {@link PropertyInfo#scanValue(ReaderTokenizer)} method to parse it; or if
    * the value is <b>{@code null}</b>, but the {@code Property}'s
    * {@link PropertyInfo#getNullValueOK()} method returns <b>{@code false}</b>;
    * or if the {@code Property}'s {@link PropertyInfo#isReadOnly()} method
    * returns <b>{@code true}</b>
    * 
    * @see #run()
    */
   protected void setPropVals () throws IllegalStateException {
      for (String[] propValPair : myCurrentTask) {
         Property prop = myRootModel.getProperty (propValPair[0]);
         if (prop == null) {
            throw new IllegalStateException (
               "property error: class \"" + myRootModel.getClass ().getName ()
               + "\" does not have property \"" + propValPair[0] + "\"");
         }

         PropertyInfo info = prop.getInfo ();
         if (info.isReadOnly ()) {
            throw new IllegalStateException (
               "property error: property \"" + propValPair[0]
               + "\" is read-only");
         }

         ReaderTokenizer rtok =
            new ReaderTokenizer (new StringReader (propValPair[1]));
         try {
            prop.set (info.scanValue (rtok));
         }
         catch (IOException e) {
            throw new IllegalStateException (
               "parse error: couldn't coerce string \"" + propValPair[1]
               + "\" into value for property \"" + propValPair[0] + "\"");
         }
      }
   }

   /**
    * A convenience method that can be called to write the current property
    * values using the {@link #myPropValFileWriter}.
    * <p>
    * If {@code writeHeader} is {@code true}, a header is also written
    * containing the property paths.
    *
    * @param writeHeader
    * write a header containing the property paths
    */
   protected void writePropVals (boolean writeHeader) {
      if (writeHeader) {
         writePropOrVal (0);
      }
      writePropOrVal (1);
   }

   /**
    * A convenience method that can be called to write the current property
    * paths OR values using the {@link #myPropValFileWriter}.
    *
    * @param propOrVal
    * write the property paths if 0; write the property values if 1
    */
   private void writePropOrVal (int propOrVal) {
      StringBuilder builder = new StringBuilder ();
      if (propOrVal == 0) {
         builder.append ("TaskNumber");
      }
      else {
         builder.append (myTaskCounter);
      }
      for (String[] propValPair : myCurrentTask) {
         builder.append (mySeparator).append (propValPair[propOrVal]);
      }
      myPropValFileWriter.println (builder);
   }

   /**
    * This method is called once for each simulation, immediately after the
    * {@link RootModel}'s {@link Property}s are set according to the current
    * simulation task (through {@link #setPropVals()}), and immediately before
    * the simulation begins playing. If a simulation fails, this method <b>gets
    * called again</b> when the simulation is re-attempted.
    * <p>
    * Subclasses can <b>optionally</b> override this method to add additional
    * custom pre-simulation behavior. In particular, since this method is called
    * after {@code setPropVals()}, it can be used by a subclass to query the
    * {@code Properties} in order to get their current values. This can be used
    * to then reset the {@code Property} values to some default value, and let
    * an {@link InputProbe} dynamically added to the {@code RootModel} within
    * this method handle the (smoother) transition of the {@code Property} value
    * from its default value to the currently-set value, which is something
    * {@code BatchWorkerBase} does not do. The {@code InputProbe} can then be
    * dynamically removed from the {@code RootModel} in the {@link #postSim()}
    * method.
    * <p>
    * Note that the {@link #myCurrentTask} contains the property-value pairs of
    * the current simulation task, and subclasses can access this list of
    * property-value pairs directly, as long as it is treated as a read-only
    * list.
    * 
    * @see #run()
    * @see #setPropVals()
    * @see #postSim()
    */
   protected abstract void preSim ();

   /**
    * This method is called <i>once</i> for each simulation, immediately after
    * the current simulation has stopped, before anything else happens. If a
    * simulation fails, this method <b>gets called again</b> after the
    * re-attempted simulation ends.
    * <p>
    * Subclasses can <b>optionally</b> override this method to add additional
    * custom post-simulation behavior. In particular, this method can be used to
    * remove {@link InputProbe}s dynamically added to the {@link RootModel} in
    * the {@link #preSim()} method.
    * 
    * @see #run()
    * @see #preSim()
    */
   protected abstract void postSim ();

   /**
    * Writes the existing way points' state to the file
    * <code>{@link #myOutputDirName}/wayPoints/&lt;current_task_number&gt;.dat</code>
    * in binary.
    * 
    * @param addEndStateWayPoint
    * if <b>{@code true}</b>, add a new way point to the {@link RootModel} at
    * the current (i.e. end-of-simulation) time, which will represent the "last"
    * or "end" state of the {@code RootModel}
    */
   protected void recordBinaryWayPoints (boolean addEndStateWayPoint) {
      Main main = Main.getMain ();
      boolean alreadyHasEndWayPoint =
         myRootModel.getWayPoint (main.getTime ()) != null;
      WayPoint wp = null;
      if (!alreadyHasEndWayPoint && addEndStateWayPoint) {
         wp = main.addWayPoint (main.getTime ());
      }
      WayPointProbe probe = myRootModel.getWayPoints ();
      File dir = new File (myOutputDirName, "wayPoints");
      dir.mkdirs ();
      String filename = new File (dir, myTaskCounter + ".dat").getPath ();
      probe.setAttachedFileName (filename);
      probe.save ();

      if (!alreadyHasEndWayPoint && addEndStateWayPoint) {
         myRootModel.removeWayPoint (wp);
      }
   }

   /**
    * For each {@link ModelComponent} in the given array, writes the
    * {@code ModelComponent}'s state (using the
    * {@link ModelComponent#write(PrintWriter, NumberFormat, Object)} method) to
    * the file {@link #myOutputDirName}{@code /ascii/<current_task_number>.txt}
    * in plain text. The {@code ModelComponent} states are written in the order
    * in which they appear in the given array.
    * 
    * @param comps
    * the {@code ModelComponents} whose state should be written
    * @param fmtStr
    * a {@link NumberFormat} string, or <b>{@code null}</b> to use the default
    * format
    */
   protected void recordStateAsASCII (ModelComponent[] comps, String fmtStr) {
      Main main = Main.getMain ();
      File dir = new File (myOutputDirName, "ascii");
      dir.mkdirs ();
      File file = new File (dir, myTaskCounter + ".txt");
      if (comps == null) {
         comps = new ModelComponent[] { myRootModel };
      }
      if (fmtStr == null) {
         fmtStr = main.getModelSaveFormat ();
      }

      IndentingPrintWriter pw = null;
      try {
         pw = ArtisynthIO.newIndentingPrintWriter (file);
         for (ModelComponent comp : comps) {
            comp.write (pw, new NumberFormat (fmtStr), comp);
         }
      }
      catch (IOException e) {
         System.err.println ("Error writing file ascii/" + file.getName ());
      }
      finally {
         pw.close ();
      }
   }

   /**
    * Writes all {@link InputProbe}s, {@link OutputProbe}s, {@link WayPoint}s
    * (and therefore breakpoints) to the file
    * <code>{@link #myOutputDirName}/probes/&lt;current_task_number&gt;.txt</code>
    * in plain text.
    */
   protected void recordAllProbes () {
      File dir = new File (myOutputDirName, "probes");
      dir.mkdirs ();
      File file = new File (dir, myTaskCounter + ".txt");
      try {
         Main.getMain ().saveProbesFile (file);
      }
      catch (IOException e) {
         System.err.println ("Error writing file probes/" + file.getName ());
      }
   }

   /**
    * Near the very end of each simulation task, this method is called to allow
    * a recording of the results of the current simulation task in any file or
    * format. Note that this method is called only once per simulation task. In
    * particular, if a simulation is retried due to failure, this method only
    * gets called only once, after both simulation attempts have been made.
    * <p>
    * Subclasses can <b>optionally</b> override this method to add custom
    * recording of simulation results, and note that no results are otherwise
    * recorded. In particular, subclasses are encouraged to make heavy use of
    * the {@link #myCurrentTask}, {@link #myTaskCounter},
    * {@link #myCurrentTaskSuccessful}, {@link #myLastStepSizeUsed}, and
    * {@link #myNumSimsAttempted} instance variables that get set to appropriate
    * values for each new simulation task, as using these variables in this
    * method <i>is their intended use</i>.
    * <p>
    * Note that this method should be used to record simulation results, not
    * logging/meta- information, which should instead be done in
    * {@link #addLogEntry()}.
    * <p>
    * A number of convenience methods for recording general simulation results
    * already exist in {@link BatchWorkerBase}. These are
    * {@link #recordBinaryWayPoints(boolean)},
    * {@link #recordStateAsASCII(ModelComponent[], String)}, and
    * {@link #recordAllProbes()}.
    * 
    * @see #run()
    * @see #addLogEntry()
    * @see #recordBinaryWayPoints(boolean)
    * @see #recordStateAsASCII(ModelComponent[], String)
    * @see #recordAllProbes()
    */
   protected abstract void recordSimResults ();

   /**
    * The very last method called at the very end of each simulation task, this
    * method is called to allow a recording of logging/meta- information
    * relating to the current simulation task in the log file (using
    * {@link #myLogFileWriter}). Note that this method is called only once per
    * simulation task. In particular, if a simulation is retried due to failure,
    * this method only gets called only once, after both simulation attempts
    * have been made.
    * <p>
    * Subclasses can <b>optionally</b> override this method to add custom data
    * logging, and note that no logging is otherwise performed. In particular,
    * subclasses are encouraged to make heavy use of the {@link #myCurrentTask},
    * {@link #myTaskCounter}, {@link #myCurrentTaskSuccessful},
    * {@link #myLastStepSizeUsed}, and {@link #myNumSimsAttempted} instance
    * variables that get set to appropriate values for each new simulation task,
    * as using these variables in this method <i>is their intended use</i>.
    * <p>
    * Note that this method should be used to record logging/meta- information,
    * not simulation results, which should instead be done in
    * {@link #recordSimResults()}.
    * 
    * @see #startLogSession()
    * @see #run()
    * @see #recordSimResults()
    */
   protected abstract void addLogEntry ();

   /**
    * Called at the very end of {@link #run()}, once all simulation tasks are
    * done, to clean up resources related to the file writers.
    */
   protected void closeWriters () {
      myLogFileWriter.flush ();
      myLogFileWriter.close ();
   }

   @Override
   public void finalize () {
      closeWriters ();
   }

   /**
    * A holder for the current simulation task (the most recent task requested
    * from the {@link BatchManager}). A simulation task is a list of
    * property-value pairs, stored as strings in a 2-element <b>{@code String[]}
    * </b>, where the first element is the {@link Property} path, and the second
    * element is a textual representation of the value to assign to the
    * {@code Property} for the current simulation task.
    * <p>
    * Subclasses are encouraged to access this instance variable, as long as it
    * is treated as a read-only variable.
    */
   protected List<String[]> myCurrentTask;

   /**
    * The task number of the {@link #myCurrentTask}.
    * <p>
    * Subclasses are encouraged to access this instance variable, as long as it
    * is treated as a read-only variable.
    */
   public long myTaskCounter;

   /**
    * <b>{@code true}</b> if, and only if, the {@link #myCurrentTask} was
    * completed successfully, either after 1 or 2 attempts, as determined by the
    * {@link StopConditionMonitor}.
    * <p>
    * Subclasses are encouraged to access this instance variable, as long as it
    * is treated as a read-only variable.
    */
   public boolean myCurrentTaskSuccessful;

   /**
    * The last step sized used when running the {@link #myCurrentTask}. This
    * value starts (before the current simulation begins) by equaling
    * {@link RootModel#getMaxStepSize()}, and <b>may</b> decrease 10-fold to
    * re-run the current simulation once more with a smaller step size if the
    * simulation did not succeed the first time. However, this value will never
    * be smaller than {@link RootModel#getMinStepSize()}.
    * <p>
    * Subclasses are encouraged to access this instance variable, as long as it
    * is treated as a read-only variable.
    */
   protected double myLastStepSizeUsed;

   /**
    * How many times was the {@link #myCurrentTask} attempted.
    * <p>
    * Subclasses are encouraged to access this instance variable, as long as it
    * is treated as a read-only variable.
    */
   protected int myNumSimsAttempted;

   /**
    * After an initial setup, requests simulation tasks from the
    * {@link BatchManager}, sets the {@link RootModel} {@link Property}s
    * according to the received task, and performs the simulation. The model is
    * made to play indefinitely, and this {@link BatchWorkerBase} waits for the
    * simulation to stop. Subclasses should override
    * {@link #setUpStopConditionMonitor()} such that it will cause the
    * simulation to stop when an appropriate stop {@link Condition} is met. If
    * the simulation fails, it is retried once, possibly at a smaller time step
    * (if the {@code RootModel} permits this). Simulations results can then be
    * recorded, after which another simulation task is requested from the
    * manager, and the entire process is repeated. This method returns when the
    * manager indicates that there are no more simulation tasks to perform.
    * 
    * @throws IllegalStateException
    * if any error occurs
    */
   @Override
   public void run () throws IllegalStateException {
      Main main = Main.getMain ();
      startLogSession (); // For subclass to override.
      setUpStopConditionMonitor (); // For subclass to override.
      myRootModel.addMonitor (myStopConditionMonitor);
      try {
         while (true) {
            requestSimTask ();
            if (myCurrentTask.size () == 0) {
               break;
            }

            myNumSimsAttempted = 0;
            boolean success = false;
            double initial = myRootModel.getMaxStepSize ();
            myRerunList.addFirst (initial);
            Iterator<Double> it = myRerunList.iterator ();
            while (!success && it.hasNext ()) {
               myLastStepSizeUsed = it.next ();
               if (myLastStepSizeUsed < myRootModel.getMinStepSize ()) {
                  continue;
               }
               myNumSimsAttempted++;
               boolean resetWorked = false;
               do {
                  try {
                     main.reset ();
                     resetWorked = true;
                  }
                  catch (Exception e) {
                     Thread.sleep (10); // Race condition?
                  }
               }
               while (!resetWorked);
               main.setMaxStep (myLastStepSizeUsed);
               setPropVals ();
               preSim (); // For subclass to override.
               boolean playWorked = false;
               do {
                  try {
                     main.play ();
                     playWorked = true;
                  }
                  catch (Exception e) {
                     Thread.sleep (10); // Race condition?
                  }
               }
               while (!playWorked);
               main.waitForStop ();
               postSim (); // For subclass to override.
               if (myStopConditionMonitor.hasAnyConditionBeenMet ()) {
                  success = true;
               }
            }
            main.setMaxStep (initial);
            myRerunList.removeFirst ();
            myCurrentTaskSuccessful = success;
            recordSimResults (); // For subclass to override.
            addLogEntry (); // For subclass to override.
         }
      }
      catch (Exception e) {
         e.printStackTrace ();
         throw new IllegalStateException (e.getMessage ());
      }
      finally {
         closeWriters ();
      }
   }

}
