// Copyright 2011 Square, Inc.
package com.segment.analytics;

import java.io.IOException;

/**
 * A queue of objects.
 *
 * @param <T> The type of queue for the elements.
 */
interface ObjectQueue<T> {

  /** Returns the number of entries in the queue. */
  int size();

  /** Enqueues an entry that can be processed at any time. */
  void add(T entry) throws IOException;

  /**
   * Returns the head of the queue, or {@code null} if the queue is empty. Does not modify the
   * queue.
   */
  T peek() throws IOException;

  /** Removes the head of the queue. */
  void remove() throws IOException;

  /**
   * Sets a listener on this queue. Invokes {@link Listener#onAdd} once for each entry that's
   * already in the queue. If an error occurs while reading the data, the listener will not receive
   * further notifications.
   */
  void setListener(Listener<T> listener) throws IOException;

  /**
   * Listens for changes to the queue.
   *
   * @param <T> The type of elements in the queue.
   */
  interface Listener<T> {

    /** Called after an entry is added. */
    void onAdd(ObjectQueue<T> queue, T entry);

    /** Called after an entry is removed. */
    void onRemove(ObjectQueue<T> queue);
  }
}