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

package build.buildfarm.proxy.http;

import java.io.OutputStream;

abstract class ChunkOutputStream extends OutputStream {
  private final byte[] buffer;
  int buflen = 0;

  ChunkOutputStream(int size) {
    buffer = new byte[size];
  }

  abstract void onChunk(byte[] b, int off, int len);

  @Override
  public void close() {
    flush();
  }

  @Override
  public void flush() {
    if (buflen > 0) {
      onChunk(buffer, 0, buflen);
      buflen = 0;
    }
  }

  @Override
  public void write(byte[] b) {
    write(b, 0, b.length);
  }

  @Override
  public void write(byte[] b, int off, int len) {
    while (buflen + len >= buffer.length) {
      int copylen = buffer.length - buflen;
      System.arraycopy(b, off, buffer, buflen, copylen);
      buflen = buffer.length;
      flush();
      len -= copylen;
      off += copylen;
      if (len == 0) {
        return;
      }
    }
    System.arraycopy(b, off, buffer, buflen, len);
    buflen += len;
  }

  @Override
  public void write(int b) {
    buffer[buflen++] = (byte) b;
    if (buflen == buffer.length) {
      flush();
    }
  }
};

