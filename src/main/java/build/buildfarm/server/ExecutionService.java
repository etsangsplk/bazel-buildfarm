// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package build.buildfarm.server;

import static com.google.common.util.concurrent.Futures.addCallback;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.Futures.scheduleAsync;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.lang.String.format;
import static java.util.logging.Level.WARNING;

import build.bazel.remote.execution.v2.ExecuteRequest;
import build.bazel.remote.execution.v2.ExecutionGrpc;
import build.bazel.remote.execution.v2.WaitExecutionRequest;
import build.buildfarm.common.DigestUtil;
import build.buildfarm.common.Watcher;
import build.buildfarm.common.grpc.TracingMetadataUtils;
import build.buildfarm.instance.Instance;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.longrunning.Operation;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.grpc.stub.ServerCallStreamObserver;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import javax.annotation.Nullable;

public class ExecutionService extends ExecutionGrpc.ExecutionImplBase {
  public static final Logger logger = Logger.getLogger(ExecutionService.class.getName());

  private final Instances instances;
  private final long keepaliveAfter;
  private final TimeUnit keepaliveUnit;
  private final ScheduledExecutorService keepaliveScheduler;

  public ExecutionService(
      Instances instances,
      long keepaliveAfter,
      TimeUnit keepaliveUnit,
      ScheduledExecutorService keepaliveScheduler) {
    this.instances = instances;
    this.keepaliveAfter = keepaliveAfter;
    this.keepaliveUnit = keepaliveUnit;
    this.keepaliveScheduler = keepaliveScheduler;
  }

  private void logExecute(String instanceName, ExecuteRequest request) {
    logger.info(format("ExecutionSuccess: %s: %s", instanceName, DigestUtil.toString(request.getActionDigest())));
  }

  private void withCancellation(
      ServerCallStreamObserver<Operation> serverCallStreamObserver,
      ListenableFuture<Void> future) {
    addCallback(
        future,
        new FutureCallback<Void>() {
          boolean isCancelled() {
            return serverCallStreamObserver.isCancelled() || Context.current().isCancelled();
          }

          @Override
          public void onSuccess(Void result) {
            if (!isCancelled()) {
              try {
                serverCallStreamObserver.onCompleted();
              } catch (Exception e) {
                onFailure(e);
              }
            }
          }

          @Override
          public void onFailure(Throwable t) {
            if (!isCancelled() && !(t instanceof CancellationException)) {
              logger.log(WARNING, "error occurred during execution", t);
              serverCallStreamObserver.onError(Status.fromThrowable(t).asException());
            }
          }
        },
        Context.current().fixedContextExecutor(directExecutor()));
    serverCallStreamObserver.setOnCancelHandler(
        () -> future.cancel(false));
  }

  abstract class KeepaliveWatcher implements Watcher {
    private final ServerCallStreamObserver<Operation> serverCallStreamObserver;
    private ListenableFuture<?> keepaliveFuture = null;

    abstract void deliver(Operation operation);

    KeepaliveWatcher(ServerCallStreamObserver serverCallStreamObserver) {
      this.serverCallStreamObserver = serverCallStreamObserver;
      serverCallStreamObserver.setOnCancelHandler(this::cancel);
    }

    @Nullable ListenableFuture<?> getFuture() {
      return keepaliveFuture;
    }

    private synchronized void cancel() {
      if (keepaliveFuture != null) {
        keepaliveFuture.cancel(false);
        keepaliveFuture = null;
      }
    }

    @Override
    public final synchronized void observe(Operation operation) {
      cancel();
      if (operation == null) {
        throw Status.NOT_FOUND.asRuntimeException();
      }
      deliver(operation);
      keepaliveFuture = scheduleKeepalive(operation.getName());
    }

    private ListenableFuture<?> scheduleKeepalive(String operationName) {
      if (keepaliveAfter <= 0) {
        return null;
      }
      return scheduleAsync(
          () -> {
            deliverKeepalive(operationName);
            return immediateFuture(null);
          },
          keepaliveAfter,
          keepaliveUnit,
          keepaliveScheduler);
    }

    private synchronized void deliverKeepalive(String operationName) {
      if (!serverCallStreamObserver.isCancelled()) {
        try {
          deliver(Operation.newBuilder()
              .setName(operationName)
              .build());
          keepaliveFuture = scheduleKeepalive(operationName);
        } catch (IllegalStateException e) {
          if (!e.getMessage().equals("call is closed")) {
            throw e;
          }
        }
      }
    }
  }

  KeepaliveWatcher createWatcher(ServerCallStreamObserver<Operation> serverCallStreamObserver) {
    return new KeepaliveWatcher(serverCallStreamObserver) {
      @Override
      void deliver(Operation operation) {
        serverCallStreamObserver.onNext(operation);
      }
    };
  }

  @Override
  public void waitExecution(
      WaitExecutionRequest request, StreamObserver<Operation> responseObserver) {
    String operationName = request.getName();
    Instance instance;
    try {
      instance = instances.getFromOperationName(operationName);
    } catch (InstanceNotFoundException e) {
      responseObserver.onError(BuildFarmInstances.toStatusException(e));
      return;
    }

    ServerCallStreamObserver<Operation> serverCallStreamObserver =
        (ServerCallStreamObserver<Operation>) responseObserver;
    withCancellation(
        serverCallStreamObserver,
        instance.watchOperation(
            operationName,
            createWatcher(serverCallStreamObserver)));
  }

  @Override
  public void execute(
      ExecuteRequest request, StreamObserver<Operation> responseObserver) {
    Instance instance;
    try {
      instance = instances.get(request.getInstanceName());
    } catch (InstanceNotFoundException e) {
      responseObserver.onError(BuildFarmInstances.toStatusException(e));
      return;
    }

    logExecute(instance.getName(), request);

    ServerCallStreamObserver<Operation> serverCallStreamObserver =
        (ServerCallStreamObserver<Operation>) responseObserver;
    try {
      withCancellation(
          serverCallStreamObserver,
          instance.execute(
              request.getActionDigest(),
              request.getSkipCacheLookup(),
              request.getExecutionPolicy(),
              request.getResultsCachePolicy(),
              TracingMetadataUtils.fromCurrentContext(),
              createWatcher(serverCallStreamObserver)));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
