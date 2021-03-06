/**
 * Copyright (C) 2010-2014 Morgner UG (haftungsbeschränkt)
 *
 * This file is part of Structr <http://structr.org>.
 *
 * Structr is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * Structr is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Structr.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.structr.core.graph;

import org.structr.common.error.FrameworkException;
import org.structr.core.GraphObject;
import org.structr.core.Transformation;
import org.structr.core.entity.AbstractNode;
import org.structr.core.entity.Principal;

//~--- JDK imports ------------------------------------------------------------

import java.util.Collection;
import java.util.Date;
import java.util.Map.Entry;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.neo4j.graphdb.GraphDatabaseService;
import org.structr.common.Permission;
import org.structr.core.app.App;
import org.structr.core.app.StructrApp;
import org.structr.core.entity.Security;
import org.structr.core.entity.relationship.PrincipalOwnsNode;
import org.structr.core.property.PropertyKey;
import org.structr.core.property.PropertyMap;
import org.structr.schema.SchemaHelper;

//~--- classes ----------------------------------------------------------------

/**
 * Creates a new node in the database with the given properties.
 *
 * @author Christian Morgner
 */
public class CreateNodeCommand<T extends NodeInterface> extends NodeServiceCommand {

	private static final Logger logger = Logger.getLogger(CreateNodeCommand.class.getName());

	public T execute(Collection<NodeAttribute<?>> attributes) throws FrameworkException {

		PropertyMap properties = new PropertyMap();
		for (NodeAttribute attribute : attributes) {

			properties.put(attribute.getKey(), attribute.getValue());
		}

		return execute(properties);

	}

	public T execute(NodeAttribute<?>... attributes) throws FrameworkException {

		PropertyMap properties = new PropertyMap();
		for (NodeAttribute attribute : attributes) {

			properties.put(attribute.getKey(), attribute.getValue());
		}

		return execute(properties);
	}

	public T execute(PropertyMap attributes) throws FrameworkException {

		GraphDatabaseService graphDb = (GraphDatabaseService) arguments.get("graphDb");
		Principal user               = securityContext.getUser(false);
		T node	                     = null;

		if (graphDb != null) {

			Date now                            = new Date();

			// Determine node type
			PropertyMap properties     = new PropertyMap(attributes);
			Object typeObject          = properties.get(AbstractNode.type);
			Class nodeType             = typeObject != null ? SchemaHelper.getEntityClassForRawType(typeObject.toString()) : StructrApp.getConfiguration().getFactoryDefinition().getGenericNodeType();
			NodeFactory<T> nodeFactory = new NodeFactory<>(securityContext);
			boolean isCreation         = true;

			// Create node with type
			node = (T) nodeFactory.instantiateWithType(graphDb.createNode(), nodeType, isCreation);
			if(node != null) {

				TransactionCommand.nodeCreated(node);

				// set type
				if (nodeType != null) {

					node.unlockReadOnlyPropertiesOnce();
					node.setProperty(GraphObject.type, nodeType.getSimpleName());
				}

				// set UUID
				node.unlockReadOnlyPropertiesOnce();
				node.setProperty(GraphObject.id, getNextUuid());

				// set created date
				node.unlockReadOnlyPropertiesOnce();
				node.setProperty(AbstractNode.createdDate, now);

				// set last modified date
				node.unlockReadOnlyPropertiesOnce();
				node.setProperty(AbstractNode.lastModifiedDate, now);

				// properties.remove(AbstractNode.type);

				if ((user != null) && user instanceof AbstractNode) {

					// Create new relationship to user and grant permissions to user or group
					Principal owner = (Principal)user;

					App app = StructrApp.getInstance(securityContext);

					app.create(owner, (NodeInterface)node, PrincipalOwnsNode.class);

					Security securityRel = app.create(owner, (NodeInterface)node, Security.class);
					securityRel.setAllowed(Permission.values());

					node.unlockReadOnlyPropertiesOnce();
					node.setProperty(AbstractNode.createdBy, user.getProperty(GraphObject.id));
				}

				for (Entry<PropertyKey, Object> attr : properties.entrySet()) {

					Object value = attr.getValue();
					PropertyKey key = attr.getKey();
					if (key.isReadOnly()) {
						node.unlockReadOnlyPropertiesOnce();
					}
					node.setProperty(key, value);

				}

				properties.clear();
			}

		}

		if (node != null) {

			// notify node of its creation
			node.onNodeCreation();

			// iterate post creation transformations
			Set<Transformation<GraphObject>> transformations = StructrApp.getConfiguration().getEntityCreationTransformations(node.getClass());
			for (Transformation<GraphObject> transformation : transformations) {

				transformation.apply(securityContext, node);

			}

			if (transformations.isEmpty()) {
				logger.log(Level.FINE, "No entity creation transformation for {0}", node.getClass());
			}
		}

		return node;
	}
}
