/*
 * (C) Copyright IBM Corp. 2014
 *
 * LICENSE: Eclipse Public License v1.0
 * http://www.eclipse.org/legal/epl-v10.html
 */

package fabric.registry.persistence.distributed;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import fabric.Fabric;
import fabric.FabricBus;
import fabric.bus.IBusIO;
import fabric.bus.NeighbourChannels;
import fabric.bus.SharedChannel;
import fabric.bus.messages.FabricMessageFactory;
import fabric.bus.messages.IFabricMessage;
import fabric.bus.messages.IMessagePayload;
import fabric.bus.messages.IServiceMessage;
import fabric.bus.messages.impl.MessagePayload;
import fabric.bus.messages.impl.ServiceMessage;
import fabric.bus.plugins.IFabletConfig;
import fabric.bus.plugins.IFabletPlugin;
import fabric.bus.plugins.IPluginConfig;
import fabric.bus.routing.impl.StaticRouting;
import fabric.core.io.ICallback;
import fabric.core.io.InputTopic;
import fabric.core.io.Message;
import fabric.core.io.OutputTopic;
import fabric.core.logging.FLog;
import fabric.core.properties.ConfigProperties;
import fabric.registry.FabricRegistry;
import fabric.registry.QueryScope;
import fabric.registry.exception.PersistenceException;
import fabric.registry.persistence.Persistence;
import fabric.registry.persistence.PersistenceManager;

public class DistributedPersistenceFablet extends FabricBus implements IFabletPlugin, ICallback {

    /** Copyright notice. */
    public static final String copyrightNotice = "(C) Copyright IBM Corp. 2014";

    private final static String CLASS_NAME = DistributedPersistenceFablet.class.getName();
    private final static String PACKAGE_NAME = DistributedPersistenceFablet.class.getPackage().getName();

    private final static Logger logger = Logger.getLogger(PACKAGE_NAME);

    /** The configuration object for this instance */
    private IFabletConfig fabletConfig = null;

    /** Accessor for Fabric Manager I/O services */
    private IBusIO busIO = null;

    /** Object used to synchronize with the mapper main thread */
    private final Object threadSync = new Object();

    /** Flag used to indicate when the main thread should terminate */
    private boolean isRunning = false;

    /** Indicates if we should forward a remote query onwards to all our known neighbours. **/
    private boolean floodRemoteQuery = false;

    // Allow concurrentAccess to parts of these Maps
    /**
     * Given a correlationId this returns the Set of nodes we have flooded the query to but not yet received a response
     */
    private ConcurrentHashMap<String, ConcurrentSkipListSet<String>> pendingNodesByCorrelationId = new ConcurrentHashMap<String, ConcurrentSkipListSet<String>>();
    /**
     * Holds the result for each correlationId, This result is appended to as results return from any flooded queries
     */
    private ConcurrentHashMap<String, DistributedQueryResult> resultByCorrelationId = new ConcurrentHashMap<String, DistributedQueryResult>();

    /**
     * Correlation IDs for which this fablet is responsible for returning results
     */
    private Set<String> myCorrelationIds = Collections.synchronizedSet(new HashSet<String>());
    /**
     * Where the results for given correlationIds should be sent
     */
    private Map<String, String> returnNodeByCorrelationID = new ConcurrentHashMap<String, String>();
    /**
     * Given a correlationId, returns the wait thread for that correlationId
     */
    private Map<String, DistributedQueryWaitThread> waitThreadsByCorrelationId = new ConcurrentHashMap<String, DistributedQueryWaitThread>();

    private String nodeName = null;
    private String myRegistryUID = null;
    int defaultQueryTimeoutDecrement = 0;
    int defaultQueryTimeOut = 0;
    private boolean perfLoggingEnabled = false;

    private InputTopic commandChannelTopic;
    private OutputTopic resultChannelTopic;

    private SharedChannel commandChannel;
    private SharedChannel resultChannel;

    private Persistence p = PersistenceManager.getPersistence();
    private DistributedJDBCPersistence jdbcp = (DistributedJDBCPersistence) p;

    @Override
    /**
     * @see fabric.bus.plugins.IPlugin#startPlugin(fabric.bus.plugins.IPluginConfig)
     */
    public void startPlugin(IPluginConfig pluginConfig) {

        fabletConfig = (IFabletConfig) pluginConfig;
        busIO = fabletConfig.getFabricServices().busIO();
        nodeName = fabletConfig.getNode();
        commandChannelTopic = new InputTopic(config(ConfigProperties.REGISTRY_COMMAND_TOPIC,
                ConfigProperties.REGISTRY_COMMAND_TOPIC_DEFAULT, nodeName));
        resultChannelTopic = new OutputTopic(config(ConfigProperties.REGISTRY_RESULT_TOPIC,
                ConfigProperties.REGISTRY_RESULT_TOPIC_DEFAULT, nodeName));
        myRegistryUID = config().getProperty(ConfigProperties.REGISTRY_UID);
        defaultQueryTimeoutDecrement = new Integer(DistributedJDBCPersistence.DEFAULT_RESPONSE_TIMEOUT_DECREMENT);
        defaultQueryTimeOut = new Integer(DistributedJDBCPersistence.DEFAULT_RESPONSE_TIMEOUT);
        perfLoggingEnabled = new Boolean(this.config(ConfigProperties.REGISTRY_DISTRIBUTED_PERF_LOGGING));
        floodRemoteQuery = new Boolean(this.config(ConfigProperties.REGISTRY_DISTRIBUTED_FLOOD_REMOTE_QUERY,
                ConfigProperties.REGISTRY_DISTRIBUTED_FLOOD_REMOTE_QUERY_DEFAULT));
    }

    /**
     * @see fabric.bus.plugins.IPlugin#stopPlugin()
     */
    @Override
    public void stopPlugin() {

        /* Tell the main thread to stop... */
        isRunning = false;

        /* ...and wake it up */
        threadSync.notify();

        if (commandChannel != null) {
            try {
                commandChannel.close();
            } catch (IOException e) {
                logger.fine("Couldn't close Channel to " + commandChannelTopic);
            }
        }
        if (resultChannel != null) {
            try {
                resultChannel.close();
            } catch (IOException e) {
                logger.fine("Couldn't close Channel to " + resultChannelTopic);
            }
        }
    }

    /**
     * @see java.lang.Runnable#run()
     */
    @Override
    public void run() {

        isRunning = true;

        try {
            commandChannel = homeNodeEndPoint().openInputChannel(commandChannelTopic, this);
        } catch (Exception e) {
            logger.warning("Cannot open channel to receive queries; distributed queries will not work :\n"
                    + e.getMessage());
            e.printStackTrace();
        }
        while (isRunning) {
            try {
                synchronized (threadSync) {
                    threadSync.wait();
                }
            } catch (InterruptedException e) {
                /* Not too worried about this happening */
            }
        }
    }

    /**
     * @see fabric.bus.plugins.IPlugin#handleControlMessage(fabric.bus.messages.IFabricMessage)
     */
    @Override
    public void handleControlMessage(IFabricMessage message) {
    }

    /**
     * @see fabric.core.io.ICallback#cancelCallback(java.lang.Object)
     */
    @Override
    public void cancelCallback(Object arg1) {
    }

    /**
     * @see fabric.core.io.ICallback#startCallback(java.lang.Object)
     */
    @Override
    public void startCallback(Object arg1) {
    }

    /**
     * @see fabric.core.io.ICallback#handleMessage(fabric.core.io.Message)
     */
    @Override
    public void handleMessage(Message message) {

        FLog.enter(logger, Level.FINER, this, "handleMessage", message);

        String messageTopic = (String) message.topic;
        byte[] messageData = message.data;
        String messageString = new String((messageData != null) ? messageData : new byte[0]);

        logger.log(Level.FINEST, "Full message:\n{0}", messageString);

        long entryTime = 0;
        int messageSize = 0;
        if (perfLoggingEnabled) {
            entryTime = System.currentTimeMillis();
            messageSize = message.data.length;
        }

        IFabricMessage parsedMessage = null;

        try {

            /* Parse the message */
            parsedMessage = FabricMessageFactory.create(messageTopic, messageData);
            logger.finest("We have a fabricMessage");

            if (parsedMessage instanceof IServiceMessage) {

                IServiceMessage serviceMessage = (IServiceMessage) parsedMessage;
                logger.finer("Service message received:\n" + serviceMessage.toString());
                String action = serviceMessage.getAction();
                String correlationId = serviceMessage.getCorrelationID();
                IMessagePayload payload = serviceMessage.getPayload();
                // Currently only json payload handled
                String payloadFormat = "json";

                if (action == null || correlationId == null || payload == null) {
                    logger.fine("Invalid message: " + serviceMessage.toString());
                    FLog.exit(logger, Level.FINER, this, "handleMessage", null);
                    return;
                }

                String prevNode = serviceMessage.getRouting().previousNode();

                switch (action) {

                    case DistributedJDBCPersistence.QUERY_ACTION:

                        logger.finer("Query action for correlation Id = " + correlationId);
                        // If this is a message we have already seen
                        // It may have arrived via different routes so return an empty result to avoid timeouts.
                        if (parsedMessage.getRouting().isDuplicate(parsedMessage)) {
                            logger.finer("Duplicate message; returning empty result");
                            returnEmptyResult(correlationId, prevNode);
                            FLog.exit(logger, Level.FINER, this, "handleMessage", null);
                            return;
                        }

                        // We should only see a query once per correlationId
                        if (myCorrelationIds.contains(correlationId)
                                || returnNodeByCorrelationID.containsKey(correlationId)) {
                            logger.finer("Unexpected correlationID [" + correlationId
                                    + "]; a query with this correlationid already active");
                            FLog.exit(logger, Level.FINER, this, "handleMessage", null);
                            return;
                        }

                        // Get Json bytes to append to our resultset.
                        byte[] payloadBytes = payload.getPayload();
                        DistributedQuery distributedQuery = new DistributedQuery(payloadBytes, payloadFormat);
                        logger.fine("Query from [" + prevNode + "], correlation ID [" + correlationId + "], query ["
                                + distributedQuery.getQuery() + "]");

                        // If this is the originating node then indicate such
                        if (nodeName.equalsIgnoreCase(prevNode)) {
                            logger.finest("Adding correlation ID to list for which this instance is responsible");
                            myCorrelationIds.add(correlationId);
                        } else {
                            // We note our returnPath for this CorrelationID
                            returnNodeByCorrelationID.put(correlationId, prevNode);
                        }

                        boolean returnImmediately = executeQuery(correlationId, prevNode, distributedQuery);
                        if (returnImmediately) {
                            // return this result immediately
                            returnResult(correlationId);
                        }

                        String[] nodes = null;
                        // Trim the nodes for flooding no point the query arriving twice in the same place
                        if (nodeName.equalsIgnoreCase(prevNode) || floodRemoteQuery) {
                            // We are the initial node (we are local to query) or flooding is enabled so flood widely
                            nodes = serviceMessage.getRouting().nextNodes();
                        }

                        if (nodes == null || nodes.length == 0) {
                            // We have no onward route so no point waiting just respond
                            returnResult(correlationId);
                        } else {
                            int newQueryTimeOut = checkQueryTimeOut(serviceMessage);

                            // If our timeout has reached 0 then we don't flood any further and log a message
                            if (newQueryTimeOut < 1) {
                                logger.fine("Our timeout expired; will not flood query further");
                                returnResult(correlationId);
                            } else {
                                // flooding onwards is the last thing we do
                                int pendingNodes = floodQuery(serviceMessage, nodes, newQueryTimeOut);
                                if (pendingNodes == 0) {
                                    // If for whatever reason the flood failed, we may end up with no pending nodes so
                                    // return results
                                    returnResult(correlationId);
                                }
                            }
                        }
                        break;

                    case DistributedJDBCPersistence.PARTIAL_RESULT_ACTION:

                        if (parsedMessage.getRouting().isDuplicate(parsedMessage)) {
                            logger.finest("Duplicate message:\n" + message.toString());
                            FLog.exit(logger, Level.FINER, this, "handleMessage", null);
                            return;
                        }

                        if (pendingNodesByCorrelationId.get(correlationId) == null) {
                            logger.finest("No result pending from from node [" + prevNode + "]");
                            FLog.exit(logger, Level.FINER, this, "handleMessage", null);
                            return;
                        }

                        // Get Json bytes to append to our resultset.
                        payloadBytes = payload.getPayload();

                        logger.finer("Pending results from node [" + prevNode + "] returned");
                        DistributedQueryResult currentResult = resultByCorrelationId.get(correlationId);
                        // Acquire lock for result so we can complete append.
                        // Prevents another thread acquiring lock and returning a result while we are in middle of
                        // appending.
                        synchronized (currentResult) {
                            currentResult.append(payloadBytes, payloadFormat);
                        }

                        logger.log(Level.FINE,
                                "Result from [{0}], correlation ID [{1}]; result for this correlation ID is now:\n{2}",
                                new Object[] {prevNode, correlationId, currentResult.toString()});

                        int numberOfNodesPending = updatePendingNodeByCorrelationIds(correlationId, prevNode);
                        if (numberOfNodesPending < 0) {
                            // Thread timeout has already processed this correlationID we missed our window so just
                            // return
                            logger.finest("correlationId " + correlationId + " no longer pending, nothing to do");
                            FLog.exit(logger, Level.FINER, this, "handleMessage", null);
                            return;
                        } else if (numberOfNodesPending == 0) {
                            // No more waiting it is time to respond
                            // We have ownership for responding as no-one else will receive 0 for numberOfNodesPending
                            // for
                            // this correlationId
                            returnResult(correlationId);
                        } else {
                            logger.finest("Still awaiting results from " + numberOfNodesPending + " nodes");
                        }
                        break;
                    case DistributedJDBCPersistence.FINAL_RESULT_ACTION:
                        logger.info("Nothing to do for final results, handled by client");
                        break;
                    default:
                        logger.info("Action [" + action + "] not recognised, doing nothing");
                        break;
                }
            } else {
                logger.finer("Unexpected message received - should be a service message for:\n"
                        + parsedMessage.toString());
            }

        } catch (Exception e) {
            // Log errors - nothing to catch them and do anything sensible
            logger.log(Level.FINE, "Exception handling message: {0}", e.getMessage());
            logger.log(Level.FINEST, "Full exception: ", e);
        }

        if (perfLoggingEnabled) {
            long timeTaken = System.currentTimeMillis() - entryTime;
            logger.info("Message size = " + messageSize + ", Approximage time taken to handle message is " + timeTaken
                    + " milliseconds");
        }
        FLog.exit(logger, Level.FINER, this, "handleMessage", null);
    }

    private int checkQueryTimeOut(IServiceMessage serviceMessage) {

        // flood query onwards if required - check timeout first
        String queryTimeoutString = serviceMessage.getProperty(ConfigProperties.REGISTRY_DISTRIBUTED_TIMEOUT);
        String queryTimeoutDecrementString = serviceMessage
                .getProperty(ConfigProperties.REGISTRY_DISTRIBUTED_TIMEOUT_DECREMENT);
        int queryTimeOut = defaultQueryTimeOut;
        int queryTimeoutDecrement = defaultQueryTimeoutDecrement;
        if (queryTimeoutString != null) {
            queryTimeOut = new Integer(queryTimeoutString);
        }
        if (queryTimeoutDecrementString != null) {
            queryTimeoutDecrement = new Integer(queryTimeoutDecrementString);
        }
        // Decrease the timeout in the message for onward flooding
        int newQueryTimeOut = queryTimeOut - queryTimeoutDecrement;
        return newQueryTimeOut;
    }

    /**
     * Remove pending node and return number of remaining pending nodes Return -1 if the node was not found to be
     * pending for the correlationID (Some other thread beat us to it perhaps) 0 indicates the caller should take
     * responsibility for returning the result >0 indicates other nodes still pending
     *
     * @param correlationId
     * @param pendingNode
     * @return
     */
    private synchronized int updatePendingNodeByCorrelationIds(String correlationId, String pendingNode) {

        int numberOfNodesPending = -1;
        if (!pendingNodesByCorrelationId.containsKey(correlationId)
                || !pendingNodesByCorrelationId.get(correlationId).contains(pendingNode)) {
            numberOfNodesPending = -1;
        } else {
            pendingNodesByCorrelationId.get(correlationId).remove(pendingNode);
            if (!pendingNodesByCorrelationId.get(correlationId).isEmpty()) {
                numberOfNodesPending = pendingNodesByCorrelationId.get(correlationId).size();
            } else {
                pendingNodesByCorrelationId.remove(correlationId);
                numberOfNodesPending = 0;
            }
        }
        return numberOfNodesPending;
    }

    /**
     * We currently always execute our local query before we flood the query onwards
     **/
    private boolean executeQuery(String correlationId, String prevNode, DistributedQuery query)
            throws PersistenceException {

        boolean returnImmediately = false;

        // Hosting multiple Fabric Managers on same host with same registry leads to duplicate queries.
        // Need a way for Distributed Registry code to realise it is the same registry
        // and not to bother doing a local query more than once.
        // For now we will use a config variable which can be manually set for each fabric manager node
        // If the value from previous node matches ours we don't bother with local Query and just flood onwards
        String prevNodeRegistryUID = jdbcp.queryString("select VALUE FROM FABRIC.NODE_CONFIG where NODE_ID='"
                + prevNode + "' and name='" + ConfigProperties.REGISTRY_UID + "'", QueryScope.LOCAL);
        logger.finest("Previous Node " + prevNode + " Registry UID = " + prevNodeRegistryUID + " my " + nodeName
                + " registryUID = " + myRegistryUID);

        if (prevNode.equals(nodeName) || myRegistryUID == null || prevNodeRegistryUID == null
                || !myRegistryUID.equals(prevNodeRegistryUID)) {
            // Execute our query locally before we flood
            try {
                // Get local Results
                DistributedQueryResult queryResult = jdbcp.getDistributedQueryResult(query.getQuery(), nodeName);
                resultByCorrelationId.put(correlationId, queryResult);
            } catch (Exception e) {
                // Exception occured create empty Result with the Exception
                DistributedQueryResult result = new DistributedQueryResult(nodeName, null);
                result.setLocalException(e, nodeName);
                resultByCorrelationId.put(correlationId, result);
                logger.finer(e.getMessage());
                returnImmediately = true;
            }
        } else {
            // We record an empty partial resultset as we don't run the query
            DistributedQueryResult result = new DistributedQueryResult(nodeName, null);
            resultByCorrelationId.put(correlationId, result);
            logger.finest("Created empty partial result");
        }

        logger.finer("Local Query Results = " + resultByCorrelationId.get(correlationId).toString());
        return returnImmediately;
    }

    private int floodQuery(IServiceMessage serviceMessage, String[] nodes, int timeOut) {

        String correlationId = serviceMessage.getCorrelationID();
        String prevNode = serviceMessage.getRouting().previousNode();
        ConcurrentSkipListSet<String> onwardNodes = new ConcurrentSkipListSet<String>();
        serviceMessage.setProperty(ConfigProperties.REGISTRY_DISTRIBUTED_TIMEOUT, Integer.toString(timeOut));
        // Loop to build up full onward nodeList before flooding to avoid timing issues.
        for (int i = 0; i < nodes.length; i++) {
            String node = nodes[i];
            if (!node.equalsIgnoreCase(nodeName) && !node.equalsIgnoreCase(prevNode)) {
                onwardNodes.add(node);
            }
        }
        pendingNodesByCorrelationId.put(correlationId, onwardNodes);
        int remainingNodes = onwardNodes.size();
        String[] nodesArray = onwardNodes.toArray(new String[] {});
        for (int i = 0; i < nodesArray.length; i++) {
            String nextNode = nodesArray[i];
            try {

                logger.finer("Flooding onwards to " + nextNode);
                NeighbourChannels neighbourChannels = busIO.connectNeighbour(nextNode);
                if (neighbourChannels == null) {
                    logger.fine("Failed to flood message to " + nextNode);
                    remainingNodes = updatePendingNodeByCorrelationIds(correlationId, nextNode);
                } else {

                    try {
                        /* Forward the message */
                        neighbourChannels.registryBusChannel().write(serviceMessage.toWireBytes());
                    } catch (Exception e) {
                        // We should disconnect from this neighbourEndPoint
                        // This will force it to be recreated if we need it in future.
                        logger.fine("Channel exception to " + nextNode + " message not flooded :\n" + e.getMessage());
                        busIO.disconnectNeighbour(neighbourChannels.neighbourDescriptor(), false);
                        remainingNodes = updatePendingNodeByCorrelationIds(correlationId, nextNode);
                    }
                }
            } catch (Exception e) {
                logger.log(Level.FINE, "Failed to flood message to [{0}]: {1}"
                        + new Object[] {nextNode, e.getMessage()});
                logger.log(Level.FINEST, "Full exception: ", e);
                remainingNodes = updatePendingNodeByCorrelationIds(correlationId, nextNode);
            }
        }

        // Last thing we do is start our wait threads so any blocking on
        // flooding our query isn't part of our timeout.
        // There is small risk our flooded queries have returned before we start our timeout threads,
        // however when the thread times out it will check there are still pending nodes before taking any action
        if (!onwardNodes.isEmpty() && remainingNodes != 0) {
            // Launch our timeout thread
            // Assume same wait time for all pending nodes and have just one
            // wait thread per correlationID
            logger.finest("Starting waitingThread for correlationId = " + correlationId);
            waitThreadsByCorrelationId.put(correlationId, new DistributedQueryWaitThread(timeOut, correlationId, this));
            waitThreadsByCorrelationId.get(correlationId).start();
        }
        return remainingNodes;
    }

    public void queryTimedOut(String correlationId) throws Exception {

        boolean iRemovedLastPendingNode = false;
        for (Iterator<String> iterator = pendingNodesByCorrelationId.get(correlationId).iterator(); iterator.hasNext();) {
            String pendingNode = iterator.next();
            int response = updatePendingNodeByCorrelationIds(correlationId, pendingNode);
            DistributedQueryResult currentResult = resultByCorrelationId.get(correlationId);
            synchronized (currentResult) {
                currentResult.addExceptionMessage("Query pending against node [" + pendingNode + "] has timed out",
                        nodeName);
            }
            if (response == 0) {
                iRemovedLastPendingNode = true;
                logger.fine("Query pending against node [" + pendingNode + "] has timed out");
                break;
            } else if (response > 0) {
                logger.fine("Query pending against node [" + pendingNode + "] has timed out");
            }
        }
        if (iRemovedLastPendingNode) {
            returnResult(correlationId);
        }
    }

    private void returnResult(String correlationId) throws Exception {

        DistributedQueryResult results = resultByCorrelationId.get(correlationId);
        if (myCorrelationIds.contains(correlationId)) {
            // Returning to client
            logger.finer("Looking to return final results" + results.toString());
            ServiceMessage serviceMessage = new ServiceMessage();
            String[] route = {nodeName};
            serviceMessage.setRouting(new StaticRouting(route));
            serviceMessage.setServiceName(DistributedJDBCPersistence.SERVICE_NAME);

            /* Indicate that this is a built-in Fabric plug-in */
            serviceMessage.setServiceFamilyName(DistributedJDBCPersistence.PLUGIN_FAMILY);
            serviceMessage.setAction(DistributedJDBCPersistence.FINAL_RESULT_ACTION);
            serviceMessage.setCorrelationID(correlationId);

            // Add SQL query to service message
            MessagePayload mp = new MessagePayload();
            synchronized (results) {
                mp.setPayloadText(results.toJsonString());
            }
            serviceMessage.setPayload(mp);
            logger.finest("About to send a final response to " + resultChannelTopic);
            resultChannel = FabricRegistry.homeNodeEndPoint.openOutputChannel(resultChannelTopic);
            resultChannel.write(serviceMessage.toWireBytes());
            FabricRegistry.homeNodeEndPoint.closeChannel(resultChannel, false);

        } else {
            // Returning to previous Node
            logger.finer("Preparing to return Partial Results for correlation Id " + correlationId + results.toString());

            String prevNode = returnNodeByCorrelationID.get(correlationId);

            // otherwise its a service return message
            logger.finest("Point to Point service response to " + prevNode);
            /* Create the service message */
            ServiceMessage serviceMessage = new ServiceMessage();

            /*
             * Set the service name: i.e. indicate that this is a message for the registry query service
             */
            serviceMessage.setServiceName(DistributedJDBCPersistence.SERVICE_NAME);

            /* Indicate that this is a built-in Fabric plug-in */
            serviceMessage.setServiceFamilyName(Fabric.FABRIC_PLUGIN_FAMILY);

            String[] route = {nodeName, prevNode};
            serviceMessage.setRouting(new StaticRouting(route));

            /* Set properties to get interim Node processing and notifications */
            serviceMessage.setActionEnRoute(false);
            serviceMessage.setNotification(false);
            serviceMessage.setAction(DistributedJDBCPersistence.PARTIAL_RESULT_ACTION);
            serviceMessage.setCorrelationID(correlationId);

            // Add result to service message
            MessagePayload mp = new MessagePayload();
            synchronized (results) {
                mp.setPayloadText(results.toJsonString());
            }
            serviceMessage.setPayload(mp);

            logger.finer("Sending results: " + serviceMessage.toXML());
            NeighbourChannels neighbourChannels = busIO.connectNeighbour(prevNode);
            if (neighbourChannels == null) {
                logger.fine("Failed to send partial result to " + prevNode);
            } else {

                try {
                    /* Forward the message */
                    neighbourChannels.registryBusChannel().write(serviceMessage.toWireBytes());
                } catch (Exception e) {
                    logger.finer("Problem with neighbour :\n" + e.getMessage());
                    // We should remove this channel from the channel list in case it caused the exception
                    // This will force it to be recreated if we need it in future.
                    busIO.disconnectNeighbour(neighbourChannels.neighbourDescriptor(), false);
                }
            }
        }

        // Clear out any record of this correlationID
        resultByCorrelationId.remove(correlationId);
        myCorrelationIds.remove(correlationId);
        returnNodeByCorrelationID.remove(correlationId);
        DistributedQueryWaitThread waitThread = waitThreadsByCorrelationId.remove(correlationId);
        if (waitThread != null) {
            waitThread.interrupt();
        }
    }

    private void returnEmptyResult(String correlationId, String prevNode) throws Exception {

        DistributedQueryResult results = new DistributedQueryResult(nodeName, null);
        // Returning to previous Node
        logger.finer("Preparing to return empty Results for correlation Id " + correlationId + results.toString());

        // otherwise its a service return message
        logger.finest("Point to Point service response to " + prevNode);
        /* Create the service message */
        ServiceMessage serviceMessage = new ServiceMessage();

        /*
         * Set the service name: i.e. indicate that this is a message for the registry query service
         */
        serviceMessage.setServiceName(DistributedJDBCPersistence.SERVICE_NAME);

        /* Indicate that this is a built-in Fabric plug-in */
        serviceMessage.setServiceFamilyName(Fabric.FABRIC_PLUGIN_FAMILY);

        String[] route = {nodeName, prevNode};
        serviceMessage.setRouting(new StaticRouting(route));

        /* Set properties to get interim Node processing and notifications */
        serviceMessage.setActionEnRoute(false);
        serviceMessage.setNotification(false);
        serviceMessage.setAction(DistributedJDBCPersistence.PARTIAL_RESULT_ACTION);
        serviceMessage.setCorrelationID(correlationId);

        // Add result to service message
        MessagePayload mp = new MessagePayload();
        synchronized (results) {
            mp.setPayloadText(results.toJsonString());
        }
        serviceMessage.setPayload(mp);

        logger.fine("Sending command: " + serviceMessage.toXML());
        NeighbourChannels neighbourChannels = busIO.connectNeighbour(prevNode);
        if (neighbourChannels == null) {
            logger.fine("Failed to send partial result to " + prevNode);
        } else {

            try {
                /* Forward the message */
                neighbourChannels.registryBusChannel().write(serviceMessage.toWireBytes());
            } catch (Exception e) {
                logger.finer("Problem with neighbour :\n" + e.getMessage());
                // We should remove this channel from the channel list in case it caused the exception
                // This will force it to be recreated if we need it in future.
                busIO.disconnectNeighbour(neighbourChannels.neighbourDescriptor(), false);
            }
        }
    }

}
