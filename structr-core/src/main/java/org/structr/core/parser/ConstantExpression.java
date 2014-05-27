package org.structr.core.parser;

import org.structr.common.error.FrameworkException;
import org.structr.core.GraphObject;
import org.structr.schema.action.ActionContext;

/**
 *
 * @author Christian Morgner
 */
public class ConstantExpression extends Expression {

	private Object value = null;

	public ConstantExpression(final Object value) {
		super(null);

		this.value = value;
	}

	public Object getValue() {
		return value;
	}

	@Override
	public Object evaluate(final ActionContext ctx, final GraphObject entity) throws FrameworkException {
		return value;
	}
}
