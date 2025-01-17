/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.broker.transport.streamapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.atomix.cluster.MemberId;
import io.camunda.zeebe.broker.jobstream.ImmutableStreamRegistry.StreamConsumer;
import io.camunda.zeebe.broker.jobstream.ImmutableStreamRegistry.StreamId;
import io.camunda.zeebe.broker.jobstream.StreamRegistry;
import io.camunda.zeebe.protocol.impl.stream.AddStreamRequest;
import io.camunda.zeebe.protocol.impl.stream.RemoveStreamRequest;
import io.camunda.zeebe.protocol.record.UUIDEncoder;
import io.camunda.zeebe.stream.api.GatewayStreamer.Metadata;
import io.camunda.zeebe.util.buffer.BufferUtil;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

final class StreamApiHandlerTest {
  private static final UnsafeBuffer SERIALIZED_METADATA =
      new UnsafeBuffer(ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(0, 1));

  private final StreamRegistry<TestMetadata> registry = new StreamRegistry<>();
  private final StreamApiHandler<TestMetadata> server =
      new StreamApiHandler<>(registry, TestMetadata::new);

  @Test
  void shouldNotAddOnMetadataReadError() {
    // given
    final var streamType = new UnsafeBuffer(BufferUtil.wrapString("foo"));
    final var request =
        new AddStreamRequest()
            .streamId(UUID.randomUUID())
            .streamType(streamType)
            .metadata(new UnsafeBuffer()); // an empty buffer will cause the read to fail
    final var sender = MemberId.anonymous();

    // when - then
    assertThatCode(() -> server.add(sender, request)).isInstanceOf(IndexOutOfBoundsException.class);
    assertThat(registry.get(streamType)).isEmpty();
  }

  @Test
  void shouldNotAddWithEmptyStreamType() {
    // given
    final var request =
        new AddStreamRequest().streamId(UUID.randomUUID()).metadata(SERIALIZED_METADATA);
    final var sender = MemberId.anonymous();

    // when - then
    assertThatCode(() -> server.add(sender, request)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldNotAddWithNullStreamId() {
    // given
    final var streamType = new UnsafeBuffer(BufferUtil.wrapString("streamType"));
    final var request = new AddStreamRequest().streamType(streamType).metadata(SERIALIZED_METADATA);
    final var sender = MemberId.anonymous();

    // when - then
    assertThatCode(() -> server.add(sender, request)).isInstanceOf(IllegalArgumentException.class);
    assertThat(registry.get(streamType)).isEmpty();
  }

  @Test
  void shouldNotAddWithNilStreamId() {
    // given
    final var streamType = new UnsafeBuffer(BufferUtil.wrapString("streamType"));
    final var nilUuid = new UUID(UUIDEncoder.highNullValue(), UUIDEncoder.lowNullValue());
    final var request =
        new AddStreamRequest()
            .streamType(streamType)
            .metadata(SERIALIZED_METADATA)
            .streamId(nilUuid);
    final var sender = MemberId.anonymous();

    // when - then
    assertThatCode(() -> server.add(sender, request)).isInstanceOf(IllegalArgumentException.class);
    assertThat(registry.get(streamType)).isEmpty();
  }

  @Test
  void shouldAddStream() {
    // given
    final var streamType = new UnsafeBuffer(BufferUtil.wrapString("foo"));
    final var streamId = UUID.randomUUID();
    final var request =
        new AddStreamRequest()
            .streamId(streamId)
            .streamType(streamType)
            .metadata(SERIALIZED_METADATA);
    final var sender = MemberId.anonymous();

    // when
    server.add(sender, request);

    // then
    final var consumers = registry.get(streamType);
    assertThat(consumers)
        .hasSize(1)
        .first()
        .extracting(StreamConsumer::streamType, StreamConsumer::id, c -> c.properties().version)
        .containsExactly(streamType, new StreamId(streamId, sender), 1);
  }

  @Test
  void shouldRemoveStream() {
    // given
    final var streamType = new UnsafeBuffer(BufferUtil.wrapString("foo"));
    final var streamId = UUID.randomUUID();
    final var sender = MemberId.anonymous();
    final var request = new RemoveStreamRequest().streamId(streamId);
    registry.add(streamType, streamId, sender, new TestMetadata());

    // when
    server.remove(sender, request);

    // then
    final var consumers = registry.get(streamType);
    assertThat(consumers).isEmpty();
  }

  @Test
  void shouldRemoveAllStream() {
    // given
    final var streamType = new UnsafeBuffer(BufferUtil.wrapString("foo"));
    final var sender = MemberId.anonymous();
    registry.add(streamType, UUID.randomUUID(), sender, new TestMetadata());

    // when
    server.removeAll(sender);

    // then
    final var consumers = registry.get(streamType);
    assertThat(consumers).isEmpty();
  }

  private static final class TestMetadata implements Metadata {
    private int version;

    private TestMetadata() {}

    @Override
    public void wrap(final DirectBuffer buffer, final int offset, final int length) {
      version = buffer.getInt(0, ByteOrder.nativeOrder());
    }
  }
}
