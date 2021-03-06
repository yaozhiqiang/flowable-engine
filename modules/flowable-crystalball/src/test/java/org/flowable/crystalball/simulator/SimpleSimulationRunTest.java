package org.flowable.crystalball.simulator;

import org.flowable.crystalball.simulator.delegate.event.Function;
import org.flowable.crystalball.simulator.delegate.event.impl.DeploymentCreateTransformer;
import org.flowable.crystalball.simulator.delegate.event.impl.InMemoryRecordFlowableEventListener;
import org.flowable.crystalball.simulator.delegate.event.impl.ProcessInstanceCreateTransformer;
import org.flowable.crystalball.simulator.delegate.event.impl.UserTaskCompleteTransformer;
import org.flowable.crystalball.simulator.impl.DeployResourcesEventHandler;
import org.flowable.crystalball.simulator.impl.EventRecorderTestUtils;
import org.flowable.crystalball.simulator.impl.RecordableProcessEngineFactory;
import org.flowable.crystalball.simulator.impl.SimulationProcessEngineFactory;
import org.flowable.crystalball.simulator.impl.StartProcessByIdEventHandler;
import org.flowable.crystalball.simulator.impl.clock.DefaultClockFactory;
import org.flowable.crystalball.simulator.impl.clock.ThreadLocalClock;
import org.flowable.crystalball.simulator.impl.playback.PlaybackUserTaskCompleteEventHandler;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.ProcessEngines;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.common.api.delegate.event.FlowableEvent;
import org.flowable.engine.common.impl.util.DefaultClockImpl;
import org.flowable.engine.common.runtime.Clock;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.history.HistoricTaskInstance;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.engine.impl.el.NoExecutionVariableScope;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.task.Task;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/**
 * @author martin.grofcik
 */
public class SimpleSimulationRunTest {
    // deployment created
    private static final String DEPLOYMENT_CREATED_EVENT_TYPE = "DEPLOYMENT_CREATED_EVENT";
    private static final String DEPLOYMENT_RESOURCES_KEY = "deploymentResources";

    // Process instance start event
    private static final String PROCESS_INSTANCE_START_EVENT_TYPE = "PROCESS_INSTANCE_START";
    private static final String PROCESS_DEFINITION_ID_KEY = "processDefinitionId";
    private static final String VARIABLES_KEY = "variables";
    // User task completed event
    private static final String USER_TASK_COMPLETED_EVENT_TYPE = "USER_TASK_COMPLETED";

    private static final String BUSINESS_KEY = "testBusinessKey";

    public static final String TEST_VALUE = "TestValue";
    public static final String TEST_VARIABLE = "testVariable";

    private static final String USERTASK_PROCESS = "org/flowable/crystalball/simulator/impl/playback/PlaybackProcessStartTest.testUserTask.bpmn20.xml";

    protected InMemoryRecordFlowableEventListener listener;

    @Before
    public void initListener() {
        listener = new InMemoryRecordFlowableEventListener(getTransformers());
    }

    @After
    public void cleanupListener() {
        listener = null;
    }

    @Test
    public void testStep() throws Exception {

        recordEvents();

        SimulationDebugger simDebugger = createDebugger();

        simDebugger.init(new NoExecutionVariableScope());

        RuntimeService runtimeService = SimulationRunContext.getRuntimeService();
        TaskService taskService = SimulationRunContext.getTaskService();
        HistoryService historyService = SimulationRunContext.getHistoryService();

        // debugger step - deploy processDefinition
        simDebugger.step();
        step0Check(SimulationRunContext.getRepositoryService());

        // debugger step - start process and stay on the userTask
        simDebugger.step();
        step1Check(runtimeService, taskService);

        // debugger step - complete userTask and finish process
        simDebugger.step();
        step2Check(runtimeService, taskService);

        checkStatus(historyService);

        simDebugger.close();
        ProcessEngines.destroy();
    }

    private void step2Check(RuntimeService runtimeService, TaskService taskService) {
        ProcessInstance procInstance = runtimeService.createProcessInstanceQuery().active().processInstanceBusinessKey("oneTaskProcessBusinessKey").singleResult();
        assertNull(procInstance);
        Task t = taskService.createTaskQuery().active().taskDefinitionKey("userTask").singleResult();
        assertNull(t);
    }

    @Test
    public void testRunToTime() throws Exception {

        recordEvents();

        SimulationDebugger simDebugger = createDebugger();

        simDebugger.init(new NoExecutionVariableScope());

        RuntimeService runtimeService = SimulationRunContext.getRuntimeService();
        TaskService taskService = SimulationRunContext.getTaskService();
        HistoryService historyService = SimulationRunContext.getHistoryService();

        simDebugger.runTo(0);
        ProcessInstance procInstance = runtimeService.createProcessInstanceQuery().active().processInstanceBusinessKey("oneTaskProcessBusinessKey").singleResult();
        assertNull(procInstance);

        // debugger step - deploy process
        simDebugger.runTo(1);
        step0Check(SimulationRunContext.getRepositoryService());

        // debugger step - start process and stay on the userTask
        simDebugger.runTo(1001);
        step1Check(runtimeService, taskService);

        // process engine should be in the same state as before
        simDebugger.runTo(2000);
        step1Check(runtimeService, taskService);

        // debugger step - complete userTask and finish process
        simDebugger.runTo(2500);
        step2Check(runtimeService, taskService);

        checkStatus(historyService);

        simDebugger.close();
        ProcessEngines.destroy();
    }

    @Test(expected = RuntimeException.class)
    public void testRunToTimeInThePast() throws Exception {

        recordEvents();
        SimulationDebugger simDebugger = createDebugger();
        simDebugger.init(new NoExecutionVariableScope());
        try {
            simDebugger.runTo(-1);
            fail("RuntimeException expected - unable to execute event from the past");
        } finally {
            simDebugger.close();
            ProcessEngines.destroy();
        }
    }

    @Test
    public void testRunToEvent() throws Exception {

        recordEvents();
        SimulationDebugger simDebugger = createDebugger();
        simDebugger.init(new NoExecutionVariableScope());
        try {
            simDebugger.runTo(USER_TASK_COMPLETED_EVENT_TYPE);
            step1Check(SimulationRunContext.getRuntimeService(), SimulationRunContext.getTaskService());
            simDebugger.runContinue();
        } finally {
            simDebugger.close();
            ProcessEngines.destroy();
        }
    }

    @Test(expected = RuntimeException.class)
    public void testRunToNonExistingEvent() throws Exception {

        recordEvents();
        SimulationDebugger simDebugger = createDebugger();
        simDebugger.init(new NoExecutionVariableScope());
        try {
            simDebugger.runTo("");
            checkStatus(SimulationRunContext.getHistoryService());
        } finally {
            simDebugger.close();
            ProcessEngines.destroy();
        }
    }

    private void step0Check(RepositoryService repositoryService) {
        Deployment deployment;
        deployment = repositoryService.createDeploymentQuery().singleResult();
        assertNotNull(deployment);
    }

    private void step1Check(RuntimeService runtimeService, TaskService taskService) {
        ProcessInstance procInstance;
        procInstance = runtimeService.createProcessInstanceQuery().active().processInstanceBusinessKey("oneTaskProcessBusinessKey").singleResult();
        assertNotNull(procInstance);
        Task t = taskService.createTaskQuery().active().taskDefinitionKey("userTask").singleResult();
        assertNotNull(t);
    }

    @Test
    public void testRunContinue() throws Exception {
        recordEvents();

        SimulationDebugger simDebugger = createDebugger();

        simDebugger.init(new NoExecutionVariableScope());
        simDebugger.runContinue();
        checkStatus(SimulationRunContext.getHistoryService());

        simDebugger.close();
        ProcessEngines.destroy();
    }

    private SimulationDebugger createDebugger() {
        final SimpleSimulationRun.Builder builder = new SimpleSimulationRun.Builder();
        // init simulation run
        Clock clock = new ThreadLocalClock(new DefaultClockFactory());
        ProcessEngineConfigurationImpl config = (ProcessEngineConfigurationImpl) ProcessEngineConfiguration.createProcessEngineConfigurationFromResourceDefault();
        config.setClock(clock);

        SimulationProcessEngineFactory simulationProcessEngineFactory = new SimulationProcessEngineFactory(config);
        builder.processEngine(simulationProcessEngineFactory.getObject())
                .eventCalendar((new SimpleEventCalendarFactory(clock, new SimulationEventComparator(), listener.getSimulationEvents())).getObject())
                .eventHandlers(getHandlers());
        return builder.build();
    }

    private void checkStatus(HistoryService historyService) {
        final HistoricProcessInstance historicProcessInstance = historyService.createHistoricProcessInstanceQuery().finished().singleResult();
        assertNotNull(historicProcessInstance);
        assertEquals("oneTaskProcessBusinessKey", historicProcessInstance.getBusinessKey());
        HistoricTaskInstance historicTaskInstance = historyService.createHistoricTaskInstanceQuery().taskDefinitionKey("userTask").singleResult();
        assertEquals("user1", historicTaskInstance.getAssignee());
    }

    private void recordEvents() {
        Clock clock = new DefaultClockImpl();
        clock.setCurrentTime(new Date(0));
        ProcessEngineConfigurationImpl config = (ProcessEngineConfigurationImpl) ProcessEngineConfiguration.createProcessEngineConfigurationFromResourceDefault();
        config.setClock(clock);

        ProcessEngine processEngine = (new RecordableProcessEngineFactory(config, listener))
                .getObject();

        processEngine.getRepositoryService().createDeployment().addClasspathResource(USERTASK_PROCESS).deploy();
        EventRecorderTestUtils.increaseTime(clock);

        TaskService taskService = processEngine.getTaskService();

        Map<String, Object> variables = new HashMap<String, Object>();
        variables.put(TEST_VARIABLE, TEST_VALUE);
        processEngine.getRuntimeService().startProcessInstanceByKey("oneTaskProcess", "oneTaskProcessBusinessKey", variables);
        EventRecorderTestUtils.increaseTime(clock);
        Task task = taskService.createTaskQuery().taskDefinitionKey("userTask").singleResult();
        taskService.complete(task.getId());
        checkStatus(processEngine.getHistoryService());
        EventRecorderTestUtils.closeProcessEngine(processEngine, listener);
        ProcessEngines.destroy();
    }

    private List<Function<FlowableEvent, SimulationEvent>> getTransformers() {
        List<Function<FlowableEvent, SimulationEvent>> transformers = new ArrayList<Function<FlowableEvent, SimulationEvent>>();
        transformers.add(new DeploymentCreateTransformer(DEPLOYMENT_CREATED_EVENT_TYPE, DEPLOYMENT_RESOURCES_KEY));
        transformers.add(new ProcessInstanceCreateTransformer(PROCESS_INSTANCE_START_EVENT_TYPE, PROCESS_DEFINITION_ID_KEY, BUSINESS_KEY, VARIABLES_KEY));
        transformers.add(new UserTaskCompleteTransformer(USER_TASK_COMPLETED_EVENT_TYPE));
        return transformers;
    }

    public static Map<String, SimulationEventHandler> getHandlers() {
        Map<String, SimulationEventHandler> handlers = new HashMap<String, SimulationEventHandler>();
        handlers.put(DEPLOYMENT_CREATED_EVENT_TYPE, new DeployResourcesEventHandler(DEPLOYMENT_RESOURCES_KEY));
        handlers.put(PROCESS_INSTANCE_START_EVENT_TYPE, new StartProcessByIdEventHandler(PROCESS_DEFINITION_ID_KEY, BUSINESS_KEY, VARIABLES_KEY));
        handlers.put(USER_TASK_COMPLETED_EVENT_TYPE, new PlaybackUserTaskCompleteEventHandler());
        return handlers;
    }
}
