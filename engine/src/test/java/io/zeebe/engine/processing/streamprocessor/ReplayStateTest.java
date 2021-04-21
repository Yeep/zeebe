/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.processing.streamprocessor;

import static org.assertj.core.api.Assertions.assertThat;

import io.zeebe.engine.processing.message.MessageObserver;
import io.zeebe.engine.processing.streamprocessor.StreamProcessor.Phase;
import io.zeebe.engine.state.ZbColumnFamilies;
import io.zeebe.engine.util.EngineRule;
import io.zeebe.model.bpmn.Bpmn;
import io.zeebe.model.bpmn.BpmnModelInstance;
import io.zeebe.protocol.record.Record;
import io.zeebe.protocol.record.intent.JobIntent;
import io.zeebe.protocol.record.intent.MessageIntent;
import io.zeebe.protocol.record.intent.ProcessInstanceIntent;
import io.zeebe.protocol.record.value.BpmnElementType;
import io.zeebe.protocol.record.value.JobBatchRecordValue;
import io.zeebe.test.util.record.RecordingExporter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import org.assertj.core.api.SoftAssertions;
import org.awaitility.Awaitility;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public final class ReplayStateTest {

  private static final String PROCESS_ID = "process";
  private static final String PROCESS_CHILD_ID = "child_process";
  @Parameter public TestCase testCase;

  private long lastProcessedPosition = -1L;

  @Rule
  public final EngineRule engine =
      EngineRule.singlePartition()
          .withOnProcessedCallback(record -> lastProcessedPosition = record.getPosition())
          .withOnSkippedCallback(record -> lastProcessedPosition = record.getPosition());

  @Parameters(name = "{0}")
  public static Collection<TestCase> testRecords() {
    return List.of(
        testCase("activated service task")
            .withProcess(
                Bpmn.createExecutableProcess(PROCESS_ID)
                    .startEvent()
                    .serviceTask("task", t -> t.zeebeJobType("test"))
                    .done())
            .withExecution(
                engine -> {
                  engine.processInstance().ofBpmnProcessId(PROCESS_ID).create();

                  RecordingExporter.processInstanceRecords(ProcessInstanceIntent.ELEMENT_ACTIVATED)
                      .withElementType(BpmnElementType.SERVICE_TASK)
                      .await();

                  return RecordingExporter.jobRecords(JobIntent.CREATED).getFirst();
                }),
        testCase("expired buffered message")
            .withExecution(
                engine -> {
                  final var timeToLive = Duration.ofMinutes(1);

                  engine
                      .message()
                      .withName("test")
                      .withCorrelationKey("1")
                      .withTimeToLive(timeToLive)
                      .publish();

                  engine
                      .getClock()
                      .addTime(
                          timeToLive.plus(MessageObserver.MESSAGE_TIME_TO_LIVE_CHECK_INTERVAL));

                  return RecordingExporter.messageRecords(MessageIntent.EXPIRED).getFirst();
                }),
        // TODO(npepinpe): remove after https://github.com/camunda-cloud/zeebe/issues/6568
        testCase("timer start event")
            .withProcess(
                Bpmn.createExecutableProcess(PROCESS_ID)
                    .startEvent("timer")
                    .timerWithDateExpression("now()")
                    .endEvent()
                    .done())
            .withExecution(
                engine ->
                    RecordingExporter.processInstanceRecords(
                            ProcessInstanceIntent.ELEMENT_COMPLETED)
                        .withBpmnProcessId(PROCESS_ID)
                        .withElementType(BpmnElementType.PROCESS)
                        .getFirst()),
        // TODO(npepinpe): remove after https://github.com/camunda-cloud/zeebe/issues/6568
        testCase("intermediate timer catch event")
            .withProcess(
                Bpmn.createExecutableProcess(PROCESS_ID)
                    .startEvent()
                    .intermediateCatchEvent("timer")
                    .timerWithDuration("PT0S")
                    .endEvent()
                    .done())
            .withExecution(
                engine -> {
                  final long piKey = engine.processInstance().ofBpmnProcessId(PROCESS_ID).create();
                  return RecordingExporter.processInstanceRecords(
                          ProcessInstanceIntent.ELEMENT_COMPLETED)
                      .withProcessInstanceKey(piKey)
                      .withElementType(BpmnElementType.PROCESS)
                      .getFirst();
                }),
        // TODO(npepinpe): remove after https://github.com/camunda-cloud/zeebe/issues/6568
        testCase("interrupting timer boundary event")
            .withProcess(
                Bpmn.createExecutableProcess(PROCESS_ID)
                    .startEvent()
                    .serviceTask("task", b -> b.zeebeJobType("type"))
                    .boundaryEvent("timer", b -> b.cancelActivity(true))
                    .timerWithDuration("PT0S")
                    .endEvent("end")
                    .done())
            .withExecution(
                engine -> {
                  final long piKey = engine.processInstance().ofBpmnProcessId(PROCESS_ID).create();
                  return RecordingExporter.processInstanceRecords(
                          ProcessInstanceIntent.ELEMENT_COMPLETED)
                      .withProcessInstanceKey(piKey)
                      .withElementType(BpmnElementType.PROCESS)
                      .getFirst();
                }),
        // TODO(@korthout): remove after https://github.com/camunda-cloud/zeebe/issues/6197
        testCase("interrupting timer boundary event on call activity")
            .withProcess(
                Bpmn.createExecutableProcess(PROCESS_CHILD_ID)
                    .startEvent()
                    .serviceTask("task", b -> b.zeebeJobType("type"))
                    .endEvent()
                    .done())
            .withProcess(
                Bpmn.createExecutableProcess(PROCESS_ID)
                    .startEvent()
                    .callActivity("call-child", b -> b.zeebeProcessId(PROCESS_CHILD_ID))
                    .boundaryEvent("timer", b -> b.cancelActivity(true))
                    .timerWithDuration("PT1M")
                    .endEvent("end")
                    .done())
            .withExecution(
                engine -> {
                  final long piKey = engine.processInstance().ofBpmnProcessId(PROCESS_ID).create();
                  RecordingExporter.jobRecords(JobIntent.CREATED).await();
                  engine.getClock().addTime(Duration.ofMinutes(1));
                  return RecordingExporter.processInstanceRecords(
                          ProcessInstanceIntent.ELEMENT_COMPLETED)
                      .withProcessInstanceKey(piKey)
                      .withElementType(BpmnElementType.PROCESS)
                      .getFirst();
                }),
        // TODO(npepinpe): remove after https://github.com/camunda-cloud/zeebe/issues/6568
        testCase("non-interrupting timer boundary event")
            .withProcess(
                Bpmn.createExecutableProcess(PROCESS_ID)
                    .startEvent()
                    .serviceTask("task", b -> b.zeebeJobType("type"))
                    .boundaryEvent("timer", b -> b.cancelActivity(false))
                    .timerWithCycle("R1/PT0S")
                    .endEvent("end")
                    .done())
            .withExecution(
                engine -> {
                  final long piKey = engine.processInstance().ofBpmnProcessId(PROCESS_ID).create();
                  return RecordingExporter.processInstanceRecords(
                          ProcessInstanceIntent.ELEMENT_COMPLETED)
                      .withProcessInstanceKey(piKey)
                      .withElementType(BpmnElementType.END_EVENT)
                      .withElementId("end")
                      .getFirst();
                }),
        testCase("throw error end event")
            .withProcess(
                Bpmn.createExecutableProcess(PROCESS_ID)
                    .startEvent()
                    .subProcess("subProcess")
                    .embeddedSubProcess()
                    .startEvent()
                    .endEvent("errorEndEvent", b -> b.error("error"))
                    .subProcessDone()
                    .boundaryEvent("errorCatchEvent", b -> b.error("error").cancelActivity(true))
                    .endEvent()
                    .moveToActivity("subProcess")
                    .intermediateCatchEvent("neverProcessed")
                    .message(m -> m.name("message").zeebeCorrelationKey("=\"key\""))
                    .done())
            .withExecution(
                engine -> {
                  final long piKey = engine.processInstance().ofBpmnProcessId(PROCESS_ID).create();

                  // the process will only finish if it goes through the error catch event boundary
                  // event, which simplifies verification
                  return RecordingExporter.processInstanceRecords(
                          ProcessInstanceIntent.ELEMENT_COMPLETED)
                      .withElementType(BpmnElementType.PROCESS)
                      .withRecordKey(piKey)
                      .getFirst();
                }),
        testCase("interrupting message boundary event on receive task")
            .withProcess(
                Bpmn.createExecutableProcess(PROCESS_ID)
                    .startEvent()
                    .receiveTask(
                        "task",
                        t -> t.message(m -> m.name("task").zeebeCorrelationKeyExpression("1")))
                    .boundaryEvent(
                        "event",
                        b ->
                            b.cancelActivity(true)
                                .message(m -> m.name("event").zeebeCorrelationKeyExpression("1")))
                    .endEvent("end")
                    .done())
            .withExecution(
                engine -> {
                  final long piKey = engine.processInstance().ofBpmnProcessId(PROCESS_ID).create();
                  engine.message().withName("event").withCorrelationKey("1").publish();
                  return RecordingExporter.processInstanceRecords(
                          ProcessInstanceIntent.ELEMENT_COMPLETED)
                      .withProcessInstanceKey(piKey)
                      .withElementType(BpmnElementType.PROCESS)
                      .getFirst();
                }),
        testCase("non-interrupting timer boundary event on receive task")
            .withProcess(
                Bpmn.createExecutableProcess(PROCESS_ID)
                    .startEvent()
                    .receiveTask(
                        "task",
                        t -> t.message(m -> m.name("task").zeebeCorrelationKeyExpression("1")))
                    .boundaryEvent("event", b -> b.cancelActivity(false).timerWithDuration("PT0S"))
                    .endEvent("end")
                    .done())
            .withExecution(
                engine -> {
                  final long piKey = engine.processInstance().ofBpmnProcessId(PROCESS_ID).create();
                  return RecordingExporter.processInstanceRecords(
                          ProcessInstanceIntent.ELEMENT_COMPLETED)
                      .withProcessInstanceKey(piKey)
                      .withElementType(BpmnElementType.END_EVENT)
                      .withElementId("end")
                      .getFirst();
                }),
        testCase("parallel multi-instance service task")
            .withProcess(
                Bpmn.createExecutableProcess(PROCESS_ID)
                    .startEvent()
                    .serviceTask(
                        "task",
                        t ->
                            t.zeebeJobType("type")
                                .multiInstance(
                                    m ->
                                        m.parallel()
                                            .zeebeInputElement("item")
                                            .zeebeInputCollectionExpression("items")
                                            .zeebeOutputElementExpression("result")
                                            .zeebeOutputCollection("results")))
                    .endEvent()
                    .done())
            .withExecution(
                engine -> {
                  final long piKey =
                      engine
                          .processInstance()
                          .ofBpmnProcessId(PROCESS_ID)
                          .withVariable("items", List.of(1, 2, 3))
                          .create();
                  Awaitility.await("until there are 3 jobs ready to be activated")
                      .pollInSameThread()
                      .until(
                          () ->
                              RecordingExporter.jobRecords(JobIntent.CREATED).limit(3).count()
                                  >= 3);
                  final JobBatchRecordValue jobs =
                      engine.jobs().withMaxJobsToActivate(3).withType("type").activate().getValue();
                  jobs.getJobKeys()
                      .forEach(
                          key -> engine.job().withKey(key).withVariable("result", 0).complete());

                  return RecordingExporter.processInstanceRecords(
                          ProcessInstanceIntent.ELEMENT_COMPLETED)
                      .withProcessInstanceKey(piKey)
                      .withElementType(BpmnElementType.PROCESS)
                      .getFirst();
                }),
        testCase("sequential multi-instance service task")
            .withProcess(
                Bpmn.createExecutableProcess(PROCESS_ID)
                    .startEvent()
                    .serviceTask(
                        "task",
                        t ->
                            t.zeebeJobType("type")
                                .multiInstance(
                                    m ->
                                        m.sequential()
                                            .zeebeInputElement("item")
                                            .zeebeInputCollectionExpression("items")
                                            .zeebeOutputElementExpression("result")
                                            .zeebeOutputCollection("results")))
                    .endEvent()
                    .done())
            .withExecution(
                engine -> {
                  final long piKey =
                      engine
                          .processInstance()
                          .ofBpmnProcessId(PROCESS_ID)
                          .withVariable("items", List.of(1, 2, 3))
                          .create();
                  for (int i = 0; i < 3; i++) {
                    final int expectedJobCount = i + 1;
                    Awaitility.await(
                            "until there are " + expectedJobCount + " jobs ready to be activated")
                        .pollInSameThread()
                        .until(
                            () ->
                                RecordingExporter.jobRecords(JobIntent.CREATED)
                                        .limit(expectedJobCount)
                                        .count()
                                    >= expectedJobCount);
                    final JobBatchRecordValue jobs =
                        engine
                            .jobs()
                            .withMaxJobsToActivate(1)
                            .withType("type")
                            .activate()
                            .getValue();
                    jobs.getJobKeys()
                        .forEach(
                            key -> engine.job().withKey(key).withVariable("result", 0).complete());
                  }

                  return RecordingExporter.processInstanceRecords(
                          ProcessInstanceIntent.ELEMENT_COMPLETED)
                      .withProcessInstanceKey(piKey)
                      .withElementType(BpmnElementType.PROCESS)
                      .getFirst();
                }),
        testCase("interrupting parallel multi-instance service task")
            .withProcess(
                Bpmn.createExecutableProcess(PROCESS_ID)
                    .startEvent()
                    .serviceTask(
                        "task",
                        t ->
                            t.zeebeJobType("type")
                                .multiInstance(
                                    m ->
                                        m.parallel()
                                            .zeebeInputElement("item")
                                            .zeebeInputCollectionExpression("items")
                                            .zeebeOutputElementExpression("result")
                                            .zeebeOutputCollection("results"))
                                .boundaryEvent(
                                    "event",
                                    b ->
                                        b.cancelActivity(true)
                                            .message(
                                                m ->
                                                    m.name("message")
                                                        .zeebeCorrelationKey("=\"key\"")))
                                .endEvent())
                    .endEvent()
                    .done())
            .withExecution(
                engine -> {
                  final long piKey =
                      engine
                          .processInstance()
                          .ofBpmnProcessId(PROCESS_ID)
                          .withVariable("items", List.of(1, 2, 3))
                          .create();
                  Awaitility.await("until there are 3 jobs ready to be activated")
                      .pollInSameThread()
                      .until(
                          () ->
                              RecordingExporter.jobRecords(JobIntent.CREATED).limit(3).count()
                                  >= 2);
                  final JobBatchRecordValue jobs =
                      engine.jobs().withMaxJobsToActivate(2).withType("type").activate().getValue();
                  jobs.getJobKeys()
                      .forEach(
                          key -> engine.job().withKey(key).withVariable("result", 0).complete());
                  engine.message().withName("message").withCorrelationKey("key").publish();

                  return RecordingExporter.processInstanceRecords(
                          ProcessInstanceIntent.ELEMENT_COMPLETED)
                      .withProcessInstanceKey(piKey)
                      .withElementType(BpmnElementType.PROCESS)
                      .getFirst();
                }));
  }

  @Before
  public void init() {
    lastProcessedPosition = -1L;
  }

  @Test
  public void shouldRestoreState() {
    // given
    testCase.processes.forEach(process -> engine.deployment().withXmlResource(process).deploy());

    final Record<?> finalRecord = testCase.execution.apply(engine);

    Awaitility.await("await until the last record is processed")
        .untilAsserted(
            () ->
                assertThat(lastProcessedPosition)
                    .isGreaterThanOrEqualTo(finalRecord.getPosition()));

    final var processingState = engine.collectState();
    engine.stop();

    // when
    engine.start();

    Awaitility.await()
        .untilAsserted(
            () ->
                assertThat(engine.getStreamProcessor(1).getCurrentPhase().join())
                    .isEqualTo(Phase.PROCESSING));

    // then
    Awaitility.await("await that the replay state is equal to the processing state")
        .untilAsserted(
            () -> {
              final var replayState = engine.collectState();

              final var softly = new SoftAssertions();

              processingState.entrySet().stream()
                  .filter(entry -> entry.getKey() != ZbColumnFamilies.DEFAULT)
                  .forEach(
                      entry -> {
                        final var column = entry.getKey();
                        final var processingEntries = entry.getValue();
                        final var replayEntries = replayState.get(column);

                        if (processingEntries.isEmpty()) {
                          softly
                              .assertThat(replayEntries)
                              .describedAs(
                                  "The state column '%s' should be empty after replay", column)
                              .isEmpty();
                        } else {
                          softly
                              .assertThat(replayEntries)
                              .describedAs(
                                  "The state column '%s' has different entries after replay",
                                  column)
                              .containsExactlyInAnyOrderEntriesOf(processingEntries);
                        }
                      });

              softly.assertAll();
            });
  }

  private static TestCase testCase(final String description) {
    return new TestCase(description);
  }

  private static final class TestCase {
    private final String description;
    private final List<BpmnModelInstance> processes = new ArrayList<>();
    private Function<EngineRule, Record<?>> execution =
        engine -> RecordingExporter.records().getFirst();

    private TestCase(final String description) {
      this.description = description;
    }

    private TestCase withProcess(final BpmnModelInstance process) {
      processes.add(process);
      return this;
    }

    private TestCase withExecution(final Function<EngineRule, Record<?>> execution) {
      this.execution = execution;
      return this;
    }

    @Override
    public String toString() {
      return "TestCase{" + description + '}';
    }
  }
}