/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.engine.processing.streamprocessor;

import static io.camunda.zeebe.engine.util.RecordToWrite.command;
import static io.camunda.zeebe.engine.util.RecordToWrite.event;
import static io.camunda.zeebe.protocol.record.intent.ProcessInstanceIntent.ACTIVATE_ELEMENT;
import static io.camunda.zeebe.protocol.record.intent.ProcessInstanceIntent.ELEMENT_ACTIVATING;
import static java.util.function.Predicate.isEqual;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import io.camunda.zeebe.engine.processing.streamprocessor.StreamProcessor.Phase;
import io.camunda.zeebe.engine.state.EventApplier;
import io.camunda.zeebe.engine.util.Records;
import io.camunda.zeebe.engine.util.StreamProcessorRule;
import io.camunda.zeebe.protocol.impl.record.value.processinstance.ProcessInstanceRecord;
import io.camunda.zeebe.protocol.record.ValueType;
import org.awaitility.Awaitility;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.verification.VerificationWithTimeout;

public final class StreamProcessorReplayModeTest {

  private static final long TIMEOUT_MILLIS = 2_000L;
  private static final VerificationWithTimeout TIMEOUT = timeout(TIMEOUT_MILLIS);

  private static final int PARTITION_ID = 1;

  private static final ProcessInstanceRecord RECORD = Records.processInstance(1);

  @Rule
  public final StreamProcessorRule replayUntilEnd =
      new StreamProcessorRule(PARTITION_ID).withStreamProcessorMode(StreamProcessorMode.PROCESSING);

  @Rule
  public final StreamProcessorRule replayContinuously =
      new StreamProcessorRule(PARTITION_ID).withStreamProcessorMode(StreamProcessorMode.REPLAY);

  @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();

  @Mock private TypedRecordProcessor<?> typedRecordProcessor;
  @Mock private EventApplier eventApplier;

  @Test
  public void shouldReplayUntilEnd() {
    // given
    replayUntilEnd.writeBatch(
        command().processInstance(ACTIVATE_ELEMENT, RECORD),
        event().processInstance(ELEMENT_ACTIVATING, RECORD).causedBy(0));

    // when
    startStreamProcessor(replayUntilEnd);

    replayUntilEnd.writeBatch(
        command().processInstance(ACTIVATE_ELEMENT, RECORD),
        event().processInstance(ELEMENT_ACTIVATING, RECORD).causedBy(0));

    // then
    final InOrder inOrder = inOrder(typedRecordProcessor, eventApplier);
    inOrder.verify(eventApplier, TIMEOUT).applyState(anyLong(), eq(ELEMENT_ACTIVATING), any());
    inOrder.verify(typedRecordProcessor, TIMEOUT.times(1)).onRecovered(any());
    inOrder
        .verify(typedRecordProcessor, TIMEOUT)
        .processRecord(anyLong(), any(), any(), any(), any());
    inOrder.verifyNoMoreInteractions();

    assertThat(getCurrentPhase(replayUntilEnd)).isEqualTo(Phase.PROCESSING);
  }

  @Test
  public void shouldReplayContinuously() {
    // given
    replayContinuously.writeBatch(
        command().processInstance(ACTIVATE_ELEMENT, RECORD),
        event().processInstance(ELEMENT_ACTIVATING, RECORD).causedBy(0));

    // when
    startStreamProcessor(replayContinuously);

    replayContinuously.writeBatch(
        command().processInstance(ACTIVATE_ELEMENT, RECORD),
        event().processInstance(ELEMENT_ACTIVATING, RECORD).causedBy(0));

    // then
    final InOrder inOrder = inOrder(typedRecordProcessor, eventApplier);
    inOrder
        .verify(eventApplier, TIMEOUT.times(2))
        .applyState(anyLong(), eq(ELEMENT_ACTIVATING), any());
    inOrder.verify(typedRecordProcessor, never()).onRecovered(any());
    inOrder.verifyNoMoreInteractions();

    assertThat(getCurrentPhase(replayContinuously)).isEqualTo(Phase.REPROCESSING);
  }

  @Test
  public void shouldUpdateLastProcessedAndWrittenPositionOnReplay() {
    // given
    startStreamProcessor(replayContinuously);

    // when
    final var commandPosition = replayContinuously.writeCommand(ACTIVATE_ELEMENT, RECORD);
    final var eventPosition =
        replayContinuously.writeEvent(
            ELEMENT_ACTIVATING, RECORD, event -> event.sourceRecordPosition(commandPosition));

    // then
    verify(eventApplier, TIMEOUT).applyState(anyLong(), eq(ELEMENT_ACTIVATING), any());

    Awaitility.await()
        .untilAsserted(
            () -> {
              final var lastProcessedPosition = getLastProcessedPosition(replayContinuously);
              final var lastWrittenPosition = getLastWrittenPosition(replayContinuously);

              assertThat(lastProcessedPosition)
                  .describedAs(
                      "Expected the position of the command to be the last processed position")
                  .isEqualTo(commandPosition);

              assertThat(lastWrittenPosition)
                  .describedAs("Expected the position of the event to be the last written position")
                  .isEqualTo(eventPosition);
            });
  }

  @Test
  public void shouldSetLastProcessedPositionOnStateToSourcePosition() {
    // given
    startStreamProcessor(replayContinuously);

    // when
    final var commandPosition = replayContinuously.writeCommand(ACTIVATE_ELEMENT, RECORD);
    replayContinuously.writeEvent(
        ELEMENT_ACTIVATING, RECORD, event -> event.sourceRecordPosition(commandPosition));

    verify(eventApplier, TIMEOUT).applyState(anyLong(), eq(ELEMENT_ACTIVATING), any());

    Awaitility.await()
        .until(() -> getLastProcessedPosition(replayContinuously), isEqual(commandPosition));

    // then
    assertThat(replayContinuously.getLastSuccessfulProcessedRecordPosition())
        .describedAs("Last processed position in the state must be the last source position")
        .isEqualTo(commandPosition);
  }

  private void startStreamProcessor(final StreamProcessorRule streamProcessorRule) {
    streamProcessorRule
        .withEventApplierFactory(zeebeState -> eventApplier)
        .startTypedStreamProcessor(
            (processors, context) ->
                processors.onCommand(
                    ValueType.PROCESS_INSTANCE, ACTIVATE_ELEMENT, typedRecordProcessor));
  }

  private Phase getCurrentPhase(final StreamProcessorRule streamProcessorRule) {
    return getStreamProcessor(streamProcessorRule).getCurrentPhase().join();
  }

  private Long getLastProcessedPosition(final StreamProcessorRule streamProcessorRule) {
    return getStreamProcessor(streamProcessorRule).getLastProcessedPositionAsync().join();
  }

  private Long getLastWrittenPosition(final StreamProcessorRule streamProcessorRule) {
    return getStreamProcessor(streamProcessorRule).getLastWrittenPositionAsync().join();
  }

  private StreamProcessor getStreamProcessor(final StreamProcessorRule streamProcessorRule) {
    return streamProcessorRule.getStreamProcessor(PARTITION_ID);
  }
}