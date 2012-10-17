package com.nearinfinity.agent.connections.blur;

import java.util.Calendar;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.json.simple.JSONValue;
import org.springframework.jdbc.core.JdbcTemplate;

import com.nearinfinity.agent.connections.blur.interfaces.BlurDatabaseInterface;
import com.nearinfinity.agent.exceptions.TableCollisionException;
import com.nearinfinity.agent.exceptions.TableMissingException;
import com.nearinfinity.agent.exceptions.ZookeeperNameCollisionException;
import com.nearinfinity.agent.exceptions.ZookeeperNameMissingException;
import com.nearinfinity.agent.types.TimeHelper;
import com.nearinfinity.blur.thrift.generated.BlurQueryStatus;
import com.nearinfinity.blur.thrift.generated.SimpleQuery;

public class BlurDatabaseConnection implements BlurDatabaseInterface {

  private final JdbcTemplate jdbc;

  private final int expireThreshold = -2;
  private final int deleteThreshold = -2;

  public BlurDatabaseConnection(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public String getConnectionString(String zookeeperName) {
    String queryString = "select distinct c.node_name from controllers c, zookeepers z where z.name = ? and c.zookeeper_id = z.id and c.status = 1";
    List<String> controller_uris = jdbc.queryForList(queryString, new String[] { zookeeperName }, String.class);
    return StringUtils.join(controller_uris, ',');
  }

  @Override
  public String getZookeeperId(final String zookeeperName) throws ZookeeperNameMissingException, ZookeeperNameCollisionException {
    List<Map<String, Object>> zookeepers = jdbc.queryForList("select id from zookeepers where name = ?", zookeeperName);
    switch (zookeepers.size()) {
    case 0:
      throw new ZookeeperNameMissingException(zookeeperName);
    case 1:
      return zookeepers.get(0).get("ID").toString();
    default:
      throw new ZookeeperNameCollisionException(zookeepers.size(), zookeeperName);
    }
  }

  @Override
  public List<Map<String, Object>> getClusters(final int zookeeperId) {
    return jdbc.queryForList("select id, name from clusters where zookeeper_id = ?", zookeeperId);
  }

  @Override
  public Map<String, Object> getExistingTable(final String table, final Integer clusterId) throws TableMissingException,
      TableCollisionException {
    List<Map<String, Object>> existingTable = jdbc.queryForList(
        "select id, cluster_id from blur_tables where table_name=? and cluster_id=?", table, clusterId);
    switch (existingTable.size()) {
    case 0:
      throw new TableMissingException(table);
    case 1:
      return existingTable.get(0);
    default:
      throw new TableCollisionException(existingTable.size(), table);
    }
  }

  @Override
  public int getTableId(int clusterId, String tableName) {
    return jdbc.queryForInt("select id from blur_tables where cluster_id=? and table_name =?", clusterId, tableName);
  }

  @Override
  public void updateTableSchema(final int tableId, final String schema, final String tableAnalyzer) {
    jdbc.update("update blur_tables set table_schema=?, table_analyzer=? where id=?", new Object[] { schema, tableAnalyzer, tableId });
  }

  @Override
  public void updateTableServer(final int tableId, String server) {
    jdbc.update("update blur_tables set server=? where id=?", new Object[] { server, tableId });
  }

  @Override
  public void updateTableStats(final int tableId, Long tableBytes, Long tableQueries, Long tableRecordCount, Long tableRowCount) {
    jdbc.update("update blur_tables set current_size=?, query_usage=?, record_count=?, row_count=? where id=?", new Object[] { tableBytes,
        tableQueries, tableRecordCount, tableRowCount, tableId });
  }

  @Override
  public int deleteOldQueries() {
    Calendar now = TimeHelper.now();

    Calendar twoHoursAgo = Calendar.getInstance();
    twoHoursAgo.setTimeInMillis(now.getTimeInMillis());
    twoHoursAgo.add(Calendar.HOUR_OF_DAY, deleteThreshold);

    return this.jdbc.update("delete from blur_queries where created_at < ?", twoHoursAgo.getTime());
  }

  @Override
  public int expireOldQueries() {
    Calendar now = TimeHelper.now();

    Calendar twoMinutesAgo = Calendar.getInstance();
    twoMinutesAgo.setTimeInMillis(now.getTimeInMillis());
    twoMinutesAgo.add(Calendar.MINUTE, expireThreshold);

    return this.jdbc.update("update blur_queries set state=1, updated_at=? where updated_at < ? and state = 0", now.getTime(),
        twoMinutesAgo);
  }

  public Map<String, Object> getQuery(long UUID) {
    return this.jdbc.queryForMap("select id, complete_shards, times, state from blur_queries where blur_table_id=? and uuid=?", UUID);
  }

  public void createQuery(BlurQueryStatus status, SimpleQuery query, String times, long startTime, int tableId) {
    this.jdbc
        .update(
            "insert into blur_queries (query_string, times, complete_shards, total_shards, state, uuid, created_at, updated_at, blur_table_id, super_query_on, facets, start, fetch_num, pre_filters, post_filters, selector_column_families, selector_columns, userid, record_only) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            query.getQueryStr(),
            times,
            status.getCompleteShards(),
            status.getTotalShards(),
            status.getState().getValue(),
            status.getUuid(),
            startTime,
            TimeHelper.now().getTime(),
            tableId,
            query.isSuperQueryOn(),
            StringUtils.join(status.getQuery().getFacets(), ", "),
            status.getQuery().getStart(),
            status.getQuery().getFetch(),
            query.getPreSuperFilter(),
            query.getPostSuperFilter(),
            status.getQuery().getSelector() == null ? null : JSONValue.toJSONString(status.getQuery().getSelector()
                .getColumnFamiliesToFetch()),
            status.getQuery().getSelector() == null ? null : JSONValue.toJSONString(status.getQuery().getSelector().getColumnsToFetch()),
            status.getQuery().getUserContext(), status.getQuery().getSelector() == null ? null : status.getQuery().getSelector()
                .isRecordOnly());
  }

  public void updateQuery(BlurQueryStatus status, String times, int queryId) {
    jdbc.update("update blur_queries set times=?, complete_shards=?, total_shards=?, state=?, updated_at=? where id=?", times,
        status.getCompleteShards(), status.getTotalShards(), status.getState().getValue(), TimeHelper.now().getTime(), queryId);
  }

}
