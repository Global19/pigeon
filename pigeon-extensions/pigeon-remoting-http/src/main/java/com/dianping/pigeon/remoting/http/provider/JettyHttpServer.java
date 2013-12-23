/**
 * Dianping.com Inc.
 * Copyright (c) 2003-2013 All Rights Reserved.
 */
package com.dianping.pigeon.remoting.http.provider;

import org.apache.log4j.Logger;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.nio.SelectChannelConnector;
import org.mortbay.jetty.servlet.ServletHandler;
import org.mortbay.jetty.servlet.ServletHolder;
import org.mortbay.thread.QueuedThreadPool;

import com.dianping.pigeon.config.ConfigManager;
import com.dianping.pigeon.domain.phase.Disposable;
import com.dianping.pigeon.extension.ExtensionLoader;
import com.dianping.pigeon.log.LoggerLoader;
import com.dianping.pigeon.remoting.common.exception.RpcException;
import com.dianping.pigeon.remoting.http.HttpUtils;
import com.dianping.pigeon.remoting.provider.AbstractServer;
import com.dianping.pigeon.remoting.provider.config.ProviderConfig;
import com.dianping.pigeon.remoting.provider.config.ServerConfig;
import com.dianping.pigeon.util.NetUtils;

public class JettyHttpServer extends AbstractServer implements Disposable {

	protected final Logger logger = LoggerLoader.getLogger(this.getClass());
	private Server server;
	private int port;

	public JettyHttpServer() {
	}

	@Override
	public void destroy() {
	}

	@Override
	public boolean support(ServerConfig serverConfig) {
		if (serverConfig.getProtocols().contains("http")) {
			return true;
		}
		return false;
	}

	@Override
	public void doStart(ServerConfig serverConfig) {
		int availablePort = NetUtils.getAvailablePort(serverConfig.getPort() + 1000);
		port = availablePort;
		DispatcherServlet.addHttpHandler(port, new HttpServerHandler(this));

		QueuedThreadPool threadPool = new QueuedThreadPool();
		threadPool.setDaemon(true);
		threadPool.setMaxThreads(serverConfig.getMaxPoolSize());
		threadPool.setMinThreads(serverConfig.getCorePoolSize());

		SelectChannelConnector connector = new SelectChannelConnector();
		ConfigManager configManager = ExtensionLoader.getExtension(ConfigManager.class);
		connector.setHost(configManager.getLocalIp());
		connector.setPort(port);

		server = new Server(port);
		server.setThreadPool(threadPool);
		server.addConnector(connector);

		ServletHandler servletHandler = new ServletHandler();
		ServletHolder servletHolder = servletHandler.addServletWithMapping(DispatcherServlet.class, "/*");
		servletHolder.setInitOrder(2);

		server.addHandler(servletHandler);

		try {
			server.start();
		} catch (Exception e) {
			throw new IllegalStateException("failed to start jetty server on " + serverConfig.getPort() + ", cause: "
					+ e.getMessage(), e);
		}
	}

	@Override
	public void doStop() {
		if (server != null) {
			try {
				server.stop();
			} catch (Exception e) {
				logger.warn(e.getMessage(), e);
			}
		}
	}

	@Override
	public <T> void addService(ProviderConfig<T> providerConfig) throws RpcException {
	}

	@Override
	public String toString() {
		return "JettyServer-" + port;
	}

	@Override
	public int getPort() {
		return port;
	}

	@Override
	public String getRegistryUrl(String url) {
		return HttpUtils.getHttpServiceUrl(url);
	}
}
