/*
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
package org.apache.jackrabbit.oak.plugins.index.solr.server;

import java.io.IOException;
import javax.annotation.Nonnull;

import org.apache.jackrabbit.oak.plugins.index.solr.configuration.SolrServerConfiguration;
import org.apache.jackrabbit.oak.plugins.index.solr.configuration.SolrServerConfigurationProvider;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.util.NamedList;

/**
 * An Oak {@link org.apache.solr.client.solrj.SolrServer}, caching a {@link org.apache.jackrabbit.oak.plugins.index.solr.server.SolrServerProvider}
 * for dispatching requests to indexing or searching specialized {@link org.apache.solr.client.solrj.SolrServer}s.
 */
public class OakSolrServer extends SolrServer {

    private final SolrServerConfiguration solrServerConfiguration;
    private final SolrServerProvider solrServerProvider;

    public OakSolrServer(@Nonnull SolrServerConfigurationProvider solrServerConfigurationProvider) {
        this.solrServerConfiguration = solrServerConfigurationProvider.getSolrServerConfiguration();
        try {
            this.solrServerProvider = solrServerConfiguration.getProvider();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public NamedList<Object> request(SolrRequest request) throws SolrServerException, IOException {
        try {

            SolrServer server = getServer(request);
            return server.request(request);

        } catch (Exception e) {
            throw new SolrServerException(e);
        }
    }

    private synchronized SolrServer getServer(SolrRequest request) throws Exception {
        boolean isIndex = request.getPath().contains("/update");
        SolrServerRegistry.Strategy strategy = isIndex ? SolrServerRegistry.Strategy.INDEXING : SolrServerRegistry.Strategy.SEARCHING;
        SolrServer solrServer = SolrServerRegistry.get(solrServerConfiguration, strategy);
        if (solrServer == null) {
            solrServer = isIndex ? solrServerProvider.getIndexingSolrServer() : solrServerProvider.getSearchingSolrServer();
            SolrServerRegistry.register(solrServerConfiguration, solrServer, strategy);
        }
        return solrServer;
    }

    @Override
    public void shutdown() {
        try {
            solrServerProvider.close();
        } catch (IOException e) {
            // do nothing
        }
    }
}
