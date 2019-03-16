// Copyright 2018 The Bazel Authors. All rights reserved.
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

package build.buildfarm.worker.operationqueue;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.DirectoryNode;
import build.bazel.remote.execution.v2.FileNode;
import build.bazel.remote.execution.v2.OutputDirectory;
import build.bazel.remote.execution.v2.Tree;
import build.buildfarm.common.DigestUtil;
import build.buildfarm.instance.stub.ByteStreamUploader;
import build.buildfarm.instance.stub.Chunker;
import build.buildfarm.v1test.CASInsertionPolicy;
import build.buildfarm.v1test.WorkerConfig;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.google.protobuf.ByteString;
import io.grpc.StatusException;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.naming.ConfigurationException;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.junit.Before;
import org.junit.Test;

public class UploadOutputsTest {
  private final Configuration config;

  private FileSystem fileSystem;
  private Path root;

  @Mock
  private ByteStreamUploader mockUploader;

  private DigestUtil digestUtil;

  protected UploadOutputsTest(Configuration config) {
    this.config = config.toBuilder()
        .setAttributeViews("posix")
        .build();
  }

  @Before
  public void setUp() throws ConfigurationException {
    MockitoAnnotations.initMocks(this);

    fileSystem = Jimfs.newFileSystem(config);
    root = Iterables.getFirst(fileSystem.getRootDirectories(), null);

    digestUtil = new DigestUtil(DigestUtil.HashFunction.SHA256);
    fileSystem = Jimfs.newFileSystem(config);
  }

  @Test
  public void uploadOutputsUploadsEmptyOutputDirectories()
      throws IOException, StatusException, InterruptedException {
    Files.createDirectory(root.resolve("foo"));
    // maybe make some files...
    ActionResult.Builder resultBuilder = ActionResult.newBuilder();
    Worker.uploadOutputs(
        resultBuilder,
        digestUtil,
        root,
        ImmutableList.<String>of(),
        ImmutableList.<String>of("foo"),
        mockUploader,
        /* inlineContentLimit=*/ 0,
        CASInsertionPolicy.ALWAYS_INSERT,
        CASInsertionPolicy.ALWAYS_INSERT,
        CASInsertionPolicy.ALWAYS_INSERT);
    Tree emptyTree = Tree.newBuilder()
        .setRoot(Directory.getDefaultInstance())
        .build();
    verify(mockUploader)
        .uploadBlobs(eq(ImmutableList.<Chunker>of(
            new Chunker(emptyTree.toByteString(), digestUtil.compute(emptyTree)))));
    assertThat(resultBuilder.getOutputDirectoriesList()).containsExactly(
        OutputDirectory.newBuilder()
            .setPath("foo")
            .setTreeDigest(digestUtil.compute(emptyTree))
            .build());
  }

  @Test
  public void uploadOutputsUploadsFiles()
      throws IOException, StatusException, InterruptedException {
    Path topdir = root.resolve("foo");
    Files.createDirectory(topdir);
    Path file = topdir.resolve("bar");
    Files.createFile(file);
    // maybe make some files...
    ActionResult.Builder resultBuilder = ActionResult.newBuilder();
    Worker.uploadOutputs(
        resultBuilder,
        digestUtil,
        root,
        ImmutableList.<String>of(),
        ImmutableList.<String>of("foo"),
        mockUploader,
        /* inlineContentLimit=*/ 0,
        CASInsertionPolicy.ALWAYS_INSERT,
        CASInsertionPolicy.ALWAYS_INSERT,
        CASInsertionPolicy.ALWAYS_INSERT);
    Tree tree = Tree.newBuilder()
        .setRoot(Directory.newBuilder()
            .addFiles(FileNode.newBuilder()
                .setName("bar")
                .setDigest(digestUtil.empty())
                .setIsExecutable(Files.isExecutable(file))
                .build())
            .build())
        .build();
    verify(mockUploader)
        .uploadBlobs(eq(ImmutableList.<Chunker>of(
            new Chunker(ByteString.EMPTY, digestUtil.empty()),
            new Chunker(tree.toByteString(), digestUtil.compute(tree)))));
    assertThat(resultBuilder.getOutputDirectoriesList()).containsExactly(
        OutputDirectory.newBuilder()
            .setPath("foo")
            .setTreeDigest(digestUtil.compute(tree))
            .build());
  }

  @Test
  public void uploadOutputsUploadsNestedDirectories()
      throws IOException, StatusException, InterruptedException {
    Path topdir = root.resolve("foo");
    Files.createDirectory(topdir);
    Path subdir = topdir.resolve("bar");
    Files.createDirectory(subdir);
    Path file = subdir.resolve("baz");
    Files.createFile(file);
    // maybe make some files...
    ActionResult.Builder resultBuilder = ActionResult.newBuilder();
    Worker.uploadOutputs(
        resultBuilder,
        digestUtil,
        root,
        ImmutableList.<String>of(),
        ImmutableList.<String>of("foo"),
        mockUploader,
        /* inlineContentLimit=*/ 0,
        CASInsertionPolicy.ALWAYS_INSERT,
        CASInsertionPolicy.ALWAYS_INSERT,
        CASInsertionPolicy.ALWAYS_INSERT);
    Directory subDirectory = Directory.newBuilder()
        .addFiles(FileNode.newBuilder()
            .setName("baz")
            .setDigest(digestUtil.empty())
            .setIsExecutable(Files.isExecutable(file))
            .build())
        .build();
    Tree tree = Tree.newBuilder()
        .setRoot(Directory.newBuilder()
            .addDirectories(DirectoryNode.newBuilder()
                .setName("bar")
                .setDigest(digestUtil.compute(subDirectory))
                .build())
            .build())
        .addChildren(subDirectory)
        .build();
    verify(mockUploader)
        .uploadBlobs(eq(ImmutableList.<Chunker>of(
            new Chunker(ByteString.EMPTY, digestUtil.empty()),
            new Chunker(tree.toByteString(), digestUtil.compute(tree)))));
    assertThat(resultBuilder.getOutputDirectoriesList()).containsExactly(
        OutputDirectory.newBuilder()
            .setPath("foo")
            .setTreeDigest(digestUtil.compute(tree))
            .build());
  }

  @Test
  public void uploadOutputsIgnoresMissingOutputDirectories()
      throws IOException, StatusException, InterruptedException {
    ActionResult.Builder resultBuilder = ActionResult.newBuilder();
    Worker.uploadOutputs(
        resultBuilder,
        digestUtil,
        root,
        ImmutableList.<String>of(),
        ImmutableList.<String>of("foo"),
        mockUploader,
        /* inlineContentLimit=*/ 0,
        CASInsertionPolicy.ALWAYS_INSERT,
        CASInsertionPolicy.ALWAYS_INSERT,
        CASInsertionPolicy.ALWAYS_INSERT);
    Tree emptyTree = Tree.newBuilder()
        .setRoot(Directory.getDefaultInstance())
        .build();
    verify(mockUploader, never())
        .uploadBlobs(any());
  }
}
