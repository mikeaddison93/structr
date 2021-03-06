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
package org.structr.core.log;

import org.fusesource.hawtdb.api.BTreeIndexFactory;
import org.fusesource.hawtdb.api.IndexFactory;
import org.fusesource.hawtdb.api.MultiIndexFactory;
import org.fusesource.hawtdb.api.SortedIndex;
import org.fusesource.hawtdb.api.Transaction;
import org.fusesource.hawtdb.api.TxPageFile;

import org.structr.common.error.FrameworkException;

//~--- JDK imports ------------------------------------------------------------

import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

//~--- classes ----------------------------------------------------------------

/**
 * Simple and fast key/value logger.
 * 
 * The execute method takes two parameters: A key (String) and a String[] with arbitrary data.
 * Each log entry gets a unique id (UUID).
 * 
 * @author Axel Morgner
 */
public class WriteLogCommand extends LogServiceCommand {

	private static final Logger logger = Logger.getLogger(WriteLogCommand.class.getName());

	//~--- methods --------------------------------------------------------

	public void execute(String key, String... obj) throws FrameworkException {

		TxPageFile logDb = (TxPageFile) arguments.get("logDb");

		if (logDb != null) {

			if (key != null) {

				synchronized (logDb) {

					Transaction tx                            = logDb.tx();
					MultiIndexFactory multiIndexFactory       = new MultiIndexFactory(tx);
					IndexFactory<String, Object> indexFactory = new BTreeIndexFactory<>();
					SortedIndex<String, Object> index         = null;

					try {

						index = (SortedIndex<String, Object>) multiIndexFactory.openOrCreate(key, indexFactory);

					} catch (Throwable t) {

						t.printStackTrace();
						
						logger.log(Level.WARNING, "Could not open or create log db page for key {0}", key);

						index = (SortedIndex<String, Object>) multiIndexFactory.create(key, indexFactory);

					}

					String uuid  = UUID.randomUUID().toString().replaceAll("[\\-]+", "");

					index.put(uuid, obj);

					// disabled to avoid parameter evaluation 
					// logger.log(Level.FINE, "Logged for key {0}: {1}", new Object[] { key, StringUtils.join((String[]) obj, ",") });
					
					tx.commit();
					logDb.flush();

				}

			}
		}
	}

}
