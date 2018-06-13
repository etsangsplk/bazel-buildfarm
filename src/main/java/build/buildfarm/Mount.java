package build.buildfarm;

import build.buildfarm.common.DigestUtil;
import build.buildfarm.instance.Instance;
import build.buildfarm.instance.stub.ByteStreamUploader;
import build.buildfarm.instance.stub.Retrier;
import build.buildfarm.instance.stub.StubInstance;
import build.buildfarm.worker.FuseCAS;
import build.buildfarm.worker.Fetcher;
import com.google.devtools.remoteexecution.v1test.Digest;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

class Mount {
  private static ManagedChannel createChannel(String target) {
    NettyChannelBuilder builder =
        NettyChannelBuilder.forTarget(target)
            .negotiationType(NegotiationType.PLAINTEXT);
    return builder.build();
  }

  public static void main(String[] args) throws Exception {
    String host = args[0];
    String instanceName = args[1];
    DigestUtil digestUtil = DigestUtil.forHash(args[2]);
    ManagedChannel channel = createChannel(host);
    Instance instance = new StubInstance(
        instanceName,
        digestUtil,
        channel,
        10, TimeUnit.SECONDS,
        Retrier.NO_RETRIES,
        new ByteStreamUploader("", channel, null, 300, Retrier.NO_RETRIES, null));

    Path cwd = Paths.get(".");

    FuseCAS fuse = new FuseCAS(cwd.resolve(args[3]), new Fetcher() {
      Map<Digest, ByteString> cache = new HashMap<>();

      public synchronized ByteString fetchBlob(Digest blobDigest) {
        if (cache.containsKey(blobDigest)) {
          return cache.get(blobDigest);
        }
        try {
          ByteString value = instance.getBlob(blobDigest);
          cache.put(blobDigest, value);
          return value;
        } catch (IOException e) {
          return null;
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return null;
        }
      }
    });
    
    // FIXME make bettar
    fuse.createInputRoot(args[5], DigestUtil.parseDigest(args[4]));

    try {
      for (;;) {
        Thread.currentThread().sleep(1000);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      fuse.stop();
    }
  }
};