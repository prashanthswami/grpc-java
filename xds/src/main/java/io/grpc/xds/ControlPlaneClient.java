/*
 * Copyright 2020 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.xds;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.protobuf.Any;
import com.google.rpc.Code;
import io.envoyproxy.envoy.service.discovery.v3.AggregatedDiscoveryServiceGrpc;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.grpc.Context;
import io.grpc.InternalLogId;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.BackoffPolicy;
import io.grpc.xds.Bootstrapper.ServerInfo;
import io.grpc.xds.EnvoyProtoData.Node;
import io.grpc.xds.XdsClient.ProcessingTracker;
import io.grpc.xds.XdsClient.ResourceStore;
import io.grpc.xds.XdsClient.XdsResponseHandler;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import io.grpc.xds.XdsTransportFactory.EventHandler;
import io.grpc.xds.XdsTransportFactory.StreamingCall;
import io.grpc.xds.XdsTransportFactory.XdsTransport;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Common base type for XdsClient implementations, which encapsulates the layer abstraction of
 * the xDS RPC stream.
 */
final class ControlPlaneClient {

  public static final String CLOSED_BY_SERVER = "Closed by server";
  private final SynchronizationContext syncContext;
  private final InternalLogId logId;
  private final XdsLogger logger;
  private final ServerInfo serverInfo;
  private final XdsTransport xdsTransport;
  private final XdsResponseHandler xdsResponseHandler;
  private final ResourceStore resourceStore;
  private final Context context;
  private final ScheduledExecutorService timeService;
  private final BackoffPolicy.Provider backoffPolicyProvider;
  private final Stopwatch stopwatch;
  private final Node bootstrapNode;
  private final XdsClient.TimerLaunch timerLaunch;

  // Last successfully applied version_info for each resource type. Starts with empty string.
  // A version_info is used to update management server with client's most recent knowledge of
  // resources.
  private final Map<XdsResourceType<?>, String> versions = new HashMap<>();

  private boolean shutdown;
  @Nullable
  private AdsStream adsStream;
  @Nullable
  private BackoffPolicy retryBackoffPolicy;
  @Nullable
  private ScheduledHandle rpcRetryTimer;

  /** An entity that manages ADS RPCs over a single channel. */
  // TODO: rename to XdsChannel
  ControlPlaneClient(
      XdsTransport xdsTransport,
      ServerInfo serverInfo,
      Node bootstrapNode,
      XdsResponseHandler xdsResponseHandler,
      ResourceStore resourceStore,
      Context context,
      ScheduledExecutorService
      timeService,
      SynchronizationContext syncContext,
      BackoffPolicy.Provider backoffPolicyProvider,
      Supplier<Stopwatch> stopwatchSupplier,
      XdsClient.TimerLaunch timerLaunch) {
    this.serverInfo = checkNotNull(serverInfo, "serverInfo");
    this.xdsTransport = checkNotNull(xdsTransport, "xdsTransport");
    this.xdsResponseHandler = checkNotNull(xdsResponseHandler, "xdsResponseHandler");
    this.resourceStore = checkNotNull(resourceStore, "resourcesSubscriber");
    this.bootstrapNode = checkNotNull(bootstrapNode, "bootstrapNode");
    this.context = checkNotNull(context, "context");
    this.timeService = checkNotNull(timeService, "timeService");
    this.syncContext = checkNotNull(syncContext, "syncContext");
    this.backoffPolicyProvider = checkNotNull(backoffPolicyProvider, "backoffPolicyProvider");
    this.timerLaunch  = checkNotNull(timerLaunch, "timerLaunch");
    stopwatch = checkNotNull(stopwatchSupplier, "stopwatchSupplier").get();
    logId = InternalLogId.allocate("xds-client", serverInfo.target());
    logger = XdsLogger.withLogId(logId);
    logger.log(XdsLogLevel.INFO, "Created");
  }

  void shutdown() {
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        shutdown = true;
        logger.log(XdsLogLevel.INFO, "Shutting down");
        if (adsStream != null) {
          adsStream.close(Status.CANCELLED.withDescription("shutdown").asException());
        }
        if (rpcRetryTimer != null && rpcRetryTimer.isPending()) {
          rpcRetryTimer.cancel();
        }
        xdsTransport.shutdown();
      }
    });
  }

  @Override
  public String toString() {
    return logId.toString();
  }

  /**
   * Updates the resource subscription for the given resource type.
   */
  // Must be synchronized.
  void adjustResourceSubscription(XdsResourceType<?> resourceType) {
    if (isInBackoff()) {
      return;
    }
    if (adsStream == null) {
      startRpcStream();
    }
    Collection<String> resources = resourceStore.getSubscribedResources(serverInfo, resourceType);
    if (resources != null) {
      adsStream.sendDiscoveryRequest(resourceType, resources);
    }
  }

  /**
   * Accepts the update for the given resource type by updating the latest resource version
   * and sends an ACK request to the management server.
   */
  // Must be synchronized.
  void ackResponse(XdsResourceType<?> type, String versionInfo, String nonce) {
    versions.put(type, versionInfo);
    logger.log(XdsLogLevel.INFO, "Sending ACK for {0} update, nonce: {1}, current version: {2}",
        type.typeName(), nonce, versionInfo);
    Collection<String> resources = resourceStore.getSubscribedResources(serverInfo, type);
    if (resources == null) {
      resources = Collections.emptyList();
    }
    adsStream.sendDiscoveryRequest(type, versionInfo, resources, nonce, null);
  }

  /**
   * Rejects the update for the given resource type and sends an NACK request (request with last
   * accepted version) to the management server.
   */
  // Must be synchronized.
  void nackResponse(XdsResourceType<?> type, String nonce, String errorDetail) {
    String versionInfo = versions.getOrDefault(type, "");
    logger.log(XdsLogLevel.INFO, "Sending NACK for {0} update, nonce: {1}, current version: {2}",
        type.typeName(), nonce, versionInfo);
    Collection<String> resources = resourceStore.getSubscribedResources(serverInfo, type);
    if (resources == null) {
      resources = Collections.emptyList();
    }
    adsStream.sendDiscoveryRequest(type, versionInfo, resources, nonce, errorDetail);
  }

  /**
   * Returns {@code true} if the resource discovery is currently in backoff.
   */
  // Must be synchronized.
  boolean isInBackoff() {
    return rpcRetryTimer != null && rpcRetryTimer.isPending();
  }

  // Must be synchronized.
  boolean isReady() {
    return adsStream != null && adsStream.call != null && adsStream.call.isReady();
  }

  /**
   * Starts a timer for each requested resource that hasn't been responded to and
   * has been waiting for the channel to get ready.
   */
  // Must be synchronized.
  void readyHandler() {
    if (!isReady()) {
      return;
    }

    if (isInBackoff()) {
      rpcRetryTimer.cancel();
      rpcRetryTimer = null;
    }

    timerLaunch.startSubscriberTimersIfNeeded(serverInfo);
  }

  /**
   * Establishes the RPC connection by creating a new RPC stream on the given channel for
   * xDS protocol communication.
   */
  // Must be synchronized.
  private void startRpcStream() {
    checkState(adsStream == null, "Previous adsStream has not been cleared yet");
    Context prevContext = context.attach();
    try {
      adsStream = new AdsStream();
    } finally {
      context.detach(prevContext);
    }
    logger.log(XdsLogLevel.INFO, "ADS stream started");
    stopwatch.reset().start();
  }

  @VisibleForTesting
  final class RpcRetryTask implements Runnable {
    @Override
    public void run() {
      if (shutdown) {
        return;
      }
      startRpcStream();
      Set<XdsResourceType<?>> subscribedResourceTypes =
          new HashSet<>(resourceStore.getSubscribedResourceTypesWithTypeUrl().values());
      for (XdsResourceType<?> type : subscribedResourceTypes) {
        Collection<String> resources = resourceStore.getSubscribedResources(serverInfo, type);
        if (resources != null) {
          adsStream.sendDiscoveryRequest(type, resources);
        }
      }
      xdsResponseHandler.handleStreamRestarted(serverInfo);
    }
  }

  @VisibleForTesting
  @Nullable
  XdsResourceType<?> fromTypeUrl(String typeUrl) {
    return resourceStore.getSubscribedResourceTypesWithTypeUrl().get(typeUrl);
  }

  private class AdsStream implements EventHandler<DiscoveryResponse> {
    private boolean responseReceived;
    private boolean closed;
    // Response nonce for the most recently received discovery responses of each resource type.
    // Client initiated requests start response nonce with empty string.
    // Nonce in each response is echoed back in the following ACK/NACK request. It is
    // used for management server to identify which response the client is ACKing/NACking.
    // To avoid confusion, client-initiated requests will always use the nonce in
    // most recently received responses of each resource type.
    private final Map<XdsResourceType<?>, String> respNonces = new HashMap<>();
    private final StreamingCall<DiscoveryRequest, DiscoveryResponse> call;
    private final MethodDescriptor<DiscoveryRequest, DiscoveryResponse> methodDescriptor =
        AggregatedDiscoveryServiceGrpc.getStreamAggregatedResourcesMethod();

    private AdsStream() {
      this.call = xdsTransport.createStreamingCall(methodDescriptor.getFullMethodName(),
          methodDescriptor.getRequestMarshaller(), methodDescriptor.getResponseMarshaller());
      call.start(this);
    }

    /**
     * Sends a discovery request with the given {@code versionInfo}, {@code nonce} and
     * {@code errorDetail}. Used for reacting to a specific discovery response. For
     * client-initiated discovery requests, use {@link
     * #sendDiscoveryRequest(XdsResourceType, Collection)}.
     */
    void sendDiscoveryRequest(XdsResourceType<?> type, String versionInfo,
                              Collection<String> resources, String nonce,
                              @Nullable String errorDetail) {
      DiscoveryRequest.Builder builder =
          DiscoveryRequest.newBuilder()
              .setVersionInfo(versionInfo)
              .setNode(bootstrapNode.toEnvoyProtoNode())
              .addAllResourceNames(resources)
              .setTypeUrl(type.typeUrl())
              .setResponseNonce(nonce);
      if (errorDetail != null) {
        com.google.rpc.Status error =
            com.google.rpc.Status.newBuilder()
                .setCode(Code.INVALID_ARGUMENT_VALUE)  // FIXME(chengyuanzhang): use correct code
                .setMessage(errorDetail)
                .build();
        builder.setErrorDetail(error);
      }
      DiscoveryRequest request = builder.build();
      call.sendMessage(request);
      if (logger.isLoggable(XdsLogLevel.DEBUG)) {
        logger.log(XdsLogLevel.DEBUG, "Sent DiscoveryRequest\n{0}", MessagePrinter.print(request));
      }
    }

    /**
     * Sends a client-initiated discovery request.
     */
    final void sendDiscoveryRequest(XdsResourceType<?> type, Collection<String> resources) {
      logger.log(XdsLogLevel.INFO, "Sending {0} request for resources: {1}", type, resources);
      sendDiscoveryRequest(type, versions.getOrDefault(type, ""), resources,
          respNonces.getOrDefault(type, ""), null);
    }

    @Override
    public void onReady() {
      syncContext.execute(ControlPlaneClient.this::readyHandler);
    }

    @Override
    public void onRecvMessage(DiscoveryResponse response) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          XdsResourceType<?> type = fromTypeUrl(response.getTypeUrl());
          if (logger.isLoggable(XdsLogLevel.DEBUG)) {
            logger.log(
                XdsLogLevel.DEBUG, "Received {0} response:\n{1}", type,
                MessagePrinter.print(response));
          }
          if (type == null) {
            logger.log(
                XdsLogLevel.WARNING,
                "Ignore an unknown type of DiscoveryResponse: {0}",
                response.getTypeUrl());

            call.startRecvMessage();
            return;
          }
          handleRpcResponse(type, response.getVersionInfo(), response.getResourcesList(),
              response.getNonce());
        }
      });
    }

    @Override
    public void onStatusReceived(final Status status) {
      syncContext.execute(() -> {
        if (status.isOk()) {
          handleRpcStreamClosed(Status.UNAVAILABLE.withDescription(CLOSED_BY_SERVER));
        } else {
          handleRpcStreamClosed(status);
        }
      });
    }

    final void handleRpcResponse(XdsResourceType<?> type, String versionInfo, List<Any> resources,
                                 String nonce) {
      checkNotNull(type, "type");
      if (closed) {
        return;
      }
      responseReceived = true;
      respNonces.put(type, nonce);
      ProcessingTracker processingTracker = new ProcessingTracker(
          () -> call.startRecvMessage(), syncContext);
      xdsResponseHandler.handleResourceResponse(type, serverInfo, versionInfo, resources, nonce,
          processingTracker);
      processingTracker.onComplete();
    }

    private void handleRpcStreamClosed(Status error) {
      if (closed) {
        return;
      }

      if (responseReceived || retryBackoffPolicy == null) {
        // Reset the backoff sequence if had received a response, or backoff sequence
        // has never been initialized.
        retryBackoffPolicy = backoffPolicyProvider.get();
      }
      // FakeClock in tests isn't thread-safe. Schedule the retry timer before notifying callbacks
      // to avoid TSAN races, since tests may wait until callbacks are called but then would run
      // concurrently with the stopwatch and schedule.
      long elapsed = stopwatch.elapsed(TimeUnit.NANOSECONDS);
      long delayNanos = Math.max(0, retryBackoffPolicy.nextBackoffNanos() - elapsed);
      rpcRetryTimer = syncContext.schedule(
          new RpcRetryTask(), delayNanos, TimeUnit.NANOSECONDS, timeService);

      checkArgument(!error.isOk(), "unexpected OK status");
      String errorMsg = error.getDescription() != null
          && error.getDescription().equals(CLOSED_BY_SERVER)
              ? "ADS stream closed with status {0}: {1}. Cause: {2}"
              : "ADS stream failed with status {0}: {1}. Cause: {2}";
      logger.log(
          XdsLogLevel.ERROR, errorMsg, error.getCode(), error.getDescription(), error.getCause());
      closed = true;
      xdsResponseHandler.handleStreamClosed(error);
      cleanUp();

      logger.log(XdsLogLevel.INFO, "Retry ADS stream in {0} ns", delayNanos);
    }

    private void close(Exception error) {
      if (closed) {
        return;
      }
      closed = true;
      cleanUp();
      call.sendError(error);
    }

    private void cleanUp() {
      if (adsStream == this) {
        adsStream = null;
      }
    }
  }
}
