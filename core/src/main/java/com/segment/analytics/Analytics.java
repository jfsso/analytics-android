/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2014 Segment.io, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.segment.analytics;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import java.util.Map;

import static com.segment.analytics.IntegrationManager.ActivityLifecyclePayload;
import static com.segment.analytics.IntegrationManager.ActivityLifecyclePayload.Type.CREATED;
import static com.segment.analytics.IntegrationManager.ActivityLifecyclePayload.Type.DESTROYED;
import static com.segment.analytics.IntegrationManager.ActivityLifecyclePayload.Type.PAUSED;
import static com.segment.analytics.IntegrationManager.ActivityLifecyclePayload.Type.RESUMED;
import static com.segment.analytics.IntegrationManager.ActivityLifecyclePayload.Type.SAVE_INSTANCE;
import static com.segment.analytics.IntegrationManager.ActivityLifecyclePayload.Type.STARTED;
import static com.segment.analytics.IntegrationManager.ActivityLifecyclePayload.Type.STOPPED;
import static com.segment.analytics.Utils.OWNER_MAIN;
import static com.segment.analytics.Utils.VERB_CREATE;
import static com.segment.analytics.Utils.checkMain;
import static com.segment.analytics.Utils.debug;
import static com.segment.analytics.Utils.getResourceBooleanOrThrow;
import static com.segment.analytics.Utils.getResourceIntegerOrThrow;
import static com.segment.analytics.Utils.getResourceString;
import static com.segment.analytics.Utils.hasPermission;
import static com.segment.analytics.Utils.isNullOrEmpty;

/**
 * The idea is simple: one pipeline for all your data.
 * <p/>
 * Use {@link #with(android.content.Context)} for the global singleton instance or construct your
 * own instance with {@link Builder}.
 *
 * @see <a href="https://segment.io/">Segment.io</a>
 */
public class Analytics {
  private static final Properties EMPTY_PROPERTIES = new Properties();
  // Resource identifiers to define options in xml
  static final String WRITE_KEY_RESOURCE_IDENTIFIER = "analytics_write_key";
  static final String QUEUE_SIZE_RESOURCE_IDENTIFIER = "analytics_queue_size";
  static final String LOGGING_RESOURCE_IDENTIFIER = "analytics_logging";

  static Analytics singleton = null;

  /**
   * The global default {@link Analytics} instance.
   * <p/>
   * This instance is automatically initialized with defaults that are suitable to most
   * implementations.
   * <p/>
   * If these settings do not meet the requirements of your application, you can provide properties
   * in {@code analytics.xml} or you can construct your own instance with full control over the
   * configuration by using {@link Builder}.
   */
  public static Analytics with(Context context) {
    if (singleton == null) {
      if (context == null) {
        throw new IllegalArgumentException("Context must not be null.");
      }
      synchronized (Analytics.class) {
        if (singleton == null) {
          String writeKey = getResourceString(context, WRITE_KEY_RESOURCE_IDENTIFIER);
          Builder builder = new Builder(context, writeKey);
          try {
            // We need the exception to be able to tell if this was not defined, or if it was
            // incorrectly defined - something we shouldn't ignore
            int maxQueueSize = getResourceIntegerOrThrow(context, QUEUE_SIZE_RESOURCE_IDENTIFIER);
            if (maxQueueSize <= 0) {
              throw new IllegalStateException(QUEUE_SIZE_RESOURCE_IDENTIFIER
                  + "("
                  + maxQueueSize
                  + ") may not be zero or negative.");
            }
            builder.maxQueueSize(maxQueueSize);
          } catch (Resources.NotFoundException e) {
            // when maxQueueSize is not defined in xml, we'll use a default option in the builder
          }
          try {
            boolean logging = getResourceBooleanOrThrow(context, LOGGING_RESOURCE_IDENTIFIER);
            builder.logging(logging);
          } catch (Resources.NotFoundException notFoundException) {
            // when debugging is not defined in xml, we'll try to figure it out from package flags
            String packageName = context.getPackageName();
            try {
              final int flags =
                  context.getPackageManager().getApplicationInfo(packageName, 0).flags;
              boolean logging = (flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
              builder.logging(logging);
            } catch (PackageManager.NameNotFoundException nameNotFoundException) {
              // if we still can't figure it out, we'll use the default options in the builder
            }
          }
          singleton = builder.build();
        }
      }
    }
    return singleton;
  }

  /** Fluent API for creating {@link Analytics} instances. */
  @SuppressWarnings("UnusedDeclaration") // Public API.
  public static class Builder {
    static final int DEFAULT_QUEUE_SIZE = 20;
    static final boolean DEFAULT_LOGGING = false;

    private final Application application;
    private String writeKey;
    private String tag;
    private int maxQueueSize = -1;
    private Options defaultOptions;
    private boolean loggingEnabled = DEFAULT_LOGGING;

    /** Start building a new {@link Analytics} instance. */
    public Builder(Context context, String writeKey) {
      if (context == null) {
        throw new IllegalArgumentException("Context must not be null.");
      }
      if (!hasPermission(context, Manifest.permission.INTERNET)) {
        throw new IllegalArgumentException("INTERNET permission is required.");
      }

      if (isNullOrEmpty(writeKey)) {
        throw new IllegalArgumentException("writeKey must not be null or empty.");
      }

      application = (Application) context.getApplicationContext();
      this.writeKey = writeKey;
    }

    /** Set the queue size at which we should flush events. */
    public Builder maxQueueSize(int maxQueueSize) {
      if (maxQueueSize <= 0) {
        throw new IllegalArgumentException("maxQueueSize must be greater than or equal to zero.");
      }
      if (this.maxQueueSize != -1) {
        throw new IllegalStateException("maxQueueSize is already set.");
      }
      this.maxQueueSize = maxQueueSize;
      return this;
    }

    /**
     * Set some default options for all calls. This options should not contain a timestamp. You
     * won't be able to change the integrations specified in this options object.
     */
    public Builder defaultOptions(Options defaultOptions) {
      if (defaultOptions == null) {
        throw new IllegalArgumentException("defaultOptions must not be null.");
      }
      if (defaultOptions.timestamp() != null) {
        throw new IllegalArgumentException("default option must not contain timestamp.");
      }
      if (this.defaultOptions != null) {
        throw new IllegalStateException("defaultOptions is already set.");
      }
      // Make a defensive copy
      this.defaultOptions = new Options();
      for (Map.Entry<String, Boolean> entry : defaultOptions.integrations().entrySet()) {
        this.defaultOptions.setIntegration(entry.getKey(), entry.getValue());
      }
      return this;
    }

    /**
     * Set a tag for this instance. The tag is used to generate keys for caching. By default the
     * writeKey is used, but you may want to specify an alternative one, if you want the instances
     * to share different caches. For example, without this tag, all instances will share the same
     * traits. By specifying a custom tag for each instance of the client, all instance will have a
     * different traits instance.
     */
    public Builder tag(String tag) {
      if (isNullOrEmpty(tag)) {
        throw new IllegalArgumentException("tag must not be null or empty.");
      }
      if (this.tag != null) {
        throw new IllegalStateException("tag is already set.");
      }
      this.tag = tag;
      return this;
    }

    /** Set whether debugging is enabled or not. */
    public Builder logging(boolean loggingEnabled) {
      this.loggingEnabled = loggingEnabled;
      return this;
    }

    /** Create Segment {@link Analytics} instance. */
    public Analytics build() {
      if (maxQueueSize == -1) {
        maxQueueSize = DEFAULT_QUEUE_SIZE;
      }
      if (defaultOptions == null) {
        defaultOptions = new Options();
      }
      if (isNullOrEmpty(tag)) tag = writeKey;

      Stats stats = new Stats();
      SegmentHTTPApi segmentHTTPApi = new SegmentHTTPApi(writeKey);
      IntegrationManager integrationManager =
          IntegrationManager.create(application, segmentHTTPApi, stats, loggingEnabled);
      Dispatcher dispatcher = Dispatcher.create(application, maxQueueSize, segmentHTTPApi,
          integrationManager.serverIntegrations, tag, stats, loggingEnabled);
      TraitsCache traitsCache = new TraitsCache(application, tag);
      AnalyticsContext analyticsContext = new AnalyticsContext(application, traitsCache.get());

      return new Analytics(application, dispatcher, integrationManager, stats, traitsCache,
          analyticsContext, defaultOptions, loggingEnabled);
    }
  }

  static final Handler MAIN_LOOPER = new Handler(Looper.getMainLooper()) {
    @Override public void handleMessage(Message msg) {
      switch (msg.what) {
        default:
          throw new AssertionError("Unknown handler message received: " + msg.what);
      }
    }
  };

  final Application application;
  final Dispatcher dispatcher;
  final IntegrationManager integrationManager;
  final Stats stats;
  final TraitsCache traitsCache;
  final AnalyticsContext analyticsContext;
  final Options defaultOptions;
  final boolean loggingEnabled;
  boolean shutdown;

  Analytics(Application application, Dispatcher dispatcher, IntegrationManager integrationManager,
      Stats stats, TraitsCache traitsCache, AnalyticsContext analyticsContext,
      Options defaultOptions, boolean loggingEnabled) {
    this.application = application;
    this.dispatcher = dispatcher;
    this.integrationManager = integrationManager;
    this.stats = stats;
    this.traitsCache = traitsCache;
    this.analyticsContext = analyticsContext;
    this.defaultOptions = defaultOptions;
    this.loggingEnabled = loggingEnabled;

    application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
      @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        submit(new ActivityLifecyclePayload(CREATED, activity, savedInstanceState));
      }

      @Override public void onActivityStarted(Activity activity) {
        submit(new ActivityLifecyclePayload(STARTED, activity, null));
      }

      @Override public void onActivityResumed(Activity activity) {
        submit(new ActivityLifecyclePayload(RESUMED, activity, null));
      }

      @Override public void onActivityPaused(Activity activity) {
        submit(new ActivityLifecyclePayload(PAUSED, activity, null));
      }

      @Override public void onActivityStopped(Activity activity) {
        submit(new ActivityLifecyclePayload(STOPPED, activity, null));
      }

      @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        submit(new ActivityLifecyclePayload(SAVE_INSTANCE, activity, outState));
      }

      @Override public void onActivityDestroyed(Activity activity) {
        submit(new ActivityLifecyclePayload(DESTROYED, activity, null));
      }
    });
  }

  /** Returns {@code true} if logging is enabled. */
  public boolean isLogging() {
    return loggingEnabled;
  }

  /**
   * Identify a user with an id in your own database without any traits.
   *
   * @see {@link #identify(String, Traits, Options)}
   */
  public void identify(String userId) {
    identify(userId, null, defaultOptions);
  }

  /**
   * Associate traits with the current user, identified or not.
   *
   * @see {@link #identify(String, Traits, Options)}
   */
  public void identify(Traits traits) {
    identify(null, traits, null);
  }

  /**
   * Identify lets you tie one of your users and their actions to a recognizable {@code userId}. It
   * also lets you record {@code traits} about the user, like their email, name, account type, etc.
   *
   * @param userId Unique identifier which you recognize a user by in your own database. If this is
   * null or empty, any previous id we have (could be the anonymous id) will be
   * used.
   * @param traits Traits about the user
   * @param options To configure the call
   * @throws IllegalArgumentException if userId is null or an empty string
   * @see <a href="https://segment.io/docs/tracking-api/identify/">Identify Documentation</a>
   */
  public void identify(String userId, Traits traits, Options options) {
    if (!isNullOrEmpty(userId)) {
      traitsCache.get().putUserId(userId);
    }
    if (options == null) {
      options = defaultOptions;
    }
    if (!isNullOrEmpty(traits)) {
      traitsCache.get().merge(traits);
      traitsCache.save();
    }

    BasePayload payload = new IdentifyPayload(traitsCache.get().anonymousId(), analyticsContext,
        traitsCache.get().userId(), traitsCache.get(), options);
    submit(payload);
  }

  /**
   * @see {@link #group(String, String, Traits, Options)}
   */
  public void group(String groupId) {
    group(null, groupId, null, null);
  }

  /**
   * The group method lets you associate a user with a group. It also lets you record custom traits
   * about the group, like industry or number of employees.
   * <p/>
   * If you've called {@link #identify(String, Traits, Options)} before, this will automatically
   * remember the user id. If not, it will fall back to use the anonymousId instead.
   *
   * @param userId To match up a user with their associated group.
   * @param groupId Unique identifier which you recognize a group by in your own database. Must not
   * be null or empty.
   * @param options To configure the call
   * @throws IllegalArgumentException if groupId is null or an empty string
   * @see <a href=" https://segment.io/docs/tracking-api/group/">Group Documentation</a>
   */
  public void group(String userId, String groupId, Traits traits, Options options) {
    if (isNullOrEmpty(groupId)) {
      throw new IllegalArgumentException("groupId must be null or empty.");
    }

    if (isNullOrEmpty(userId)) {
      userId = traitsCache.get().userId();
    }
    if (!isNullOrEmpty(traits)) {
      traitsCache.get().merge(traits);
      traitsCache.save();
    }
    if (options == null) {
      options = defaultOptions;
    }

    BasePayload payload =
        new GroupPayload(traitsCache.get().anonymousId(), analyticsContext, userId, groupId,
            traitsCache.get(), options);

    submit(payload);
  }

  /**
   * @see {@link #track(String, Properties, Options)}
   */
  public void track(String event) {
    track(event, null, null);
  }

  /**
   * @see {@link #track(String, Properties, Options)}
   */
  public void track(String event, Properties properties) {
    track(event, properties, null);
  }

  /**
   * The track method is how you record any actions your users perform. Each action is known by a
   * name, like 'Purchased a T-Shirt'. You can also record properties specific to those actions.
   * For
   * example a 'Purchased a Shirt' event might have properties like revenue or size.
   *
   * @param event Name of the event. Must not be null or empty.
   * @param properties {@link Properties} to add extra information to this call
   * @param options To configure the call
   * @throws IllegalArgumentException if event name is null or an empty string
   * @see <a href="https://segment.io/docs/tracking-api/track/">Track Documentation</a>
   */
  public void track(String event, Properties properties, Options options) {
    if (isNullOrEmpty(event)) {
      throw new IllegalArgumentException("event must not be null or empty.");
    }
    if (properties == null) {
      properties = EMPTY_PROPERTIES;
    }
    if (options == null) {
      options = defaultOptions;
    }

    BasePayload payload = new TrackPayload(traitsCache.get().anonymousId(), analyticsContext,
        traitsCache.get().userId(), event, properties, options);
    submit(payload);
  }

  /**
   * @see {@link #screen(String, String, Properties, Options)}
   */
  public void screen(String category, String name) {
    screen(category, name, null, null);
  }

  /**
   * @see {@link #screen(String, String, Properties, Options)}
   */
  public void screen(String category, String name, Properties properties) {
    screen(category, name, properties, null);
  }

  /**
   * The screen methods let your record whenever a user sees a screen of your mobile app, and
   * attach
   * a name, category or properties to the screen.
   * <p/>
   * Either category or name must be provided.
   *
   * @param category A category to describe the screen
   * @param name A name for the screen
   * @param properties {@link Properties} to add extra information to this call
   * @param options To configure the call
   * @see <a href="http://segment.io/docs/tracking-api/page-and-screen/">Screen Documentation</a>
   */
  public void screen(String category, String name, Properties properties, Options options) {
    if (isNullOrEmpty(category) && isNullOrEmpty(name)) {
      throw new IllegalArgumentException("either category or name must be provided.");
    }

    if (properties == null) {
      properties = EMPTY_PROPERTIES;
    }
    if (options == null) {
      options = defaultOptions;
    }

    BasePayload payload = new ScreenPayload(traitsCache.get().anonymousId(), analyticsContext,
        traitsCache.get().userId(), category, name, properties, options);
    submit(payload);
  }

  /**
   * @see {@link #alias(String, String, Options)}
   */
  public void alias(String newId, String previousId) {
    alias(newId, previousId, null);
  }

  /**
   * The alias method is used to merge two user identities, effectively connecting two sets of user
   * data as one. This is an advanced method, but it is required to manage user identities
   * successfully in some of our integrations. You should still call {@link #identify(String,
   * Traits, Options)} with {@code newId} if you want to use it as the default id.
   *
   * @param newId The newId to map the old id to. Must not be null to empty.
   * @param previousId The old id we want to map. If it is null, the userId we've cached will
   * automatically used.
   * @param options To configure the call
   * @throws IllegalArgumentException if newId is null or empty
   * @see <a href="https://segment.io/docs/tracking-api/alias/">Alias Documentation</a>
   */
  public void alias(String newId, String previousId, Options options) {
    if (isNullOrEmpty(newId)) {
      throw new IllegalArgumentException("newId must not be null or empty.");
    }
    if (isNullOrEmpty(previousId)) {
      previousId = traitsCache.get().userId();
    }
    if (options == null) {
      options = defaultOptions;
    }

    BasePayload payload = new AliasPayload(traitsCache.get().anonymousId(), analyticsContext,
        traitsCache.get().userId(), previousId, options);
    submit(payload);
  }

  /**
   * Flush all the messages in the queue. This wil do nothing for bundled integrations that don't
   * have an explicit flush method.
   */
  public void flush() {
    dispatcher.dispatchFlush();
    integrationManager.flush();
  }

  /** Get the {@link AnalyticsContext} used by this instance. */
  public AnalyticsContext getAnalyticsContext() {
    return analyticsContext;
  }

  /** Creates a {@link StatsSnapshot} of the current stats for this instance. */
  public StatsSnapshot getSnapshot() {
    return stats.createSnapshot();
  }

  /** Clear any information about the current user. */
  public void logout() {
    traitsCache.delete(application);
    analyticsContext.putTraits(traitsCache.get());
  }

  /** Stops this instance from accepting further requests. */
  public void shutdown() {
    if (this == singleton) {
      throw new UnsupportedOperationException("Default singleton instance cannot be shutdown.");
    }
    if (shutdown) {
      return;
    }
    integrationManager.shutdown();
    stats.shutdown();
    dispatcher.shutdown();
    shutdown = true;
  }

  public interface OnIntegrationReadyListener {
    void onIntegrationReady(String key, Object integration);
  }

  /**
   * Register to be notified when a bundled integration is ready. <p></p> This must be called from
   * the main thread.
   */
  public void onIntegrationReady(OnIntegrationReadyListener onIntegrationReadyListener) {
    checkMain();
    integrationManager.registerIntegrationInitializedListener(onIntegrationReadyListener);
  }

  void submit(BasePayload payload) {
    if (loggingEnabled) {
      debug(OWNER_MAIN, VERB_CREATE, payload.id(), "type: " + payload.type());
    }
    dispatcher.dispatchEnqueue(payload);
    integrationManager.submit(payload);
  }

  void submit(ActivityLifecyclePayload payload) {
    if (loggingEnabled) {
      debug(OWNER_MAIN, VERB_CREATE, payload.id(),
          "type: " + payload.type.toString().toLowerCase());
    }
    integrationManager.submit(payload);
  }
}
