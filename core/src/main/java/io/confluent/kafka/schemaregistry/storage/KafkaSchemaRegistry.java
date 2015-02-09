/**
 * Copyright 2014 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.confluent.kafka.schemaregistry.storage;

import org.I0Itec.zkclient.ZkClient;
import org.apache.avro.reflect.Nullable;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.confluent.common.metrics.JmxReporter;
import io.confluent.common.metrics.MetricConfig;
import io.confluent.common.metrics.MetricName;
import io.confluent.common.metrics.Metrics;
import io.confluent.common.metrics.MetricsReporter;
import io.confluent.common.metrics.Sensor;
import io.confluent.common.metrics.stats.Gauge;
import io.confluent.common.utils.SystemTime;
import io.confluent.common.utils.zookeeper.ZkData;
import io.confluent.common.utils.zookeeper.ZkUtils;
import io.confluent.kafka.schemaregistry.avro.AvroCompatibilityLevel;
import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.avro.AvroUtils;
import io.confluent.kafka.schemaregistry.client.rest.entities.Schema;
import io.confluent.kafka.schemaregistry.client.rest.entities.SchemaString;
import io.confluent.kafka.schemaregistry.client.rest.entities.requests.RegisterSchemaRequest;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import io.confluent.kafka.schemaregistry.client.rest.utils.RestUtils;
import io.confluent.kafka.schemaregistry.exceptions.IncompatibleSchemaException;
import io.confluent.kafka.schemaregistry.exceptions.InvalidSchemaException;
import io.confluent.kafka.schemaregistry.exceptions.SchemaRegistryException;
import io.confluent.kafka.schemaregistry.exceptions.SchemaRegistryInitializationException;
import io.confluent.kafka.schemaregistry.exceptions.SchemaRegistryRequestForwardingException;
import io.confluent.kafka.schemaregistry.exceptions.SchemaRegistryStoreException;
import io.confluent.kafka.schemaregistry.exceptions.SchemaRegistryTimeoutException;
import io.confluent.kafka.schemaregistry.exceptions.UnknownMasterException;
import io.confluent.kafka.schemaregistry.rest.SchemaRegistryConfig;
import io.confluent.kafka.schemaregistry.rest.VersionId;
import io.confluent.kafka.schemaregistry.rest.resources.SchemaIdAndSubjects;
import io.confluent.kafka.schemaregistry.storage.exceptions.StoreException;
import io.confluent.kafka.schemaregistry.storage.exceptions.StoreInitializationException;
import io.confluent.kafka.schemaregistry.storage.exceptions.StoreTimeoutException;
import io.confluent.kafka.schemaregistry.storage.serialization.Serializer;
import io.confluent.kafka.schemaregistry.storage.serialization.ZkStringSerializer;
import io.confluent.kafka.schemaregistry.zookeeper.SchemaRegistryIdentity;
import io.confluent.kafka.schemaregistry.zookeeper.ZookeeperMasterElector;
import io.confluent.rest.exceptions.RestException;

public class KafkaSchemaRegistry implements SchemaRegistry {

  /**
   * Schema versions under a particular subject are indexed from MIN_VERSION.
   */
  public static final int MIN_VERSION = 1;
  public static final int MAX_VERSION = Integer.MAX_VALUE;
  private static final String ZOOKEEPER_SCHEMA_ID_COUNTER = "/schema_id_counter";
  private static final int ZOOKEEPER_SCHEMA_ID_COUNTER_BATCH_SIZE = 20;
  private static final int ZOOKEEPER_SCHEMA_ID_COUNTER_BATCH_WRITE_RETRY_BACKOFF_MS = 50;
  private static final Logger log = LoggerFactory.getLogger(KafkaSchemaRegistry.class);
  final Map<Integer, SchemaKey> guidToSchemaKey;
  final Map<MD5, SchemaIdAndSubjects> schemaHashToGuid;
  private final KafkaStore<SchemaRegistryKey, SchemaRegistryValue> kafkaStore;
  private final Serializer<SchemaRegistryKey, SchemaRegistryValue> serializer;
  private final SchemaRegistryIdentity myIdentity;
  private final Object masterLock = new Object();
  private final AvroCompatibilityLevel defaultCompatibilityLevel;
  private final String kafkaClusterZkUrl;
  private String schemaRegistryZkUrl;
  private ZkClient zkClient;
  private final int zkSessionTimeoutMs;
  private final String clusterName;
  private SchemaRegistryIdentity masterIdentity;
  private ZookeeperMasterElector masterElector = null;
  private final boolean isEligibleForMasterElector;
  private AtomicInteger schemaIdCounter;
  private int maxSchemaIdCounterValue;
  private Metrics metrics;
  private Sensor masterNodeSensor;
  // Track the largest id in the kafka store so far (-1 indicates none in the store)
  private int maxIdInStore = -1;

  public KafkaSchemaRegistry(SchemaRegistryConfig config,
                             Serializer<SchemaRegistryKey, SchemaRegistryValue> serializer)
      throws SchemaRegistryException {
    String host = config.getString(SchemaRegistryConfig.HOST_NAME_CONFIG);
    int port = config.getInt(SchemaRegistryConfig.PORT_CONFIG);
    clusterName = config.getString(SchemaRegistryConfig.CLUSTER_NAME);
    isEligibleForMasterElector = config.getBoolean(SchemaRegistryConfig.MASTER_ELIGIBILITY);
    myIdentity = new SchemaRegistryIdentity(host, port, isEligibleForMasterElector);
    kafkaClusterZkUrl =
        config.getString(SchemaRegistryConfig.KAFKASTORE_CONNECTION_URL_CONFIG);
    zkSessionTimeoutMs =
        config.getInt(SchemaRegistryConfig.KAFKASTORE_ZK_SESSION_TIMEOUT_MS_CONFIG);
    this.serializer = serializer;
    this.defaultCompatibilityLevel = config.compatibilityType();
    this.guidToSchemaKey = new HashMap<Integer, SchemaKey>();
    this.schemaHashToGuid = new HashMap<MD5, SchemaIdAndSubjects>();
    kafkaStore =
        new KafkaStore<SchemaRegistryKey, SchemaRegistryValue>(config,
                                                               new KafkaStoreMessageHandler(this),
                                                               this.serializer,
                                                               new InMemoryStore<SchemaRegistryKey, SchemaRegistryValue>());
    MetricConfig metricConfig =
        new MetricConfig().samples(config.getInt(ProducerConfig.METRICS_NUM_SAMPLES_CONFIG))
            .timeWindow(config.getLong(ProducerConfig.METRICS_SAMPLE_WINDOW_MS_CONFIG),
                        TimeUnit.MILLISECONDS);
    List<MetricsReporter> reporters =
        config.getConfiguredInstances(ProducerConfig.METRIC_REPORTER_CLASSES_CONFIG,
                                      MetricsReporter.class);
    String jmxPrefix = "kafka.schema.registry";
    reporters.add(new JmxReporter(jmxPrefix));
    this.metrics = new Metrics(metricConfig, reporters, new SystemTime());
    this.masterNodeSensor = metrics.sensor("master-slave-role");
    MetricName
        m = new MetricName("master-slave-role", "master-slave-role",
                           "1.0 indicates the node is the active master in the cluster and is the"
                           + " node where all register schema and config update requests are "
                           + "served.");
    this.masterNodeSensor.add(m, new Gauge());
  }

  @Override
  public void init() throws SchemaRegistryInitializationException {
    try {
      kafkaStore.init();
    } catch (StoreInitializationException e) {
      throw new SchemaRegistryInitializationException(
          "Error initializing kafka store while initializing schema registry", e);
    }
    
    try {
      createZkNamespace();
      masterElector = new ZookeeperMasterElector(zkClient, myIdentity, this, 
                                                 isEligibleForMasterElector);
    } catch (SchemaRegistryStoreException e) {
      throw new SchemaRegistryInitializationException(
          "Error electing master while initializing schema registry", e);
    } catch (SchemaRegistryTimeoutException e) {
      throw new SchemaRegistryInitializationException(e);
    }
  }

  private void createZkNamespace() {
    String kafkaNamespace = "";
    int kafkaNamespaceIndex = kafkaClusterZkUrl.indexOf("/");
    if (kafkaNamespaceIndex > 0) {
      kafkaNamespace = kafkaClusterZkUrl.substring(kafkaNamespaceIndex);
    }
    String zkConnForNamespaceCreation = kafkaClusterZkUrl;
    if (kafkaNamespace.length() > 1) {
      zkConnForNamespaceCreation = kafkaClusterZkUrl.substring(0, kafkaNamespaceIndex);
    }
    String schemaRegistryNamespace = "/" + clusterName;
    schemaRegistryZkUrl = zkConnForNamespaceCreation + schemaRegistryNamespace;

    ZkClient zkClientForNamespaceCreation = new ZkClient(zkConnForNamespaceCreation,
                                                         zkSessionTimeoutMs, zkSessionTimeoutMs,
                                                         new ZkStringSerializer());
    // create the zookeeper namespace using cluster.name if it doesn't already exist
    ZkUtils.makeSurePersistentPathExists(zkClientForNamespaceCreation, schemaRegistryNamespace);
    log.info("Created schema registry namespace " + 
             zkConnForNamespaceCreation + schemaRegistryNamespace);
    zkClientForNamespaceCreation.close();
    this.zkClient = new ZkClient(schemaRegistryZkUrl, zkSessionTimeoutMs, zkSessionTimeoutMs,
                                 new ZkStringSerializer());
    System.out.println("Schema registry url = " + schemaRegistryZkUrl);
  }
  
  public boolean isMaster() {
    synchronized (masterLock) {
      if (masterIdentity != null && masterIdentity.equals(myIdentity)) {
        return true;
      } else {
        return false;
      }
    }
  }
  
  /**
   * 'Inform' this SchemaRegistry instance which SchemaRegistry is the current master.
   * If this instance is set as the new master, ensure it is up-to-date with data in
   * the kafka store, and tell Zookeeper to allocate the next batch of IDs.
   *
   * @param schemaRegistryIdentity Identity of the current master. null means no master is alive.
   * @throws SchemaRegistryException
   */
  public void setMaster(@Nullable SchemaRegistryIdentity schemaRegistryIdentity)
      throws SchemaRegistryTimeoutException, SchemaRegistryStoreException {
    log.debug("Setting the master to " + schemaRegistryIdentity);

    // Only schema registry instances eligible for master can be set to master
    if (schemaRegistryIdentity != null && !schemaRegistryIdentity.getMasterEligibility()) {
      throw new IllegalStateException(
          "Tried to set an ineligible node to master: " + schemaRegistryIdentity);
    }

    synchronized (masterLock) {
      masterIdentity = schemaRegistryIdentity;
      if (isMaster()) {
        // To become the master, wait until the Kafka store is fully caught up.
        try {
          kafkaStore.waitUntilBootstrapCompletes();
        } catch (StoreTimeoutException stoe) {
          throw new SchemaRegistryTimeoutException("Bootstrap timed out", stoe);
        } catch (StoreException se) {
          throw new SchemaRegistryStoreException("Error while bootstrapping schema registry" 
                                                 + " from the Kafka store log", se);
        }
        schemaIdCounter = new AtomicInteger(nextSchemaIdCounterBatch());
        maxSchemaIdCounterValue =
            schemaIdCounter.intValue() + ZOOKEEPER_SCHEMA_ID_COUNTER_BATCH_SIZE;
        masterNodeSensor.record(1.0);
      } else {
        masterNodeSensor.record(0.0);
      }
    }
  }

  /**
   * Return json data encoding basic information about this SchemaRegistry instance, such as
   * host, port, etc.
   */
  public SchemaRegistryIdentity myIdentity() {
    return myIdentity;
  }

  /**
   * Return the identity of the SchemaRegistry that this instance thinks is current master.
   * Any request that requires writing new data gets forwarded to the master.
   */
  public SchemaRegistryIdentity masterIdentity() {
    synchronized (masterLock) {
      return masterIdentity;
    }
  }

  @Override
  public int register(String subject,
                      Schema schema)
      throws SchemaRegistryException {
    try {
      // see if the schema to be registered already exists
      AvroSchema avroSchema = canonicalizeSchema(schema);
      MD5 md5 = MD5.ofString(schema.getSchema());
      int schemaId = -1;
      if (this.schemaHashToGuid.containsKey(md5)) {
        SchemaIdAndSubjects schemaIdAndSubjects = this.schemaHashToGuid.get(md5);
        if (schemaIdAndSubjects.hasSubject(subject)) {
          // return only if the schema was previously registered under the input subject
          return schemaIdAndSubjects.getSchemaId();
        } else {
          // need to register schema under the input subject
          schemaId = schemaIdAndSubjects.getSchemaId();
        }
      }

      // determine the latest version of the schema in the subject
      Iterator<Schema> allVersions = getAllVersions(subject);
      Schema latestSchema = null;
      int newVersion = MIN_VERSION;
      while (allVersions.hasNext()) {
        latestSchema = allVersions.next();
        newVersion = latestSchema.getVersion() + 1;
      }

      // assign a guid and put the schema in the kafka store
      if (latestSchema == null || isCompatible(subject, avroSchema.canonicalString,
                                               latestSchema.getSchema())) {
        SchemaKey keyForNewVersion = new SchemaKey(subject, newVersion);
        schema.setVersion(newVersion);

        if (schemaId > 0) {
          schema.setId(schemaId);
        } else {
          schema.setId(schemaIdCounter.getAndIncrement());
        }
        if (schemaIdCounter.get() == maxSchemaIdCounterValue) {
          maxSchemaIdCounterValue =
              nextSchemaIdCounterBatch().intValue() + ZOOKEEPER_SCHEMA_ID_COUNTER_BATCH_SIZE;
        }

        SchemaValue schemaValue = new SchemaValue(schema);
        kafkaStore.put(keyForNewVersion, schemaValue);
        return schema.getId();
      } else {
        throw new IncompatibleSchemaException(
            "New schema is incompatible with the latest schema " + latestSchema);
      }
    } catch (StoreTimeoutException te) {
      throw new SchemaRegistryTimeoutException("Write to the Kafka store timed out while", te);
    } catch (StoreException e) {
      throw new SchemaRegistryStoreException("Error while registering the schema in the" +
                                             " backend Kafka store", e);
    }
  }

  public int registerOrForward(String subject,
                               Schema schema,
                               Map<String, String> headerProperties)
      throws SchemaRegistryException {
    synchronized (masterLock) {
      if (isMaster()) {
        return register(subject, schema);
      } else {
        // forward registering request to the master
        if (masterIdentity != null) {
          return forwardRegisterRequestToMaster(subject, schema.getSchema(),
                                                masterIdentity.getHost(),
                                                masterIdentity.getPort(), headerProperties);
        } else {
          throw new UnknownMasterException("Register schema request failed since master is "
                                            + "unknown");
        }
      }
    }
  }

  public Schema lookUpSchemaUnderSubjectOrForward(
      String subject, Schema schema, Map<String, String> headerProperties)
      throws SchemaRegistryRequestForwardingException, UnknownMasterException {
    synchronized (masterLock) {
      if (isMaster()) {
        return lookUpSchemaUnderSubject(subject, schema);
      } else {
        // forward registering request to the master
        if (masterIdentity != null) {
          return forwardSubjectVersionRequestToMaster(subject, schema.getSchema(),
                                                      masterIdentity.getHost(),
                                                      masterIdentity.getPort(), headerProperties);
        } else {
          throw new UnknownMasterException("Register schema request failed since master is "
                                            + "unknown");
        }
      }
    }
  }

  /**
   * Checks if given schema was ever registered under a subject. If found, it returns the version of
   * the schema under the subject. If not, returns -1
   */
  public Schema lookUpSchemaUnderSubject(
      String subject, Schema schema) {
    // see if the schema to be registered already exists
    MD5 md5 = MD5.ofString(schema.getSchema());
    if (this.schemaHashToGuid.containsKey(md5)) {
      SchemaIdAndSubjects schemaIdAndSubjects = this.schemaHashToGuid.get(md5);
      if (schemaIdAndSubjects.hasSubject(subject)) {
        Schema matchingSchema = new Schema(subject,
                                           schemaIdAndSubjects.getVersion(subject),
                                           schemaIdAndSubjects.getSchemaId(),
                                           schema.getSchema());
        return matchingSchema;
      } else {
        // this schema was never registered under the input subject
        return null;
      }
    } else {
      // this schema was never registered in the registry under any subject
      return null;
    }
  }

  /**
   * Allocate and lock the next batch of ids. Indicate a lock over the next batch by writing the
   * upper bound of the batch to zookeeper.
   *
   * When a schema registry server is initialized, kafka may have preexisting persistent
   * schema -> id assignments, and zookeeper may have preexisting counter data.
   * Therefore, when allocating the next batch of ids, it's necessary to ensure the entire new batch
   * is both greater than the greatest id in kafka and greater than the
   * previously recorded batch in zookeeper.
   *
   * Return the start index of the next batch.
   */
  private Integer nextSchemaIdCounterBatch() throws SchemaRegistryStoreException {
    int nextIdBatchLowerBound = 0;

    // create ZOOKEEPER_SCHEMA_ID_COUNTER if it already doesn't exist
    if (!zkClient.exists(ZOOKEEPER_SCHEMA_ID_COUNTER)) {
      // Infer next batch from preexisting kafka data (if present)
      nextIdBatchLowerBound = getNextBatchLowerBoundFromKafkaStore();
      int nextIdBatchUpperBound = nextIdBatchLowerBound + ZOOKEEPER_SCHEMA_ID_COUNTER_BATCH_SIZE;
      ZkUtils.createPersistentPath(zkClient, ZOOKEEPER_SCHEMA_ID_COUNTER,
                                   String.valueOf(nextIdBatchUpperBound));
      return nextIdBatchLowerBound;
    }

    // ZOOKEEPER_SCHEMA_ID_COUNTER exists
    int newSchemaIdCounterDataVersion = -1;
    while (newSchemaIdCounterDataVersion < 0) {
      int zkNextIdBatchLowerBound = 0;

      // read the latest counter value
      final ZkData counterValue = ZkUtils.readData(zkClient, ZOOKEEPER_SCHEMA_ID_COUNTER);
      if (counterValue.getData() != null) {
        zkNextIdBatchLowerBound = Integer.valueOf(counterValue.getData());


        if (zkNextIdBatchLowerBound % ZOOKEEPER_SCHEMA_ID_COUNTER_BATCH_SIZE != 0) {
          int oldZkIdBatchLowerBound = zkNextIdBatchLowerBound;
          zkNextIdBatchLowerBound =
              (1 + zkNextIdBatchLowerBound / ZOOKEEPER_SCHEMA_ID_COUNTER_BATCH_SIZE) *
              ZOOKEEPER_SCHEMA_ID_COUNTER_BATCH_SIZE;

          log.warn(
              "Zookeeper schema id counter is not an integer multiple of id batch size." +
              " Zookeeper may have stale id counter data.\n" +
              "zk id counter: " + oldZkIdBatchLowerBound + "\n" +
              "id batch size: " + ZOOKEEPER_SCHEMA_ID_COUNTER_BATCH_SIZE);
        }

      } else {
        throw new SchemaRegistryStoreException(
            "Failed to initialize schema registry. Failed to read " +
            "schema id counter " + ZOOKEEPER_SCHEMA_ID_COUNTER + " from zookeeper");
      }
      nextIdBatchLowerBound =
          Math.max(zkNextIdBatchLowerBound, getNextBatchLowerBoundFromKafkaStore());

      // conditionally update the zookeeper path with the upper bound of the new id batch
      String newCounterValue = String.valueOf(nextIdBatchLowerBound +
                                              ZOOKEEPER_SCHEMA_ID_COUNTER_BATCH_SIZE);

      // newSchemaIdCounterDataVersion < 0 indicates a failed conditional update. Most probable reason is the
      // existence of another master who also tries to do the same counter batch allocation at
      // the same time. If this happens, re-read the value and continue until one master is
      // determined to be the zombie master.
      // NOTE: The handling of multiple masters is still a TODO
      newSchemaIdCounterDataVersion = ZkUtils.conditionalUpdatePersistentPath(zkClient,
                                                  ZOOKEEPER_SCHEMA_ID_COUNTER,
                                                  newCounterValue,
                                                  counterValue.getStat().getVersion(),
                                                  null);
      try {
        Thread.sleep(ZOOKEEPER_SCHEMA_ID_COUNTER_BATCH_WRITE_RETRY_BACKOFF_MS);
      } catch (InterruptedException ignored) {
      }
    }

    return nextIdBatchLowerBound;
  }

  private int forwardRegisterRequestToMaster(String subject, String schemaString, String host,
                                             int port,
                                             Map<String, String> headerProperties)
      throws SchemaRegistryRequestForwardingException {
    String baseUrl = String.format("http://%s:%d", host, port);
    RegisterSchemaRequest registerSchemaRequest = new RegisterSchemaRequest();
    registerSchemaRequest.setSchema(schemaString);
    log.debug(String.format("Forwarding registering schema request %s to %s",
                            registerSchemaRequest, baseUrl));
    try {
      int id = RestUtils.registerSchema(baseUrl, headerProperties, registerSchemaRequest,
                                        subject);
      return id;
    } catch (IOException e) {
      throw new SchemaRegistryRequestForwardingException(
          String.format("Unexpected error while forwarding the registering schema request %s to %s",
                        registerSchemaRequest, baseUrl),
          e);
    } catch (RestClientException e) {
      throw new RestException(e.getMessage(), e.getStatus(), e.getErrorCode(), e);
    }
  }

  private Schema forwardSubjectVersionRequestToMaster(
      String subject, String schemaString, String host, int port,
      Map<String, String> headerProperties) throws SchemaRegistryRequestForwardingException {
    String baseUrl = String.format("http://%s:%d", host, port);
    RegisterSchemaRequest registerSchemaRequest = new RegisterSchemaRequest();
    registerSchemaRequest.setSchema(schemaString);
    log.debug(String.format("Forwarding schema version search request %s to %s",
                            registerSchemaRequest, baseUrl));
    try {
      Schema schema =
          RestUtils.lookUpSubjectVersion(baseUrl, headerProperties, registerSchemaRequest,
                                         subject);
      return schema;
    } catch (IOException e) {
      throw new SchemaRegistryRequestForwardingException(
          String.format("Unexpected error while forwarding the registering schema request %s to %s",
                        registerSchemaRequest, baseUrl),
          e);
    } catch (RestClientException e) {
      throw new RestException(e.getMessage(), e.getStatus(), e.getErrorCode(), e);
      // TODO: Fix this
//    } catch (RestClientException e) {
//      throw new SchemaRegistryRequestForwardingException(e);
    }
  }

  private AvroSchema canonicalizeSchema(Schema schema) throws InvalidSchemaException {
    AvroSchema avroSchema = AvroUtils.parseSchema(schema.getSchema());
    if (avroSchema == null) {
      throw new InvalidSchemaException("Invalid schema " + schema.toString());
    }
    schema.setSchema(avroSchema.canonicalString);
    return avroSchema;
  }

  @Override
  public Schema get(String subject, int version) throws SchemaRegistryException {
    VersionId versionId = new VersionId(version);
    if (versionId.isLatest()) {
      return getLatestVersion(subject);
    } else {
      SchemaKey key = new SchemaKey(subject, version);
      try {
        SchemaValue schemaValue = (SchemaValue) kafkaStore.get(key);
        Schema schema = getSchemaEntityFromSchemaValue(schemaValue);
        return schema;
      } catch (StoreException e) {
        throw new SchemaRegistryStoreException(
            "Error while retrieving schema from the backend Kafka" +
            " store", e);
      }
    }
  }

  @Override
  public SchemaString get(int id) throws SchemaRegistryException {
    SchemaValue schema = null;
    try {
      SchemaKey subjectVersionKey = guidToSchemaKey.get(id);
      if (subjectVersionKey == null) {
        return null;
      }
        schema = (SchemaValue) kafkaStore.get(subjectVersionKey);
    } catch (StoreException e) {
      throw new SchemaRegistryStoreException(
          "Error while retrieving schema with id " + id + " from the backend Kafka" +
          " store", e);
    }
    SchemaString schemaString = new SchemaString();
    schemaString.setSchemaString(schema.getSchema());
    return schemaString;
  }

  @Override
  public Set<String> listSubjects() throws SchemaRegistryException {
    try {
      Iterator<SchemaRegistryKey> allKeys = kafkaStore.getAllKeys();
      return extractUniqueSubjects(allKeys);
    } catch (StoreException e) {
      throw new SchemaRegistryStoreException(
          "Error from the backend Kafka store", e);
    }
  }

  private Set<String> extractUniqueSubjects(Iterator<SchemaRegistryKey> allKeys) {
    Set<String> subjects = new HashSet<String>();
    while (allKeys.hasNext()) {
      SchemaRegistryKey k = allKeys.next();
      if (k instanceof SchemaKey) {
        SchemaKey key = (SchemaKey) k;
        subjects.add(key.getSubject());
      }
    }
    return subjects;
  }

  @Override
  public Iterator<Schema> getAllVersions(String subject) throws SchemaRegistryException {
    try {
      SchemaKey key1 = new SchemaKey(subject, MIN_VERSION);
      SchemaKey key2 = new SchemaKey(subject, MAX_VERSION);
      Iterator<SchemaRegistryValue> allVersions = kafkaStore.getAll(key1, key2);
      return sortSchemasByVersion(allVersions).iterator();
    } catch (StoreException e) {
      throw new SchemaRegistryStoreException(
          "Error from the backend Kafka store", e);
    }
  }

  @Override
  public Schema getLatestVersion(String subject) throws SchemaRegistryException {
    try {
      SchemaKey key1 = new SchemaKey(subject, MIN_VERSION);
      SchemaKey key2 = new SchemaKey(subject, MAX_VERSION);
      Iterator<SchemaRegistryValue> allVersions = kafkaStore.getAll(key1, key2);
      Vector<Schema> sortedVersions = sortSchemasByVersion(allVersions);
      Schema latestSchema = null;
      if (sortedVersions.size() > 0) {
        latestSchema = sortedVersions.lastElement();
      }
      return latestSchema;
    } catch (StoreException e) {
      throw new SchemaRegistryStoreException(
          "Error from the backend Kafka store", e);
    }
  }

  @Override
  public void close() {
    log.info("Shutting down schema registry");
    kafkaStore.close();
    if (masterElector != null) {
      masterElector.close();
    }
    if (zkClient != null) {
      zkClient.close();
    }
  }

  public void updateCompatibilityLevel(String subject, AvroCompatibilityLevel newCompatibilityLevel)
      throws SchemaRegistryStoreException, UnknownMasterException {
    synchronized (masterLock) {
      if (isMaster()) {
        ConfigKey configKey = new ConfigKey(subject);
        try {
          kafkaStore.put(configKey, new ConfigValue(newCompatibilityLevel));
          log.debug("Wrote new compatibility level: " + newCompatibilityLevel.name + " to the"
                    + " Kafka data store with key " + configKey.toString());
        } catch (StoreException e) {
          throw new SchemaRegistryStoreException("Failed to write new config value to the store",
                                                 e);
        }
      } else {
        // TODO: logic to forward will be included as part of issue#96
        throw new UnknownMasterException("Config update request failed since this is not the "
                                          + "master");

      }
    }
  }

  public AvroCompatibilityLevel getCompatibilityLevel(String subject)
      throws SchemaRegistryStoreException {
    ConfigKey subjectConfigKey = new ConfigKey(subject);
    ConfigValue config;
    try {
      config = (ConfigValue) kafkaStore.get(subjectConfigKey);
      if (config == null && subject == null) {
        // if top level config was never updated, send the configured value for this instance
        config = new ConfigValue(this.defaultCompatibilityLevel);
      } else if (config == null) {
        config = new ConfigValue();
      }
    } catch (StoreException e) {
      throw new SchemaRegistryStoreException("Failed to read config from the kafka store", e);
    }
    return config.getCompatibilityLevel();
  }

  @Override
  public boolean isCompatible(String subject,
                              String newSchemaObj,
                              String latestSchema)
      throws SchemaRegistryException {
    AvroSchema newAvroSchema = AvroUtils.parseSchema(newSchemaObj);
    AvroSchema latestAvroSchema = AvroUtils.parseSchema(latestSchema);
    if (latestSchema == null) {
      throw new InvalidSchemaException(
          "Existing schema " + latestSchema + " is not a valid Avro schema");
    }
    AvroCompatibilityLevel compatibility = getCompatibilityLevel(subject);
    if (compatibility == null) {
      compatibility = getCompatibilityLevel(null);
    }
    return compatibility.compatibilityChecker
        .isCompatible(newAvroSchema.schemaObj, latestAvroSchema.schemaObj);
  }

  private Vector<Schema> sortSchemasByVersion(Iterator<SchemaRegistryValue> schemas) {
    Vector<Schema> schemaVector = new Vector<Schema>();
    while (schemas.hasNext()) {
      SchemaValue schemaValue = (SchemaValue) schemas.next();
      schemaVector.add(getSchemaEntityFromSchemaValue(schemaValue));
    }
    Collections.sort(schemaVector);
    return schemaVector;
  }

  private Schema getSchemaEntityFromSchemaValue(SchemaValue schemaValue) {
    if (schemaValue == null) {
      return null;
    }
    return new Schema(schemaValue.getSubject(), schemaValue.getVersion(),
                      schemaValue.getId(), schemaValue.getSchema());
  }

  int getMaxIdInStore() {
    return this.maxIdInStore;
  }

  void setMaxIdInStore(int id) {
    this.maxIdInStore = id;
  }

  /**
   * Return a minimum lower bound on the next batch of ids based on ids currently in the
   * kafka store.
   */
  private int getNextBatchLowerBoundFromKafkaStore() {
    synchronized (masterLock) {
      if (this.getMaxIdInStore() < 0) {
        return 0;
      }

      int nextBatchLowerBound = 1 + this.getMaxIdInStore() / ZOOKEEPER_SCHEMA_ID_COUNTER_BATCH_SIZE;
      return nextBatchLowerBound * ZOOKEEPER_SCHEMA_ID_COUNTER_BATCH_SIZE;
    }
  }
}
