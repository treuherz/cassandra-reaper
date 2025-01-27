/*
 * Copyright 2018-2019 The Last Pickle Ltd
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.cassandrareaper.jmx;

import io.cassandrareaper.AppContext;
import io.cassandrareaper.ReaperApplicationConfiguration.DatacenterAvailability;
import io.cassandrareaper.ReaperException;
import io.cassandrareaper.core.Cluster;
import io.cassandrareaper.core.Compaction;
import io.cassandrareaper.core.DroppedMessages;
import io.cassandrareaper.core.GenericMetric;
import io.cassandrareaper.core.JmxStat;
import io.cassandrareaper.core.MetricsHistogram;
import io.cassandrareaper.core.Node;
import io.cassandrareaper.core.Segment;
import io.cassandrareaper.core.Snapshot;
import io.cassandrareaper.core.Table;
import io.cassandrareaper.core.ThreadPoolStat;
import io.cassandrareaper.resources.view.NodesStatus;
import io.cassandrareaper.service.RingRange;

import java.io.IOError;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.management.JMException;
import javax.management.MalformedObjectNameException;
import javax.management.ReflectionException;
import javax.management.openmbean.CompositeData;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Lists;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ClusterFacade {
  private static final Logger LOG = LoggerFactory.getLogger(ClusterFacade.class);

  private static final Cache<Pair<Cluster,String>,String> CLUSTER_VERSIONS
      = CacheBuilder.newBuilder().expireAfterWrite(1, TimeUnit.MINUTES).build();

  private static final Cache<Pair<Cluster,String>,Set<Table>> TABLES_IN_KEYSPACE
      = CacheBuilder.newBuilder().expireAfterWrite(1, TimeUnit.MINUTES).build();

  private final AppContext context;

  private ClusterFacade(AppContext context) {
    this.context = context;
  }

  public static ClusterFacade create(AppContext context) {
    return new ClusterFacade(context);
  }

  private JmxProxy connectAnyNode(Cluster cluster, Collection<String> endpoints) throws ReaperException {
    return context.jmxConnectionFactory.connectAny(
        endpoints
            .stream()
            .map(host -> Node.builder().withCluster(cluster).withHostname(host).build())
            .collect(Collectors.toList()));
  }

  private JmxProxy connectNode(Node node) throws ReaperException, InterruptedException {
    return context.jmxConnectionFactory.connect(node);
  }

  /**
   * Pre-heats JMX connections to all provided endpoints.
   * In EACH, LOCAL and ALL : connect directly to any available node
   * In SIDECAR : We skip that code path as we don’t need to pre-heat connections
   *
   * @param cluster the cluster to connect to
   * @param endpoints the list of endpoints to connect to
   * @return a JmxProxy object
   * @throws ReaperException any runtime exception we catch
   */
  public JmxProxy preHeatJmxConnections(Cluster cluster, Collection<String> endpoints) throws ReaperException {
    return connectAnyNode(cluster, endpoints);
  }

  /**
   * Connect to any of the provided endpoints and allow enforcing to localhost for sidecar mode.
   * In EACH, LOCAL and ALL : connect directly to any available node
   *
   * @param cluster the cluster to connect to
   * @param endpoints the list of endpoints to connect to
   * @return a JmxProxy object
   * @throws ReaperException any runtime exception we catch
   */
  public JmxProxy connectAny(Cluster cluster, Collection<String> endpoints) throws ReaperException {
    return connectAnyNode(cluster, endpoints);
  }

  /**
   * Get the cluster name from any of the provided endpoints.
   * In EACH, LOCAL and ALL : connect directly to any available node to get the information
   * In SIDECAR : Enforce connecting to the local node to get the information
   *
   * @param cluster the cluster to connect to
   * @param endpoints the list of endpoints to connect to
   * @return the cluster name
   * @throws ReaperException any runtime exception we catch
   */
  public String getClusterName(Cluster cluster, Collection<String> endpoints) throws ReaperException {
    JmxProxy jmxProxy = connectAnyNode(cluster, endpoints);
    return jmxProxy.getClusterName();
  }

  /**
   * Get the cluster name that the node belongs to.
   *
   * @param node the node to connect to
   * @return the cluster name
   * @throws ReaperException any runtime exception we catch in the process
   * @throws InterruptedException if the JMX connection gets interrupted
   */
  public String getClusterName(Node node) throws ReaperException, InterruptedException {
    JmxProxy jmxProxy = connectNode(node);
    return jmxProxy.getClusterName();
  }

  /**
   * Get the partitioner in use from any of the provided endpoints.
   * In EACH, LOCAL and ALL : connect directly to any available node to get the information
   * In SIDECAR : Enforce connecting to the local node to get the information
   *
   * @param cluster the cluster to connect to
   * @param endpoints the list of endpoints to connect to
   * @return the partitioner in use on the cluster
   * @throws ReaperException any runtime exception we catch
   */
  public String getPartitioner(Cluster cluster, Collection<String> endpoints) throws ReaperException {
    JmxProxy jmxProxy = connectAnyNode(cluster, endpoints);
    return jmxProxy.getPartitioner();
  }

  /**
   * Get the list of live nodes in the cluster.
   * In EACH, LOCAL and ALL : connect directly to any available node to get the information
   * In SIDECAR : Enforce connecting to the local node to get the information
   *
   * @param cluster the cluster to connect to
   * @return the list of live endpoints in the cluster
   * @throws ReaperException any runtime exception we catch
   */
  public List<String> getLiveNodes(Cluster cluster) throws ReaperException {
    return getLiveNodes(cluster, cluster.getSeedHosts());
  }

  /**
   * Get the list of live nodes in the cluster from any of the provided endpoints.
   * In EACH, LOCAL and ALL : connect directly to any available node to get the information
   * In SIDECAR : Enforce connecting to the local node to get the information
   *
   * @param cluster the cluster to connect to
   * @param endpoints the list of endpoints to connect to
   * @return the list of live endpoints in the cluster
   * @throws ReaperException any runtime exception we catch
   */
  public List<String> getLiveNodes(Cluster cluster, Collection<String> endpoints) throws ReaperException {
    JmxProxy jmxProxy = connectAnyNode(cluster, endpoints);
    return jmxProxy.getLiveNodes();
  }

  /**
   * Get the status of all nodes in the cluster.
   * In EACH, LOCAL and ALL : connect directly to any provided node to get the information
   * In SIDECAR : Enforce connecting to the local node to get the information
   *
   * @param cluster the cluster to connect to
   * @param endpoints the list of endpoints to connect to
   * @return a NodeStatus object with all nodes state
   * @throws ReaperException any runtime exception we catch
   */
  public NodesStatus getNodesStatus(Cluster cluster, Collection<String> endpoints) throws ReaperException {
    JmxProxy jmxProxy = connectAnyNode(cluster, endpoints);
    FailureDetectorProxy proxy = FailureDetectorProxy.create(jmxProxy);

    return new NodesStatus(
        jmxProxy.getHost(), proxy.getAllEndpointsState(), proxy.getSimpleStates());
  }

  /**
   * Get the version of Cassandra in use in the cluster.
   * In EACH, LOCAL and ALL : connect directly to any provided node to get the information
   * In SIDECAR : Enforce connecting to the local node to get the information
   *
   * @param cluster the cluster to connect to
   * @return the version of Cassandra used
   * @throws ReaperException any runtime exception we catch
   */
  public String getCassandraVersion(Cluster cluster) throws ReaperException {
    return getCassandraVersion(cluster, cluster.getSeedHosts());
  }

  /**
   * Get the version of Cassandra in use in the cluster.
   * In EACH, LOCAL and ALL : connect directly to any provided node to get the information
   * In SIDECAR : Enforce connecting to the local node to get the information
   *
   * @param cluster the cluster to connect to
   * @param endpoints the list of endpoints to connect to
   * @return the version of Cassandra used
   * @throws ReaperException any runtime exception we catch
   */
  public String getCassandraVersion(Cluster cluster, Collection<String> endpoints) throws ReaperException {
    for (String endpoint : endpoints) {
      String version = CLUSTER_VERSIONS.getIfPresent(Pair.of(cluster, endpoint));
      if (null != version) {
        return version;
      }
    }
    JmxProxy jmxProxy = connectAnyNode(cluster, endpoints);
    String version = jmxProxy.getCassandraVersion();
    CLUSTER_VERSIONS.put(Pair.of(cluster, jmxProxy.getHost()), version);
    return version;
  }

  /**
   * Get the list of tokens of the cluster.
   * In EACH, LOCAL and ALL : connect directly to any provided node to get the information
   * In SIDECAR : Enforce connecting to the local node to get the information
   *
   * @param cluster the cluster to connect to
   * @return the list of tokens as BigInteger
   * @throws ReaperException any runtime exception we catch
   */
  public List<BigInteger> getTokens(Cluster cluster) throws ReaperException {
    JmxProxy jmxProxy = connectAnyNode(cluster, cluster.getSeedHosts());
    return jmxProxy.getTokens();
  }

  /**
   * Get a map of all the token ranges with the list of replicas. In EACH, LOCAL and ALL : connect
   * directly to any provided node to get the information In SIDECAR : Enforce connecting to the
   * local node to get the information
   *
   * @param cluster the cluster to connect to
   * @param keyspaceName the ks to get a map of token ranges for
   * @return a map of token ranges with endpoints as items
   * @throws ReaperException any runtime exception we catch
   */
  public Map<List<String>, List<String>> getRangeToEndpointMap(Cluster cluster, String keyspaceName)
      throws ReaperException {
    JmxProxy jmxProxy = connectAnyNode(cluster, cluster.getSeedHosts());
    return jmxProxy.getRangeToEndpointMap(keyspaceName);
  }

  /**
   * Get a list of tables for a specific keyspace.
   * In EACH, LOCAL and ALL : connect directly to any provided node to get the information
   * In SIDECAR : Enforce connecting to the local node to get the information
   *
   * @param cluster the cluster to connect to
   * @param keyspaceName a keyspace name
   * @return a list of table names
   * @throws ReaperException any runtime exception we catch
   */
  public Set<Table> getTablesForKeyspace(Cluster cluster, String keyspaceName) throws ReaperException {
    try {
      return TABLES_IN_KEYSPACE.get(Pair.of(cluster, keyspaceName), () -> {
        return getTablesForKeyspaceImpl(cluster, keyspaceName);
      });
    } catch (ExecutionException ex) {
      throw new ReaperException(ex);
    }
  }

  private Set<Table> getTablesForKeyspaceImpl(Cluster cluster, String keyspaceName) throws ReaperException {
    return connectAnyNode(cluster, cluster.getSeedHosts()).getTablesForKeyspace(keyspaceName);
  }

  /**
   * List all tables by keyspace.
   * In EACH, LOCAL and ALL : connect directly to any provided node to get the information
   * In SIDECAR : Enforce connecting to the local node to get the information
   *
   * @param cluster the cluster to connect to
   * @return a map of table names by keyspace
   * @throws ReaperException any runtime exception we catch
   */
  public Map<String, List<String>> listTablesByKeyspace(Cluster cluster) throws ReaperException {
    JmxProxy jmxProxy = connectAnyNode(cluster, cluster.getSeedHosts());
    return jmxProxy.listTablesByKeyspace();
  }

  /**
   * List the keyspaces of the cluster.
   *
   * @param cluster the cluster to connect to
   * @return a list of keyspace names
   * @throws ReaperException any runtime exception
   */
  public List<String> getKeyspaces(Cluster cluster) throws ReaperException {
    JmxProxy jmxProxy = connectAnyNode(cluster, cluster.getSeedHosts());
    return jmxProxy.getKeyspaces();
  }

  /**
   * Get a map of endpoints with the associated host id.
   * In EACH, LOCAL and ALL : connect directly to any provided node to get the information
   * In SIDECAR : Enforce connecting to the local node to get the information
   *
   * @param cluster the cluster to connect to
   * @return the map of endpoints to host id
   * @throws ReaperException any runtime exception we catch
   */
  public Map<String, String> getEndpointToHostId(Cluster cluster) throws ReaperException {
    JmxProxy jmxProxy = connectAnyNode(cluster, cluster.getSeedHosts());
    return jmxProxy.getEndpointToHostId();
  }

  /**
   * Get the list of replicas for a token range.
   *
   * @param cluster the cluster to connect to
   * @param keyspace the keyspace to get the replicas for
   * @param segment the token range for which we want the list of replicas
   * @return a list of endpoints
   */
  public List<String> tokenRangeToEndpoint(Cluster cluster, String keyspace, Segment segment) {
    Set<Map.Entry<List<String>, List<String>>> entries;
    try {
      entries = getRangeToEndpointMap(cluster, keyspace).entrySet();
    } catch (ReaperException e) {
      LOG.error("[tokenRangeToEndpoint] no replicas found for token range {}", segment, e);
      return Lists.newArrayList();
    }

    for (Map.Entry<List<String>, List<String>> entry : entries) {
      BigInteger rangeStart = new BigInteger(entry.getKey().get(0));
      BigInteger rangeEnd = new BigInteger(entry.getKey().get(1));
      if (new RingRange(rangeStart, rangeEnd).encloses(segment.getTokenRanges().get(0))) {
        return entry.getValue();
      }
    }
    LOG.error("[tokenRangeToEndpoint] no replicas found for token range {}", segment);
    LOG.debug("[tokenRangeToEndpoint] checked token ranges were {}", entries);
    return Lists.newArrayList();
  }

  /**
   * Get the datacenter of a specific endpoint.
   *
   * @param cluster the cluster to connect to
   * @param endpoint the node which we're trying to locate in the topology
   * @return the datacenter this endpoint belongs to
   * @throws ReaperException any runtime exception we catch in the process
   */
  public String getDatacenter(Cluster cluster, String endpoint) throws ReaperException {
    JmxProxy jmxProxy = connectAny(cluster, cluster.getSeedHosts());
    return EndpointSnitchInfoProxy.create(jmxProxy).getDataCenter(endpoint);
  }

  /**
   * Get the datacenter of a specific endpoint.
   *
   * @param node the node to connect to
   * @return the datacenter this endpoint belongs to
   * @throws ReaperException any runtime exception we catch in the process
   * @throws InterruptedException in case the JMX connection gets interrupted
   */
  public String getDatacenter(Node node) throws ReaperException, InterruptedException {
    JmxProxy jmxProxy = connectNode(node);
    return EndpointSnitchInfoProxy.create(jmxProxy).getDataCenter();
  }

  /**
   * Get the endpoint name/ip indentifying the node in the cluster.
   *
   * @param node the node to connect to
   * @return the endpoint as a string
   * @throws ReaperException any runtime exception we catch in the process
   * @throws InterruptedException if the JMX connection gets interrupted
   */
  public String getLocalEndpoint(Node node) throws ReaperException, InterruptedException {
    JmxProxy jmxProxy = connectNode(node);
    return jmxProxy.getLocalEndpoint();
  }

  /**
   * Retrieve a map of endpoints with the associated tokens.
   *
   * @param cluster the cluster we want to retrieve the tokens from
   * @return a map of nodes with the list of tokens as items
   * @throws ReaperException any runtime exception we catch in the process
   */
  public Map<String, List<String>> getTokensByNode(Cluster cluster) throws ReaperException {
    JmxProxy jmxProxy = connectAny(cluster, cluster.getSeedHosts());
    return StorageServiceProxy.create(jmxProxy).getTokensByNode();
  }

  /**
   * List running compactions on a specific node either through JMX or through the backend.
   *
   * @param node the node to get the compactions from.
   * @return a list of compactions
   * @throws MalformedObjectNameException ¯\_(ツ)_/¯
   * @throws ReflectionException ¯\_(ツ)_/¯
   * @throws ReaperException any runtime exception we catch in the process
   * @throws InterruptedException in case the JMX connection gets interrupted
   * @throws IOException errors in parsing JSON encoded compaction objects
   */
  public List<Compaction> listActiveCompactions(Node node)
      throws MalformedObjectNameException, ReflectionException, ReaperException,
          InterruptedException, IOException {
    String nodeDc = getDatacenter(node);
    if (nodeIsAccessibleThroughJmx(nodeDc, node.getHostname())) {
      // We have direct JMX access to the node
      return listActiveCompactionsDirect(node);
    }

    return Collections.emptyList();
  }

  /**
   * List running compactions on a specific node by connecting directly to it through JMX.
   *
   * @param node the node to get the compactions from.
   * @return a list of compactions
   * @throws MalformedObjectNameException ¯\_(ツ)_/¯
   * @throws ReflectionException ¯\_(ツ)_/¯
   * @throws ReaperException any runtime exception we catch in the process
   * @throws InterruptedException in case the JMX connection gets interrupted
   */
  public List<Compaction> listActiveCompactionsDirect(Node node)
      throws ReaperException, InterruptedException, MalformedObjectNameException,
          ReflectionException {
    JmxProxy jmxProxy = connectAny(node.getCluster(), Arrays.asList(node.getHostname()));
    return CompactionProxy.create(jmxProxy, context.metricRegistry).listActiveCompactions();
  }

  /**
   * Checks if Reaper can access the target node through JMX directly.
   * The result will depend on the chosen datacenterAvailability setting and the datacenter the node belongs to.
   *
   * @param nodeDc datacenter of the target node
   * @param node the target node
   * @return true if the node is supposedly accessible through JMX, otherwise false
   */
  public boolean nodeIsAccessibleThroughJmx(String nodeDc, String node) {
    return DatacenterAvailability.ALL == context.config.getDatacenterAvailability()
        || context.jmxConnectionFactory.getAccessibleDatacenters().contains(nodeDc);
  }

  /**
   * Collect a set of metrics through JMX on a specific node.
   *
   * @param node the node to collect metrics on
   * @param collectedMetrics the list of metrics to collect
   * @return the list of collected metrics
   * @throws ReaperException any runtime exception we catch in the process
   */
  public Map<String, List<JmxStat>> collectMetrics(Node node, String[] collectedMetrics) throws ReaperException {
    try {
      JmxProxy jmxProxy = connectNode(node);
      MetricsProxy proxy = MetricsProxy.create(jmxProxy);
      return proxy.collectMetrics(collectedMetrics);
    } catch (JMException | InterruptedException | IOException e) {
      LOG.error("Failed collecting metrics for host {}", node, e);
      throw new ReaperException(e);
    }
  }

  /**
   * Collect ClientRequest metrics through JMX on a specific node.
   *
   * @param node the node to collect metrics on
   * @param collectedMetrics the list of metrics to collect
   * @return the list of collected metrics
   * @throws ReaperException any runtime exception we catch in the process
   */
  public List<MetricsHistogram> getClientRequestLatencies(Node node) throws ReaperException {
    try {
      String nodeDc = getDatacenter(node);
      if (nodeIsAccessibleThroughJmx(nodeDc, node.getHostname())) {
        MetricsProxy metricsProxy = MetricsProxy.create(connectNode(node));
        return convertToMetricsHistogram(
            MetricsProxy.convertToGenericMetrics(metricsProxy.collectLatencyMetrics(), node));
      }

      return Collections.emptyList();
    } catch (JMException | InterruptedException | IOException e) {
      LOG.error("Failed collecting tpstats for host {}", node, e);
      throw new ReaperException(e);
    }
  }

  /**
   * List dropped messages metrics
   *
   * @param node the node to get the metrics from
   * @return a list of dropped messages metrics objects
   * @throws ReaperException any runtime exception we can catch in the process
   */
  public List<DroppedMessages> getDroppedMessages(Node node) throws ReaperException {
    try {
      String nodeDc = getDatacenter(node);
      if (nodeIsAccessibleThroughJmx(nodeDc, node.getHostname())) {
        MetricsProxy proxy = MetricsProxy.create(connectNode(node));
        return convertToDroppedMessages(MetricsProxy.convertToGenericMetrics(proxy.collectDroppedMessages(), node));
      }

      return Collections.emptyList();
    } catch (JMException | InterruptedException | IOException e) {
      LOG.error("Failed collecting tpstats for host {}", node, e);
      throw new ReaperException(e);
    }
  }

  @VisibleForTesting
  public List<DroppedMessages> convertToDroppedMessages(List<GenericMetric> metrics) {
    List<DroppedMessages> droppedMessages = Lists.newArrayList();
    Map<String, List<GenericMetric>> metricsByScope
        = metrics.stream().collect(Collectors.groupingBy(GenericMetric::getMetricScope));
    for (Entry<String, List<GenericMetric>> pool : metricsByScope.entrySet()) {
      DroppedMessages.Builder builder = DroppedMessages.builder().withName(pool.getKey());
      for (GenericMetric stat : pool.getValue()) {
        builder = MetricsProxy.updateGenericMetricAttribute(stat, builder);
      }
      droppedMessages.add(builder.build());
    }
    return droppedMessages;
  }

  /**
   * List thread pool stats metrics.
   *
   * @param node the node to get the metrics from
   * @return a list of thread pools stats metrics objects
   * @throws ReaperException any runtime exception we can catch in the process
   */
  public List<ThreadPoolStat> getTpStats(Node node) throws ReaperException {
    try {
      String nodeDc = getDatacenter(node);
      if (nodeIsAccessibleThroughJmx(nodeDc, node.getHostname())) {
        MetricsProxy proxy = MetricsProxy.create(connectNode(node));
        return convertToThreadPoolStats(MetricsProxy.convertToGenericMetrics(proxy.collectTpStats(), node));
      }

      return Collections.emptyList();
    } catch (JMException | InterruptedException | IOException e) {
      LOG.error("Failed collecting tpstats for host {}", node, e);
      throw new ReaperException(e);
    }
  }

  @VisibleForTesting
  public List<ThreadPoolStat> convertToThreadPoolStats(List<GenericMetric> metrics) {
    List<ThreadPoolStat> tpstats = Lists.newArrayList();
    Map<String, List<GenericMetric>> metricsByScope
        = metrics.stream().collect(Collectors.groupingBy(GenericMetric::getMetricScope));
    for (Entry<String, List<GenericMetric>> pool : metricsByScope.entrySet()) {
      ThreadPoolStat.Builder builder = ThreadPoolStat.builder().withName(pool.getKey());
      for (GenericMetric stat : pool.getValue()) {
        builder = MetricsProxy.updateGenericMetricAttribute(stat, builder);
      }
      tpstats.add(builder.build());
    }
    return tpstats;
  }

  @VisibleForTesting
  public List<MetricsHistogram> convertToMetricsHistogram(List<GenericMetric> metrics) {
    List<MetricsHistogram> histograms = Lists.newArrayList();
    // We have several metric types that we need to process separately
    // We'll group on MetricsHistogram::getType in order to generate one histogram per type
    Map<String, List<GenericMetric>> metricsByScope
        = metrics.stream().collect(Collectors.groupingBy(GenericMetric::getMetricScope));

    for (Entry<String, List<GenericMetric>> metricByScope : metricsByScope.entrySet()) {
      Map<String, List<GenericMetric>> metricsByName
          = metricByScope
              .getValue()
              .stream()
              .collect(Collectors.groupingBy(GenericMetric::getMetricName));
      for (Entry<String, List<GenericMetric>> metricByName : metricsByName.entrySet()) {
        MetricsHistogram.Builder builder
            = MetricsHistogram.builder()
                .withName(metricByScope.getKey())
                .withType(metricByName.getKey());
        for (GenericMetric stat : metricByName.getValue()) {
          builder = MetricsProxy.updateGenericMetricAttribute(stat, builder);
        }
        histograms.add(builder.build());
      }
    }
    return histograms;
  }

  /**
   * Take a snapshot on one node.
   *
   * @param snapshotName the name of the snapshot
   * @param host the node to take the snapshot on
   * @param keyspaces the list of keyspaces to snapshot
   * @return a tuple of the node object and the snapshot name
   * @throws ReaperException  any runtime exception we can catch in the process
   */
  public Pair<Node, String> takeSnapshot(String snapshotName, Node host, String... keyspaces) throws ReaperException {
    try {
      JmxProxy jmx = connectNode(host);
      SnapshotProxy snapshotProxy = SnapshotProxy.create(jmx);
      LOG.info("Taking snapshot for node {} and keyspace {}", host, keyspaces);
      return Pair.of(host, snapshotProxy.takeSnapshot(snapshotName, keyspaces));
    } catch (InterruptedException e) {
      LOG.error("Interrupted taking snapshot for host {} and keyspaces {}", host, keyspaces, e);
      throw new ReaperException(e);
    }
  }

  /**
   * List all snapshots on a node.
   *
   * @param host the node to list the snapshot from
   * @return a list of snapshots
   * @throws ReaperException any runtime exception we can catch in the process
   */
  public List<Snapshot> listSnapshots(Node host) throws ReaperException {
    try {
      JmxProxy jmx = connectNode(host);
      SnapshotProxy snapshotProxy = SnapshotProxy.create(jmx);

      return snapshotProxy.listSnapshots();

    } catch (UnsupportedOperationException unsupported) {
      LOG.debug("Listing snapshot is unsupported with Cassandra 2.0 and prior");
      throw unsupported;
    } catch (InterruptedException e) {
      LOG.error("Interrupted listing snapshots for host {}", host, e);
      throw new ReaperException(e);
    }
  }

  /**
   * Delete a given snapshot from a node.
   *
   * @param snapshotName the name of the snapshot to clear
   * @param host the node to delete the snapshot from
   * @throws ReaperException any runtime exception we can catch in the process
   */
  public void clearSnapshot(String snapshotName, Node host) throws ReaperException {
    try {
      JmxProxy jmx = connectNode(host);
      SnapshotProxy snapshotProxy = SnapshotProxy.create(jmx);
      snapshotProxy.clearSnapshot(snapshotName);
    } catch (IOError e) {
      // StorageService.clearSnapshot(..) throws a FSWriteError when snapshot already deleted
      LOG.info("already cleared snapshot " + snapshotName, e);
    } catch (InterruptedException e) {
      LOG.error("Interrupted clearing snapshot {} for host {}", snapshotName, host, e);
      throw new ReaperException(e);
    }
  }

  /**
   * List the ongoing streaming operations on a node.
   *
   * @param node the node to list streamings on
   * @return a list of CompositeData objects with streaming information
   * @throws ReaperException any runtime exception we can catch in the process
   */
  public Set<CompositeData> listStreams(Node node) throws ReaperException {
    try {
      JmxProxy jmxProxy = connectNode(node);
      return StreamsProxy.create(jmxProxy).listStreams();
    } catch (InterruptedException e) {
      throw new ReaperException(e);
    }
  }
}
