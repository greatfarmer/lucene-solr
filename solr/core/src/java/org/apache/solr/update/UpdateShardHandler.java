/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.update;

import java.lang.invoke.MethodHandles;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.apache.http.client.HttpClient;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.PoolingClientConnectionManager;
import org.apache.http.impl.conn.SchemeRegistryFactory;
import org.apache.http.util.Args;
import org.apache.solr.client.solrj.impl.HttpClientConfigurer;
import org.apache.solr.client.solrj.impl.HttpClientUtil;
import org.apache.solr.cloud.RecoveryStrategy;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.ExecutorUtil;
import org.apache.solr.common.util.IOUtils;
import org.apache.solr.common.util.SolrjNamedThreadFactory;
import org.apache.solr.core.NodeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UpdateShardHandler {
  
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  /*
   * A downside to configuring an upper bound will be big update reorders (when that upper bound is hit)
   * and then undetected shard inconsistency as a result.
   * This update executor is used for different things too... both update streams (which may be very long lived)
   * and control messages (peersync? LIR?) and could lead to starvation if limited.
   * Therefore this thread pool is left unbounded. See SOLR-8205
   */
  private ExecutorService updateExecutor = ExecutorUtil.newMDCAwareCachedThreadPool(
      new SolrjNamedThreadFactory("updateExecutor"));
  
  private ExecutorService recoveryExecutor = ExecutorUtil.newMDCAwareCachedThreadPool(
      new SolrjNamedThreadFactory("recoveryExecutor"));
  
  private PoolingClientConnectionManager clientConnectionManager;
  
  private final CloseableHttpClient client;

  private final UpdateShardHandlerConfig cfg;

  private IdleConnectionsEvictor idleConnectionsEvictor;

  @Deprecated
  public UpdateShardHandler(NodeConfig cfg) {
    this(cfg.getUpdateShardHandlerConfig());
  }

  public UpdateShardHandler(UpdateShardHandlerConfig cfg) {
    this.cfg = cfg;
    clientConnectionManager = new PoolingClientConnectionManager(SchemeRegistryFactory.createSystemDefault());
    if (cfg != null ) {
      clientConnectionManager.setMaxTotal(cfg.getMaxUpdateConnections());
      clientConnectionManager.setDefaultMaxPerRoute(cfg.getMaxUpdateConnectionsPerHost());
    }

    ModifiableSolrParams clientParams = getClientParams();
    log.info("Creating UpdateShardHandler HTTP client with params: {}", clientParams);
    client = HttpClientUtil.createClient(clientParams, clientConnectionManager);

    if (cfg != null)  {
      idleConnectionsEvictor = new IdleConnectionsEvictor(clientConnectionManager,
          cfg.getUpdateConnectionsEvictorSleepDelay(), TimeUnit.MILLISECONDS,
          cfg.getMaxUpdateConnectionIdleTime(), TimeUnit.MILLISECONDS);
      idleConnectionsEvictor.start();
    }
  }

  protected ModifiableSolrParams getClientParams() {
    ModifiableSolrParams clientParams = new ModifiableSolrParams();
    if (cfg != null) {
      clientParams.set(HttpClientUtil.PROP_SO_TIMEOUT,
          cfg.getDistributedSocketTimeout());
      clientParams.set(HttpClientUtil.PROP_CONNECTION_TIMEOUT,
          cfg.getDistributedConnectionTimeout());
    }
    // in the update case, we want to do retries, and to use
    // the default Solr retry handler that createClient will 
    // give us
    clientParams.set(HttpClientUtil.PROP_USE_RETRY, true);
    return clientParams;
  }
  
  
  public HttpClient getHttpClient() {
    return client;
  }

  public void reconfigureHttpClient(HttpClientConfigurer configurer) {
    log.info("Reconfiguring the default client with: " + configurer);
    configurer.configure((DefaultHttpClient)client, getClientParams());
  }

  public ClientConnectionManager getConnectionManager() {
    return clientConnectionManager;
  }
  
  /**
   * This method returns an executor that is not meant for disk IO and that will
   * be interrupted on shutdown.
   * 
   * @return an executor for update related activities that do not do disk IO.
   */
  public ExecutorService getUpdateExecutor() {
    return updateExecutor;
  }
  
  /**
   * In general, RecoveryStrategy threads do not do disk IO, but they open and close SolrCores
   * in async threads, among other things, and can trigger disk IO, so we use this alternate 
   * executor rather than the 'updateExecutor', which is interrupted on shutdown.
   * 
   * @return executor for {@link RecoveryStrategy} thread which will not be interrupted on close.
   */
  public ExecutorService getRecoveryExecutor() {
    return recoveryExecutor;
  }

  public void close() {
    try {
      // we interrupt on purpose here, but this executor should not run threads that do disk IO!
      ExecutorUtil.shutdownWithInterruptAndAwaitTermination(updateExecutor);
      ExecutorUtil.shutdownAndAwaitTermination(recoveryExecutor);
      if (idleConnectionsEvictor != null) {
        idleConnectionsEvictor.shutdown();
      }
    } catch (Exception e) {
      SolrException.log(log, e);
    } finally {
      IOUtils.closeQuietly(client);
      clientConnectionManager.shutdown();
    }
  }

  /**
   * This class is adapted from org.apache.http.impl.client.IdleConnectionEvictor and changed to use
   * the deprecated ClientConnectionManager instead of the new HttpClientConnectionManager.
   * <p>
   * This class maintains a background thread to enforce an eviction policy for expired / idle
   * persistent connections kept alive in the connection pool.
   * <p>
   * See SOLR-9290 for related discussion.
   */
  public static final class IdleConnectionsEvictor {

    private final ClientConnectionManager connectionManager;
    private final ThreadFactory threadFactory;
    private final Thread thread;
    private final long sleepTimeMs;
    private final long maxIdleTimeMs;

    private volatile Exception exception;

    public IdleConnectionsEvictor(
        final ClientConnectionManager connectionManager,
        final ThreadFactory threadFactory,
        final long sleepTime, final TimeUnit sleepTimeUnit,
        final long maxIdleTime, final TimeUnit maxIdleTimeUnit) {
      this.connectionManager = Args.notNull(connectionManager, "Connection manager");
      this.threadFactory = threadFactory != null ? threadFactory : new DefaultThreadFactory();
      this.sleepTimeMs = sleepTimeUnit != null ? sleepTimeUnit.toMillis(sleepTime) : sleepTime;
      this.maxIdleTimeMs = maxIdleTimeUnit != null ? maxIdleTimeUnit.toMillis(maxIdleTime) : maxIdleTime;
      this.thread = this.threadFactory.newThread(new Runnable() {
        @Override
        public void run() {
          try {
            while (!Thread.currentThread().isInterrupted()) {
              Thread.sleep(sleepTimeMs);
              connectionManager.closeExpiredConnections();
              if (maxIdleTimeMs > 0) {
                connectionManager.closeIdleConnections(maxIdleTimeMs, TimeUnit.MILLISECONDS);
              }
            }
          } catch (Exception ex) {
            exception = ex;
          }

        }
      });
    }

    public IdleConnectionsEvictor(ClientConnectionManager connectionManager,
                                  long sleepTime, TimeUnit sleepTimeUnit, long maxIdleTime, TimeUnit maxIdleTimeUnit) {
      this(connectionManager, null, sleepTime, sleepTimeUnit, maxIdleTime, maxIdleTimeUnit);
    }

    public void start() {
      thread.start();
    }

    public void shutdown() {
      thread.interrupt();
    }

    public boolean isRunning() {
      return thread.isAlive();
    }

    public void awaitTermination(final long time, final TimeUnit tunit) throws InterruptedException {
      thread.join((tunit != null ? tunit : TimeUnit.MILLISECONDS).toMillis(time));
    }

    static class DefaultThreadFactory implements ThreadFactory {

      @Override
      public Thread newThread(final Runnable r) {
        final Thread t = new Thread(r, "solr-idle-connections-evictor");
        t.setDaemon(true);
        return t;
      }

    }

  }

}
