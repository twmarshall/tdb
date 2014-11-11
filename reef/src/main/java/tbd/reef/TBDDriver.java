package tbd.reef;

import com.microsoft.reef.driver.task.RunningTask;
import com.microsoft.reef.driver.context.ActiveContext;
import com.microsoft.reef.driver.context.ContextConfiguration;
import com.microsoft.reef.driver.evaluator.AllocatedEvaluator;
import com.microsoft.reef.driver.evaluator.EvaluatorRequest;
import com.microsoft.reef.driver.evaluator.EvaluatorRequestor;
import com.microsoft.reef.driver.task.TaskConfiguration;
import com.microsoft.tang.JavaConfigurationBuilder;
import com.microsoft.tang.Tang;
import com.microsoft.tang.annotations.Name;
import com.microsoft.tang.annotations.NamedParameter;
import com.microsoft.tang.annotations.Unit;
import com.microsoft.wake.EventHandler;
import com.microsoft.wake.time.event.StartTime;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.inject.Inject;

@Unit
public final class TBDDriver {
  private static final Logger LOG = Logger.getLogger(TBDDriver.class.getName());
  private final EvaluatorRequestor requestor;

  private final int numWorkers=2;
  private final int numEvaluators=numWorkers+1;
  private int numEvalAlloced = 0;
  private int numWorkerContexts = 0;
  private boolean masterEvalAlloced = false;
  private String masterIP = "";
  private final Integer masterPort = 2555;
  private String masterAkka = "";

  private Map<String, ActiveContext> contexts = new HashMap<String, ActiveContext>();
  private Map<String, String> ctxt2ip = new HashMap<String, String>();
  private Map<String, Integer> ctxt2port = new HashMap<String, Integer>();

  @NamedParameter(doc = "IP address", short_name = "ip", default_value = "127.0.0.1")
  final class HostIP implements Name<String> {
  }

  @NamedParameter(doc = "port number", short_name = "port", default_value = "2555")
  final class HostPort implements Name<String> {
  }

  @NamedParameter(doc = "master akka", short_name = "master", default_value = "akka.tcp://masterSystem0@127.0.0.1:2555/user/master")
  final class MasterAkka implements Name<String> {
  }

  /**
   * Job driver constructor - instantiated via TANG.
   *
   * @param requestor evaluator requestor object used to create new evaluator containers.
   */
  @Inject
  public TBDDriver(final EvaluatorRequestor requestor) {
    this.requestor = requestor;
    LOG.log(Level.INFO, "Instantiated 'TBDDriver'");
  }

  /**
   * Handles the StartTime event: Request Evaluators.
   */
  public final class StartHandler implements EventHandler<StartTime> {
    @Override
    public void onNext(final StartTime startTime) {
      LOG.log(Level.INFO, "TIME: Start Driver.");

      TBDDriver.this.requestor.submit(EvaluatorRequest.newBuilder()
          .setNumber(numEvaluators)
          .setMemory(4096)
          .setNumberOfCores(2)
          .build());
      LOG.log(Level.INFO, "Requested Evaluators.");
    }
  }

  /**
   * Handles AllocatedEvaluator: Submit the Task
   */
  public final class EvaluatorAllocatedHandler implements EventHandler<AllocatedEvaluator> {
    @Override
    public void onNext(final AllocatedEvaluator allocatedEvaluator) {
      LOG.log(Level.INFO, "TIME: Evaluator Allocated {0}", allocatedEvaluator.getId());
      LOG.log(Level.INFO, "Socket address {0}", allocatedEvaluator.getEvaluatorDescriptor().getNodeDescriptor().getInetSocketAddress());

      final String socketAddr = allocatedEvaluator.getEvaluatorDescriptor().getNodeDescriptor().getInetSocketAddress().toString();
      final String hostIP = socketAddr.substring(socketAddr.indexOf("/")+1, socketAddr.indexOf(":"));
      final int nEval;
      final boolean masterEval;
      final boolean workerEval;
      final boolean legalEval;

      synchronized (TBDDriver.this) {
        masterEval = !masterEvalAlloced;
        workerEval = masterEvalAlloced && (numEvalAlloced < numEvaluators);
        legalEval = masterEval || workerEval;
        if (masterEval) {
          ++numEvalAlloced;
          masterEvalAlloced = true;
          masterIP = hostIP;
          masterAkka = "akka.tcp://masterSystem0@" + hostIP + ":" + masterPort + "/user/master";
        } else if (workerEval) {
          ++numEvalAlloced;
        }
        nEval = numEvalAlloced;
      }

      if (legalEval) {
        String contextId = "";
        if (masterEval) {
          contextId = String.format("context_master_%06d", nEval-1);
        } else if (workerEval) {
          contextId = String.format("context_worker_%06d", nEval-1);
        }
        ctxt2ip.put(contextId, hostIP);
        ctxt2port.put(contextId, masterPort+nEval-1);

        final JavaConfigurationBuilder contextConfigBuilder = Tang.Factory.getTang().newConfigurationBuilder();
        contextConfigBuilder.addConfiguration(ContextConfiguration.CONF
            .set(ContextConfiguration.IDENTIFIER, contextId)
            .build());
        allocatedEvaluator.submitContext(contextConfigBuilder.build());
        LOG.log(Level.INFO, "Submit context {0} to evaluator {1}", new Object[]{contextId, allocatedEvaluator.getId()});
      } else {
        LOG.log(Level.INFO, "Close Evaluator {0}", allocatedEvaluator.getId());
        allocatedEvaluator.close();
      }
    }
  }

  /**
   * Receive notification that the Context is active.
   */
  public final class ActiveContextHandler implements EventHandler<ActiveContext> {
    @Override
    public void onNext(final ActiveContext context) {
      LOG.log(Level.INFO, "TIME: Active Context {0}", context.getId());

      final String contextId = context.getId();
      final String character = contextId.split("_")[1];
      final boolean masterCtxt = character.equals("master");
      final boolean workerCtxt = character.equals("worker");

      synchronized (TBDDriver.this) {
        if (masterCtxt) {
        } else if (workerCtxt){
          ++numWorkerContexts;
        }
      }

      if (masterCtxt) {
        contexts.put(contextId, context);
        final String taskId = String.format("task_master_%06d", 0);
        
        final JavaConfigurationBuilder cb = Tang.Factory.getTang().newConfigurationBuilder();
        cb.addConfiguration(
            TaskConfiguration.CONF
                .set(TaskConfiguration.IDENTIFIER, taskId)
                .set(TaskConfiguration.TASK, TBDMasterTask.class)
                .build()
        );
        cb.bindNamedParameter(HostIP.class, masterIP);
        cb.bindNamedParameter(HostPort.class, masterPort.toString());
        context.submitTask(cb.build());
        LOG.log(Level.INFO, "Submit {0} to context {1}", new Object[]{taskId, contextId});
      } else if (workerCtxt) {
        contexts.put(contextId, context);
        LOG.log(Level.INFO, "Context active: {0}", contextId);
      } else {
        LOG.log(Level.INFO, "Close context {0} : {1}", new Object[]{contextId.split("_")[1], contextId});
        context.close();
      }
    }
  }

  /**
   * Receive notification that the Task is running.
   */
  public final class RunningTaskHandler implements EventHandler<RunningTask> {
    @Override
    public void onNext(final RunningTask task) {
      LOG.log(Level.INFO, "TIME: Running Task {0}", task.getId());
      
      final String contextId = task.getActiveContext().getId();
      final String character = contextId.split("_")[1];
      
      if (character.equals("worker")) {
        return;
      } else if (character.equals("master")) {
        waitAndSubmitWorkerTasks();
      }
    }
  }

  private void waitAndSubmitWorkerTasks() {
    if (numWorkerContexts == numWorkers) {
      for (Entry<String, ActiveContext> e : contexts.entrySet()) {
        String contextId = e.getKey();
        ActiveContext context = e.getValue();
        if (contextId.startsWith("context_worker")) {
          final String taskId = contextId.replaceFirst("context", "task");
          final JavaConfigurationBuilder cb = Tang.Factory.getTang().newConfigurationBuilder();
          cb.addConfiguration(
              TaskConfiguration.CONF
                  .set(TaskConfiguration.IDENTIFIER, taskId)
                  .set(TaskConfiguration.TASK, TBDWorkerTask.class)
                  .build()
          );
          cb.bindNamedParameter(HostIP.class, ctxt2ip.get(contextId));
          cb.bindNamedParameter(HostPort.class, ctxt2port.get(contextId).toString());
          cb.bindNamedParameter(MasterAkka.class, masterAkka);
          context.submitTask(cb.build());
          LOG.log(Level.INFO, "Submit {0} to context {1}", new Object[]{taskId, contextId});
        }
      }
    } else {
      LOG.log(Level.INFO, "Sleep: wait worker contexts");
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        e.printStackTrace();
        LOG.log(Level.INFO, "Sleep exception.");
      }
      waitAndSubmitWorkerTasks();
    }
  }
}