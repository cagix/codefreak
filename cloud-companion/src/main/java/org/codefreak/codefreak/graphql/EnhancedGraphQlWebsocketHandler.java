package org.codefreak.codefreak.graphql;

/*
 * Copyright 2002-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import graphql.ErrorType;
import graphql.ExecutionResult;
import graphql.GraphqlErrorBuilder;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.ResolvableType;
import org.springframework.core.codec.Decoder;
import org.springframework.core.codec.Encoder;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.graphql.web.WebGraphQlHandler;
import org.springframework.graphql.web.WebInput;
import org.springframework.graphql.web.WebOutput;
import org.springframework.graphql.web.webflux.GraphQlWebSocketHandler;
import org.springframework.http.MediaType;
import org.springframework.http.codec.DecoderHttpMessageReader;
import org.springframework.http.codec.EncoderHttpMessageWriter;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.reactive.socket.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * WebSocketHandler for GraphQL based on <a href=
 * "https://github.com/enisdenjo/graphql-ws/blob/master/PROTOCOL.md">GraphQL Over WebSocket
 * Protocol</a> and for use in a Spring WebFlux application.
 *
 * <p>Modified to allow a custom handler for ConnectionInit messages.
 *
 * @author Rossen Stoyanchev
 * @since 1.0.0
 */
public class EnhancedGraphQlWebsocketHandler extends GraphQlWebSocketHandler {

  private static final Log logger = LogFactory.getLog(
    EnhancedGraphQlWebsocketHandler.class
  );

  private static final List<String> SUB_PROTOCOL_LIST = Arrays.asList(
    "graphql-transport-ws",
    "subscriptions-transport-ws"
  );

  static final ResolvableType MAP_RESOLVABLE_TYPE = ResolvableType.forType(
    new ParameterizedTypeReference<Map<String, Object>>() {}
  );

  private final WebGraphQlHandler graphQlHandler;

  private final Decoder<?> decoder;

  private final Encoder<?> encoder;

  private final Duration initTimeoutDuration;

  private final GraphQlConnectionInitHandler connectionInitHandler;

  /**
   * {@inheritDoc}
   */
  public EnhancedGraphQlWebsocketHandler(
    WebGraphQlHandler graphQlHandler,
    ServerCodecConfigurer configurer,
    Duration connectionInitTimeout,
    GraphQlConnectionInitHandler connectionInitHandler
  ) {
    super(graphQlHandler, configurer, connectionInitTimeout);
    this.graphQlHandler = graphQlHandler;
    this.decoder = initDecoder(configurer);
    this.encoder = initEncoder(configurer);
    this.initTimeoutDuration = connectionInitTimeout;
    this.connectionInitHandler = connectionInitHandler;
  }

  private static Decoder<?> initDecoder(ServerCodecConfigurer configurer) {
    return configurer
      .getReaders()
      .stream()
      .filter(reader ->
        reader.canRead(MAP_RESOLVABLE_TYPE, MediaType.APPLICATION_JSON)
      )
      .map(reader -> ((DecoderHttpMessageReader<?>) reader).getDecoder())
      .findFirst()
      .orElseThrow(() -> new IllegalArgumentException("No JSON Decoder"));
  }

  private static Encoder<?> initEncoder(ServerCodecConfigurer configurer) {
    return configurer
      .getWriters()
      .stream()
      .filter(writer ->
        writer.canWrite(MAP_RESOLVABLE_TYPE, MediaType.APPLICATION_JSON)
      )
      .map(writer -> ((EncoderHttpMessageWriter<?>) writer).getEncoder())
      .findFirst()
      .orElseThrow(() -> new IllegalArgumentException("No JSON Encoder"));
  }

  @Override
  public List<String> getSubProtocols() {
    return SUB_PROTOCOL_LIST;
  }

  @Override
  public Mono<Void> handle(WebSocketSession session) {
    HandshakeInfo handshakeInfo = session.getHandshakeInfo();
    if (
      "subscriptions-transport-ws".equalsIgnoreCase(
          handshakeInfo.getSubProtocol()
        )
    ) {
      if (logger.isDebugEnabled()) {
        logger.debug(
          "apollographql/subscriptions-transport-ws is not supported, nor maintained. " +
          "Please, use https://github.com/enisdenjo/graphql-ws."
        );
      }
      return session.close(GraphQlStatus.INVALID_MESSAGE_STATUS);
    }

    // Session state
    AtomicBoolean connectionInitProcessed = new AtomicBoolean();
    Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();

    Mono
      .delay(this.initTimeoutDuration)
      .then(
        Mono.defer(() ->
          connectionInitProcessed.compareAndSet(false, true)
            ? session.close(GraphQlStatus.INIT_TIMEOUT_STATUS)
            : Mono.empty()
        )
      )
      .subscribe();

    return session.send(
      session
        .receive()
        .flatMap(message -> {
          Map<String, Object> map = decode(message);
          String id = (String) map.get("id");
          MessageType messageType = MessageType.resolve(
            (String) map.get("type")
          );
          if (messageType == null) {
            return GraphQlStatus.close(
              session,
              GraphQlStatus.INVALID_MESSAGE_STATUS
            );
          }
          switch (messageType) {
            case SUBSCRIBE:
              if (!connectionInitProcessed.get()) {
                return GraphQlStatus.close(
                  session,
                  GraphQlStatus.UNAUTHORIZED_STATUS
                );
              }
              if (id == null) {
                return GraphQlStatus.close(
                  session,
                  GraphQlStatus.INVALID_MESSAGE_STATUS
                );
              }
              WebInput input = new WebInput(
                handshakeInfo.getUri(),
                handshakeInfo.getHeaders(),
                getPayload(map),
                id
              );
              if (logger.isDebugEnabled()) {
                logger.debug("Executing: " + input);
              }
              return this.graphQlHandler.handle(input)
                .flatMapMany(output ->
                  handleWebOutput(session, id, subscriptions, output)
                )
                .doOnTerminate(() -> subscriptions.remove(id));
            case COMPLETE:
              if (id != null) {
                Subscription subscription = subscriptions.remove(id);
                if (subscription != null) {
                  subscription.cancel();
                }
              }
              return Flux.empty();
            case CONNECTION_INIT:
              if (!connectionInitProcessed.compareAndSet(false, true)) {
                return GraphQlStatus.close(
                  session,
                  GraphQlStatus.TOO_MANY_INIT_REQUESTS_STATUS
                );
              }
              Map<String, Object> payload = (Map<String, Object>) map.get(
                "payload"
              );
              return handleInit(payload, session)
                .flatMapMany(responsePayload ->
                  Flux.just(
                    encode(
                      session,
                      null,
                      MessageType.CONNECTION_ACK,
                      responsePayload
                    )
                  )
                )
                .onErrorResume(e -> {
                  if (e instanceof GraphQlConnectionInitException) {
                    return Flux.from(
                      GraphQlStatus.close(
                        session,
                        ((GraphQlConnectionInitException) e).getCloseCode()
                      )
                    );
                  } else {
                    return Flux.from(
                      GraphQlStatus.close(
                        session,
                        GraphQlStatus.UNAUTHORIZED_STATUS
                      )
                    );
                  }
                });
            default:
              return GraphQlStatus.close(
                session,
                GraphQlStatus.INVALID_MESSAGE_STATUS
              );
          }
        })
    );
  }

  protected Mono<Map<String, Object>> handleInit(
    Map<String, Object> payload,
    WebSocketSession session
  ) {
    if (connectionInitHandler != null) {
      try {
        return connectionInitHandler.handleInit(payload, session);
      } catch (GraphQlConnectionInitException e) {
        return Mono.error(e);
      }
    } else {
      return Mono.just(new HashMap<>());
    }
  }

  @SuppressWarnings({ "unchecked", "ConstantConditions" })
  private Map<String, Object> decode(WebSocketMessage message) {
    DataBuffer buffer = DataBufferUtils.retain(message.getPayload());
    return (Map<String, Object>) this.decoder.decode(
        buffer,
        MAP_RESOLVABLE_TYPE,
        null,
        null
      );
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> getPayload(Map<String, Object> message) {
    Map<String, Object> payload = (Map<String, Object>) message.get("payload");
    Assert.notNull(payload, "No \"payload\" in message: " + message);
    return payload;
  }

  @SuppressWarnings("unchecked")
  private Flux<WebSocketMessage> handleWebOutput(
    WebSocketSession session,
    String id,
    Map<String, Subscription> subscriptions,
    WebOutput output
  ) {
    if (logger.isDebugEnabled()) {
      logger.debug(
        "Execution result ready" +
        (
          !CollectionUtils.isEmpty(output.getErrors())
            ? " with errors: " + output.getErrors()
            : ""
        ) +
        "."
      );
    }

    Flux<ExecutionResult> outputFlux;
    if (output.getData() instanceof Publisher) {
      // Subscription
      outputFlux =
        Flux
          .from((Publisher<ExecutionResult>) output.getData())
          .doOnSubscribe(subscription -> {
            Subscription previous = subscriptions.putIfAbsent(id, subscription);
            if (previous != null) {
              throw new SubscriptionExistsException();
            }
          });
    } else {
      // Single response operation (query or mutation)
      outputFlux =
        (
          CollectionUtils.isEmpty(output.getErrors())
            ? Flux.just(output)
            : Flux.error(
              new IllegalStateException(
                "Execution failed: " + output.getErrors()
              )
            )
        );
    }

    return outputFlux
      .map(result -> {
        Map<String, Object> dataMap = result.toSpecification();
        return encode(session, id, MessageType.NEXT, dataMap);
      })
      .concatWith(
        Mono.fromCallable(() -> encode(session, id, MessageType.COMPLETE, null))
      )
      .onErrorResume(ex -> {
        if (ex instanceof SubscriptionExistsException) {
          CloseStatus status = new CloseStatus(
            4409,
            "Subscriber for " + id + " already exists"
          );
          return GraphQlStatus.close(session, status);
        }
        Map<String, Object> errorMap = GraphqlErrorBuilder
          .newError()
          .errorType(ErrorType.DataFetchingException)
          .message(ex.getMessage())
          .build()
          .toSpecification();
        return Mono.just(encode(session, id, MessageType.ERROR, errorMap));
      });
  }

  @SuppressWarnings("unchecked")
  private <T> WebSocketMessage encode(
    WebSocketSession session,
    @Nullable String id,
    MessageType messageType,
    @Nullable Object payload
  ) {
    Map<String, Object> payloadMap = new HashMap<>(3);
    if (id != null) {
      payloadMap.put("id", id);
    }
    payloadMap.put("type", messageType.getType());
    if (payload != null) {
      payloadMap.put("payload", payload);
    }

    DataBuffer buffer =
      ((Encoder<T>) this.encoder).encodeValue(
          (T) payloadMap,
          session.bufferFactory(),
          MAP_RESOLVABLE_TYPE,
          MimeTypeUtils.APPLICATION_JSON,
          null
        );

    return new WebSocketMessage(WebSocketMessage.Type.TEXT, buffer);
  }

  private enum MessageType {
    CONNECTION_INIT("connection_init"),
    CONNECTION_ACK("connection_ack"),
    SUBSCRIBE("subscribe"),
    NEXT("next"),
    ERROR("error"),
    COMPLETE("complete");

    private static final Map<String, MessageType> messageTypes = new HashMap<>(
      6
    );

    static {
      for (MessageType messageType : MessageType.values()) {
        messageTypes.put(messageType.getType(), messageType);
      }
    }

    private final String type;

    MessageType(String type) {
      this.type = type;
    }

    public String getType() {
      return this.type;
    }

    @Nullable
    public static MessageType resolve(@Nullable String type) {
      return (type != null) ? messageTypes.get(type) : null;
    }
  }

  private static class GraphQlStatus {

    static final CloseStatus INVALID_MESSAGE_STATUS = new CloseStatus(
      4400,
      "Invalid message"
    );

    static final CloseStatus UNAUTHORIZED_STATUS = new CloseStatus(
      4401,
      "Unauthorized"
    );

    static final CloseStatus INIT_TIMEOUT_STATUS = new CloseStatus(
      4408,
      "Connection initialisation timeout"
    );

    static final CloseStatus TOO_MANY_INIT_REQUESTS_STATUS = new CloseStatus(
      4429,
      "Too many initialisation requests"
    );

    static <V> Flux<V> close(WebSocketSession session, CloseStatus status) {
      return session.close(status).thenMany(Mono.empty());
    }
  }

  @SuppressWarnings("serial")
  private static class SubscriptionExistsException extends RuntimeException {}
}
