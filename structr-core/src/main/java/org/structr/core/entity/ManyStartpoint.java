package org.structr.core.entity;

import java.util.LinkedHashSet;
import java.util.Set;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.helpers.Function;
import org.neo4j.helpers.collection.Iterables;
import org.structr.common.SecurityContext;
import org.structr.common.error.FrameworkException;
import org.structr.core.app.App;
import org.structr.core.app.StructrApp;
import org.structr.core.graph.NodeFactory;
import org.structr.core.graph.NodeInterface;

/**
 *
 * @author Christian Morgner
 */
public class ManyStartpoint<S extends NodeInterface> extends AbstractEndpoint implements Source<Iterable<Relationship>, Iterable<S>> {

	private Relation<S, ?, ManyStartpoint<S>, ?> relation = null;
	
	public ManyStartpoint(final Relation<S, ?, ManyStartpoint<S>, ?> relation) {
		this.relation = relation;
	}
	
	@Override
	public Iterable<S> get(final SecurityContext securityContext, final NodeInterface node) {
		
		final NodeFactory<S> nodeFactory  = new NodeFactory<>(securityContext);
		final Iterable<Relationship> rels = getRawSource(securityContext, node.getNode());
		
		if (rels != null) {
			
			return Iterables.map(nodeFactory, Iterables.map(new Function<Relationship, Node>() {

				@Override
				public Node apply(Relationship from) {
					return from.getStartNode();
				}
				
			}, rels));
		}
		
		return null;
	}

	@Override
	public void set(final SecurityContext securityContext, final NodeInterface targetNode, final Iterable<S> collection) throws FrameworkException {

		final App app            = StructrApp.getInstance(securityContext);
		final Set<S> toBeDeleted = new LinkedHashSet<>(Iterables.toList(get(securityContext, targetNode)));
		final Set<S> toBeCreated = new LinkedHashSet<>();

		if (collection != null) {
			Iterables.addAll(toBeCreated, collection);
		}

		// create intersection of both sets
		final Set<S> intersection = new LinkedHashSet<>(toBeCreated);
		intersection.retainAll(toBeDeleted);

		// intersection needs no change
		toBeCreated.removeAll(intersection);
		toBeDeleted.removeAll(intersection);
		
		// remove existing relationships
		for (S sourceNode : toBeDeleted) {

			for (AbstractRelationship rel : targetNode.getIncomingRelationships()) {

				if (rel.getRelType().equals(relation) && rel.getSourceNode().equals(sourceNode)) {

					app.delete(rel);
				}

			}
		}

		// create new relationships
		for (S sourceNode : toBeCreated) {

			relation.ensureCardinality(sourceNode, targetNode);

			app.create(sourceNode, targetNode, relation.getClass());
		}
	}

	@Override
	public Iterable<Relationship> getRawSource(final SecurityContext securityContext, final Node dbNode) {
		return getMultiple(securityContext, dbNode, relation, Direction.INCOMING, relation.getSourceType());
	}

	@Override
	public boolean hasElements(SecurityContext securityContext, Node dbNode) {
		return getRawSource(securityContext, dbNode).iterator().hasNext();
	}
}
