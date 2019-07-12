// Copyright 2019 The Bazel Authors. All rights reserved.
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

package build.buildfarm.common;

import static com.google.common.io.ByteStreams.nullOutputStream;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

public interface Write {
  long getCommittedSize();

  boolean isComplete();

  OutputStream getOutput(long deadlineAfter, TimeUnit deadlineAfterUnits) throws IOException;

  void reset();

  /** add a callback to be invoked when blob has been completed */
  void addListener(Runnable onCompleted, Executor executor);

  public static class CompleteWrite implements Write {
    private final long committedSize;

    public CompleteWrite(long committedSize) {
      this.committedSize = committedSize;
    }

    @Override
    public long getCommittedSize() {
      return committedSize;
    }

    @Override
    public boolean isComplete() {
      return true;
    }

    @Override
    public OutputStream getOutput(long deadlineAfter, TimeUnit deadlineAfterUnits) {
      return nullOutputStream();
    }

    @Override
    public void reset() {
    }

    @Override
    public void addListener(Runnable onCompleted, Executor executor) {
      executor.execute(onCompleted);
    }
  }
}
