/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kafka.controller

import java.util.concurrent.{CountDownLatch, TimeUnit}

import com.yammer.metrics.core.Gauge
import kafka.admin.{AdminUtils, PreferredReplicaLeaderElectionCommand}
import kafka.api._
import kafka.cluster.Broker
import kafka.common.{TopicAndPartition, _}
import kafka.log.LogConfig
import kafka.metrics.{KafkaMetricsGroup, KafkaTimer}
import kafka.server._
import kafka.utils.ZkUtils._
import kafka.utils._
import org.I0Itec.zkclient.exception.{ZkNoNodeException, ZkNodeExistsException}
import org.I0Itec.zkclient.{IZkChildListener, IZkDataListener, IZkStateListener}
import org.apache.kafka.common.errors.{BrokerNotAvailableException, ControllerMovedException}
import org.apache.kafka.common.metrics.Metrics
import org.apache.kafka.common.protocol.{ApiKeys, Errors}
import org.apache.kafka.common.requests.{AbstractRequest, AbstractResponse, LeaderAndIsrResponse, StopReplicaResponse}
import org.apache.kafka.common.utils.Time
import org.apache.zookeeper.Watcher.Event.KeeperState

import scala.collection._
import scala.util.Try

class ControllerContext(val zkUtils: ZkUtils) {
  val stats = new ControllerStats

  var controllerChannelManager: ControllerChannelManager = null

  var shuttingDownBrokerIds: mutable.Set[Int] = mutable.Set.empty
  var epoch: Int = KafkaController.InitialControllerEpoch
  var epochZkVersion: Int = KafkaController.InitialControllerEpochZkVersion
  var allTopics: Set[String] = Set.empty
  private var partitionReplicaAssignmentUnderlying: mutable.Map[String, mutable.Map[Int, Seq[Int]]] = mutable.Map.empty
  var partitionLeadershipInfo: mutable.Map[TopicAndPartition, LeaderIsrAndControllerEpoch] = mutable.Map.empty
  val partitionsBeingReassigned: mutable.Map[TopicAndPartition, ReassignedPartitionsContext] = new mutable.HashMap
  val replicasOnOfflineDirs: mutable.Map[Int, Set[TopicAndPartition]] = mutable.HashMap.empty

  private var liveBrokersUnderlying: Set[Broker] = Set.empty
  private var liveBrokerIdsUnderlying: Set[Int] = Set.empty

  def partitionReplicaAssignment(topicAndPartition: TopicAndPartition) : Seq[Int] = {
    partitionReplicaAssignmentUnderlying.getOrElse(topicAndPartition.topic, mutable.Map.empty)
      .getOrElse(topicAndPartition.partition, Seq.empty)
  }

  // setter
  def clearPartitionReplicaAssignment() = {
    partitionReplicaAssignmentUnderlying = mutable.Map.empty
  }

  def updatePartitionReplicaAssignment(topicAndPartition: TopicAndPartition, newReplicas : Seq[Int]) = {
    partitionReplicaAssignmentUnderlying.getOrElseUpdate(topicAndPartition.topic, mutable.Map.empty)
      .put(topicAndPartition.partition, newReplicas)
  }

  def partitionReplicaAssignmentForTopic(topic : String) : mutable.Map[TopicAndPartition, Seq[Int]] = {
    partitionReplicaAssignmentUnderlying.getOrElse(topic, mutable.Map.empty).map {
      case (partition, replicas) => (new TopicAndPartition(topic, partition), replicas)
    }
  }

  def removePartitionReplicaAssignmentForTopic(topic : String) = {
    partitionReplicaAssignmentUnderlying.remove(topic)
  }

  def allPartitions : Set[TopicAndPartition] = {
    partitionReplicaAssignmentUnderlying.flatMap {
      case (topic, topicReplicaAssignment) => topicReplicaAssignment.map {
        case (partition, _) => new TopicAndPartition(topic, partition)
      }
    }.toSet
  }

  // setter
  def liveBrokers_=(brokers: Set[Broker]) {
    liveBrokersUnderlying = brokers
    liveBrokerIdsUnderlying = liveBrokersUnderlying.map(_.id)
  }

  // getter
  def liveBrokers = liveBrokersUnderlying.filter(broker => !shuttingDownBrokerIds.contains(broker.id))
  def liveBrokerIds = liveBrokerIdsUnderlying -- shuttingDownBrokerIds

  def liveOrShuttingDownBrokerIds = liveBrokerIdsUnderlying
  def liveOrShuttingDownBrokers = liveBrokersUnderlying

  def partitionsOnBroker(brokerId: Int): Set[TopicAndPartition] = {
    partitionReplicaAssignmentUnderlying.flatMap {
      case (topic, topicReplicaAssignment) => topicReplicaAssignment.filter {
        case (_, replicas) => replicas.contains(brokerId)
      }.map {
        case (partition, _) => new TopicAndPartition(topic, partition)
      }
    }.toSet
  }

  def isReplicaOnline(brokerId: Int, topicAndPartition: TopicAndPartition, includeShuttingDownBrokers: Boolean = false): Boolean = {
    val brokerOnline = {
      if (includeShuttingDownBrokers) liveOrShuttingDownBrokerIds.contains(brokerId)
      else liveBrokerIds.contains(brokerId)
    }
    brokerOnline && !replicasOnOfflineDirs.getOrElse(brokerId, Set.empty).contains(topicAndPartition)
  }

  def replicasOnBrokers(brokerIds: Set[Int]): Set[PartitionAndReplica] = {
    brokerIds.flatMap { brokerId =>
      partitionReplicaAssignmentUnderlying.flatMap {
        case (topic, topicReplicaAssignment) => topicReplicaAssignment.collect {
          case (partition, replicas)  if replicas.contains(brokerId) =>
              PartitionAndReplica(topic, partition, brokerId)
        }
      }
    }
  }

  def replicasForTopic(topic: String): Set[PartitionAndReplica] = {
    partitionReplicaAssignmentUnderlying.getOrElse(topic, mutable.Map.empty).flatMap {
      case (partition, replicas) => replicas.map(r => new PartitionAndReplica(topic, partition, r))
    }.toSet
  }

  def partitionsForTopic(topic: String): Set[TopicAndPartition] = {
    partitionReplicaAssignmentUnderlying.getOrElse(topic, mutable.Map.empty).map {
      case (partition, _) => new TopicAndPartition(topic, partition)
    }.toSet
  }

  def allLiveReplicas(): Set[PartitionAndReplica] = {
    replicasOnBrokers(liveBrokerIds).filter { partitionAndReplica =>
      isReplicaOnline(partitionAndReplica.replica, TopicAndPartition(partitionAndReplica.topic, partitionAndReplica.partition))
    }
  }

  def replicasForPartition(partitions: collection.Set[TopicAndPartition]): collection.Set[PartitionAndReplica] = {
    partitions.flatMap { p =>
      val replicas = partitionReplicaAssignment(p)
      replicas.map(r => new PartitionAndReplica(p.topic, p.partition, r))
    }
  }

  def removeTopic(topic: String) = {
    partitionLeadershipInfo = partitionLeadershipInfo.filter{ case (topicAndPartition, _) => topicAndPartition.topic != topic }
    partitionReplicaAssignmentUnderlying.remove(topic)
    allTopics -= topic
  }

}


object KafkaController extends Logging {
  val stateChangeLogger = new StateChangeLogger("state.change.logger")
  val InitialControllerEpoch = 0
  val InitialControllerEpochZkVersion = 0

  case class StateChangeLogger(override val loggerName: String) extends Logging

  // Used only by test
  private[controller] case class AwaitOnLatch(latch: CountDownLatch) extends ControllerEvent {
    override def state: ControllerState = ControllerState.ControllerChange
    override def process(): Unit = latch.await()
  }

  def parseControllerId(controllerInfoString: String): Int = {
    try {
      Json.parseFull(controllerInfoString) match {
        case Some(js) => js.asJsonObject("brokerid").to[Int]
        case None => throw new KafkaException("Failed to parse the controller info json [%s].".format(controllerInfoString))
      }
    } catch {
      case _: Throwable =>
        // It may be due to an incompatible controller register version
        warn("Failed to parse the controller info as json. "
          + "Probably this controller is still using the old format [%s] to store the broker id in zookeeper".format(controllerInfoString))
        try controllerInfoString.toInt
        catch {
          case t: Throwable => throw new KafkaException("Failed to parse the controller info: " + controllerInfoString + ". This is neither the new or the old format.", t)
        }
    }
  }
}

class KafkaController(val config: KafkaConfig, zkUtils: ZkUtils, time: Time, metrics: Metrics, threadNamePrefix: Option[String] = None) extends Logging with KafkaMetricsGroup {
  this.logIdent = "[Controller " + config.brokerId + "]: "
  private val stateChangeLogger = KafkaController.stateChangeLogger
  val controllerContext = new ControllerContext(zkUtils)
  val partitionStateMachine = new PartitionStateMachine(this)
  val replicaStateMachine = new ReplicaStateMachine(this)

  // have a separate scheduler for the controller to be able to start and stop independently of the kafka server
  // visible for testing
  private[controller] val kafkaScheduler = new KafkaScheduler(1)

  // visible for testing
  private[controller] val eventManager = new ControllerEventManager(controllerContext.stats.rateAndTimeMetrics,
    _ => updateMetrics(), () => maybeResign())

  val topicDeletionManager = new TopicDeletionManager(this, eventManager)
  val offlinePartitionSelector = new OfflinePartitionLeaderSelector(controllerContext, config)
  private val reassignedPartitionLeaderSelector = new ReassignedPartitionLeaderSelector(controllerContext)
  private val preferredReplicaPartitionLeaderSelector = new PreferredReplicaPartitionLeaderSelector(controllerContext)
  private val controlledShutdownPartitionLeaderSelector = new ControlledShutdownLeaderSelector(controllerContext)
  private val brokerRequestBatch = new ControllerBrokerRequestBatch(this)

  private val brokerChangeListener = new BrokerChangeListener(this, eventManager)
  private val topicChangeListener = new TopicChangeListener(this, eventManager)
  private val topicDeletionListener = new TopicDeletionListener(this, eventManager)
  private val topicDeletionFlagListener = new TopicDeletionFlagListener(this, eventManager)
  private val partitionModificationsListeners: mutable.Map[String, PartitionModificationsListener] = mutable.Map.empty
  private val partitionReassignmentListener = new PartitionReassignmentListener(this, eventManager)
  private val preferredReplicaElectionListener = new PreferredReplicaElectionListener(this, eventManager)
  private val isrChangeNotificationListener = new IsrChangeNotificationListener(this, eventManager)
  private val logDirEventNotificationListener = new LogDirEventNotificationListener(this, eventManager)

  @volatile private var activeControllerId = -1
  @volatile private var offlinePartitionCount = 0
  @volatile private var globalTopicCount = 0
  @volatile private var globalPartitionCount = 0

  newGauge(
    "ActiveControllerCount",
    new Gauge[Int] {
      def value = if (isActive) 1 else 0
    }
  )

  newGauge(
    "OfflinePartitionsCount",
    new Gauge[Int] {
      def value: Int = offlinePartitionCount
    }
  )

  newGauge(
    "ControllerState",
    new Gauge[Byte] {
      def value: Byte = state.value
    }
  )

  newGauge(
    "GlobalTopicCount",
    new Gauge[Int] {
      def value: Int = globalTopicCount
    }
  )

  newGauge(
    "GlobalPartitionCount",
    new Gauge[Int] {
      def value: Int = globalPartitionCount
    }
  )

  def epoch: Int = controllerContext.epoch

  def state: ControllerState = eventManager.state

  def clientId: String = {
    val controllerListener = config.listeners.find(_.listenerName == config.interBrokerListenerName).getOrElse(
      throw new IllegalArgumentException(s"No listener with name ${config.interBrokerListenerName} is configured."))
    "id_%d-host_%s-port_%d".format(config.brokerId, controllerListener.host, controllerListener.port)
  }

  /**
   * On clean shutdown, the controller first determines the partitions that the
   * shutting down broker leads, and moves leadership of those partitions to another broker
   * that is in that partition's ISR.
   *
   * @param id Id of the broker to shutdown.
   * @return The number of partitions that the broker still leads.
   */
  def shutdownBroker(id: Int, controlledShutdownCallback: Try[Set[TopicAndPartition]] => Unit): Unit = {
    val controlledShutdownEvent = ControlledShutdown(id, controlledShutdownCallback)
    eventManager.put(controlledShutdownEvent)
  }

  /**
   * This callback is invoked by the zookeeper leader elector on electing the current broker as the new controller.
   * It does the following things on the become-controller state change -
   * 1. Initializes the controller's context object that holds cache objects for current topics, live brokers and
   *    leaders for all existing partitions.
   * 2. Starts the controller's channel manager
   * 3. Starts the replica state machine
   * 4. Starts the partition state machine
   * If it encounters any unexpected exception/error while becoming controller, it resigns as the current controller.
   * This ensures another controller election will be triggered and there will always be an actively serving controller
   */
  def onControllerFailover() {
    info("Broker %d starting become controller state transition".format(config.brokerId))
    LogDirUtils.deleteLogDirEvents(zkUtils)

    // before reading source of truth from zookeeper, register the listeners to get broker/topic callbacks
    registerPartitionReassignmentListener()
    registerIsrChangeNotificationListener()
    registerPreferredReplicaElectionListener()
    registerTopicChangeListener()
    registerTopicDeletionListener()
    registerTopicDeletionFlagListener()
    registerBrokerChangeListener()
    registerLogDirEventNotificationListener()

    val topicsToBeDeleted = fetchTopicDeletionsInProgress()
    initializeControllerContext(topicsToBeDeleted)
    val topicsIneligibleForDeletion = fetchTopicsIneligibleForDeletion()

    topicDeletionManager.init(topicsToBeDeleted, topicsIneligibleForDeletion)

    // We need to send UpdateMetadataRequest after the controller context is initialized and before the state machines
    // are started. The is because brokers need to receive the list of live brokers from UpdateMetadataRequest before
    // they can process the LeaderAndIsrRequests that are generated by replicaStateMachine.startup() and
    // partitionStateMachine.startup().
    sendUpdateMetadataRequest(controllerContext.liveOrShuttingDownBrokerIds.toSeq)

    replicaStateMachine.startup()
    partitionStateMachine.startup()

    // register the partition change listeners for all existing topics on failover
    controllerContext.allTopics.foreach(topic => registerPartitionModificationsListener(topic))
    info("Broker %d is ready to serve as the new controller with epoch %d".format(config.brokerId, epoch))
    maybeTriggerPartitionReassignment()
    topicDeletionManager.tryTopicDeletion()
    val pendingPreferredReplicaElections = fetchPendingPreferredReplicaElections()
    onPreferredReplicaElection(pendingPreferredReplicaElections)
    info("starting the controller scheduler")
    kafkaScheduler.startup()
    if (config.autoLeaderRebalanceEnable) {
      scheduleAutoLeaderRebalanceTask(delay = 5, unit = TimeUnit.SECONDS)
    }
  }

  private def scheduleAutoLeaderRebalanceTask(delay: Long, unit: TimeUnit): Unit = {
    kafkaScheduler.schedule("auto-leader-rebalance-task", () => eventManager.put(AutoPreferredReplicaLeaderElection),
      delay = delay, unit = unit)
  }

  /**
   * This callback is invoked by the zookeeper leader elector when the current broker resigns as the controller. This is
   * required to clean up internal controller data structures
   */
  def onControllerResignation() {
    debug("Controller resigning, broker id %d".format(config.brokerId))
    // de-register listeners
    deregisterIsrChangeNotificationListener()
    deregisterPartitionReassignmentListener()
    deregisterPreferredReplicaElectionListener()
    deregisterLogDirEventNotificationListener()

    // reset topic deletion manager
    topicDeletionManager.reset()

    // shutdown leader rebalance scheduler
    kafkaScheduler.shutdown()
    offlinePartitionCount = 0
    globalTopicCount = 0
    globalPartitionCount = 0

    // de-register partition ISR listener for on-going partition reassignment task
    deregisterPartitionReassignmentIsrChangeListeners()
    // shutdown partition state machine
    partitionStateMachine.shutdown()
    deregisterTopicChangeListener()
    partitionModificationsListeners.keys.foreach(deregisterPartitionModificationsListener)
    deregisterTopicDeletionListener()
    deregisterTopicDeletionFlagListener()
    // shutdown replica state machine
    replicaStateMachine.shutdown()
    deregisterBrokerChangeListener()

    resetControllerContext()

    info("Broker %d resigned as the controller".format(config.brokerId))
  }

  /**
   * Returns true if this broker is the current controller.
   */
  def isActive: Boolean = activeControllerId == config.brokerId

  /*
   * This callback is invoked by the controller's LogDirEventNotificationListener with the list of broker ids who
   * have experienced new log directory failures. In response the controller should send LeaderAndIsrRequest
   * to all these brokers to query the state of their replicas
   */
  def onBrokerLogDirFailure(brokerIds: Seq[Int]) {
    // send LeaderAndIsrRequest for all replicas on those brokers to see if they are still online.
    val replicasOnBrokers = controllerContext.replicasOnBrokers(brokerIds.toSet)
    replicaStateMachine.handleStateChanges(replicasOnBrokers, OnlineReplica)
  }

  /**
   * This callback is invoked by the replica state machine's broker change listener, with the list of newly started
   * brokers as input. It does the following -
   * 1. Sends update metadata request to all live and shutting down brokers
   * 2. Triggers the OnlinePartition state change for all new/offline partitions
   * 3. It checks whether there are reassigned replicas assigned to any newly started brokers.  If
   *    so, it performs the reassignment logic for each topic/partition.
   *
   * Note that we don't need to refresh the leader/isr cache for all topic/partitions at this point for two reasons:
   * 1. The partition state machine, when triggering online state change, will refresh leader and ISR for only those
   *    partitions currently new or offline (rather than every partition this controller is aware of)
   * 2. Even if we do refresh the cache, there is no guarantee that by the time the leader and ISR request reaches
   *    every broker that it is still valid.  Brokers check the leader epoch to determine validity of the request.
   */
  def onBrokerStartup(newBrokers: Seq[Int]) {
    info("New broker startup callback for %s".format(newBrokers.mkString(",")))
    newBrokers.foreach(controllerContext.replicasOnOfflineDirs.remove)
    val newBrokersSet = newBrokers.toSet
    // send update metadata request to all live and shutting down brokers. Old brokers will get to know of the new
    // broker via this update.
    // In cases of controlled shutdown leaders will not be elected when a new broker comes up. So at least in the
    // common controlled shutdown case, the metadata will reach the new brokers faster
    sendUpdateMetadataRequest(controllerContext.liveOrShuttingDownBrokerIds.toSeq)
    // the very first thing to do when a new broker comes up is send it the entire list of partitions that it is
    // supposed to host. Based on that the broker starts the high watermark threads for the input list of partitions
    val allReplicasOnNewBrokers = controllerContext.replicasOnBrokers(newBrokersSet)
    replicaStateMachine.handleStateChanges(allReplicasOnNewBrokers, OnlineReplica)
    // when a new broker comes up, the controller needs to trigger leader election for all new and offline partitions
    // to see if these brokers can become leaders for some/all of those
    partitionStateMachine.triggerOnlinePartitionStateChange()
    // check if reassignment of some partitions need to be restarted
    val partitionsWithReplicasOnNewBrokers = controllerContext.partitionsBeingReassigned.filter {
      case (_, reassignmentContext) => reassignmentContext.newReplicas.exists(newBrokersSet.contains(_))
    }
    partitionsWithReplicasOnNewBrokers.foreach(p => onPartitionReassignment(p._1, p._2))
    // check if topic deletion needs to be resumed. If at least one replica that belongs to the topic being deleted exists
    // on the newly restarted brokers, there is a chance that topic deletion can resume
    val replicasForTopicsToBeDeleted = allReplicasOnNewBrokers.filter(p => topicDeletionManager.isTopicQueuedUpForDeletion(p.topic))
    if(replicasForTopicsToBeDeleted.nonEmpty) {
      info(("Some replicas %s for topics scheduled for deletion %s are on the newly restarted brokers %s. " +
        "Signaling restart of topic deletion for these topics").format(replicasForTopicsToBeDeleted.mkString(","),
        topicDeletionManager.topicsToBeDeleted.mkString(","), newBrokers.mkString(",")))
      topicDeletionManager.resumeDeletionForTopics(replicasForTopicsToBeDeleted.map(_.topic))
    }
  }

  /*
   * This callback is invoked by the replica state machine's broker change listener with the list of failed brokers
   * as input. It will call onReplicaBecomeOffline(...) with the list of replicas on those failed brokers as input.
   */
  def onBrokerFailure(deadBrokers: Seq[Int]) {
    info("Broker failure callback for %s".format(deadBrokers.mkString(",")))
    deadBrokers.foreach(controllerContext.replicasOnOfflineDirs.remove)
    val deadBrokersThatWereShuttingDown =
      deadBrokers.filter(id => controllerContext.shuttingDownBrokerIds.remove(id))
    info("Removed %s from list of shutting down brokers.".format(deadBrokersThatWereShuttingDown))
    val allReplicasOnDeadBrokers = controllerContext.replicasOnBrokers(deadBrokers.toSet)
    onReplicasBecomeOffline(allReplicasOnDeadBrokers)
  }

  /**
    * This method marks the given replicas as offline. It does the following -
    * 1. Mark the given partitions as offline
    * 2. Triggers the OnlinePartition state change for all new/offline partitions
    * 3. Invokes the OfflineReplica state change on the input list of newly offline replicas
    * 4. If no partitions are affected then send UpdateMetadataRequest to live or shutting down brokers
    *
    * Note that we don't need to refresh the leader/isr cache for all topic/partitions at this point.  This is because
    * the partition state machine will refresh our cache for us when performing leader election for all new/offline
    * partitions coming online.
    */
  def onReplicasBecomeOffline(newOfflineReplicas: Set[PartitionAndReplica]): Unit = {
    val (newOfflineReplicasForDeletion, newOfflineReplicasNotForDeletion) =
      newOfflineReplicas.partition(p => topicDeletionManager.isTopicQueuedUpForDeletion(p.topic))

    val partitionsWithoutLeader = controllerContext.partitionLeadershipInfo.filter(partitionAndLeader =>
      !controllerContext.isReplicaOnline(partitionAndLeader._2.leaderAndIsr.leader, partitionAndLeader._1) &&
        !topicDeletionManager.isTopicQueuedUpForDeletion(partitionAndLeader._1.topic)).keySet

    // trigger OfflinePartition state for all partitions whose current leader is one amongst the newOfflineReplicas
    partitionStateMachine.handleStateChanges(partitionsWithoutLeader, OfflinePartition)
    // trigger OnlinePartition state changes for offline or new partitions
    partitionStateMachine.triggerOnlinePartitionStateChange()
    // trigger OfflineReplica state change for those newly offline replicas
    replicaStateMachine.handleStateChanges(newOfflineReplicasNotForDeletion, OfflineReplica)

    // fail deletion of topics that affected by the offline replicas
    if (newOfflineReplicasForDeletion.nonEmpty) {
      // it is required to mark the respective replicas in TopicDeletionFailed state since the replica cannot be
      // deleted when its log directory is offline. This will prevent the replica from being in TopicDeletionStarted state indefinitely
      // since topic deletion cannot be retried until at least one replica is in TopicDeletionStarted state
      topicDeletionManager.failReplicaDeletion(newOfflineReplicasForDeletion)
    }

    // If replica failure did not require leader re-election, inform brokers of the offline replica
    // Note that during leader re-election, brokers update their metadata
    if (partitionsWithoutLeader.isEmpty) {
      sendUpdateMetadataRequest(controllerContext.liveOrShuttingDownBrokerIds.toSeq)
    }
  }

  /**
   * This callback is invoked by the partition state machine's topic change listener with the list of new topics
   * and partitions as input. It does the following -
   * 1. Registers partition change listener. This is not required until KAFKA-347
   * 2. Invokes the new partition callback
   * 3. Send metadata request with the new topic to all brokers so they allow requests for that topic to be served
   */
  def onNewTopicCreation(topics: Set[String], newPartitions: Set[TopicAndPartition]) {
    info("New topic creation callback for %s".format(newPartitions.mkString(",")))
    // subscribe to partition changes
    topics.foreach(topic => registerPartitionModificationsListener(topic))
    onNewPartitionCreation(newPartitions)
  }

  /**
   * This callback is invoked by the topic change callback with the list of failed brokers as input.
   * It does the following -
   * 1. Move the newly created partitions to the NewPartition state
   * 2. Move the newly created partitions from NewPartition->OnlinePartition state
   */
  def onNewPartitionCreation(newPartitions: Set[TopicAndPartition]) {
    info("New partition creation callback for %s".format(newPartitions.mkString(",")))
    partitionStateMachine.handleStateChanges(newPartitions, NewPartition)
    replicaStateMachine.handleStateChanges(controllerContext.replicasForPartition(newPartitions), NewReplica)
    partitionStateMachine.handleStateChanges(newPartitions, OnlinePartition, offlinePartitionSelector)
    replicaStateMachine.handleStateChanges(controllerContext.replicasForPartition(newPartitions), OnlineReplica)
  }

  /**
   * This callback is invoked by the reassigned partitions listener. When an admin command initiates a partition
   * reassignment, it creates the /admin/reassign_partitions path that triggers the zookeeper listener.
   * Reassigning replicas for a partition goes through a few steps listed in the code.
   * RAR = Reassigned replicas
   * OAR = Original list of replicas for partition
   * AR = current assigned replicas
   *
   * 1. Update AR in ZK with OAR + RAR.
   * 2. Send LeaderAndIsr request to every replica in OAR + RAR (with AR as OAR + RAR). We do this by forcing an update
   *    of the leader epoch in zookeeper.
   * 3. Start new replicas RAR - OAR by moving replicas in RAR - OAR to NewReplica state.
   * 4. Wait until all replicas in RAR are in sync with the leader.
   * 5  Move all replicas in RAR to OnlineReplica state.
   * 6. Set AR to RAR in memory.
   * 7. If the leader is not in RAR, elect a new leader from RAR. If new leader needs to be elected from RAR, a LeaderAndIsr
   *    will be sent. If not, then leader epoch will be incremented in zookeeper and a LeaderAndIsr request will be sent.
   *    In any case, the LeaderAndIsr request will have AR = RAR. This will prevent the leader from adding any replica in
   *    RAR - OAR back in the isr.
   * 8. Move all replicas in OAR - RAR to OfflineReplica state. As part of OfflineReplica state change, we shrink the
   *    isr to remove OAR - RAR in zookeeper and send a LeaderAndIsr ONLY to the Leader to notify it of the shrunk isr.
   *    After that, we send a StopReplica (delete = false) to the replicas in OAR - RAR.
   * 9. Move all replicas in OAR - RAR to NonExistentReplica state. This will send a StopReplica (delete = true) to
   *    the replicas in OAR - RAR to physically delete the replicas on disk.
   * 10. Update AR in ZK with RAR.
   * 11. Update the /admin/reassign_partitions path in ZK to remove this partition.
   * 12. After electing leader, the replicas and isr information changes. So resend the update metadata request to every broker.
   *
   * For example, if OAR = {1, 2, 3} and RAR = {4,5,6}, the values in the assigned replica (AR) and leader/isr path in ZK
   * may go through the following transition.
   * AR                 leader/isr
   * {1,2,3}            1/{1,2,3}           (initial state)
   * {1,2,3,4,5,6}      1/{1,2,3}           (step 2)
   * {1,2,3,4,5,6}      1/{1,2,3,4,5,6}     (step 4)
   * {1,2,3,4,5,6}      4/{1,2,3,4,5,6}     (step 7)
   * {1,2,3,4,5,6}      4/{4,5,6}           (step 8)
   * {4,5,6}            4/{4,5,6}           (step 10)
   *
   * Note that we have to update AR in ZK with RAR last since it's the only place where we store OAR persistently.
   * This way, if the controller crashes before that step, we can still recover.
   */
  def onPartitionReassignment(topicAndPartition: TopicAndPartition, reassignedPartitionContext: ReassignedPartitionsContext) {
    val reassignedReplicas = reassignedPartitionContext.newReplicas
    if (!areReplicasInIsr(topicAndPartition.topic, topicAndPartition.partition, reassignedReplicas)) {
      info("New replicas %s for partition %s being ".format(reassignedReplicas.mkString(","), topicAndPartition) +
        "reassigned not yet caught up with the leader")
      val newReplicasNotInOldReplicaList = reassignedReplicas.toSet -- controllerContext.partitionReplicaAssignment(topicAndPartition).toSet
      val newAndOldReplicas = (reassignedPartitionContext.newReplicas ++ controllerContext.partitionReplicaAssignment(topicAndPartition)).toSet
      //1. Update AR in ZK with OAR + RAR.
      updateAssignedReplicasForPartition(topicAndPartition, newAndOldReplicas.toSeq)
      //2. Send LeaderAndIsr request to every replica in OAR + RAR (with AR as OAR + RAR).
      updateLeaderEpochAndSendRequest(topicAndPartition, controllerContext.partitionReplicaAssignment(topicAndPartition),
        newAndOldReplicas.toSeq)
      //3. replicas in RAR - OAR -> NewReplica
      startNewReplicasForReassignedPartition(topicAndPartition, reassignedPartitionContext, newReplicasNotInOldReplicaList)
      info("Waiting for new replicas %s for partition %s being ".format(reassignedReplicas.mkString(","), topicAndPartition) +
        "reassigned to catch up with the leader")
    } else {
      //4. Wait until all replicas in RAR are in sync with the leader.
      val oldReplicas = controllerContext.partitionReplicaAssignment(topicAndPartition).toSet -- reassignedReplicas.toSet
      //5. replicas in RAR -> OnlineReplica
      reassignedReplicas.foreach { replica =>
        replicaStateMachine.handleStateChanges(Set(new PartitionAndReplica(topicAndPartition.topic, topicAndPartition.partition,
          replica)), OnlineReplica)
      }
      //6. Set AR to RAR in memory.
      //7. Send LeaderAndIsr request with a potential new leader (if current leader not in RAR) and
      //   a new AR (using RAR) and same isr to every broker in RAR
      moveReassignedPartitionLeaderIfRequired(topicAndPartition, reassignedPartitionContext)
      //8. replicas in OAR - RAR -> Offline (force those replicas out of isr)
      //9. replicas in OAR - RAR -> NonExistentReplica (force those replicas to be deleted)
      stopOldReplicasOfReassignedPartition(topicAndPartition, reassignedPartitionContext, oldReplicas)
      //10. Update AR in ZK with RAR.
      updateAssignedReplicasForPartition(topicAndPartition, reassignedReplicas)
      //11. Update the /admin/reassign_partitions path in ZK to remove this partition.
      removePartitionFromReassignedPartitions(topicAndPartition)
      info("Removed partition %s from the list of reassigned partitions in zookeeper".format(topicAndPartition))
      controllerContext.partitionsBeingReassigned.remove(topicAndPartition)
      //12. After electing leader, the replicas and isr information changes, so resend the update metadata request to every broker
      sendUpdateMetadataRequest(controllerContext.liveOrShuttingDownBrokerIds.toSeq, Set(topicAndPartition))
      // signal delete topic thread if reassignment for some partitions belonging to topics being deleted just completed
      topicDeletionManager.resumeDeletionForTopics(Set(topicAndPartition.topic))
    }
  }

  private def watchIsrChangesForReassignedPartition(topic: String,
                                                    partition: Int,
                                                    reassignedPartitionContext: ReassignedPartitionsContext) {
    val reassignedReplicas = reassignedPartitionContext.newReplicas
    val isrChangeListener = new PartitionReassignmentIsrChangeListener(this, eventManager, topic, partition,
      reassignedReplicas.toSet)
    reassignedPartitionContext.isrChangeListener = isrChangeListener
    // register listener on the leader and isr path to wait until they catch up with the current leader
    zkUtils.subscribeDataChanges(getTopicPartitionLeaderAndIsrPath(topic, partition), isrChangeListener)
  }

  def initiateReassignReplicasForTopicPartition(topicAndPartition: TopicAndPartition,
                                                reassignedPartitionContext: ReassignedPartitionsContext) {
    val newReplicas = reassignedPartitionContext.newReplicas
    val topic = topicAndPartition.topic
    val partition = topicAndPartition.partition
    try {
      val assignedReplicas = controllerContext.partitionReplicaAssignment(topicAndPartition)
      if (assignedReplicas.nonEmpty) {
        if (assignedReplicas == newReplicas) {
          throw new KafkaException("Partition %s to be reassigned is already assigned to replicas".format(topicAndPartition) +
            " %s. Ignoring request for partition reassignment".format(newReplicas.mkString(",")))
        } else {
          info("Handling reassignment of partition %s to new replicas %s".format(topicAndPartition, newReplicas.mkString(",")))
          // first register ISR change listener
          watchIsrChangesForReassignedPartition(topic, partition, reassignedPartitionContext)
          controllerContext.partitionsBeingReassigned.put(topicAndPartition, reassignedPartitionContext)
          // mark topic ineligible for deletion for the partitions being reassigned
          topicDeletionManager.markTopicIneligibleForDeletion(Set(topic))
          onPartitionReassignment(topicAndPartition, reassignedPartitionContext)
        }
      } else {
        throw new KafkaException("Attempt to reassign partition %s that doesn't exist"
          .format(topicAndPartition))
      }
    } catch {
      case e1: ControllerMovedException =>
        error("Error completing reassignment of partition %s because controller moved to another broker".format(topicAndPartition), e1)
        throw e1
      case e2: Throwable => error("Error completing reassignment of partition %s".format(topicAndPartition), e2)
      // remove the partition from the admin path to unblock the admin client
      removePartitionFromReassignedPartitions(topicAndPartition)
    }
  }

  def onPreferredReplicaElection(partitions: Set[TopicAndPartition], isTriggeredByAutoRebalance: Boolean = false) {
    info("Starting preferred replica leader election for partitions %s".format(partitions.mkString(",")))
    try {
      partitionStateMachine.handleStateChanges(partitions, OnlinePartition, preferredReplicaPartitionLeaderSelector)
    } catch {
      case e: ControllerMovedException =>
        error("Error completing preferred replica leader election for partitions %s because controller moved to another broker".format(partitions.mkString(",")), e)
        throw e
      case e: Throwable => error("Error completing preferred replica leader election for partitions %s".format(partitions.mkString(",")), e)
    } finally {
      removePartitionsFromPreferredReplicaElection(partitions, isTriggeredByAutoRebalance)
    }
  }

  /**
   * Invoked when the controller module of a Kafka server is started up. This does not assume that the current broker
   * is the controller. It merely registers the session expiration listener and starts the controller leader
   * elector
   */
  def startup() = {
    eventManager.put(Startup)
    eventManager.start()
  }

  /**
   * Invoked when the controller module of a Kafka server is shutting down. If the broker was the current controller,
   * it shuts down the partition and replica state machines. If not, those are a no-op. In addition to that, it also
   * shuts down the controller channel manager, if one exists (i.e. if it was the current controller)
   */
  def shutdown() = {
    eventManager.close()
    onControllerResignation()
  }

  def sendRequest(brokerId: Int, apiKey: ApiKeys, request: AbstractRequest.Builder[_ <: AbstractRequest],
                  callback: AbstractResponse => Unit = null) = {
    controllerContext.controllerChannelManager.sendRequest(brokerId, apiKey, request, callback)
  }

  private def registerSessionExpirationListener() = {
    zkUtils.subscribeStateChanges(new SessionExpirationListener(this, eventManager))
  }

  private def registerControllerChangeListener() = {
    zkUtils.subscribeDataChanges(ZkUtils.ControllerPath, new ControllerChangeListener(this, eventManager))
  }

  private def initializeControllerContext(initialTopicsToBeDeleted: Set[String]) {
    // update controller cache with delete topic information
    controllerContext.liveBrokers = zkUtils.getAllBrokersInCluster().toSet
    controllerContext.allTopics = zkUtils.getAllTopics().toSet
    zkUtils.getReplicaAssignmentForTopics(controllerContext.allTopics.toSeq).foreach {
      case (topicAndPartition, assignedReplicas) => controllerContext.updatePartitionReplicaAssignment(topicAndPartition, assignedReplicas)
    }
    controllerContext.partitionLeadershipInfo = new mutable.HashMap[TopicAndPartition, LeaderIsrAndControllerEpoch]
    controllerContext.shuttingDownBrokerIds = mutable.Set.empty[Int]
    // update the leader and isr cache for all existing partitions from Zookeeper
    updateLeaderAndIsrCache()
    // start the channel manager
    startChannelManager()
    initializePartitionReassignment(initialTopicsToBeDeleted)
    info("Currently active brokers in the cluster: %s".format(controllerContext.liveBrokerIds))
    info("Currently shutting brokers in the cluster: %s".format(controllerContext.shuttingDownBrokerIds))
    info("Current list of topics in the cluster: %s".format(controllerContext.allTopics))
  }

  private def fetchPendingPreferredReplicaElections(): Set[TopicAndPartition] = {
    val partitionsUndergoingPreferredReplicaElection = zkUtils.getPartitionsUndergoingPreferredReplicaElection()
    // check if they are already completed or topic was deleted
    val partitionsThatCompletedPreferredReplicaElection = partitionsUndergoingPreferredReplicaElection.filter { partition =>
      val replicas = controllerContext.partitionReplicaAssignment(partition)
      val topicDeleted = replicas.isEmpty
      val successful =
        if(!topicDeleted) controllerContext.partitionLeadershipInfo(partition).leaderAndIsr.leader == replicas.head else false
      successful || topicDeleted
    }
    val pendingPreferredReplicaElectionsIgnoringTopicDeletion = partitionsUndergoingPreferredReplicaElection -- partitionsThatCompletedPreferredReplicaElection
    val pendingPreferredReplicaElectionsSkippedFromTopicDeletion = pendingPreferredReplicaElectionsIgnoringTopicDeletion.filter(partition => topicDeletionManager.isTopicQueuedUpForDeletion(partition.topic))
    val pendingPreferredReplicaElections = pendingPreferredReplicaElectionsIgnoringTopicDeletion -- pendingPreferredReplicaElectionsSkippedFromTopicDeletion
    info("Partitions undergoing preferred replica election: %s".format(partitionsUndergoingPreferredReplicaElection.mkString(",")))
    info("Partitions that completed preferred replica election: %s".format(partitionsThatCompletedPreferredReplicaElection.mkString(",")))
    info("Skipping preferred replica election for partitions due to topic deletion: %s".format(pendingPreferredReplicaElectionsSkippedFromTopicDeletion.mkString(",")))
    info("Resuming preferred replica election for partitions: %s".format(pendingPreferredReplicaElections.mkString(",")))
    pendingPreferredReplicaElections
  }

  private def resetControllerContext(): Unit = {
    if (controllerContext.controllerChannelManager != null) {
      controllerContext.controllerChannelManager.shutdown()
      controllerContext.controllerChannelManager = null
    }
    controllerContext.shuttingDownBrokerIds.clear()
    controllerContext.epoch = 0
    controllerContext.epochZkVersion = 0
    controllerContext.allTopics = Set.empty
    controllerContext.clearPartitionReplicaAssignment()
    controllerContext.partitionLeadershipInfo.clear()
    controllerContext.partitionsBeingReassigned.clear()
    controllerContext.liveBrokers = Set.empty
  }

  private def initializePartitionReassignment(initialTopicsToBeDeleted: Set[String]) {
    // read the partitions being reassigned from zookeeper path /admin/reassign_partitions
    val partitionsBeingReassigned = zkUtils.getPartitionsBeingReassigned()
    // check if they are already completed or topic was deleted
    val reassignedPartitions = partitionsBeingReassigned.filter { partition =>
      val replicas = controllerContext.partitionReplicaAssignment(partition._1)
      val topicDeleted = replicas.isEmpty
      val successful = if (!topicDeleted) replicas == partition._2.newReplicas else false
      topicDeleted || successful
    }.keys.toSet

    val partitionsBeingDeleted = partitionsBeingReassigned.filter { partition =>
      initialTopicsToBeDeleted.contains(partition._1.topic)
    }.keys.toSet

    reassignedPartitions.foreach(p => removePartitionFromReassignedPartitions(p))
    partitionsBeingDeleted.foreach(p => removePartitionFromReassignedPartitions(p))
    val partitionsToReassign = mutable.Map[TopicAndPartition, ReassignedPartitionsContext]()
    partitionsToReassign ++= partitionsBeingReassigned
    partitionsToReassign --= reassignedPartitions
    partitionsToReassign --= partitionsBeingDeleted
    controllerContext.partitionsBeingReassigned ++= partitionsToReassign
    info("Partitions being reassigned: %s".format(partitionsBeingReassigned.toString()))
    info("Partitions already reassigned: %s".format(reassignedPartitions.toString()))
    info("Partition assignments canceled due to topic deletion: %s".format(partitionsBeingDeleted.toString()))
    info("Resuming reassignment of partitions: %s".format(partitionsToReassign.toString()))
  }

  private def fetchTopicDeletionsInProgress(): Set[String] = {
    val topicsToBeDeleted = zkUtils.getChildrenParentMayNotExist(ZkUtils.DeleteTopicsPath).toSet
    info("List of topics to be deleted: %s".format(topicsToBeDeleted.mkString(",")))
    topicsToBeDeleted
  }

  private def fetchTopicsIneligibleForDeletion(): Set[String] = {
    val topicsWithOfflineReplicas = controllerContext.allTopics.filter { topic => {
      val replicasForTopic = controllerContext.replicasForTopic(topic)
      replicasForTopic.exists(r => !controllerContext.isReplicaOnline(r.replica, new TopicAndPartition(topic, r.partition)))
    }}
    val topicsForWhichPartitionReassignmentIsInProgress = controllerContext.partitionsBeingReassigned.keySet.map(_.topic)
    val topicsIneligibleForDeletion = topicsWithOfflineReplicas | topicsForWhichPartitionReassignmentIsInProgress
    info("List of topics ineligible for deletion: %s".format(topicsIneligibleForDeletion.mkString(",")))
    topicsIneligibleForDeletion
  }

  private def maybeTriggerPartitionReassignment() {
    controllerContext.partitionsBeingReassigned.foreach { topicPartitionToReassign =>
      initiateReassignReplicasForTopicPartition(topicPartitionToReassign._1, topicPartitionToReassign._2)
    }
  }

  private def startChannelManager() {
    controllerContext.controllerChannelManager = new ControllerChannelManager(controllerContext, config, time, metrics, threadNamePrefix)
    controllerContext.controllerChannelManager.startup()
  }

  def updateLeaderAndIsrCache(topicAndPartitions: Set[TopicAndPartition] = controllerContext.allPartitions) {
    val leaderAndIsrInfo = zkUtils.getPartitionLeaderAndIsrForTopics(topicAndPartitions)
    for ((topicPartition, leaderIsrAndControllerEpoch) <- leaderAndIsrInfo)
      controllerContext.partitionLeadershipInfo.put(topicPartition, leaderIsrAndControllerEpoch)
  }

  private def areReplicasInIsr(topic: String, partition: Int, replicas: Seq[Int]): Boolean = {
    zkUtils.getLeaderAndIsrForPartition(topic, partition).map { leaderAndIsr =>
      replicas.forall(leaderAndIsr.isr.contains)
    }.getOrElse(false)
  }

  private def moveReassignedPartitionLeaderIfRequired(topicAndPartition: TopicAndPartition,
                                                      reassignedPartitionContext: ReassignedPartitionsContext) {
    val reassignedReplicas = reassignedPartitionContext.newReplicas
    val currentLeader = controllerContext.partitionLeadershipInfo(topicAndPartition).leaderAndIsr.leader
    // change the assigned replica list to just the reassigned replicas in the cache so it gets sent out on the LeaderAndIsr
    // request to the current or new leader. This will prevent it from adding the old replicas to the ISR
    val oldAndNewReplicas = controllerContext.partitionReplicaAssignment(topicAndPartition)
    controllerContext.updatePartitionReplicaAssignment(topicAndPartition, reassignedReplicas)
    if(!reassignedPartitionContext.newReplicas.contains(currentLeader)) {
      info("Leader %s for partition %s being reassigned, ".format(currentLeader, topicAndPartition) +
        "is not in the new list of replicas %s. Re-electing leader".format(reassignedReplicas.mkString(",")))
      // move the leader to one of the alive and caught up new replicas
      partitionStateMachine.handleStateChanges(Set(topicAndPartition), OnlinePartition, reassignedPartitionLeaderSelector)
    } else {
      // check if the leader is alive or not
      if (controllerContext.isReplicaOnline(currentLeader, topicAndPartition)) {
        info("Leader %s for partition %s being reassigned, ".format(currentLeader, topicAndPartition) +
          "is already in the new list of replicas %s and is alive".format(reassignedReplicas.mkString(",")))
        // shrink replication factor and update the leader epoch in zookeeper to use on the next LeaderAndIsrRequest
        updateLeaderEpochAndSendRequest(topicAndPartition, oldAndNewReplicas, reassignedReplicas)
      } else {
        info("Leader %s for partition %s being reassigned, ".format(currentLeader, topicAndPartition) +
          "is already in the new list of replicas %s but is dead".format(reassignedReplicas.mkString(",")))
        partitionStateMachine.handleStateChanges(Set(topicAndPartition), OnlinePartition, reassignedPartitionLeaderSelector)
      }
    }
  }

  private def stopOldReplicasOfReassignedPartition(topicAndPartition: TopicAndPartition,
                                                   reassignedPartitionContext: ReassignedPartitionsContext,
                                                   oldReplicas: Set[Int]) {
    val topic = topicAndPartition.topic
    val partition = topicAndPartition.partition
    // first move the replica to offline state (the controller removes it from the ISR)
    val replicasToBeDeleted = oldReplicas.map(r => PartitionAndReplica(topic, partition, r))
    replicaStateMachine.handleStateChanges(replicasToBeDeleted, OfflineReplica)
    // send stop replica command to the old replicas
    replicaStateMachine.handleStateChanges(replicasToBeDeleted, ReplicaDeletionStarted)
    // TODO: Eventually partition reassignment could use a callback that does retries if deletion failed
    replicaStateMachine.handleStateChanges(replicasToBeDeleted, ReplicaDeletionSuccessful)
    replicaStateMachine.handleStateChanges(replicasToBeDeleted, NonExistentReplica)
  }

  private def updateAssignedReplicasForPartition(topicAndPartition: TopicAndPartition,
                                                 replicas: Seq[Int]) {
    val partitionsAndReplicasForThisTopic = controllerContext.partitionReplicaAssignmentForTopic(topicAndPartition.topic)
    partitionsAndReplicasForThisTopic.put(topicAndPartition, replicas)
    updateAssignedReplicasForTopic(topicAndPartition, partitionsAndReplicasForThisTopic)
    info("Updated assigned replicas for partition %s being reassigned to %s ".format(topicAndPartition, replicas.mkString(",")))
    // update the assigned replica list after a successful zookeeper write
    controllerContext.updatePartitionReplicaAssignment(topicAndPartition, replicas)
  }

  private def startNewReplicasForReassignedPartition(topicAndPartition: TopicAndPartition,
                                                     reassignedPartitionContext: ReassignedPartitionsContext,
                                                     newReplicas: Set[Int]) {
    // send the start replica request to the brokers in the reassigned replicas list that are not in the assigned
    // replicas list
    newReplicas.foreach { replica =>
      replicaStateMachine.handleStateChanges(Set(new PartitionAndReplica(topicAndPartition.topic, topicAndPartition.partition, replica)), NewReplica)
    }
  }

  private def updateLeaderEpochAndSendRequest(topicAndPartition: TopicAndPartition, replicasToReceiveRequest: Seq[Int], newAssignedReplicas: Seq[Int]) {
    updateLeaderEpoch(topicAndPartition.topic, topicAndPartition.partition) match {
      case Some(updatedLeaderIsrAndControllerEpoch) =>
        try {
          brokerRequestBatch.newBatch()
          brokerRequestBatch.addLeaderAndIsrRequestForBrokers(replicasToReceiveRequest, topicAndPartition.topic,
            topicAndPartition.partition, updatedLeaderIsrAndControllerEpoch, newAssignedReplicas)
          brokerRequestBatch.sendRequestsToBrokers(controllerContext.epoch)
        } catch {
          case e: IllegalStateException =>
            handleIllegalState(e)
        }
        stateChangeLogger.trace(("Controller %d epoch %d sent LeaderAndIsr request %s with new assigned replica list %s " +
          "to leader %d for partition being reassigned %s").format(config.brokerId, controllerContext.epoch, updatedLeaderIsrAndControllerEpoch,
          newAssignedReplicas.mkString(","), updatedLeaderIsrAndControllerEpoch.leaderAndIsr.leader, topicAndPartition))
      case None => // fail the reassignment
        stateChangeLogger.error(("Controller %d epoch %d failed to send LeaderAndIsr request with new assigned replica list %s " +
          "to leader for partition being reassigned %s").format(config.brokerId, controllerContext.epoch,
          newAssignedReplicas.mkString(","), topicAndPartition))
    }
  }

  private def registerBrokerChangeListener() = {
    zkUtils.subscribeChildChanges(ZkUtils.BrokerIdsPath, brokerChangeListener)
  }

  private def deregisterBrokerChangeListener() = {
    zkUtils.unsubscribeChildChanges(ZkUtils.BrokerIdsPath, brokerChangeListener)
  }

  private def registerTopicChangeListener() = {
    zkUtils.subscribeChildChanges(BrokerTopicsPath, topicChangeListener)
  }

  private def deregisterTopicChangeListener() = {
    zkUtils.unsubscribeChildChanges(BrokerTopicsPath, topicChangeListener)
  }

  def registerPartitionModificationsListener(topic: String) = {
    partitionModificationsListeners.put(topic, new PartitionModificationsListener(this, eventManager, topic))
    zkUtils.subscribeDataChanges(getTopicPath(topic), partitionModificationsListeners(topic))
  }

  def deregisterPartitionModificationsListener(topic: String) = {
    zkUtils.unsubscribeDataChanges(getTopicPath(topic), partitionModificationsListeners(topic))
    partitionModificationsListeners.remove(topic)
  }

  private def registerTopicDeletionListener() = {
    zkUtils.subscribeChildChanges(DeleteTopicsPath, topicDeletionListener)
  }

  private def deregisterTopicDeletionListener() = {
    zkUtils.unsubscribeChildChanges(DeleteTopicsPath, topicDeletionListener)
  }

  private def registerTopicDeletionFlagListener() = {
    zkUtils.subscribeDataChanges(TopicDeletionEnabledPath, topicDeletionFlagListener)
  }

  private def deregisterTopicDeletionFlagListener() = {
    zkUtils.unsubscribeDataChanges(TopicDeletionEnabledPath, topicDeletionFlagListener)
  }

  private def registerPartitionReassignmentListener() = {
    zkUtils.subscribeDataChanges(ZkUtils.ReassignPartitionsPath, partitionReassignmentListener)
  }

  private def deregisterPartitionReassignmentListener() = {
    zkUtils.unsubscribeDataChanges(ZkUtils.ReassignPartitionsPath, partitionReassignmentListener)
  }

  private def registerIsrChangeNotificationListener() = {
    debug("Registering IsrChangeNotificationListener")
    zkUtils.subscribeChildChanges(ZkUtils.IsrChangeNotificationPath, isrChangeNotificationListener)
  }

  private def deregisterIsrChangeNotificationListener() = {
    debug("De-registering IsrChangeNotificationListener")
    zkUtils.unsubscribeChildChanges(ZkUtils.IsrChangeNotificationPath, isrChangeNotificationListener)
  }

  private def registerPreferredReplicaElectionListener() {
    zkUtils.subscribeDataChanges(ZkUtils.PreferredReplicaLeaderElectionPath, preferredReplicaElectionListener)
  }

  private def deregisterPreferredReplicaElectionListener() {
    zkUtils.unsubscribeDataChanges(ZkUtils.PreferredReplicaLeaderElectionPath, preferredReplicaElectionListener)
  }

  private def deregisterPartitionReassignmentIsrChangeListeners() {
    controllerContext.partitionsBeingReassigned.foreach {
      case (topicAndPartition, reassignedPartitionsContext) =>
        val zkPartitionPath = getTopicPartitionLeaderAndIsrPath(topicAndPartition.topic, topicAndPartition.partition)
        zkUtils.unsubscribeDataChanges(zkPartitionPath, reassignedPartitionsContext.isrChangeListener)
    }
  }

  private def registerLogDirEventNotificationListener() = {
    debug("Registering logDirEventNotificationListener")
    zkUtils.zkClient.subscribeChildChanges(ZkUtils.LogDirEventNotificationPath, logDirEventNotificationListener)
  }

  private def deregisterLogDirEventNotificationListener() = {
    debug("De-registering logDirEventNotificationListener")
    zkUtils.zkClient.unsubscribeChildChanges(ZkUtils.LogDirEventNotificationPath, logDirEventNotificationListener)
  }

  def removePartitionFromReassignedPartitions(topicAndPartition: TopicAndPartition) {
    if(controllerContext.partitionsBeingReassigned.get(topicAndPartition).isDefined) {
      // stop watching the ISR changes for this partition
      zkUtils.unsubscribeDataChanges(getTopicPartitionLeaderAndIsrPath(topicAndPartition.topic, topicAndPartition.partition),
        controllerContext.partitionsBeingReassigned(topicAndPartition).isrChangeListener)
    }
    // read the current list of reassigned partitions from zookeeper
    val partitionsBeingReassigned = zkUtils.getPartitionsBeingReassigned()
    // remove this partition from that list
    val updatedPartitionsBeingReassigned = partitionsBeingReassigned - topicAndPartition
    // write the new list to zookeeper
    if (updatedPartitionsBeingReassigned.size < partitionsBeingReassigned.size)
      zkUtils.transactionalUpdatePartitionReassignmentData(controllerContext.epochZkVersion,
        updatedPartitionsBeingReassigned.mapValues(_.newReplicas))
    // update the cache. NO-OP if the partition's reassignment was never started
    controllerContext.partitionsBeingReassigned.remove(topicAndPartition)
  }

  def updateAssignedReplicasForTopic(topicAndPartition: TopicAndPartition,
                                     newReplicaAssignmentForTopic: Map[TopicAndPartition, Seq[Int]]) {
    try {
      val zkPath = getTopicPath(topicAndPartition.topic)
      val jsonPartitionMap = zkUtils.replicaAssignmentZkData(newReplicaAssignmentForTopic.map(e => e._1.partition.toString -> e._2))
      zkUtils.transactionalUpdatePersistentPath(controllerContext.epochZkVersion, zkPath, jsonPartitionMap)
      debug("Updated path %s with %s for replica assignment".format(zkPath, jsonPartitionMap))
    } catch {
      case _: ZkNoNodeException => throw new IllegalStateException("Topic %s doesn't exist".format(topicAndPartition.topic))
      case e2: ControllerMovedException =>
        error(s"Error when updating assigned replicas for ${topicAndPartition} because controller moved to another broker", e2)
        throw e2
      case e3: Throwable => throw new KafkaException(e3.toString)
    }
  }

  def removePartitionsFromPreferredReplicaElection(partitionsToBeRemoved: Set[TopicAndPartition],
                                                   isTriggeredByAutoRebalance : Boolean) {
    for(partition <- partitionsToBeRemoved) {
      // check the status
      val currentLeader = controllerContext.partitionLeadershipInfo(partition).leaderAndIsr.leader
      val preferredReplica = controllerContext.partitionReplicaAssignment(partition).head
      if(currentLeader == preferredReplica) {
        info("Partition %s completed preferred replica leader election. New leader is %d".format(partition, preferredReplica))
      } else {
        warn("Partition %s failed to complete preferred replica leader election. Leader is %d".format(partition, currentLeader))
      }
    }
    if (!isTriggeredByAutoRebalance)
      zkUtils.transactionalDeletePath(controllerContext.epochZkVersion, ZkUtils.PreferredReplicaLeaderElectionPath)
  }

  /**
   * Send the leader information for selected partitions to selected brokers so that they can correctly respond to
   * metadata requests
   *
   * @param brokers The brokers that the update metadata request should be sent to
   */
  def sendUpdateMetadataRequest(brokers: Seq[Int], partitions: Set[TopicAndPartition] = Set.empty[TopicAndPartition]) {
    try {
      brokerRequestBatch.newBatch()
      brokerRequestBatch.addUpdateMetadataRequestForBrokers(brokers, partitions)
      brokerRequestBatch.sendRequestsToBrokers(epoch)
    } catch {
      case e: IllegalStateException =>
        handleIllegalState(e)
    }
  }

  /**
   * Removes a given partition replica from the ISR; if it is not the current
   * leader and there are sufficient remaining replicas in ISR.
   *
   * @param topic topic
   * @param partition partition
   * @param replicaId replica Id
   * @return the new leaderAndIsr (with the replica removed if it was present),
   *         or None if leaderAndIsr is empty.
   */
  def removeReplicaFromIsr(topic: String, partition: Int, replicaId: Int): Option[LeaderIsrAndControllerEpoch] = {
    val topicAndPartition = TopicAndPartition(topic, partition)
    debug("Removing replica %d from ISR %s for partition %s.".format(replicaId,
      controllerContext.partitionLeadershipInfo(topicAndPartition).leaderAndIsr.isr.mkString(","), topicAndPartition))
    var finalLeaderIsrAndControllerEpoch: Option[LeaderIsrAndControllerEpoch] = None
    var zkWriteCompleteOrUnnecessary = false
    while (!zkWriteCompleteOrUnnecessary) {
      // refresh leader and isr from zookeeper again
      val leaderIsrAndEpochOpt = ReplicationUtils.getLeaderIsrAndEpochForPartition(zkUtils, topic, partition)
      zkWriteCompleteOrUnnecessary = leaderIsrAndEpochOpt match {
        case Some(leaderIsrAndEpoch) => // increment the leader epoch even if the ISR changes
          val leaderAndIsr = leaderIsrAndEpoch.leaderAndIsr
          val controllerEpoch = leaderIsrAndEpoch.controllerEpoch
          if(controllerEpoch > epoch)
            throw new StateChangeFailedException("Leader and isr path written by another controller. This probably" +
              "means the current controller with epoch %d went through a soft failure and another ".format(epoch) +
              "controller was elected with epoch %d. Aborting state change by this controller".format(controllerEpoch))
          if (leaderAndIsr.isr.contains(replicaId)) {
            // if the replica to be removed from the ISR is also the leader, set the new leader value to -1
            val newLeader = if (replicaId == leaderAndIsr.leader) LeaderAndIsr.NoLeader else leaderAndIsr.leader
            var newIsr = leaderAndIsr.isr.filter(b => b != replicaId)

            // if the replica to be removed from the ISR is the last surviving member of the ISR and unclean leader election
            // is disallowed for the corresponding topic, then we must preserve the ISR membership so that the replica can
            // eventually be restored as the leader.
            if (newIsr.isEmpty && !LogConfig.fromProps(config.originals, AdminUtils.fetchEntityConfig(zkUtils,
              ConfigType.Topic, topicAndPartition.topic)).uncleanLeaderElectionEnable) {
              info("Retaining last ISR %d of partition %s since unclean leader election is disabled".format(replicaId, topicAndPartition))
              newIsr = leaderAndIsr.isr
            }

            val newLeaderAndIsr = leaderAndIsr.newLeaderAndIsr(newLeader, newIsr)
            // update the new leadership decision in zookeeper or retry
            val (updateSucceeded, newVersion) = ReplicationUtils.transactionalUpdateLeaderAndIsr(
              zkUtils, topic, partition, newLeaderAndIsr, leaderAndIsr.zkVersion,
              controllerContext.epoch, controllerContext.epochZkVersion)

            val leaderWithNewVersion = newLeaderAndIsr.withZkVersion(newVersion)
            finalLeaderIsrAndControllerEpoch = Some(LeaderIsrAndControllerEpoch(leaderWithNewVersion, epoch))
            controllerContext.partitionLeadershipInfo.put(topicAndPartition, finalLeaderIsrAndControllerEpoch.get)
            if (updateSucceeded) {
              info(s"New leader and ISR for partition $topicAndPartition is $leaderWithNewVersion")
            }
            updateSucceeded
          } else {
            warn(s"Cannot remove replica $replicaId from ISR of partition $topicAndPartition since it is not in the ISR." +
              s" Leader = ${leaderAndIsr.leader} ; ISR = ${leaderAndIsr.isr}")
            finalLeaderIsrAndControllerEpoch = Some(LeaderIsrAndControllerEpoch(leaderAndIsr, epoch))
            controllerContext.partitionLeadershipInfo.put(topicAndPartition, finalLeaderIsrAndControllerEpoch.get)
            true
          }
        case None =>
          warn("Cannot remove replica %d from ISR of %s - leaderAndIsr is empty.".format(replicaId, topicAndPartition))
          true
      }
    }
    finalLeaderIsrAndControllerEpoch
  }

  /**
   * Does not change leader or isr, but just increments the leader epoch
   *
   * @param topic topic
   * @param partition partition
   * @return the new leaderAndIsr with an incremented leader epoch, or None if leaderAndIsr is empty.
   */
  private def updateLeaderEpoch(topic: String, partition: Int): Option[LeaderIsrAndControllerEpoch] = {
    val topicAndPartition = TopicAndPartition(topic, partition)
    debug("Updating leader epoch for partition %s.".format(topicAndPartition))
    var finalLeaderIsrAndControllerEpoch: Option[LeaderIsrAndControllerEpoch] = None
    var zkWriteCompleteOrUnnecessary = false
    while (!zkWriteCompleteOrUnnecessary) {
      // refresh leader and isr from zookeeper again
      val leaderIsrAndEpochOpt = ReplicationUtils.getLeaderIsrAndEpochForPartition(zkUtils, topic, partition)
      zkWriteCompleteOrUnnecessary = leaderIsrAndEpochOpt match {
        case Some(leaderIsrAndEpoch) =>
          val leaderAndIsr = leaderIsrAndEpoch.leaderAndIsr
          val controllerEpoch = leaderIsrAndEpoch.controllerEpoch
          if(controllerEpoch > epoch)
            throw new StateChangeFailedException("Leader and isr path written by another controller. This probably" +
              "means the current controller with epoch %d went through a soft failure and another ".format(epoch) +
              "controller was elected with epoch %d. Aborting state change by this controller".format(controllerEpoch))
          // increment the leader epoch even if there are no leader or isr changes to allow the leader to cache the expanded
          // assigned replica list
          val newLeaderAndIsr = leaderAndIsr.newEpochAndZkVersion
          // update the new leadership decision in zookeeper or retry
          val (updateSucceeded, newVersion) = ReplicationUtils.transactionalUpdateLeaderAndIsr(zkUtils, topic,
            partition, newLeaderAndIsr, leaderAndIsr.zkVersion,
            controllerContext.epoch, controllerContext.epochZkVersion)

          val leaderWithNewVersion = newLeaderAndIsr.withZkVersion(newVersion)
          finalLeaderIsrAndControllerEpoch = Some(LeaderIsrAndControllerEpoch(leaderWithNewVersion, epoch))
          if (updateSucceeded) {
            info(s"Updated leader epoch for partition $topicAndPartition to ${leaderWithNewVersion.leaderEpoch}")
          }
          updateSucceeded
        case None =>
          throw new IllegalStateException(s"Cannot update leader epoch for partition $topicAndPartition as " +
            "leaderAndIsr path is empty. This could mean we somehow tried to reassign a partition that doesn't exist")
          true
      }
    }
    finalLeaderIsrAndControllerEpoch
  }

  private def checkAndTriggerAutoLeaderRebalance(): Unit = {
    trace("Checking need to trigger auto leader balancing")
    val preferredReplicasForTopicsByBrokers: Map[Int, Map[TopicAndPartition, Seq[Int]]] =
      controllerContext.allPartitions.filterNot {
        tp => topicDeletionManager.isTopicQueuedUpForDeletion(tp.topic)
      }.map { tp =>
        (tp, controllerContext.partitionReplicaAssignment(tp) )
      }.toMap.groupBy { case (_, assignedReplicas) => assignedReplicas.head }

    debug(s"Preferred replicas by broker $preferredReplicasForTopicsByBrokers")

    // for each broker, check if a preferred replica election needs to be triggered
    preferredReplicasForTopicsByBrokers.foreach { case (leaderBroker, topicAndPartitionsForBroker) =>
      val topicsNotInPreferredReplica = topicAndPartitionsForBroker.filter { case (topicPartition, _) =>
        val leadershipInfo = controllerContext.partitionLeadershipInfo.get(topicPartition)
        leadershipInfo.map(_.leaderAndIsr.leader != leaderBroker).getOrElse(false)
      }
      debug(s"Topics not in preferred replica $topicsNotInPreferredReplica")

      val imbalanceRatio = topicsNotInPreferredReplica.size.toDouble / topicAndPartitionsForBroker.size
      trace(s"Leader imbalance ratio for broker $leaderBroker is $imbalanceRatio")

      // check ratio and if greater than desired ratio, trigger a rebalance for the topic partitions
      // that need to be on this broker
      if (imbalanceRatio > (config.leaderImbalancePerBrokerPercentage.toDouble / 100)) {
        topicsNotInPreferredReplica.keys.foreach { topicPartition =>
          // do this check only if the broker is live and there are no partitions being reassigned currently
          // and preferred replica election is not in progress
          if (controllerContext.isReplicaOnline(leaderBroker, topicPartition) &&
            controllerContext.partitionsBeingReassigned.isEmpty &&
            !topicDeletionManager.isTopicQueuedUpForDeletion(topicPartition.topic) &&
            controllerContext.allTopics.contains(topicPartition.topic)) {
            onPreferredReplicaElection(Set(topicPartition), isTriggeredByAutoRebalance = true)
          }
        }
      }
    }
  }

  def getControllerID(): Int = {
    controllerContext.zkUtils.readDataMaybeNull(ZkUtils.ControllerPath)._1 match {
      case Some(controller) => KafkaController.parseControllerId(controller)
      case None => -1
    }
  }

  case class BrokerChange(brokersOnEventReceived: Seq[String]) extends ControllerEvent {

    def state = ControllerState.BrokerChange

    override def process(): Unit = {
      if (!isActive) return

      info(s"Process broker change event with broker list ${brokersOnEventReceived.mkString(",")}")
      // Read the current broker list from ZK again instead of using currentBrokerList to increase
      // the odds of processing recent broker changes in a single ControllerEvent (KAFKA-5502).
      val curBrokers = zkUtils.getAllBrokersInCluster().toSet
      val curBrokerIds = curBrokers.map(_.id)
      val brokerIdsOnEventReceived = brokersOnEventReceived.map(_.toInt).toSet
      val liveOrShuttingDownBrokerIds = controllerContext.liveOrShuttingDownBrokerIds
      val shuttingdowBrokerIds = controllerContext.shuttingDownBrokerIds
      // The dead brokers are the brokers that exist before this event but no longer exist now.
      val deadBrokerIds = liveOrShuttingDownBrokerIds -- curBrokerIds
      // The new brokers are the brokers that exist now but not before this event.
      val newBrokerIds = curBrokerIds -- liveOrShuttingDownBrokerIds
      // If the broker existed before the event fires and also exists now, but did not exist when the event fires, the
      // broker was bounced. In this case, we need to first clean up their state then treat them as newly added
      // brokers.
      val bouncedBrokerIds = (curBrokerIds & liveOrShuttingDownBrokerIds) -- brokerIdsOnEventReceived
      val newBrokers = curBrokers.filter(broker => newBrokerIds(broker.id))
      val bouncedBrokers = curBrokers.filter(broker => bouncedBrokerIds(broker.id))
      controllerContext.liveBrokers = curBrokers
      val newBrokerIdsSorted = newBrokerIds.toSeq.sorted
      val deadBrokerIdsSorted = deadBrokerIds.toSeq.sorted
      val bouncedBrokerIdsSorted = bouncedBrokerIds.toSeq.sorted
      val liveBrokerIdsSorted = curBrokerIds.toSeq.sorted
      info("Newly added brokers: %s, deleted brokers: %s, bounced Brokers: %s, all live brokers: %s"
        .format(newBrokerIdsSorted.mkString(","), deadBrokerIdsSorted.mkString(","), bouncedBrokerIdsSorted.mkString(","),
          liveBrokerIdsSorted.mkString(",")))
      deadBrokerIds.foreach(controllerContext.controllerChannelManager.removeBroker)
      bouncedBrokerIds.foreach(controllerContext.controllerChannelManager.removeBroker)
      bouncedBrokers.foreach(controllerContext.controllerChannelManager.addBroker)
      newBrokers.foreach(controllerContext.controllerChannelManager.addBroker)
      // Process the new brokers
      if (newBrokerIds.nonEmpty)
        onBrokerStartup(newBrokerIdsSorted)
      // handle the bounced brokers
      if (bouncedBrokerIds.nonEmpty) {
        onBrokerFailure(bouncedBrokerIdsSorted.filter(shuttingdowBrokerIds.contains))
        onBrokerStartup(bouncedBrokerIdsSorted)
      }
      // finally handle the dead brokers.
      if (deadBrokerIds.nonEmpty)
        onBrokerFailure(deadBrokerIdsSorted)
    }
  }

  case class TopicChange(topics: Set[String]) extends ControllerEvent {

    def state = ControllerState.TopicChange

    override def process(): Unit = {
      if (!isActive) return
      val newTopics = topics -- controllerContext.allTopics
      val deletedTopics = controllerContext.allTopics -- topics
      controllerContext.allTopics = topics

      val addedPartitionReplicaAssignment = zkUtils.getReplicaAssignmentForTopics(newTopics.toSeq)
      deletedTopics.foreach(controllerContext.removePartitionReplicaAssignmentForTopic)
      addedPartitionReplicaAssignment.foreach {
        case (topicAndPartition, newReplicas) => controllerContext.updatePartitionReplicaAssignment(topicAndPartition, newReplicas)
      }
      info("New topics: [%s], deleted topics: [%s], new partition replica assignment [%s]".format(newTopics,
        deletedTopics, addedPartitionReplicaAssignment))
      if (newTopics.nonEmpty)
        onNewTopicCreation(newTopics, addedPartitionReplicaAssignment.keySet)
    }
  }

  case class PartitionModifications(topic: String) extends ControllerEvent {

    def state = ControllerState.TopicChange

    override def process(): Unit = {
      if (!isActive) return
      val partitionReplicaAssignment = zkUtils.getReplicaAssignmentForTopics(List(topic))
      val partitionsToBeAdded = partitionReplicaAssignment.filter(p =>
        controllerContext.partitionReplicaAssignment(p._1).isEmpty)
      if(topicDeletionManager.isTopicQueuedUpForDeletion(topic))
        error("Skipping adding partitions %s for topic %s since it is currently being deleted"
          .format(partitionsToBeAdded.map(_._1.partition).mkString(","), topic))
      else {
        if (partitionsToBeAdded.nonEmpty) {
          info("New partitions to be added %s".format(partitionsToBeAdded))
          partitionsToBeAdded.foreach { case (topicAndPartition, assignedReplicas) =>
            controllerContext.updatePartitionReplicaAssignment(topicAndPartition, assignedReplicas)
          }
          onNewPartitionCreation(partitionsToBeAdded.keySet)
        }
      }
    }
  }

  case class TopicDeletion(var topicsToBeDeleted: Set[String]) extends ControllerEvent {

    def state = ControllerState.TopicDeletion

    override def process(): Unit = {
      if (!isActive) return
      debug("Delete topics listener fired for topics %s to be deleted".format(topicsToBeDeleted.mkString(",")))
      val nonExistentTopics = topicsToBeDeleted -- controllerContext.allTopics
      if (nonExistentTopics.nonEmpty) {
        warn("Ignoring request to delete non-existing topics " + nonExistentTopics.mkString(","))
        nonExistentTopics.foreach(topic =>
          zkUtils.transactionalDeletePathRecursive(controllerContext.epochZkVersion, getDeleteTopicPath(topic)))
      }
      topicsToBeDeleted --= nonExistentTopics
      if (topicDeletionManager.isDeleteTopicEnabled) {
        if (topicsToBeDeleted.nonEmpty) {
          info("Starting topic deletion for topics " + topicsToBeDeleted.mkString(","))
          // mark topic ineligible for deletion if other state changes are in progress
          topicsToBeDeleted.foreach { topic =>
            val partitionReassignmentInProgress =
              controllerContext.partitionsBeingReassigned.keySet.map(_.topic).contains(topic)
            if (partitionReassignmentInProgress)
              topicDeletionManager.markTopicIneligibleForDeletion(Set(topic))
          }
          // add topic to deletion list
          val topicsToBeDeletedNotAlreadyEnqueued = topicsToBeDeleted -- topicDeletionManager.topicsToBeDeleted
          topicDeletionManager.enqueueTopicsForDeletion(topicsToBeDeletedNotAlreadyEnqueued)
        }
      } else {
        // If delete topic is disabled remove entries under zookeeper path : /admin/delete_topics
        for (topic <- topicsToBeDeleted) {
          info("Removing " + getDeleteTopicPath(topic) + " since delete topic is disabled")
          zkUtils.transactionalDeletePath(controllerContext.epochZkVersion, getDeleteTopicPath(topic))
        }
      }
    }
  }

  case class TopicDeletionFlagChange(topicDeletionFlag: String, reset: Boolean = false) extends ControllerEvent {

    def state = ControllerState.TopicDeletionFlagChange

    override def process(): Unit = {
      info("Process TopicDeletionFlagChange event")
      if (!isActive) return
      if (reset)
        topicDeletionManager.resetDeleteTopicEnabled()
      else {
        if (topicDeletionFlag != "true" && topicDeletionFlag != "false") {
          info(s"Overwrite ${ZkUtils.TopicDeletionEnabledPath} to ${topicDeletionManager.isDeleteTopicEnabled}")
          zkUtils.updatePersistentPath(ZkUtils.TopicDeletionEnabledPath, topicDeletionManager.isDeleteTopicEnabled.toString)
        }
        else {
          info(s"Set isDeleteTopicEnabled flag to $topicDeletionFlag")
          topicDeletionManager.isDeleteTopicEnabled = topicDeletionFlag.toBoolean
        }
      }
    }
  }

  case object PartitionReassignment extends ControllerEvent {

    def state = ControllerState.PartitionReassignment

    override def process(): Unit = {
      if (!isActive) return
      // We read the reassignment data fresh so that we don't need to maintain it in memory. While
      // a reassignment is in progress, there can be potentially many reassignment events in the queue
      // since the completion of every individual reassignment causes the reassignment path to be updated.
      val partitionReassignment = zkUtils.getPartitionsBeingReassigned()
      val partitionsToBeReassigned = partitionReassignment.filterNot(p => controllerContext.partitionsBeingReassigned.contains(p._1))
      partitionsToBeReassigned.foreach { case (partition, context) =>
        if(topicDeletionManager.isTopicQueuedUpForDeletion(partition.topic)) {
          error(s"Skipping reassignment of partition $partition since it is currently being deleted")
          removePartitionFromReassignedPartitions(partition)
        } else {
          initiateReassignReplicasForTopicPartition(partition, context)
        }
      }
    }
  }

  case class PartitionReassignmentIsrChange(topicAndPartition: TopicAndPartition, reassignedReplicas: Set[Int]) extends ControllerEvent {

    def state = ControllerState.PartitionReassignment

    override def process(): Unit = {
      if (!isActive) return
        // check if this partition is still being reassigned or not
      controllerContext.partitionsBeingReassigned.get(topicAndPartition).foreach { reassignedPartitionContext =>
        // need to re-read leader and isr from zookeeper since the zkclient callback doesn't return the Stat object
        val newLeaderAndIsrOpt = zkUtils.getLeaderAndIsrForPartition(topicAndPartition.topic, topicAndPartition.partition)
        newLeaderAndIsrOpt match {
          case Some(leaderAndIsr) => // check if new replicas have joined ISR
            val caughtUpReplicas = reassignedReplicas & leaderAndIsr.isr.toSet
            if(caughtUpReplicas == reassignedReplicas) {
              // resume the partition reassignment process
              info("%d/%d replicas have caught up with the leader for partition %s being reassigned."
                .format(caughtUpReplicas.size, reassignedReplicas.size, topicAndPartition) +
                "Resuming partition reassignment")
              onPartitionReassignment(topicAndPartition, reassignedPartitionContext)
            }
            else {
              info("%d/%d replicas have caught up with the leader for partition %s being reassigned."
                .format(caughtUpReplicas.size, reassignedReplicas.size, topicAndPartition) +
                "Replica(s) %s still need to catch up".format((reassignedReplicas -- leaderAndIsr.isr.toSet).mkString(",")))
            }
          case None => error("Error handling reassignment of partition %s to replicas %s as it was never created"
            .format(topicAndPartition, reassignedReplicas.mkString(",")))
        }
      }
    }
  }

  case class IsrChangeNotification(sequenceNumbers: Seq[String]) extends ControllerEvent {

    def state = ControllerState.IsrChange

    override def process(): Unit = {
      val currentSequenceNumbers = zkUtils.getChildrenParentMayNotExist(ZkUtils.IsrChangeNotificationPath)
      info(s"Process isr change event for znodes with sequence number ${currentSequenceNumbers.mkString(",")}")
      if (!isActive) return
      try {
        val topicAndPartitions = currentSequenceNumbers.flatMap(getTopicAndPartition).toSet
        if (topicAndPartitions.nonEmpty) {
          updateLeaderAndIsrCache(topicAndPartitions)
          processUpdateNotifications(topicAndPartitions)
        }
      } finally {
        // delete the notifications
        val deletePaths = currentSequenceNumbers.map(ZkUtils.IsrChangeNotificationPath + "/" + _)
        zkUtils.transactionalDeletePaths(controllerContext.epochZkVersion, deletePaths)
      }
    }

    private def processUpdateNotifications(topicAndPartitions: immutable.Set[TopicAndPartition]) {
      val liveBrokers: Seq[Int] = controllerContext.liveOrShuttingDownBrokerIds.toSeq
      debug("Sending MetadataRequest to Brokers:" + liveBrokers + " for TopicAndPartitions:" + topicAndPartitions)
      sendUpdateMetadataRequest(liveBrokers, topicAndPartitions)
    }

    private def getTopicAndPartition(child: String): Set[TopicAndPartition] = {
      val changeZnode = ZkUtils.IsrChangeNotificationPath + "/" + child
      val (jsonOpt, _) = controllerContext.zkUtils.readDataMaybeNull(changeZnode)
      jsonOpt.map { json =>
        Json.parseFull(json) match {
          case Some(js) =>
            val isrChanges = js.asJsonObject
            isrChanges("partitions").asJsonArray.iterator.map(_.asJsonObject).map { tpJs =>
              val topic = tpJs("topic").to[String]
              val partition = tpJs("partition").to[Int]
              TopicAndPartition(topic, partition)
            }.toSet
          case None =>
            error(s"Invalid topic and partition JSON in ZK. ZK notification node: $changeZnode, JSON: $json")
            Set.empty[TopicAndPartition]
        }
      }.getOrElse(Set.empty[TopicAndPartition])
    }

  }

  case class LogDirEventNotification(sequenceNumbers: Seq[String]) extends ControllerEvent {

    def state = ControllerState.LogDirChange

    override def process(): Unit = {
      val zkUtils = controllerContext.zkUtils
      try {
        val brokerIds = sequenceNumbers.flatMap(LogDirUtils.getBrokerIdFromLogDirEvent(zkUtils, _))
        info(s"Process log dir event with broker list ${brokerIds.mkString(",")}")

        onBrokerLogDirFailure(brokerIds)
      } finally {
        // delete processed children
        val deletePaths = sequenceNumbers.map(ZkUtils.LogDirEventNotificationPath + "/" + _)
        zkUtils.transactionalDeletePaths(controllerContext.epochZkVersion, deletePaths)
      }
    }
  }

  case class PreferredReplicaLeaderElection(partitions: Set[TopicAndPartition]) extends ControllerEvent {

    def state = ControllerState.ManualLeaderBalance

    override def process(): Unit = {
      if (!isActive) return
      val partitionsForTopicsToBeDeleted = partitions.filter(p => topicDeletionManager.isTopicQueuedUpForDeletion(p.topic))
      if (partitionsForTopicsToBeDeleted.nonEmpty) {
        error("Skipping preferred replica election for partitions %s since the respective topics are being deleted"
          .format(partitionsForTopicsToBeDeleted))
      }
      onPreferredReplicaElection(partitions -- partitionsForTopicsToBeDeleted)
    }

  }

  case object AutoPreferredReplicaLeaderElection extends ControllerEvent {

    def state = ControllerState.AutoLeaderBalance

    override def process(): Unit = {
      if (!isActive) return
      try {
        checkAndTriggerAutoLeaderRebalance()
      } finally {
        scheduleAutoLeaderRebalanceTask(delay = config.leaderImbalanceCheckIntervalSeconds, unit = TimeUnit.SECONDS)
      }
    }
  }

  case class ControlledShutdown(id: Int, controlledShutdownCallback: Try[Set[TopicAndPartition]] => Unit) extends ControllerEvent {

    def state = ControllerState.ControlledShutdown

    override def process(): Unit = {
      val controlledShutdownResult = Try { doControlledShutdown(id) }
      controlledShutdownCallback(controlledShutdownResult)
    }

    private def doControlledShutdown(id: Int): Set[TopicAndPartition] = {
      if (!isActive) {
        throw new ControllerMovedException("Controller moved to another broker. Aborting controlled shutdown")
      }

      info("Shutting down broker " + id)

      if (!controllerContext.liveOrShuttingDownBrokerIds.contains(id))
        throw new BrokerNotAvailableException("Broker id %d does not exist.".format(id))

      controllerContext.shuttingDownBrokerIds.add(id)
      debug("All shutting down brokers: " + controllerContext.shuttingDownBrokerIds.mkString(","))
      debug("Live brokers: " + controllerContext.liveBrokerIds.mkString(","))

      val allPartitionsAndReplicationFactorOnBroker: Set[(TopicAndPartition, Int)] =
          controllerContext.partitionsOnBroker(id)
            .map(topicAndPartition => (topicAndPartition, controllerContext.partitionReplicaAssignment(topicAndPartition).size))

      val leadersOnBroker = new mutable.HashSet[TopicAndPartition]
      val followersOnBroker = new mutable.HashSet[PartitionAndReplica]

      allPartitionsAndReplicationFactorOnBroker.foreach {
        case (topicAndPartition, replicationFactor) =>
          controllerContext.partitionLeadershipInfo.get(topicAndPartition).foreach { currLeaderIsrAndControllerEpoch =>
            if (replicationFactor > 1) {
              if (currLeaderIsrAndControllerEpoch.leaderAndIsr.leader == id) {
                leadersOnBroker += topicAndPartition
              } else {
                followersOnBroker += PartitionAndReplica(topicAndPartition.topic, topicAndPartition.partition, id)
              }
            }
          }
      }

      val groupedLeadersOnBroker = leadersOnBroker.grouped(config.controlledShutdownPartitionBatchSize)
      val groupedFollowersOnBroker = followersOnBroker.grouped(config.controlledShutdownPartitionBatchSize)

      groupedLeadersOnBroker.foreach { leadersOnBroker =>
        // If the broker leads the topic partition, transition the leader and update isr. Updates zk and
        // notifies all affected brokers
        partitionStateMachine.handleStateChanges(leadersOnBroker, OnlinePartition,
          controlledShutdownPartitionLeaderSelector)
      }

      groupedFollowersOnBroker.foreach { followersOnBroker =>
        // Stop the replica first. The state change below initiates ZK changes which should take some time
        // before which the stop replica request should be completed (in most cases)
        brokerRequestBatch.newBatch()
        followersOnBroker.foreach { partitionAndReplica =>
          try {
            brokerRequestBatch.addStopReplicaRequestForBrokers(Seq(partitionAndReplica.replica), partitionAndReplica.topic,
              partitionAndReplica.partition, deletePartition = false)
          } catch {
            case e: IllegalStateException => {
              handleIllegalState(e)
            }
          }
        }
        brokerRequestBatch.sendRequestsToBrokers(epoch)
        // If the broker is a follower, updates the isr in ZK and notifies the current leader
        replicaStateMachine.handleStateChanges(followersOnBroker, OfflineReplica)
      }

      def replicatedPartitionsBrokerLeads() = {
        trace("All leaders = " + controllerContext.partitionLeadershipInfo.mkString(","))
        controllerContext.partitionLeadershipInfo.filter {
          case (topicAndPartition, leaderIsrAndControllerEpoch) =>
            leaderIsrAndControllerEpoch.leaderAndIsr.leader == id && controllerContext.partitionReplicaAssignment(topicAndPartition).size > 1
        }.keys
      }
      replicatedPartitionsBrokerLeads().toSet
    }
  }

  case class LeaderAndIsrResponseReceived(LeaderAndIsrResponseObj: AbstractResponse, brokerId: Int) extends ControllerEvent {

    def state = ControllerState.LeaderAndIsrResponseReceived

    override def process(): Unit = {
      import JavaConverters._
      val leaderAndIsrResponse = LeaderAndIsrResponseObj.asInstanceOf[LeaderAndIsrResponse]

      if (leaderAndIsrResponse.error() != Errors.NONE) {
        stateChangeLogger.error(s"Received error in leaderAndIsrResponse $leaderAndIsrResponse from broker $brokerId")
        return
      }

      val offlineReplicas = leaderAndIsrResponse.responses().asScala.filter(_._2 == Errors.KAFKA_STORAGE_ERROR).keys.map(
        tp => TopicAndPartition(tp.topic(), tp.partition())).toSet
      val onlineReplicas = leaderAndIsrResponse.responses().asScala.filter(_._2 == Errors.NONE).keys.map(
        tp => TopicAndPartition(tp.topic(), tp.partition())).toSet
      val previousOfflineReplicas = controllerContext.replicasOnOfflineDirs.getOrElse(brokerId, Set.empty[TopicAndPartition])
      val currentOfflineReplicas = previousOfflineReplicas -- onlineReplicas ++ offlineReplicas
      controllerContext.replicasOnOfflineDirs.put(brokerId, currentOfflineReplicas)
      val newOfflineReplicas = (currentOfflineReplicas -- previousOfflineReplicas).map(tp => PartitionAndReplica(tp.topic, tp.partition, brokerId))
      stateChangeLogger.info(s"Mark replicas ${currentOfflineReplicas -- previousOfflineReplicas} on broker $brokerId as offline")

      if (newOfflineReplicas.nonEmpty)
        onReplicasBecomeOffline(newOfflineReplicas)
    }
  }

  case class TopicDeletionStopReplicaResponseReceived(stopReplicaResponseObj: AbstractResponse, replicaId: Int) extends ControllerEvent {

    def state = ControllerState.TopicDeletion

    override def process(): Unit = {
      import JavaConverters._
      if (!isActive) return
      val stopReplicaResponse = stopReplicaResponseObj.asInstanceOf[StopReplicaResponse]
      debug("Delete topic callback invoked for %s".format(stopReplicaResponse))
      val responseMap = stopReplicaResponse.responses.asScala
      val partitionsInError =
        if (stopReplicaResponse.error != Errors.NONE) responseMap.keySet
        else responseMap.filter { case (_, error) => error != Errors.NONE }.keySet
      val replicasInError = partitionsInError.map(p => PartitionAndReplica(p.topic, p.partition, replicaId))
      // move all the failed replicas to ReplicaDeletionIneligible
      topicDeletionManager.failReplicaDeletion(replicasInError)
      if (replicasInError.size != responseMap.size) {
        // some replicas could have been successfully deleted
        val deletedReplicas = responseMap.keySet -- partitionsInError
        topicDeletionManager.completeReplicaDeletion(deletedReplicas.map(p => PartitionAndReplica(p.topic, p.partition, replicaId)))
      }
    }
  }

  case object Startup extends ControllerEvent {

    def state = ControllerState.ControllerChange

    override def process(): Unit = {
      registerSessionExpirationListener()
      registerControllerChangeListener()
      elect()
    }

  }

  case class ControllerChange(newControllerId: Int) extends ControllerEvent {

    def state = ControllerState.ControllerChange

    override def process(): Unit = {
      info(s"Process controller change event with new controller id $newControllerId")
      val wasActiveBeforeChange = isActive
      activeControllerId = newControllerId
      if (wasActiveBeforeChange && !isActive) {
        onControllerResignation()
      }
    }

  }

  case object Reelect extends ControllerEvent {

    def state = ControllerState.ControllerChange

    override def process(): Unit = {
      info(s"Process controller re-elect event")
      maybeResign()
      elect()
    }

  }

  private def updateMetrics(): Unit = {
    offlinePartitionCount =
      if (!isActive) {
        0
      } else {
        partitionStateMachine.offlinePartitionCount
      }

    globalTopicCount = if (!isActive) 0 else controllerContext.allTopics.size

    globalPartitionCount = if (!isActive) 0 else controllerContext.partitionLeadershipInfo.size
  }

  // visible for testing
  private[controller] def handleIllegalState(e: IllegalStateException): Nothing = {
    // Resign if the controller is in an illegal state
    error("Forcing the controller to resign")
    brokerRequestBatch.clear()
    triggerControllerMove()
    throw e
  }

  private def triggerControllerMove(): Unit = {
    activeControllerId = getControllerID()
    if (!isActive) {
      warn("Current broker is not the active controller when trying to trigger controller movement")
      return
    }
    try {
      val expectedControllerEpochZkVersion = controllerContext.epochZkVersion
      activeControllerId = -1
      onControllerResignation()
      zkUtils.transactionalDeletePath(expectedControllerEpochZkVersion, ZkUtils.ControllerPath)
    } catch {
      case _: ControllerMovedException =>
        warn("Controller has already moved when trying to trigger controller movement")
    }
  }

  private def maybeResign(): Unit = {
    val wasActiveBeforeChange = isActive
    activeControllerId = getControllerID()
    if (wasActiveBeforeChange && !isActive) {
      onControllerResignation()
    }
  }

  def elect(): Unit = {
    val timestamp = time.milliseconds
    val electString = ZkUtils.controllerZkData(config.brokerId, timestamp)

    activeControllerId = getControllerID()
    /*
     * We can get here during the initial startup and the handleDeleted ZK callback. Because of the potential race condition,
     * it's possible that the controller has already been elected when we get here. This check will prevent the following
     * createEphemeralPath method from getting into an infinite loop if this broker is already the controller.
     */
    if (activeControllerId != -1) {
      debug("Broker %d has been elected as the controller, so stopping the election process.".format(activeControllerId))
      return
    }

    try {
      val (epoch, epochZkVersion) = zkUtils.registerControllerAndIncrementControllerEpochInZk(electString)
      controllerContext.epoch = epoch
      controllerContext.epochZkVersion = epochZkVersion
      activeControllerId = config.brokerId

      info(s"${config.brokerId} successfully elected as the controller. Epoch incremented to ${controllerContext.epoch} " +
        s"and epoch zk version is now ${controllerContext.epochZkVersion}")
      onControllerFailover()
    } catch {
      case e: ControllerMovedException =>
        maybeResign()

        if (activeControllerId != -1)
          debug("Broker %d was elected as controller instead of broker %d".format(activeControllerId, config.brokerId), e)
        else
          warn("A controller has been elected but just resigned, this will result in another round of election", e)

      case t: Throwable =>
        error("Error while electing or becoming controller on broker %d. Trigger controller movement immediately"
          .format(config.brokerId), t)
        triggerControllerMove()
    }
  }
}

/**
  * This is the zookeeper listener that triggers all the state transitions for a replica
  */
class BrokerChangeListener(controller: KafkaController, eventManager: ControllerEventManager) extends IZkChildListener with Logging {
  override def handleChildChange(parentPath: String, currentChilds: java.util.List[String]): Unit = {
    import JavaConverters._
    info(s"Received broker change event with broker list ${currentChilds.asScala.mkString(",")}")
    eventManager.put(controller.BrokerChange(currentChilds.asScala))
  }
}

class TopicChangeListener(controller: KafkaController, eventManager: ControllerEventManager) extends IZkChildListener with Logging {
  override def handleChildChange(parentPath: String, currentChilds: java.util.List[String]): Unit = {
    import JavaConverters._
    eventManager.put(controller.TopicChange(currentChilds.asScala.toSet))
  }
}

/**
  * Called when broker notifies controller of log directory change
  */
class LogDirEventNotificationListener(controller: KafkaController, eventManager: ControllerEventManager) extends IZkChildListener with Logging {
  override def handleChildChange(parentPath: String, currentChilds: java.util.List[String]): Unit = {
    import JavaConverters._
    info(s"Received log dir event with broker list ${currentChilds.asScala.mkString(",")}")
    eventManager.put(controller.LogDirEventNotification(currentChilds.asScala))
  }
}

object LogDirEventNotificationListener {
  val version: Long = 1L
}

class PartitionModificationsListener(controller: KafkaController, eventManager: ControllerEventManager, topic: String) extends IZkDataListener with Logging {
  override def handleDataChange(dataPath: String, data: Any): Unit = {
    eventManager.put(controller.PartitionModifications(topic))
  }

  override def handleDataDeleted(dataPath: String): Unit = {}
}

/**
  * Delete topics includes the following operations -
  * 1. Add the topic to be deleted to the delete topics cache, only if the topic exists
  * 2. If there are topics to be deleted, it signals the delete topic thread
  */
class TopicDeletionListener(controller: KafkaController, eventManager: ControllerEventManager) extends IZkChildListener with Logging {
  override def handleChildChange(parentPath: String, currentChilds: java.util.List[String]): Unit = {
    import JavaConverters._
    eventManager.put(controller.TopicDeletion(currentChilds.asScala.toSet))
  }
}

/**
  * Listener for /topic_deletion_flag znode.
  *   If the data of the znode is set to true/false, it will trigger the in memory isDeleteTopicEnabled to be set accordingly.
  *   If the znode data cannot be converted to boolean, it will overwrite znode with the previous valid value.
  *   If the znode path is deleted, it will reset the in memory isDeleteTopicEnabled to the config value.
  */
class TopicDeletionFlagListener(controller: KafkaController, eventManager: ControllerEventManager) extends IZkDataListener with Logging {
  override def handleDataDeleted(dataPath: String): Unit = {
    info(s"$TopicDeletionEnabledPath is deleted. Put TopicDeletionFlagChange event into controller event queue")
    eventManager.put(controller.TopicDeletionFlagChange(null, reset = true))
  }

  override def handleDataChange(dataPath: String, data: scala.Any): Unit = {
    info(s"$TopicDeletionEnabledPath data changes to $data. Put TopicDeletionFlagChange event into controller event queue")
    eventManager.put(controller.TopicDeletionFlagChange(data.toString))
  }
}

/**
 * Starts the partition reassignment process unless -
 * 1. Partition previously existed
 * 2. New replicas are the same as existing replicas
 * 3. Any replica in the new set of replicas are dead
 * If any of the above conditions are satisfied, it logs an error and removes the partition from list of reassigned
 * partitions.
 */
class PartitionReassignmentListener(controller: KafkaController, eventManager: ControllerEventManager) extends IZkDataListener with Logging {
  override def handleDataChange(dataPath: String, data: Any): Unit = {
    eventManager.put(controller.PartitionReassignment)
  }

  override def handleDataDeleted(dataPath: String): Unit = {}
}

class PartitionReassignmentIsrChangeListener(controller: KafkaController, eventManager: ControllerEventManager,
                                             topic: String, partition: Int, reassignedReplicas: Set[Int]) extends IZkDataListener with Logging {
  override def handleDataChange(dataPath: String, data: Any): Unit = {
    eventManager.put(controller.PartitionReassignmentIsrChange(TopicAndPartition(topic, partition), reassignedReplicas))
  }

  override def handleDataDeleted(dataPath: String): Unit = {}
}

/**
 * Called when replica leader initiates isr change
 */
class IsrChangeNotificationListener(controller: KafkaController, eventManager: ControllerEventManager) extends IZkChildListener with Logging {
  override def handleChildChange(parentPath: String, currentChilds: java.util.List[String]): Unit = {
    import JavaConverters._
    info(s"Received isr change event for znodes with sequence number ${currentChilds.asScala.mkString(",")}")
    eventManager.put(controller.IsrChangeNotification(currentChilds.asScala))
  }
}

object IsrChangeNotificationListener {
  val version: Long = 1L
}

/**
 * Starts the preferred replica leader election for the list of partitions specified under
 * /admin/preferred_replica_election -
 */
class PreferredReplicaElectionListener(controller: KafkaController, eventManager: ControllerEventManager) extends IZkDataListener with Logging {
  override def handleDataChange(dataPath: String, data: Any): Unit = {
    val partitions = PreferredReplicaLeaderElectionCommand.parsePreferredReplicaElectionData(data.toString)
    eventManager.put(controller.PreferredReplicaLeaderElection(partitions))
  }

  override def handleDataDeleted(dataPath: String): Unit = {}
}

class ControllerChangeListener(controller: KafkaController, eventManager: ControllerEventManager) extends IZkDataListener with Logging {
  override def handleDataChange(dataPath: String, data: Any): Unit = {
    val controllerId = KafkaController.parseControllerId(data.toString)
    info(s"Received controller change event with new controller id $controllerId")
    eventManager.put(controller.ControllerChange(controllerId))
  }

  override def handleDataDeleted(dataPath: String): Unit = {
    info(s"Received controller re-elect event due to data deleted")
    eventManager.put(controller.Reelect)
  }
}

class SessionExpirationListener(controller: KafkaController, eventManager: ControllerEventManager) extends IZkStateListener with Logging {
  override def handleStateChanged(state: KeeperState) {
    // do nothing, since zkclient will do reconnect for us.
  }

  /**
    * Called after the zookeeper session has expired and a new session has been created. You would have to re-create
    * any ephemeral nodes here.
    *
    * @throws Exception On any error.
    */
  @throws[Exception]
  override def handleNewSession(): Unit = {
    info(s"Received controller re-elect event due to new session")
    eventManager.put(controller.Reelect)
  }

  override def handleSessionEstablishmentError(error: Throwable): Unit = {
    //no-op handleSessionEstablishmentError in KafkaHealthCheck should handle this error in its handleSessionEstablishmentError
  }
}

case class ReassignedPartitionsContext(var newReplicas: Seq[Int] = Seq.empty,
                                       var isrChangeListener: PartitionReassignmentIsrChangeListener = null)

case class PartitionAndReplica(topic: String, partition: Int, replica: Int) {
  override def toString: String = {
    "[Topic=%s,Partition=%d,Replica=%d]".format(topic, partition, replica)
  }
}

case class LeaderIsrAndControllerEpoch(leaderAndIsr: LeaderAndIsr, controllerEpoch: Int) {
  override def toString: String = {
    val leaderAndIsrInfo = new StringBuilder
    leaderAndIsrInfo.append("(Leader:" + leaderAndIsr.leader)
    leaderAndIsrInfo.append(",ISR:" + leaderAndIsr.isr.mkString(","))
    leaderAndIsrInfo.append(",LeaderEpoch:" + leaderAndIsr.leaderEpoch)
    leaderAndIsrInfo.append(",ControllerEpoch:" + controllerEpoch + ")")
    leaderAndIsrInfo.toString()
  }
}

private[controller] class ControllerStats extends KafkaMetricsGroup {
  val uncleanLeaderElectionRate = newMeter("UncleanLeaderElectionsPerSec", "elections", TimeUnit.SECONDS)

  val rateAndTimeMetrics: Map[ControllerState, KafkaTimer] = ControllerState.values.flatMap { state =>
    state.rateAndTimeMetricName.map { metricName =>
      state -> new KafkaTimer(newTimer(s"$metricName", TimeUnit.MILLISECONDS, TimeUnit.SECONDS))
    }
  }.toMap

}

sealed trait ControllerEvent {
  def state: ControllerState
  def process(): Unit
}
