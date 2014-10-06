/*
 * Licensed Materials - Property of IBM
 *  
 * (C) Copyright IBM Corp. 2014
 * 
 * LICENSE: Eclipse Public License v1.0
 * http://www.eclipse.org/legal/epl-v10.html
 */

package tests.common;

import java.io.IOException;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttPersistenceException;

import fabric.core.properties.ConfigProperties;

/**
 * 
 * TestClient for JSON messages to Edgware
 *
 */
public class FabricTestJsonClient implements MqttCallback {
	
	/** Copyright notice. */
	public static final String copyrightNotice = "(C) Copyright IBM Corp. 2014";
	
	private final static String PACKAGE_NAME = FabricTestJsonClient.class.getSimpleName();
	private final static Logger logger = Logger.getLogger(PACKAGE_NAME);

	/** Hold the returned messages in order received */
	LinkedBlockingQueue<byte[]> payloadsArrived = new LinkedBlockingQueue<byte[]>();
	
	private String nodeName = "default";
	private MqttClient mqttClient = null;
	private String clientID = null;
	private String sendToAdapterTopic = null;
	private String receiveFromAdapterTopic = null;
	protected String disconnectMessage = null;
	
	private boolean connected = false;

	/**
	 * Construct the TestJsonClient
	 * 
	 * @param props Contains Properties for configuring the client, must contain
	 *  {@link ConfigProperties.NODE_NAME}, {@link ConfigProperties.MQTT_REMOTE_PORT},
	 * broker.ipaddress, fabric.adapter.mqtt.intopic, fabric.adapter.mqtt.outtopic
	 * @param clientID Identity for the client
	 */
    public FabricTestJsonClient(Properties props, String clientID) throws IOException {
    	this.clientID = clientID;
    	this.nodeName = props.getProperty(ConfigProperties.NODE_NAME);
		sendToAdapterTopic = props.getProperty("fabric.adapters.mqtt.intopic") + "/" + this.clientID;
		receiveFromAdapterTopic = props.getProperty("fabric.adapters.mqtt.outtopic") + "/" + this.clientID;
		disconnectMessage = String.format("{\"op\":\"disconnect\",\"client-id\":\"%s\"}", this.clientID);
    	init(props.getProperty("broker.ipaddress"), props.getProperty(ConfigProperties.MQTT_REMOTE_PORT));
    }

    /**
     * Initialise the MqttClient.
     * 
     * @param nodeIp
     * @param port
     * @throws IOException
     */
    private void init(String nodeIp, String port) throws IOException {
		try {

			mqttClient = new MqttClient("tcp://" + nodeIp + ":" + port, clientID, null);
			mqttClient.setCallback(this);
			connect();
			subscribe();

		} catch (MqttException e) {

			throw new IOException(e.getMessage());
		}
	}
    
    /**
     * Connect to MQTT
     */
	protected void connect() {

		connected = false;

		while (!connected) {

			try {

				System.out.println("Connecting to broker...");
				MqttConnectOptions co = new MqttConnectOptions();
				co.setCleanSession(true);
				co.setKeepAliveInterval(60);
				co.setWill(sendToAdapterTopic, disconnectMessage.getBytes(), 2, false);
				mqttClient.connect(co);
				connected = true;
				System.out.println("Connected");

			} catch (Exception e) {

				System.out.println("MQTT connection failed: " + e);

				/* Wait before retrying */
				try {
					Thread.sleep(10000);
				} catch (InterruptedException e1) {
					System.out.println("Sleep interrupted: " + e1);
				}
			}
		}
	}

	/**
	 * Subscribe to relevant topics
	 */
	protected void subscribe() {

		boolean subscribed = false;

		while (!subscribed) {

			try {

				String[] topics = new String[] {receiveFromAdapterTopic};
				System.out.println("Subscribing to topics:");
				for (String topic : topics) {
					System.out.println(topic);
				}
				mqttClient.subscribe(topics, new int[] {2});
				subscribed = true;
				System.out.println("Subscribed.");

			} catch (Exception e) {

				System.out.println("MQTT subscription failed:\n" + e);

				/* Wait before retrying */
				try {
					Thread.sleep(5000);
				} catch (InterruptedException e1) {
					System.out.println("Sleep interrupted: " + e1);
				}

			}
		}
	}

    /**
     * publish a message to broker.address this clients fabric.adapters.mqtt.intopic
     * 
     * @param payload message for publication
     * @throws MqttPersistenceException
     * @throws MqttException
     */
	public void publish(String payload) throws MqttPersistenceException, MqttException {
		mqttClient.getTopic(sendToAdapterTopic).publish(payload.getBytes(), 2, false);		
	}
	

	@Override
	public void connectionLost(Throwable arg0) {
		connected = false;
		System.out.println("MQTT connection lost, re-trying...");
		connect();
		subscribe();
		System.out.println("Reconnected and resubscribed");
	}

	@Override
	public void deliveryComplete(IMqttDeliveryToken arg0) {
	}


	/**
	 * Simply push the 
	 */
	@Override
	public void messageArrived(String topic, MqttMessage message) throws Exception {
		payloadsArrived.add(message.getPayload());				
	}

	/**
	 * Get next response waiting up to timeout milliseconds if required
	 */
	public String getResponse(long timeout) throws InterruptedException {		
		byte[] responseBytes = payloadsArrived.poll(timeout, TimeUnit.MILLISECONDS);
		if (responseBytes!=null) {
			return new String(responseBytes);
		}
		else {
			return null;
		}
	}
	
	/**
	 * Is the TestJsonClient connected?
	 * @return
	 */
	public boolean isConnected() {
		return connected;
	}
}
