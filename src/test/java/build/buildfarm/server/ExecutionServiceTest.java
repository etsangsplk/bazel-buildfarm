package build.buildfarm.server;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.shutdownAndAwaitTermination;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import build.buildfarm.instance.Instance;
import build.buildfarm.server.ExecutionService.KeepaliveWatcher;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.longrunning.Operation;
import io.grpc.stub.ServerCallStreamObserver;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

@RunWith(JUnit4.class)
public class ExecutionServiceTest {
  private Instances instances;

  @Before
  public void setUp() throws Exception {
    instances = mock(Instances.class);
  }

  @Test
  public void keepaliveIsCancelledWithContext() throws Exception {
    ScheduledExecutorService keepaliveScheduler = newSingleThreadScheduledExecutor();
    ExecutionService service = new ExecutionService(
        instances,
        /* keepaliveAfter=*/ 1,
        /* keepaliveUnit=*/ SECONDS, // far enough in the future that we'll get scheduled and cancelled without executing
        keepaliveScheduler);
    ServerCallStreamObserver<Operation> response = mock(ServerCallStreamObserver.class);
    Operation operation = Operation.newBuilder()
        .setName("immediately-cancelled-watch-operation")
        .build();
    KeepaliveWatcher watcher = service.createWatcher(response);
    watcher.observe(operation);
    ListenableFuture<?> future = watcher.getFuture();
    assertThat(future).isNotNull();
    ArgumentCaptor<Runnable> onCancelHandlerCaptor = ArgumentCaptor.forClass(Runnable.class);
    verify(response, times(1)).setOnCancelHandler(onCancelHandlerCaptor.capture());
    Runnable onCancelHandler = onCancelHandlerCaptor.getValue();
    onCancelHandler.run();
    assertThat(future.isCancelled()).isTrue();
    assertThat(shutdownAndAwaitTermination(keepaliveScheduler, 1, SECONDS)).isTrue();
    // should only get one call for the real operation
    verify(response, times(1)).onNext(operation);
  }
}
