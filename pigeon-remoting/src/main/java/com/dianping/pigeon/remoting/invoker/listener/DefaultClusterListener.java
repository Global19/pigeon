/**
 * Dianping.com Inc.
 * Copyright (c) 2003-2013 All Rights Reserved.
 */
package com.dianping.pigeon.remoting.invoker.listener;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;

import com.dianping.dpsf.exception.NetException;
import com.dianping.pigeon.extension.ExtensionLoader;
import com.dianping.pigeon.remoting.invoker.Client;
import com.dianping.pigeon.remoting.invoker.ClientFactory;
import com.dianping.pigeon.remoting.invoker.component.ConnectInfo;
import com.dianping.pigeon.threadpool.DefaultThreadFactory;

public class DefaultClusterListener implements ClusterListener {

	private static final Logger logger = Logger
			.getLogger(DefaultClusterListener.class);

	private Map<String, List<Client>> serviceClients = new ConcurrentHashMap<String, List<Client>>();

	private Map<ConnectInfo, Client> allClients = new ConcurrentHashMap<ConnectInfo, Client>();

	private HeartBeatListener heartTask;

	private ReconnectListener reconnectTask;

	private ScheduledThreadPoolExecutor closeExecutor = new ScheduledThreadPoolExecutor(
			5, new DefaultThreadFactory("Pigeon-Client-Cache-Close-ThreadPool"));

	private ClusterListenerManager clusterListenerManager = ClusterListenerManager
			.getInstance();

	public DefaultClusterListener(HeartBeatListener heartTask,
			ReconnectListener reconnectTask) {
		this.heartTask = heartTask;
		this.reconnectTask = reconnectTask;
		this.heartTask.setWorkingClients(this.serviceClients);
	}

	public void clear() {

		serviceClients = new ConcurrentHashMap<String, List<Client>>();
		allClients = new ConcurrentHashMap<ConnectInfo, Client>();
	}

	public List<Client> getClientList(String serviceName) {
		List<Client> clientList = this.serviceClients.get(serviceName);
		if (clientList == null || clientList.size() == 0) {
			throw new NetException("no available connection for service:"
					+ serviceName);
		}
		return clientList;
	}

	public synchronized void addConnect(ConnectInfo cmd) {
		addConnect(cmd, this.allClients.get(cmd));
	}

	public void addConnect(ConnectInfo cmd, Client client) {
		if (clientExisted(cmd)) {
			if (client != null) {
				for (List<Client> clientList : serviceClients.values()) {
					int idx = clientList.indexOf(client);
					if (idx >= 0 && clientList.get(idx) != client) { // FIXME
																		// equals
																		// but
																		// no ==
						closeClientInFuture(client);
					}
				}
			} else {
				return;
			}
		}

		if (client == null) {
			client = ExtensionLoader.getExtension(ClientFactory.class)
					.createClient(cmd);
		}

		if (!this.allClients.containsKey(cmd)) {
			this.allClients.put(cmd, client);
		}
		try {
			if (!client.isConnected()) {
				client.connect();
			}

			if (client.isConnected()) {
				String serviceName = client.getServiceName();
				List<Client> clientList = this.serviceClients.get(serviceName);
				if (clientList == null) {
					clientList = new ArrayList<Client>();

					this.serviceClients.put(serviceName, clientList);

				}
				if (!clientList.contains(client)) {

					clientList.add(client);
				}
				// }
			} else {
				clusterListenerManager.removeConnect(client);
			}

		} catch (NetException e) {
			logger.error(e.getMessage(), e);
		}
	}

	/**
	 * 检查是否已经有cmd对应的Client，避免重复添加Client
	 * 
	 * @param cmd
	 * @return
	 */
	private boolean clientExisted(ConnectInfo cmd) {
		boolean existed = true;
		List<Client> clientList = serviceClients.get(cmd.getServiceName());
		if (clientList == null) {
			return false;

		}
		boolean findClient = false;
		for (Client client : clientList) {
			if (client.getAddress().equals(cmd.getConnect())
					&& client.getServiceName().equals(cmd.getServiceName())) {
				findClient = true;
			}
		}
		if (!findClient) {
			return false;

		}
		// }
		return existed;
	}

	public synchronized void removeConnect(Client client) {
		String connect = client.getConnectInfo().getConnect();
		Client clientRemoved = this.allClients.remove(connect);
		if (clientRemoved != null) {
			for (String serviceName : this.serviceClients.keySet()) {
				List<Client> clientList = this.serviceClients.get(serviceName);
				if (clientList != null && clientList.contains(client)) {
					clientList.remove(client);
				}
			}
		}

	}

	@Override
	public synchronized void doNotUse(String serviceName, String host, int port) {
		List<Client> cs = serviceClients.get(serviceName);
		List<Client> newCS = new ArrayList<Client>(cs);
		Client clientFound = null;
		for (Client client : cs) {
			if (client.getHost().equals(host) && client.getPort() == port) {
				newCS.remove(client);
				clientFound = client;
			}
		}
		serviceClients.put(serviceName, newCS);

		// 一个client可能对应多个serviceName，仅当client不被任何serviceName使用时才关闭
		if (clientFound != null) {
			if (!isClientInUse(clientFound)) {
				removeClientFromReconnectTask(clientFound);
				allClients.remove(clientFound.getAddress());
				closeClientInFuture(clientFound);
			}
		}
	}

	// move to HeartTask?
	private void removeClientFromReconnectTask(Client clientToRemove) {
		Map<String, Client> closedClients = reconnectTask.getClosedClients();
		Set<String> keySet = closedClients.keySet();
		Iterator<String> iterator = keySet.iterator();
		while (iterator.hasNext()) {
			String connect = iterator.next();
			if (closedClients.get(connect).equals(clientToRemove)) {
				iterator.remove();
			}
		}
	}

	private boolean isClientInUse(Client clientToFind) {
		for (List<Client> clientList : serviceClients.values()) {
			if (clientList.contains(clientToFind)) {
				return true;
			}
		}
		return false;
	}

	private void closeClientInFuture(final Client client) {

		Runnable command = new Runnable() {

			@Override
			public void run() {
				client.close();
			}

		};

		try {
			String waitTimeStr = System
					.getProperty("com.dianping.pigeon.invoker.closewaittime");
			int waitTime = 3000;
			if (waitTimeStr != null) {
				try {
					waitTime = Integer.parseInt(waitTimeStr);
				} catch (Exception e) {
					logger.error(
							"error parsing com.dianping.pigeon.invoker.closewaittime",
							e);
				}
			}
			if (waitTime < 0) {
				waitTime = 3000;
			}
			closeExecutor.schedule(command, waitTime, TimeUnit.MILLISECONDS);
		} catch (Exception e) {
			logger.error("error schedule task to close client", e);
		}
	}

}