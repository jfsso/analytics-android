// Copyright 2012 Square, Inc.
package com.segment.analytics;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Base queue class, implements common functionality for a QueueFile-backed
 * queue manager.  This class is not thread safe; instances should be kept
 * thread-confined.
 *
 * @param <T> The type of elements in the queue.
 */
class FileObjectQueue<T> implements ObjectQueue<T> {
  /** Backing storage implementation. */
  private final QueueFile queueFile;
  /** Reusable byte output buffer. */
  private final DirectByteArrayOutputStream bytes = new DirectByteArrayOutputStream();
  private final Converter<T> converter;
  private Listener<T> listener;

  FileObjectQueue(File file, Converter<T> converter) throws IOException {
    this.converter = converter;
    this.queueFile = new QueueFile(file);
  }

  @Override public int size() {
    return 0;
  }

  @Override public final void add(T entry) throws IOException {
    bytes.reset();
    converter.toStream(entry, bytes);
    queueFile.add(bytes.getArray(), 0, bytes.size());
    if (listener != null) listener.onAdd(this, entry);
  }

  @Override public T peek() throws IOException {
    byte[] bytes = queueFile.peek();
    if (bytes == null) return null;
    return converter.from(bytes);
  }

  @Override public final void remove() throws IOException {
    queueFile.remove();
    if (listener != null) listener.onRemove(this);
  }

  final void close() throws IOException {
    queueFile.close();
  }

  @Override public void setListener(final Listener<T> listener) throws IOException {
    if (listener != null) {
      queueFile.forEach(new QueueFile.ElementReader() {
        @Override
        public void read(InputStream in, int length) throws IOException {
          byte[] data = new byte[length];
          in.read(data, 0, length);
          listener.onAdd(FileObjectQueue.this, converter.from(data));
        }
      });
    }
    this.listener = listener;
  }

  /**
   * Convert a byte stream to and from a concrete type.
   *
   * @param <T> Object type.
   */
  interface Converter<T> {
    /** Converts bytes to an object. */
    T from(byte[] bytes) throws IOException;

    /** Converts o to bytes written to the specified stream. */
    void toStream(T o, OutputStream bytes) throws IOException;
  }

  /** Enables direct access to the internal array. Avoids unnecessary copying. */
  private static class DirectByteArrayOutputStream extends ByteArrayOutputStream {
    DirectByteArrayOutputStream() {
      super();
    }

    /**
     * Gets a reference to the internal byte array.  The {@link #size()} method indicates how many
     * bytes contain actual data added since the last {@link #reset()} call.
     */
    byte[] getArray() {
      return buf;
    }
  }
}