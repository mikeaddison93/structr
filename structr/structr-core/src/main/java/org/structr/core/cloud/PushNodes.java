/*
 *  Copyright (C) 2011 Axel Morgner, structr <structr@structr.org>
 * 
 *  This file is part of structr <http://structr.org>.
 * 
 *  structr is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 * 
 *  structr is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 * 
 *  You should have received a copy of the GNU General Public License
 *  along with structr.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.structr.core.cloud;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryonet.Client;
import com.esotericsoftware.kryonet.Connection;
import com.esotericsoftware.kryonet.Listener;
import com.esotericsoftware.minlog.Log;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.codec.digest.DigestUtils;
import org.structr.core.Command;
import org.structr.core.Services;
import org.structr.core.UnsupportedArgumentError;
import org.structr.core.entity.AbstractNode;
import org.structr.core.entity.File;
import org.structr.core.entity.StructrRelationship;
import org.structr.core.entity.SuperUser;
import org.structr.core.node.FindNodeCommand;
import org.structr.core.notification.AddNotificationCommand;
import org.structr.core.notification.ProgressBarNotification;

/**
 *
 * @author axel
 */
public class PushNodes extends CloudServiceCommand
{
	private static final Logger logger = Logger.getLogger(PushNodes.class.getName());

	@Override
	public Object execute(Object... parameters)
	{
		String userName = null;
		String password = null;
		AbstractNode sourceNode = null;
		long remoteTargetNodeId = 0L;
		String remoteHost = null;
		int remoteTcpPort = 0;
		int remoteUdpPort = 0;
		boolean recursive = false;

		Command findNode = Services.command(FindNodeCommand.class);

		switch(parameters.length)
		{
			case 0:
				throw new UnsupportedArgumentError("No arguments supplied");

			case 8:

				// username
				if(parameters[0] instanceof String)
				{
					userName = (String)parameters[0];
				}

				// password
				if(parameters[1] instanceof String)
				{
					password = (String)parameters[1];
				}

				// source node
				if(parameters[2] instanceof Long)
				{
					long id = ((Long)parameters[2]).longValue();
					sourceNode = (AbstractNode)findNode.execute(null, id);

				} else if(parameters[2] instanceof AbstractNode)
				{
					sourceNode = ((AbstractNode)parameters[2]);

				} else if(parameters[2] instanceof String)
				{
					long id = Long.parseLong((String)parameters[2]);
					sourceNode = (AbstractNode)findNode.execute(null, id);
				}

				// target node
				if(parameters[3] instanceof Long)
				{
					remoteTargetNodeId = ((Long)parameters[3]).longValue();
				}

				if(parameters[4] instanceof String)
				{
					remoteHost = (String)parameters[4];
				}

				if(parameters[5] instanceof Integer)
				{
					remoteTcpPort = (Integer)parameters[5];
				}

				if(parameters[6] instanceof Integer)
				{
					remoteUdpPort = (Integer)parameters[6];
				}

				if(parameters[7] instanceof Boolean)
				{
					recursive = (Boolean)parameters[7];
				}

				pushNodes(userName, password, sourceNode, remoteTargetNodeId, remoteHost, remoteTcpPort, remoteUdpPort, recursive);
				break;

			default:
				break;
		}

		return null;
	}

	private void pushNodes(final String userName, String password, final AbstractNode sourceNode, final long remoteTargetNodeId, final String remoteHost, final int remoteTcpPort, final int remoteUdpPort, final boolean recursive)
	{
		final SynchronizingListener listener = new SynchronizingListener();
		int chunkSize = CloudService.CHUNK_SIZE;
		int writeBufferSize = CloudService.BUFFER_SIZE * 4;
		int objectBufferSize = CloudService.BUFFER_SIZE * 2;

		Client client = new Client(writeBufferSize, objectBufferSize);
		client.addListener(listener);
		client.start();

		Log.set(CloudService.KRYONET_LOG_LEVEL);

		Kryo kryo = client.getKryo();
		CipherProvider cipherProvider = new CipherProviderImpl(null);
		CloudService.registerClasses(kryo, cipherProvider);

		// add GUI notification
		StringBuilder titleBuffer = new StringBuilder();
		titleBuffer.append("Transmission to ").append(remoteHost).append(":").append(remoteTcpPort);
		ProgressBarNotification progressNotification = new ProgressBarNotification(titleBuffer.toString());
		Services.command(AddNotificationCommand.class).execute(progressNotification);
		
		// enable notifications to be passed to UI
		listener.setNotification(progressNotification);
		
		try
		{

			// connect
			client.connect(10000, remoteHost, remoteTcpPort, remoteUdpPort);

			// mark start of transaction
			client.sendTCP(CloudService.BEGIN_TRANSACTION);
			
			// send authentication container
			client.sendTCP(new AuthenticationContainer(userName));
			
			// enable encryption after handshake
			cipherProvider.enableEncryption(DigestUtils.sha512Hex(password));

			// send type of request
			client.sendTCP(new PushNodeRequestContainer(remoteTargetNodeId));

			// send child nodes when recursive sending is requested
			if(recursive)
			{
				List<AbstractNode> nodes = sourceNode.getAllChildrenForRemotePush(new SuperUser()); // FIXME: use real user here

				// FIXME: were collecting the nodes twice here, the first time is only for counting..
				progressNotification.setTargetProgress(sourceNode.getRemotePushSize(new SuperUser(), chunkSize));

				for(AbstractNode n : nodes)
				{
					if(n instanceof File)
					{
						sendFile(client, listener, (File)n, chunkSize, progressNotification);

					} else
					{
						NodeDataContainer container = new NodeDataContainer(n);
						client.sendTCP(container);

						progressNotification.increaseProgress();
					}
				}

				for(AbstractNode n : nodes)
				{
					// Collect all relationships whose start and end nodes are contained in the above list
					List<StructrRelationship> rels = n.getOutgoingRelationships();
					for(StructrRelationship r : rels)
					{

						if(nodes.contains(r.getStartNode()) && nodes.contains(r.getEndNode()))
						{
							client.sendTCP(new RelationshipDataContainer(r));
							progressNotification.increaseProgress();
						}
					}
				}

			} else
			{
				// send start node
				if(sourceNode instanceof File)
				{
					sendFile(client, listener, (File)sourceNode, chunkSize, progressNotification);

				} else
				{
					// If not recursive, add only the node itself
					client.sendTCP(new NodeDataContainer(sourceNode));
					progressNotification.increaseProgress();
				}

			}

			// mark end of transaction
			client.sendTCP(CloudService.END_TRANSACTION);


		} catch(IOException ex)
		{
			progressNotification.setErrorMessage("Transmission failed");
			
			logger.log(Level.SEVERE, "Error while sending nodes to remote instance", ex.getMessage());
		}

	}

	/**
	 * Splits the given file and sends it over the client connection. This
	 * method first creates a <code>FileNodeDataContainer</code> and sends
	 * it to the remote end. The file from disk is then split into multiple
	 * instances of <code>FileChunkContainer</code> while being sent. To
	 * finalize the transfer, a <code>FileNodeEndChunk</code> is sent to
	 * notify the receiving end of the successful transfer.
	 *
	 * @param client the client to send over
	 * @param file the file to split and send
	 * @param chunkSize the chunk size for a single chunk
	 *
	 * @return the number of objects that have been sent over the network
	 */
	private void sendFile(Client client, final SynchronizingListener listener, File file, int chunkSize, ProgressBarNotification progressNotification)
	{
		// send file container first
		FileNodeDataContainer container = new FileNodeDataContainer(file);
		client.sendTCP(container);
		progressNotification.increaseProgress();

		// send chunks
		for(FileNodeChunk chunk : FileNodeDataContainer.getChunks(file, chunkSize))
		{
			try
			{
				client.sendTCP(chunk);

				// wait for remote end to confirm transmission
				listener.waitFor(chunk.getSequenceNumber());

			} catch(Throwable t)
			{
				break;
			}

			progressNotification.increaseProgress();
		}

		// mark end of file with special chunk
		client.sendTCP(new FileNodeEndChunk(container.getSourceNodeId(), container.getFileSize()));
		progressNotification.increaseProgress();
	}

	private class SynchronizingListener extends Listener
	{
		private ProgressBarNotification notification = null;
		private String errorMessage = null;
		private long lastReceived = 0;
		private int currentValue = 0;
		
		@Override
		public void received(Connection connection, Object object)
		{
			if(object instanceof Integer)
			{
				Integer value = (Integer)object;
				currentValue = value.intValue();

				lastReceived = System.currentTimeMillis();
				
			} else if(object instanceof String) {
				
				errorMessage = object.toString();
			}
		}
		
		@Override
		public void disconnected(Connection connection) {

			if(this.notification != null) {
				
				if(errorMessage == null) {
					
					errorMessage = "Disconnected";
				}
				
				this.notification.setErrorMessage(errorMessage);
			}
		}
		
		public void setNotification(ProgressBarNotification notification) {
			
			this.notification = notification;
		}

		public void waitFor(int sequenceNumber)
		{
			try
			{
				// wait for at most 10 seconds for arrival of correct sequence number
				while(currentValue != sequenceNumber && System.currentTimeMillis() < lastReceived + TimeUnit.SECONDS.toMillis(10))
				{
					Thread.yield();
				}

			} catch(Throwable t)
			{
				logger.log(Level.WARNING, "Exception while waiting for sequence number {0}: {1}", new Object[] { sequenceNumber, t } );
			}
		}
	}
}
