/**
 * Copyright (C) 2013 Carnegie Mellon University
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package tdb.reef;

import org.apache.reef.client.ClientConfiguration;
import org.apache.reef.runtime.local.client.LocalRuntimeConfiguration;
import org.apache.reef.runtime.yarn.client.YarnClientConfiguration;
import org.apache.reef.tang.Configuration;
import org.apache.reef.tang.Injector;
import org.apache.reef.tang.JavaConfigurationBuilder;
import org.apache.reef.tang.Tang;
import org.apache.reef.tang.annotations.Name;
import org.apache.reef.tang.annotations.NamedParameter;
import org.apache.reef.tang.exceptions.BindException;
import org.apache.reef.tang.exceptions.InjectionException;
import org.apache.reef.tang.formats.AvroConfigurationSerializer;
import org.apache.reef.tang.formats.CommandLine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.logging.Level;
import java.util.logging.Logger;

import tdb.reef.param.Memory;

/**
 * TDB REEF application launcher - main class.
 */
public final class Main {

  /**
   * Number of REEF worker threads in local mode.
   */
  private static final int NUM_LOCAL_THREADS = 4;
  /**
   * Standard Java logger
   */
  private static final Logger LOG =
      Logger.getLogger(Main.class.getName());

  private static final BufferedReader prompt =
    new BufferedReader(new InputStreamReader(System.in));

  /**
   * This class should not be instantiated.
   */
  private Main() {
    throw new RuntimeException("Do not instantiate this class!");
  }

  /**
   * Parse the command line arguments.
   *
   * @param args command line arguments, as passed to main()
   * @return Configuration object.
   * @throws BindException configuration error.
   * @throws IOException   error reading the configuration.
   */
  private static Configuration parseCommandLine(final String[] args)
      throws BindException, IOException {
    final JavaConfigurationBuilder confBuilder =
        Tang.Factory.getTang().newConfigurationBuilder();
    final CommandLine cl = new CommandLine(confBuilder);
    cl.registerShortNameOfClass(Main.Local.class);
    cl.registerShortNameOfClass(Main.NumWorkers.class);
    cl.registerShortNameOfClass(Main.Timeout.class);
    cl.registerShortNameOfClass(Memory.class);
    cl.processCommandLine(args);
    return confBuilder.build();
  }

  private static Configuration cloneCommandLineConfiguration(
      final Configuration commandLineConf)
          throws InjectionException, BindException {
    final Injector injector =
        Tang.Factory.getTang().newInjector(commandLineConf);
    final JavaConfigurationBuilder cb =
        Tang.Factory.getTang().newConfigurationBuilder();
    cb.bindNamedParameter(Main.NumWorkers.class,
        String.valueOf(
            injector.getNamedInstance(Main.NumWorkers.class)));
    cb.bindNamedParameter(Main.Timeout.class,
        String.valueOf(injector.getNamedInstance(Main.Timeout.class)));
    cb.bindNamedParameter(Memory.class,
        String.valueOf(injector.getNamedInstance(Memory.class)));
    return cb.build();
  }

  /**
   * Parse command line arguments and create TANG configuration ready to be
   * submitted to REEF.
   *
   * @param args Command line arguments, as passed into main().
   * @return (immutable) TANG Configuration object.
   * @throws BindException      if configuration commandLineInjector fails.
   * @throws InjectionException if configuration commandLineInjector fails.
   * @throws IOException        error reading the configuration.
   */
  private static Configuration getClientConfiguration(final String[] args)
      throws BindException, InjectionException, IOException {

    final Configuration commandLineConf = parseCommandLine(args);

    final Configuration clientConfiguration = ClientConfiguration.CONF
        .set(ClientConfiguration.ON_JOB_RUNNING,
            Client.RunningJobHandler.class)
        .set(ClientConfiguration.ON_JOB_MESSAGE,
            Client.JobMessageHandler.class)
        .set(ClientConfiguration.ON_JOB_COMPLETED,
            Client.CompletedJobHandler.class)
        .set(ClientConfiguration.ON_JOB_FAILED,
            Client.FailedJobHandler.class)
        .set(ClientConfiguration.ON_RUNTIME_ERROR,
            Client.RuntimeErrorHandler.class)
        .build();

    final Injector commandLineInjector =
        Tang.Factory.getTang().newInjector(commandLineConf);
    final boolean isLocal =
        commandLineInjector.getNamedInstance(Main.Local.class);
    final Configuration runtimeConfiguration;
    if (isLocal) {
      LOG.log(Level.INFO, "Running on the local runtime");
      runtimeConfiguration = LocalRuntimeConfiguration.CONF
          .set(LocalRuntimeConfiguration.NUMBER_OF_THREADS, NUM_LOCAL_THREADS)
          .build();
    } else {
      LOG.log(Level.INFO, "Running on YARN");
      runtimeConfiguration = YarnClientConfiguration.CONF.build();
    }

    return Tang.Factory.getTang()
        .newConfigurationBuilder(runtimeConfiguration, clientConfiguration,
            cloneCommandLineConfiguration(commandLineConf))
        .build();
  }

  /**
   * Main method that starts the Retained Evaluators job.
   *
   * @return a string that contains last results from all evaluators.
   */
  public static void run(final Configuration config)
      throws InjectionException {
    final Injector injector = Tang.Factory.getTang().newInjector(config);
    final Client client = injector.getInstance(Client.class);
    client.submit();
    //return client.waitForCompletion();

    String cmd;
    while (client.isBusy) {
      try {
        do {
          System.out.print("\n");
          cmd = prompt.readLine();
        } while (cmd != null && cmd.trim().isEmpty());
      } catch (final IOException ex) {
        LOG.log(Level.FINE, "Error reading from stdin: {0}", ex);
        cmd = null;
      }
      client.processCmd(cmd);
    }
  }

  /**
   * Main method that starts the Retained Evaluators job.
   *
   * @param args command line parameters.
   */
  public static void main(final String[] args) {
    try {
      final Configuration config = getClientConfiguration(args);
      LOG.log(Level.FINEST, "Configuration:\n--\n{0}--",
          new AvroConfigurationSerializer().toString(config));
      run(config);
      LOG.info("Done!");
    } catch (final BindException | InjectionException | IOException ex) {
      LOG.log(Level.SEVERE, "Job configuration error", ex);
    }
    return;
  }

  /**
   * Command line parameter: number of evaluators to allocate.
   */
  @NamedParameter(doc = "Number of workers",
      short_name = "numWorkers", default_value = "2")
  public static final class NumWorkers implements Name<Integer> {
  }

  /**
   * Command line parameter = true to run locally, or false to run on YARN.
   */
  @NamedParameter(doc = "Whether or not to run on the local runtime",
      short_name = "local", default_value = "true")
  public static final class Local implements Name<Boolean> {
  }

  /**
   * Command line parameter = system timeout in minutes.
   */
  @NamedParameter(
      doc = "Timeout (in minutes), after which the REEF app will terminate",
      short_name = "timeout",
      default_value = "10")
  public static final class Timeout implements Name<Integer> {
  }
}