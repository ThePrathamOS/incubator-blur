package org.apache.blur.agent.cleaners;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import org.apache.blur.agent.connections.cleaners.interfaces.HdfsDatabaseCleanerInterface;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.dao.DataAccessException;


public class HdfsStatsCleaner implements Runnable {
	private static final Log log = LogFactory.getLog(QueriesCleaner.class);

	private final HdfsDatabaseCleanerInterface database;

	public HdfsStatsCleaner(HdfsDatabaseCleanerInterface database) {
		this.database = database;
	}

	@Override
	public void run() {
		try {
			this.database.deleteOldStats();
		} catch (DataAccessException e) {
			log.error("An error occured while deleting hdfs stats from the database!", e);
		} catch (Exception e) {
			log.error("An unkown error occured while cleaning up the hdfs stats!", e);
		}
	}
}