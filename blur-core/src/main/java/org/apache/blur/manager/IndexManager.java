package org.apache.blur.manager;

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
import static org.apache.blur.metrics.MetricsConstants.BLUR;
import static org.apache.blur.metrics.MetricsConstants.ORG_APACHE_BLUR;
import static org.apache.blur.thrift.util.BlurThriftHelper.findRecordMutation;
import static org.apache.blur.utils.BlurConstants.FAMILY;
import static org.apache.blur.utils.BlurConstants.PRIME_DOC;
import static org.apache.blur.utils.BlurConstants.RECORD_ID;
import static org.apache.blur.utils.BlurConstants.ROW_ID;
import static org.apache.blur.utils.RowDocumentUtil.getRecord;
import static org.apache.blur.utils.RowDocumentUtil.getRow;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLongArray;

import org.apache.blur.analysis.FieldManager;
import org.apache.blur.concurrent.Executors;
import org.apache.blur.index.ExitableReader;
import org.apache.blur.index.ExitableReader.ExitingReaderException;
import org.apache.blur.log.Log;
import org.apache.blur.log.LogFactory;
import org.apache.blur.lucene.search.FacetExecutor;
import org.apache.blur.lucene.search.FacetQuery;
import org.apache.blur.lucene.search.StopExecutionCollector.StopExecutionCollectorException;
import org.apache.blur.manager.clusterstatus.ClusterStatus;
import org.apache.blur.manager.results.BlurResultIterable;
import org.apache.blur.manager.results.BlurResultIterableSearcher;
import org.apache.blur.manager.results.MergerBlurResultIterable;
import org.apache.blur.manager.status.QueryStatus;
import org.apache.blur.manager.status.QueryStatusManager;
import org.apache.blur.manager.writer.BlurIndex;
import org.apache.blur.server.IndexSearcherClosable;
import org.apache.blur.server.ShardServerContext;
import org.apache.blur.server.TableContext;
import org.apache.blur.thrift.BException;
import org.apache.blur.thrift.MutationHelper;
import org.apache.blur.thrift.generated.BlurException;
import org.apache.blur.thrift.generated.BlurQuery;
import org.apache.blur.thrift.generated.BlurQueryStatus;
import org.apache.blur.thrift.generated.Column;
import org.apache.blur.thrift.generated.ErrorType;
import org.apache.blur.thrift.generated.Facet;
import org.apache.blur.thrift.generated.FetchResult;
import org.apache.blur.thrift.generated.FetchRowResult;
import org.apache.blur.thrift.generated.HighlightOptions;
import org.apache.blur.thrift.generated.QueryState;
import org.apache.blur.thrift.generated.Record;
import org.apache.blur.thrift.generated.RecordMutation;
import org.apache.blur.thrift.generated.RecordMutationType;
import org.apache.blur.thrift.generated.Row;
import org.apache.blur.thrift.generated.RowMutation;
import org.apache.blur.thrift.generated.RowMutationType;
import org.apache.blur.thrift.generated.ScoreType;
import org.apache.blur.thrift.generated.Selector;
import org.apache.blur.trace.Trace;
import org.apache.blur.trace.Tracer;
import org.apache.blur.utils.BlurExecutorCompletionService;
import org.apache.blur.utils.BlurExecutorCompletionService.Cancel;
import org.apache.blur.utils.BlurUtil;
import org.apache.blur.utils.ForkJoin;
import org.apache.blur.utils.ForkJoin.Merger;
import org.apache.blur.utils.ForkJoin.ParallelCall;
import org.apache.blur.utils.HighlightHelper;
import org.apache.blur.utils.ResetableDocumentStoredFieldVisitor;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.AtomicReader;
import org.apache.lucene.index.BaseCompositeReader;
import org.apache.lucene.index.BaseCompositeReaderUtil;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiFields;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.index.StoredFieldVisitor;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DocIdSet;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.highlight.InvalidTokenOffsetsException;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;

import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Meter;
import com.yammer.metrics.core.MetricName;
import com.yammer.metrics.core.Timer;
import com.yammer.metrics.core.TimerContext;

public class IndexManager {

  private static final String NOT_FOUND = "NOT_FOUND";
  private static final Log LOG = LogFactory.getLog(IndexManager.class);

  private final Meter _readRecordsMeter;
  private final Meter _readRowMeter;
  private final Meter _writeRecordsMeter;
  private final Meter _writeRowMeter;
  private final Meter _queriesExternalMeter;
  private final Meter _queriesInternalMeter;

  private final IndexServer _indexServer;
  private final ClusterStatus _clusterStatus;
  private final ExecutorService _executor;
  private final ExecutorService _mutateExecutor;

  private final QueryStatusManager _statusManager = new QueryStatusManager();
  private final AtomicBoolean _closed = new AtomicBoolean(false);
  private final BlurPartitioner _blurPartitioner = new BlurPartitioner();
  private final BlurFilterCache _filterCache;
  private final long _defaultParallelCallTimeout = TimeUnit.MINUTES.toMillis(1);

  private final Timer _fetchTimer;
  private final int _fetchCount;
  private final int _maxHeapPerRowFetch;

  private final int _threadCount;
  private final int _mutateThreadCount;

  public static AtomicBoolean DEBUG_RUN_SLOW = new AtomicBoolean(false);

  public IndexManager(IndexServer indexServer, ClusterStatus clusterStatus, BlurFilterCache filterCache,
      int maxHeapPerRowFetch, int fetchCount, int threadCount, int mutateThreadCount, long statusCleanupTimerDelay) {
    _indexServer = indexServer;
    _clusterStatus = clusterStatus;
    _filterCache = filterCache;

    MetricName metricName1 = new MetricName(ORG_APACHE_BLUR, BLUR, "Read Records/s");
    MetricName metricName2 = new MetricName(ORG_APACHE_BLUR, BLUR, "Read Row/s");
    MetricName metricName3 = new MetricName(ORG_APACHE_BLUR, BLUR, "Write Records/s");
    MetricName metricName4 = new MetricName(ORG_APACHE_BLUR, BLUR, "Write Row/s");
    MetricName metricName5 = new MetricName(ORG_APACHE_BLUR, BLUR, "External Queries/s");
    MetricName metricName6 = new MetricName(ORG_APACHE_BLUR, BLUR, "Internal Queries/s");
    MetricName metricName7 = new MetricName(ORG_APACHE_BLUR, BLUR, "Fetch Timer");

    _readRecordsMeter = Metrics.newMeter(metricName1, "Records/s", TimeUnit.SECONDS);
    _readRowMeter = Metrics.newMeter(metricName2, "Row/s", TimeUnit.SECONDS);
    _writeRecordsMeter = Metrics.newMeter(metricName3, "Records/s", TimeUnit.SECONDS);
    _writeRowMeter = Metrics.newMeter(metricName4, "Row/s", TimeUnit.SECONDS);
    _queriesExternalMeter = Metrics.newMeter(metricName5, "External Queries/s", TimeUnit.SECONDS);
    _queriesInternalMeter = Metrics.newMeter(metricName6, "Internal Queries/s", TimeUnit.SECONDS);
    _fetchTimer = Metrics.newTimer(metricName7, TimeUnit.MICROSECONDS, TimeUnit.SECONDS);

    if (threadCount == 0) {
      throw new RuntimeException("Thread Count cannot be 0.");
    }
    _threadCount = threadCount;
    if (mutateThreadCount == 0) {
      throw new RuntimeException("Mutate Thread Count cannot be 0.");
    }
    _mutateThreadCount = mutateThreadCount;
    _fetchCount = fetchCount;
    _maxHeapPerRowFetch = maxHeapPerRowFetch;

    _executor = Executors.newThreadPool("index-manager", _threadCount);
    _mutateExecutor = Executors.newThreadPool("index-manager-mutate", _mutateThreadCount);
    _statusManager.setStatusCleanupTimerDelay(statusCleanupTimerDelay);
    _statusManager.init();
    LOG.info("Init Complete");

  }

  public synchronized void close() {
    if (!_closed.get()) {
      _closed.set(true);
      _statusManager.close();
      _executor.shutdownNow();
      _mutateExecutor.shutdownNow();
      try {
        _indexServer.close();
      } catch (IOException e) {
        LOG.error("Unknown error while trying to close the index server", e);
      }
    }
  }

  public List<FetchResult> fetchRowBatch(final String table, List<Selector> selectors) throws BlurException {
    List<Future<FetchResult>> futures = new ArrayList<Future<FetchResult>>();
    for (Selector s : selectors) {
      final Selector selector = s;
      futures.add(_executor.submit(new Callable<FetchResult>() {
        @Override
        public FetchResult call() throws Exception {
          FetchResult fetchResult = new FetchResult();
          fetchRow(table, selector, fetchResult, false);
          return fetchResult;
        }
      }));
    }
    List<FetchResult> results = new ArrayList<FetchResult>();
    for (Future<FetchResult> future : futures) {
      try {
        results.add(future.get());
      } catch (InterruptedException e) {
        throw new BException("Unkown error while fetching batch table [{0}] selectors [{1}].", e, table, selectors);
      } catch (ExecutionException e) {
        throw new BException("Unkown error while fetching batch table [{0}] selectors [{1}].", e.getCause(), table,
            selectors);
      }
    }
    return results;
  }

  public void fetchRow(String table, Selector selector, FetchResult fetchResult) throws BlurException {
    fetchRow(table, selector, fetchResult, false);
  }

  public void fetchRow(String table, Selector selector, FetchResult fetchResult, boolean mutation) throws BlurException {
    validSelector(selector);
    TableContext tableContext = getTableContext(table);
    ReadInterceptor interceptor = tableContext.getReadInterceptor();
    Filter filter;
    if (mutation) {
      filter = interceptor.getFilterForMutation();
    } else {
      filter = interceptor.getFilter();
    }
    BlurIndex index = null;
    String shard = null;
    Tracer trace = Trace.trace("manager fetch", Trace.param("table", table));
    IndexSearcherClosable searcher = null;
    try {
      if (selector.getLocationId() == null) {
        // Not looking up by location id so we should resetSearchers.
        ShardServerContext.resetSearchers();
        shard = MutationHelper.getShardName(table, selector.rowId, getNumberOfShards(table), _blurPartitioner);
        index = getBlurIndex(table, shard);
        searcher = index.getIndexSearcher();
        populateSelector(searcher, shard, table, selector);
      }
      String locationId = selector.getLocationId();
      if (locationId.equals(NOT_FOUND)) {
        fetchResult.setDeleted(false);
        fetchResult.setExists(false);
        return;
      }
      if (shard == null) {
        shard = getShard(locationId);
      }
      if (index == null) {
        index = getBlurIndex(table, shard);
      }
    } catch (BlurException e) {
      throw e;
    } catch (Exception e) {
      LOG.error("Unknown error while trying to get the correct index reader for selector [{0}].", e, selector);
      throw new BException(e.getMessage(), e);
    }
    TimerContext timerContext = _fetchTimer.time();
    boolean usedCache = true;
    try {
      ShardServerContext shardServerContext = ShardServerContext.getShardServerContext();
      if (shardServerContext != null) {
        searcher = shardServerContext.getIndexSearcherClosable(table, shard);
      }
      if (searcher == null) {
        // Was not pulled from cache, get a fresh one from the index.
        searcher = index.getIndexSearcher();
        usedCache = false;
      }
      FieldManager fieldManager = tableContext.getFieldManager();

      Query highlightQuery = getHighlightQuery(selector, table, fieldManager);

      fetchRow(searcher.getIndexReader(), table, shard, selector, fetchResult, highlightQuery, fieldManager,
          _maxHeapPerRowFetch, tableContext, filter);

      if (fetchResult.rowResult != null) {
        if (fetchResult.rowResult.row != null && fetchResult.rowResult.row.records != null) {
          _readRecordsMeter.mark(fetchResult.rowResult.row.records.size());
        }
        _readRowMeter.mark();
      } else if (fetchResult.recordResult != null) {
        _readRecordsMeter.mark();
      }
    } catch (Exception e) {
      LOG.error("Unknown error while trying to fetch row.", e);
      throw new BException(e.getMessage(), e);
    } finally {
      trace.done();
      timerContext.stop();
      if (!usedCache && searcher != null) {
        // if the cached search was not used, close the searcher.
        // this will allow for closing of index
        try {
          searcher.close();
        } catch (IOException e) {
          LOG.error("Unknown error trying to call close on searcher [{0}]", e, searcher);
        }
      }
    }
  }

  private BlurIndex getBlurIndex(String table, String shard) throws BException, IOException {
    Map<String, BlurIndex> blurIndexes = _indexServer.getIndexes(table);
    if (blurIndexes == null) {
      LOG.error("Table [{0}] not found", table);
      // @TODO probably should make a enum for not found on this server so the
      // controller knows to try another server.
      throw new BException("Table [" + table + "] not found");
    }
    BlurIndex index = blurIndexes.get(shard);
    if (index == null) {
      LOG.error("Shard [{0}] not found in table [{1}]", shard, table);
      // @TODO probably should make a enum for not found on this server so the
      // controller knows to try another server.
      throw new BException("Shard [" + shard + "] not found in table [" + table + "]");
    }
    return index;
  }

  private Query getHighlightQuery(Selector selector, String table, FieldManager fieldManager) throws ParseException,
      BlurException {
    HighlightOptions highlightOptions = selector.getHighlightOptions();
    if (highlightOptions == null) {
      return null;
    }
    org.apache.blur.thrift.generated.Query query = highlightOptions.getQuery();
    if (query == null) {
      return null;
    }

    TableContext context = getTableContext(table);
    Filter preFilter = QueryParserUtil.parseFilter(table, query.recordFilter, false, fieldManager, _filterCache,
        context);
    Filter postFilter = QueryParserUtil.parseFilter(table, query.rowFilter, true, fieldManager, _filterCache, context);
    return QueryParserUtil.parseQuery(query.query, query.rowQuery, fieldManager, postFilter, preFilter,
        getScoreType(query.scoreType), context);
  }

  private void populateSelector(IndexSearcherClosable searcher, String shardName, String table, Selector selector)
      throws IOException, BlurException {
    Tracer trace = Trace.trace("populate selector");
    String rowId = selector.rowId;
    String recordId = selector.recordId;
    try {
      BooleanQuery query = new BooleanQuery();
      if (selector.recordOnly) {
        query.add(new TermQuery(new Term(RECORD_ID, recordId)), Occur.MUST);
        query.add(new TermQuery(new Term(ROW_ID, rowId)), Occur.MUST);
      } else {
        query.add(new TermQuery(new Term(ROW_ID, rowId)), Occur.MUST);
        query.add(new TermQuery(BlurUtil.PRIME_DOC_TERM), Occur.MUST);
      }
      TopDocs topDocs = searcher.search(query, 1);
      if (topDocs.totalHits > 1) {
        if (selector.recordOnly) {
          LOG.warn("Rowid [" + rowId + "], recordId [" + recordId
              + "] has more than one prime doc that is not deleted.");
        } else {
          LOG.warn("Rowid [" + rowId + "] has more than one prime doc that is not deleted.");
        }
      }
      if (topDocs.totalHits == 1) {
        selector.setLocationId(shardName + "/" + topDocs.scoreDocs[0].doc);
      } else {
        selector.setLocationId(NOT_FOUND);
      }
    } finally {
      trace.done();
    }
  }

  public static void validSelector(Selector selector) throws BlurException {
    String locationId = selector.locationId;
    String rowId = selector.rowId;
    String recordId = selector.recordId;
    boolean recordOnly = selector.recordOnly;

    if (locationId != null) {
      if (recordId != null && rowId != null) {
        throw new BException("Invalid selector locationId [" + locationId + "] and recordId [" + recordId
            + "] and rowId [" + rowId + "] are set, if using locationId, then rowId and recordId are not needed.");
      } else if (recordId != null) {
        throw new BException("Invalid selector locationId [" + locationId + "] and recordId [" + recordId
            + "] sre set, if using locationId recordId is not needed.");
      } else if (rowId != null) {
        throw new BException("Invalid selector locationId [" + locationId + "] and rowId [" + rowId
            + "] are set, if using locationId rowId is not needed.");
      }
    } else {
      if (rowId != null && recordId != null) {
        if (!recordOnly) {
          throw new BException("Invalid both rowid [" + rowId + "] and recordId [" + recordId
              + "] are set, and recordOnly is set to [false].  "
              + "If you want entire row, then remove recordId, if you want record only set recordOnly to [true].");
        }
      } else if (recordId != null) {
        throw new BException("Invalid recordId [" + recordId
            + "] is set but rowId is not set.  If rowId is not known then a query will be required.");
      }
    }
  }

  /**
   * Location id format is <shard>/luceneid.
   * 
   * @param locationId
   * @return
   */
  private String getShard(String locationId) {
    String[] split = locationId.split("\\/");
    if (split.length != 2) {
      throw new IllegalArgumentException("Location id invalid [" + locationId + "]");
    }
    return split[0];
  }

  public BlurResultIterable query(final String table, final BlurQuery blurQuery, AtomicLongArray facetedCounts)
      throws Exception {
    boolean runSlow = DEBUG_RUN_SLOW.get();
    final AtomicBoolean running = new AtomicBoolean(true);
    final QueryStatus status = _statusManager.newQueryStatus(table, blurQuery, _threadCount, running);
    _queriesExternalMeter.mark();
    try {
      Map<String, BlurIndex> blurIndexes;
      try {
        blurIndexes = _indexServer.getIndexes(table);
      } catch (IOException e) {
        LOG.error("Unknown error while trying to fetch index readers.", e);
        throw new BException(e.getMessage(), e);
      }
      Tracer trace = Trace.trace("query setup", Trace.param("table", table));
      ShardServerContext shardServerContext = ShardServerContext.getShardServerContext();
      ParallelCall<Entry<String, BlurIndex>, BlurResultIterable> call;
      TableContext context = getTableContext(table);
      FieldManager fieldManager = context.getFieldManager();
      org.apache.blur.thrift.generated.Query simpleQuery = blurQuery.query;
      Filter preFilter = QueryParserUtil.parseFilter(table, simpleQuery.recordFilter, false, fieldManager,
          _filterCache, context);
      Filter postFilter = QueryParserUtil.parseFilter(table, simpleQuery.rowFilter, true, fieldManager, _filterCache,
          context);
      Query userQuery = QueryParserUtil.parseQuery(simpleQuery.query, simpleQuery.rowQuery, fieldManager, postFilter,
          preFilter, getScoreType(simpleQuery.scoreType), context);

      Query facetedQuery;
      FacetExecutor executor = null;
      if (blurQuery.facets != null) {
        long[] facetMinimums = getFacetMinimums(blurQuery.facets);
        executor = new FacetExecutor(blurQuery.facets.size(), facetMinimums, facetedCounts);
        facetedQuery = new FacetQuery(userQuery, getFacetQueries(blurQuery, fieldManager, context, postFilter,
            preFilter), executor);
      } else {
        facetedQuery = userQuery;
      }

      ReadInterceptor interceptor = context.getReadInterceptor();
      call = new SimpleQueryParallelCall(running, table, status, facetedQuery, interceptor.getFilter(),
          blurQuery.selector, _queriesInternalMeter, shardServerContext, runSlow, _fetchCount, _maxHeapPerRowFetch,
          context.getSimilarity(), context);
      trace.done();
      MergerBlurResultIterable merger = new MergerBlurResultIterable(blurQuery);
      BlurResultIterable merge = ForkJoin.execute(_executor, blurIndexes.entrySet(), call, new Cancel() {
        @Override
        public void cancel() {
          running.set(false);
        }
      }).merge(merger);

      if (executor != null) {
        executor.processFacets(null);
      }
      return merge;
    } catch (StopExecutionCollectorException e) {
      BlurQueryStatus queryStatus = status.getQueryStatus();
      QueryState state = queryStatus.getState();
      if (state == QueryState.BACK_PRESSURE_INTERRUPTED) {
        throw new BlurException("Cannot execute query right now.", null, ErrorType.BACK_PRESSURE);
      } else if (state == QueryState.INTERRUPTED) {
        throw new BlurException("Cannot execute query right now.", null, ErrorType.QUERY_CANCEL);
      }
      throw e;
    } catch (ExitingReaderException e) {
      BlurQueryStatus queryStatus = status.getQueryStatus();
      QueryState state = queryStatus.getState();
      if (state == QueryState.BACK_PRESSURE_INTERRUPTED) {
        throw new BlurException("Cannot execute query right now.", null, ErrorType.BACK_PRESSURE);
      } else if (state == QueryState.INTERRUPTED) {
        throw new BlurException("Cannot execute query right now.", null, ErrorType.QUERY_CANCEL);
      }
      throw e;
    } finally {
      _statusManager.removeStatus(status);
    }
  }

  private long[] getFacetMinimums(List<Facet> facets) {
    long[] mins = new long[facets.size()];
    boolean smallerThanMaxLong = false;
    for (int i = 0; i < facets.size(); i++) {
      Facet facet = facets.get(i);
      if (facet != null) {
        long minimumNumberOfBlurResults = facet.getMinimumNumberOfBlurResults();
        mins[i] = minimumNumberOfBlurResults;
        if (minimumNumberOfBlurResults < Long.MAX_VALUE) {
          smallerThanMaxLong = true;
        }
      }
    }
    if (smallerThanMaxLong) {
      return mins;
    }
    return null;
  }

  public String parseQuery(String table, org.apache.blur.thrift.generated.Query simpleQuery) throws ParseException,
      BlurException {
    TableContext context = getTableContext(table);
    FieldManager fieldManager = context.getFieldManager();
    Filter preFilter = QueryParserUtil.parseFilter(table, simpleQuery.recordFilter, false, fieldManager, _filterCache,
        context);
    Filter postFilter = QueryParserUtil.parseFilter(table, simpleQuery.rowFilter, true, fieldManager, _filterCache,
        context);
    Query userQuery = QueryParserUtil.parseQuery(simpleQuery.query, simpleQuery.rowQuery, fieldManager, postFilter,
        preFilter, getScoreType(simpleQuery.scoreType), context);
    return userQuery.toString();
  }

  private TableContext getTableContext(final String table) {
    return TableContext.create(_clusterStatus.getTableDescriptor(true, _clusterStatus.getCluster(true, table), table));
  }

  private Query[] getFacetQueries(BlurQuery blurQuery, FieldManager fieldManager, TableContext context,
      Filter postFilter, Filter preFilter) throws ParseException {
    int size = blurQuery.facets.size();
    Query[] queries = new Query[size];
    for (int i = 0; i < size; i++) {
      queries[i] = QueryParserUtil.parseQuery(blurQuery.facets.get(i).queryStr, blurQuery.query.rowQuery, fieldManager,
          postFilter, preFilter, ScoreType.CONSTANT, context);
    }
    return queries;
  }

  private ScoreType getScoreType(ScoreType type) {
    if (type == null) {
      return ScoreType.SUPER;
    }
    return type;
  }

  public void cancelQuery(String table, String uuid) {
    _statusManager.cancelQuery(table, uuid);
  }

  public List<BlurQueryStatus> currentQueries(String table) {
    return _statusManager.currentQueries(table);
  }

  public BlurQueryStatus queryStatus(String table, String uuid) {
    return _statusManager.queryStatus(table, uuid);
  }

  public List<String> queryStatusIdList(String table) {
    return _statusManager.queryStatusIdList(table);
  }

  public static void fetchRow(IndexReader reader, String table, String shard, Selector selector,
      FetchResult fetchResult, Query highlightQuery, int maxHeap, TableContext tableContext, Filter filter)
      throws CorruptIndexException, IOException {
    fetchRow(reader, table, shard, selector, fetchResult, highlightQuery, null, maxHeap, tableContext, filter);
  }

  public static void fetchRow(IndexReader reader, String table, String shard, Selector selector,
      FetchResult fetchResult, Query highlightQuery, FieldManager fieldManager, int maxHeap, TableContext tableContext,
      Filter filter) throws CorruptIndexException, IOException {
    fetchResult.table = table;
    String locationId = selector.locationId;
    int lastSlash = locationId.lastIndexOf('/');
    int docId = Integer.parseInt(locationId.substring(lastSlash + 1));
    if (docId >= reader.maxDoc()) {
      throw new RuntimeException("Location id [" + locationId + "] with docId [" + docId + "] is not valid.");
    }

    boolean returnIdsOnly = false;
    if (selector.columnFamiliesToFetch != null && selector.columnsToFetch != null
        && selector.columnFamiliesToFetch.isEmpty() && selector.columnsToFetch.isEmpty()) {
      // exit early
      returnIdsOnly = true;
    }

    Tracer t1 = Trace.trace("fetchRow - live docs");
    Bits liveDocs = MultiFields.getLiveDocs(reader);
    t1.done();
    ResetableDocumentStoredFieldVisitor fieldVisitor = getFieldSelector(selector);
    if (selector.isRecordOnly()) {
      // select only the row for the given data or location id.
      if (isFiltered(docId, reader, filter)) {
        fetchResult.exists = false;
        fetchResult.deleted = false;
        return;
      } else if (liveDocs != null && !liveDocs.get(docId)) {
        fetchResult.exists = false;
        fetchResult.deleted = true;
        return;
      } else {
        fetchResult.exists = true;
        fetchResult.deleted = false;
        reader.document(docId, fieldVisitor);
        Document document = fieldVisitor.getDocument();
        if (highlightQuery != null && fieldManager != null) {
          HighlightOptions highlightOptions = selector.getHighlightOptions();
          String preTag = highlightOptions.getPreTag();
          String postTag = highlightOptions.getPostTag();
          try {
            document = HighlightHelper
                .highlight(docId, document, highlightQuery, fieldManager, reader, preTag, postTag);
          } catch (InvalidTokenOffsetsException e) {
            LOG.error("Unknown error while tring to highlight", e);
          }
        }
        fieldVisitor.reset();
        fetchResult.recordResult = getRecord(document);
        return;
      }
    } else {
      Tracer trace = Trace.trace("fetchRow - Row read");
      try {
        if (liveDocs != null && !liveDocs.get(docId)) {
          fetchResult.exists = false;
          fetchResult.deleted = true;
          return;
        } else {
          fetchResult.exists = true;
          fetchResult.deleted = false;
          if (returnIdsOnly) {
            String rowId = selector.getRowId();
            if (rowId == null) {
              rowId = getRowId(reader, docId);
            }
            Term term = new Term(ROW_ID, rowId);
            int recordCount = BlurUtil.countDocuments(reader, term);
            fetchResult.rowResult = new FetchRowResult();
            fetchResult.rowResult.row = new Row(rowId, null, recordCount);
          } else {
            List<Document> docs;
            if (highlightQuery != null && fieldManager != null) {
              String rowId = selector.getRowId();
              if (rowId == null) {
                rowId = getRowId(reader, docId);
              }
              Term term = new Term(ROW_ID, rowId);
              HighlightOptions highlightOptions = selector.getHighlightOptions();
              String preTag = highlightOptions.getPreTag();
              String postTag = highlightOptions.getPostTag();
              Tracer docTrace = Trace.trace("fetchRow - Document w/Highlight read");
              docs = HighlightHelper.highlightDocuments(reader, term, fieldVisitor, selector, highlightQuery,
                  fieldManager, preTag, postTag, filter);
              docTrace.done();
            } else {
              Tracer docTrace = Trace.trace("fetchRow - Document read");
              docs = BlurUtil.fetchDocuments(reader, fieldVisitor, selector, maxHeap, table + "/" + shard,
                  tableContext.getDefaultPrimeDocTerm(), filter);
              docTrace.done();
            }
            Tracer rowTrace = Trace.trace("fetchRow - Row create");
            fetchResult.rowResult = new FetchRowResult(getRow(docs));
            rowTrace.done();
          }
          return;
        }
      } finally {
        trace.done();
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static boolean isFiltered(int notAdjustedDocId, IndexReader reader, Filter filter) throws IOException {
    if (filter == null) {
      return false;
    }
    if (reader instanceof BaseCompositeReader) {
      BaseCompositeReader<IndexReader> indexReader = (BaseCompositeReader<IndexReader>) reader;
      List<? extends IndexReader> sequentialSubReaders = BaseCompositeReaderUtil.getSequentialSubReaders(indexReader);
      int readerIndex = BaseCompositeReaderUtil.readerIndex(indexReader, notAdjustedDocId);
      int readerBase = BaseCompositeReaderUtil.readerBase(indexReader, readerIndex);
      int docId = notAdjustedDocId - readerBase;
      IndexReader orgReader = sequentialSubReaders.get(readerIndex);
      SegmentReader sReader = BlurUtil.getSegmentReader(orgReader);
      if (sReader != null) {
        SegmentReader segmentReader = (SegmentReader) sReader;
        DocIdSet docIdSet = filter.getDocIdSet(segmentReader.getContext(), segmentReader.getLiveDocs());
        DocIdSetIterator iterator = docIdSet.iterator();
        if (iterator == null) {
          return true;
        }
        if (iterator.advance(docId) == docId) {
          return false;
        }
        return true;
      }
      throw new RuntimeException("Reader has to be a SegmentReader [" + orgReader + "]");
    } else {
      throw new RuntimeException("Reader has to be a BaseCompositeReader [" + reader + "]");
    }
  }

  private static String getRowId(IndexReader reader, int docId) throws CorruptIndexException, IOException {
    reader.document(docId, new StoredFieldVisitor() {
      @Override
      public Status needsField(FieldInfo fieldInfo) throws IOException {
        if (ROW_ID.equals(fieldInfo.name)) {
          return StoredFieldVisitor.Status.STOP;
        }
        return StoredFieldVisitor.Status.NO;
      }
    });
    return reader.document(docId).get(ROW_ID);
  }

  private static String getColumnName(String fieldName) {
    return fieldName.substring(fieldName.lastIndexOf('.') + 1);
  }

  private static String getColumnFamily(String fieldName) {
    return fieldName.substring(0, fieldName.lastIndexOf('.'));
  }

  public static ResetableDocumentStoredFieldVisitor getFieldSelector(final Selector selector) {
    return new ResetableDocumentStoredFieldVisitor() {
      @Override
      public Status needsField(FieldInfo fieldInfo) throws IOException {
        if (ROW_ID.equals(fieldInfo.name)) {
          return StoredFieldVisitor.Status.YES;
        }
        if (RECORD_ID.equals(fieldInfo.name)) {
          return StoredFieldVisitor.Status.YES;
        }
        if (PRIME_DOC.equals(fieldInfo.name)) {
          return StoredFieldVisitor.Status.NO;
        }
        if (FAMILY.equals(fieldInfo.name)) {
          return StoredFieldVisitor.Status.YES;
        }
        if (selector.columnFamiliesToFetch == null && selector.columnsToFetch == null) {
          return StoredFieldVisitor.Status.YES;
        }
        String columnFamily = getColumnFamily(fieldInfo.name);
        if (selector.columnFamiliesToFetch != null) {
          if (selector.columnFamiliesToFetch.contains(columnFamily)) {
            return StoredFieldVisitor.Status.YES;
          }
        }
        String columnName = getColumnName(fieldInfo.name);
        if (selector.columnsToFetch != null) {
          Set<String> columns = selector.columnsToFetch.get(columnFamily);
          if (columns != null && columns.contains(columnName)) {
            return StoredFieldVisitor.Status.YES;
          }
        }
        return StoredFieldVisitor.Status.NO;
      }

    };
  }

  public IndexServer getIndexServer() {
    return _indexServer;
  }

  public long recordFrequency(final String table, final String columnFamily, final String columnName, final String value)
      throws Exception {
    Map<String, BlurIndex> blurIndexes;
    try {
      blurIndexes = _indexServer.getIndexes(table);
    } catch (IOException e) {
      LOG.error("Unknown error while trying to fetch index readers.", e);
      throw new BException(e.getMessage(), e);
    }
    return ForkJoin.execute(_executor, blurIndexes.entrySet(), new ParallelCall<Entry<String, BlurIndex>, Long>() {
      @Override
      public Long call(Entry<String, BlurIndex> input) throws Exception {
        BlurIndex index = input.getValue();
        IndexSearcherClosable searcher = index.getIndexSearcher();
        try {
          return recordFrequency(searcher.getIndexReader(), columnFamily, columnName, value);
        } finally {
          // this will allow for closing of index
          searcher.close();
        }
      }
    }).merge(new Merger<Long>() {
      @Override
      public Long merge(BlurExecutorCompletionService<Long> service) throws BlurException {
        long total = 0;
        while (service.getRemainingCount() > 0) {
          Future<Long> future = service.poll(_defaultParallelCallTimeout, TimeUnit.MILLISECONDS, true, table,
              columnFamily, columnName, value);
          total += service.getResultThrowException(future, table, columnFamily, columnName, value);
        }
        return total;
      }
    });
  }

  public List<String> terms(final String table, final String columnFamily, final String columnName,
      final String startWith, final short size) throws Exception {
    Map<String, BlurIndex> blurIndexes;
    try {
      blurIndexes = _indexServer.getIndexes(table);
    } catch (IOException e) {
      LOG.error("Unknown error while trying to fetch index readers.", e);
      throw new BException(e.getMessage(), e);
    }
    return ForkJoin.execute(_executor, blurIndexes.entrySet(),
        new ParallelCall<Entry<String, BlurIndex>, List<String>>() {
          @Override
          public List<String> call(Entry<String, BlurIndex> input) throws Exception {
            BlurIndex index = input.getValue();
            IndexSearcherClosable searcher = index.getIndexSearcher();
            try {
              return terms(searcher.getIndexReader(), columnFamily, columnName, startWith, size);
            } finally {
              // this will allow for closing of index
              searcher.close();
            }
          }
        }).merge(new Merger<List<String>>() {
      @Override
      public List<String> merge(BlurExecutorCompletionService<List<String>> service) throws BlurException {
        TreeSet<String> terms = new TreeSet<String>();
        while (service.getRemainingCount() > 0) {
          Future<List<String>> future = service.poll(_defaultParallelCallTimeout, TimeUnit.MILLISECONDS, true, table,
              columnFamily, columnName, startWith, size);
          terms.addAll(service.getResultThrowException(future, table, columnFamily, columnName, startWith, size));
        }
        return new ArrayList<String>(terms).subList(0, Math.min(size, terms.size()));
      }
    });
  }

  public static long recordFrequency(IndexReader reader, String columnFamily, String columnName, String value)
      throws IOException {
    return reader.docFreq(getTerm(columnFamily, columnName, value));
  }

  public static List<String> terms(IndexReader reader, String columnFamily, String columnName, String startWith,
      short size) throws IOException {
    if (startWith == null) {
      startWith = "";
    }
    Term term = getTerm(columnFamily, columnName, startWith);
    List<String> terms = new ArrayList<String>(size);
    AtomicReader areader = BlurUtil.getAtomicReader(reader);
    Terms termsAll = areader.terms(term.field());

    if (termsAll == null) {
      return terms;
    }

    TermsEnum termEnum = termsAll.iterator(null);

    termEnum.seekCeil(term.bytes());

    BytesRef currentTermText = termEnum.term();
    do {
      terms.add(currentTermText.utf8ToString());
      if (terms.size() >= size) {
        return terms;
      }
    } while ((currentTermText = termEnum.next()) != null);
    return terms;
  }

  private static Term getTerm(String columnFamily, String columnName, String value) {
    if (columnName == null) {
      throw new NullPointerException("ColumnName cannot be null.");
    }
    if (columnFamily == null) {
      return new Term(columnName, value);
    }
    return new Term(columnFamily + "." + columnName, value);
  }

  public void mutate(final RowMutation mutation) throws BlurException, IOException {
    long s = System.nanoTime();
    doMutate(mutation);
    long e = System.nanoTime();
    LOG.debug("doMutate took [{0} ms] to complete", (e - s) / 1000000.0);
  }

  public void mutate(final List<RowMutation> mutations) throws BlurException, IOException {
    long s = System.nanoTime();
    doMutates(mutations);
    long e = System.nanoTime();
    LOG.debug("doMutates took [{0} ms] to complete", (e - s) / 1000000.0);
  }

  private void doMutates(List<RowMutation> mutations) throws BlurException, IOException {
    Map<String, List<RowMutation>> map = getMutatesPerTable(mutations);
    for (Entry<String, List<RowMutation>> entry : map.entrySet()) {
      doMutates(entry.getKey(), entry.getValue());
    }
  }

  private void doMutates(final String table, List<RowMutation> mutations) throws IOException, BlurException {
    final Map<String, BlurIndex> indexes = _indexServer.getIndexes(table);

    Map<String, List<RowMutation>> mutationsByShard = new HashMap<String, List<RowMutation>>();

    for (int i = 0; i < mutations.size(); i++) {
      RowMutation mutation = mutations.get(i);
      String shard = MutationHelper.getShardName(table, mutation.rowId, getNumberOfShards(table), _blurPartitioner);
      List<RowMutation> list = mutationsByShard.get(shard);
      if (list == null) {
        list = new ArrayList<RowMutation>();
        mutationsByShard.put(shard, list);
      }
      list.add(mutation);
    }

    List<Future<Void>> futures = new ArrayList<Future<Void>>();

    for (Entry<String, List<RowMutation>> entry : mutationsByShard.entrySet()) {
      final String shard = entry.getKey();
      final List<RowMutation> value = entry.getValue();
      futures.add(_mutateExecutor.submit(new Callable<Void>() {
        @Override
        public Void call() throws Exception {
          executeMutates(table, shard, indexes, value);
          return null;
        }
      }));
    }

    for (Future<Void> future : futures) {
      try {
        future.get();
      } catch (InterruptedException e) {
        throw new BException("Unknown error during mutation", e);
      } catch (ExecutionException e) {
        throw new BException("Unknown error during mutation", e.getCause());
      }
    }
  }

  private void executeMutates(String table, String shard, Map<String, BlurIndex> indexes, List<RowMutation> mutations)
      throws BlurException, IOException {
    long s = System.nanoTime();
    boolean waitToBeVisible = false;
    for (int i = 0; i < mutations.size(); i++) {
      RowMutation mutation = mutations.get(i);
      if (mutation.waitToBeVisible) {
        waitToBeVisible = true;
      }
      BlurIndex blurIndex = indexes.get(shard);
      if (blurIndex == null) {
        throw new BException("Shard [" + shard + "] in table [" + table + "] is not being served by this server.");
      }

      boolean waitVisiblity = false;
      if (i + 1 == mutations.size()) {
        waitVisiblity = waitToBeVisible;
      }
      RowMutationType type = mutation.rowMutationType;
      switch (type) {
      case REPLACE_ROW:
        Row row = MutationHelper.getRowFromMutations(mutation.rowId, mutation.recordMutations);
        blurIndex.replaceRow(waitVisiblity, mutation.wal, updateMetrics(row));
        break;
      case UPDATE_ROW:
        doUpdateRowMutation(mutation, blurIndex);
        break;
      case DELETE_ROW:
        blurIndex.deleteRow(waitVisiblity, mutation.wal, mutation.rowId);
        break;
      default:
        throw new RuntimeException("Not supported [" + type + "]");
      }
    }
    long e = System.nanoTime();
    LOG.debug("executeMutates took [" + (e - s) / 1000000.0 + " ms] to complete");
  }

  private Map<String, List<RowMutation>> getMutatesPerTable(List<RowMutation> mutations) {
    Map<String, List<RowMutation>> map = new HashMap<String, List<RowMutation>>();
    for (RowMutation mutation : mutations) {
      String table = mutation.table;
      List<RowMutation> list = map.get(table);
      if (list == null) {
        list = new ArrayList<RowMutation>();
        map.put(table, list);
      }
      list.add(mutation);
    }
    return map;
  }

  private void doMutate(RowMutation mutation) throws BlurException, IOException {
    String table = mutation.table;
    Map<String, BlurIndex> indexes = _indexServer.getIndexes(table);
    MutationHelper.validateMutation(mutation);
    String shard = MutationHelper.getShardName(table, mutation.rowId, getNumberOfShards(table), _blurPartitioner);
    BlurIndex blurIndex = indexes.get(shard);
    if (blurIndex == null) {
      throw new BException("Shard [" + shard + "] in table [" + table + "] is not being served by this server.");
    }

    RowMutationType type = mutation.rowMutationType;
    switch (type) {
    case REPLACE_ROW:
      Row row = MutationHelper.getRowFromMutations(mutation.rowId, mutation.recordMutations);
      blurIndex.replaceRow(mutation.waitToBeVisible, mutation.wal, updateMetrics(row));
      break;
    case UPDATE_ROW:
      doUpdateRowMutation(mutation, blurIndex);
      break;
    case DELETE_ROW:
      blurIndex.deleteRow(mutation.waitToBeVisible, mutation.wal, mutation.rowId);
      break;
    default:
      throw new RuntimeException("Not supported [" + type + "]");
    }
  }

  private Row updateMetrics(Row row) {
    _writeRowMeter.mark();
    List<Record> records = row.getRecords();
    if (records != null) {
      _writeRecordsMeter.mark(records.size());
    }
    return row;
  }

  private void doUpdateRowMutation(RowMutation mutation, BlurIndex blurIndex) throws BlurException, IOException {
    FetchResult fetchResult = new FetchResult();
    Selector selector = new Selector();
    selector.setRowId(mutation.rowId);
    fetchRow(mutation.table, selector, fetchResult, true);
    Row existingRow;
    if (fetchResult.exists) {
      // We will examine the contents of the existing row and add records
      // onto a new replacement row based on the mutation we have been given.
      existingRow = fetchResult.rowResult.row;
    } else {
      // The row does not exist, create empty new row.
      existingRow = new Row().setId(mutation.getRowId());
      existingRow.records = new ArrayList<Record>();
    }
    Row newRow = new Row().setId(existingRow.id);

    // Create a local copy of the mutation we can modify
    RowMutation mutationCopy = mutation.deepCopy();

    // Match existing records against record mutations. Once a record
    // mutation has been processed, remove it from our local copy.
    for (Record existingRecord : existingRow.records) {
      RecordMutation recordMutation = findRecordMutation(mutationCopy, existingRecord);
      if (recordMutation != null) {
        mutationCopy.recordMutations.remove(recordMutation);
        doUpdateRecordMutation(recordMutation, existingRecord, newRow);
      } else {
        // Copy existing records over to the new row unmodified if there
        // is no matching mutation.
        newRow.addToRecords(existingRecord);
      }
    }

    // Examine all remaining record mutations. For any record replacements
    // we need to create a new record in the table even though an existing
    // record did not match. Record deletions are also ok here since the
    // record is effectively already deleted. Other record mutations are
    // an error and should generate an exception.
    for (RecordMutation recordMutation : mutationCopy.recordMutations) {
      RecordMutationType type = recordMutation.recordMutationType;
      switch (type) {
      case DELETE_ENTIRE_RECORD:
        // do nothing as missing record is already in desired state
        break;
      case APPEND_COLUMN_VALUES:
      case REPLACE_ENTIRE_RECORD:
      case REPLACE_COLUMNS:
        // If record do not exist, create new record in Row
        newRow.addToRecords(recordMutation.record);
        break;
      default:
        throw new RuntimeException("Unsupported record mutation type [" + type + "]");
      }
    }

    // Finally, replace the existing row with the new row we have built.
    blurIndex.replaceRow(mutation.waitToBeVisible, mutation.wal, updateMetrics(newRow));

  }

  private static void doUpdateRecordMutation(RecordMutation recordMutation, Record existingRecord, Row newRow) {
    Record mutationRecord = recordMutation.record;
    switch (recordMutation.recordMutationType) {
    case DELETE_ENTIRE_RECORD:
      return;
    case APPEND_COLUMN_VALUES:
      for (Column column : mutationRecord.columns) {
        if (column.getValue() == null) {
          continue;
        }
        existingRecord.addToColumns(column);
      }
      newRow.addToRecords(existingRecord);
      break;
    case REPLACE_ENTIRE_RECORD:
      newRow.addToRecords(mutationRecord);
      break;
    case REPLACE_COLUMNS:
      Set<String> removeColumnNames = new HashSet<String>();
      for (Column column : mutationRecord.getColumns()) {
        removeColumnNames.add(column.getName());
      }

      for (Column column : existingRecord.getColumns()) {
        // skip columns in existing record that are contained in the mutation
        // record
        if (!removeColumnNames.contains(column.getName())) {
          mutationRecord.addToColumns(column);
        }
      }
      newRow.addToRecords(mutationRecord);
      break;
    default:
      break;
    }
  }

  private int getNumberOfShards(String table) {
    return getTableContext(table).getDescriptor().getShardCount();
  }

  static class SimpleQueryParallelCall implements ParallelCall<Entry<String, BlurIndex>, BlurResultIterable> {

    private final String _table;
    private final QueryStatus _status;
    private final Query _query;
    private final Selector _selector;
    private final AtomicBoolean _running;
    private final Meter _queriesInternalMeter;
    private final ShardServerContext _shardServerContext;
    private final boolean _runSlow;
    private final int _fetchCount;
    private final int _maxHeapPerRowFetch;
    private final Similarity _similarity;
    private final TableContext _context;
    private final Filter _filter;

    public SimpleQueryParallelCall(AtomicBoolean running, String table, QueryStatus status, Query query, Filter filter,
        Selector selector, Meter queriesInternalMeter, ShardServerContext shardServerContext, boolean runSlow,
        int fetchCount, int maxHeapPerRowFetch, Similarity similarity, TableContext context) {
      _running = running;
      _table = table;
      _status = status;
      _query = query;
      _selector = selector;
      _queriesInternalMeter = queriesInternalMeter;
      _shardServerContext = shardServerContext;
      _runSlow = runSlow;
      _fetchCount = fetchCount;
      _maxHeapPerRowFetch = maxHeapPerRowFetch;
      _similarity = similarity;
      _context = context;
      _filter = filter;
    }

    @Override
    public BlurResultIterable call(Entry<String, BlurIndex> entry) throws Exception {
      String shard = entry.getKey();
      _status.attachThread(shard);
      BlurIndex index = entry.getValue();
      IndexSearcherClosable searcher = index.getIndexSearcher();
      Tracer trace2 = null;
      try {
        IndexReader indexReader = searcher.getIndexReader();
        if (indexReader instanceof ExitableReader) {
          ExitableReader er = (ExitableReader) indexReader;
          er.setRunning(_running);
        } else {
          throw new IOException("IndexReader is not ExitableReader");
        }
        if (_shardServerContext != null) {
          _shardServerContext.setIndexSearcherClosable(_table, shard, searcher);
        }
        searcher.setSimilarity(_similarity);
        Tracer trace1 = Trace.trace("query rewrite", Trace.param("table", _table));
        Query rewrite;
        try {
          rewrite = searcher.rewrite((Query) _query.clone());
        } catch (ExitingReaderException e) {
          LOG.info("Query [{0}] has been cancelled during the rewrite phase.", _query);
          throw e;
        } finally {
          trace1.done();
        }

        // BlurResultIterableSearcher will close searcher, if shard server
        // context is null.
        trace2 = Trace.trace("query initial search");
        return new BlurResultIterableSearcher(_running, rewrite, _table, shard, searcher, _selector,
            _shardServerContext == null, _runSlow, _fetchCount, _maxHeapPerRowFetch, _context, _filter);
      } catch (BlurException e) {
        switch (_status.getQueryStatus().getState()) {
        case INTERRUPTED:
          e.setErrorType(ErrorType.QUERY_CANCEL);
          throw e;
        case BACK_PRESSURE_INTERRUPTED:
          e.setErrorType(ErrorType.BACK_PRESSURE);
          throw e;
        default:
          e.setErrorType(ErrorType.UNKNOWN);
          throw e;
        }
      } finally {
        if (trace2 != null) {
          trace2.done();
        }
        _queriesInternalMeter.mark();
        _status.deattachThread(shard);
      }
    }
  }

  public void optimize(String table, int numberOfSegmentsPerShard) throws BException {
    Map<String, BlurIndex> blurIndexes;
    try {
      blurIndexes = _indexServer.getIndexes(table);
    } catch (IOException e) {
      LOG.error("Unknown error while trying to fetch index readers.", e);
      throw new BException(e.getMessage(), e);
    }

    Collection<BlurIndex> values = blurIndexes.values();
    for (BlurIndex index : values) {
      try {
        index.optimize(numberOfSegmentsPerShard);
      } catch (IOException e) {
        LOG.error("Unknown error while trying to optimize indexes.", e);
        throw new BException(e.getMessage(), e);
      }
    }
  }

}
