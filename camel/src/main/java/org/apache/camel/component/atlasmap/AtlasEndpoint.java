/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.atlasmap;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.Message;
import org.apache.camel.component.ResourceEndpoint;
import org.apache.camel.spi.UriEndpoint;
import org.apache.camel.spi.UriParam;
import org.apache.camel.util.IOHelper;
import org.apache.camel.util.ObjectHelper;
import org.apache.camel.util.ResourceHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.atlasmap.api.AtlasContext;
import io.atlasmap.api.AtlasContextFactory;
import io.atlasmap.api.AtlasException;
import io.atlasmap.api.AtlasSession;
import io.atlasmap.core.AtlasMappingService.AtlasMappingFormat;
import io.atlasmap.core.DefaultAtlasContextFactory;
import io.atlasmap.v2.AtlasMapping;
import io.atlasmap.v2.Audit;
import io.atlasmap.v2.DataSource;
import io.atlasmap.v2.DataSourceType;

/**
 * Transforms the message using an AtlasMap transformation
 */
@UriEndpoint(firstVersion = "2.19.0", scheme = "atlas", title = "AtlasMap", syntax = "atlas:resourceUri", producerOnly = true, label = "transformation")
public class AtlasEndpoint extends ResourceEndpoint {

    private static final Logger LOG = LoggerFactory.getLogger(AtlasEndpoint.class);
    private AtlasContextFactory atlasContextFactory;
    private AtlasContext atlasContext;

    @UriParam(defaultValue = "true")
    private boolean loaderCache = true;
    @UriParam
    private String encoding;
    @UriParam
    private String propertiesFile;

    public AtlasEndpoint(String uri, AtlasComponent component, String resourceUri) {
        super(uri, component, resourceUri);
    }

    @Override
    public boolean isSingleton() {
        return true;
    }

    @Override
    public ExchangePattern getExchangePattern() {
        return ExchangePattern.InOut;
    }

    @Override
    protected String createEndpointUri() {
        return "atlas:" + getResourceUri();
    }

    public AtlasContextFactory getAtlasContextFactory() {
        return this.atlasContextFactory;
    }

    public void setAtlasContextFactory(AtlasContextFactory atlasContextFactory) {
        this.atlasContextFactory = atlasContextFactory;
    }

    public AtlasContext getAtlasContext() {
        return this.atlasContext;
    }

    public void setAtlasContext(AtlasContext atlasContext) {
        this.atlasContext = atlasContext;
    }

    public boolean isLoaderCache() {
        return loaderCache;
    }

    /**
     * Enables / disables the atlas map resource loader cache which is enabled by
     * default
     */
    public void setLoaderCache(boolean loaderCache) {
        this.loaderCache = loaderCache;
    }

    /**
     * Character encoding of the resource content.
     */
    public void setEncoding(String encoding) {
        this.encoding = encoding;
    }

    public String getEncoding() {
        return encoding;
    }

    /**
     * The URI of the properties file which is used for AtlasContextFactory
     * initialization.
     */
    public void setPropertiesFile(String file) {
        propertiesFile = file;
    }

    public String getPropertiesFile() {
        return propertiesFile;
    }

    public AtlasEndpoint findOrCreateEndpoint(String uri, String newResourceUri) {
        String newUri = uri.replace(getResourceUri(), newResourceUri);
        log.debug("Getting endpoint with URI: {}", newUri);
        return getCamelContext().getEndpoint(newUri, AtlasEndpoint.class);
    }

    @Override
    protected void onExchange(Exchange exchange) throws Exception {
        Message incomingMessage = exchange.getIn();
        String newResourceUri = incomingMessage.getHeader(AtlasConstants.ATLAS_RESOURCE_URI, String.class);
        if (newResourceUri != null) {
            incomingMessage.removeHeader(AtlasConstants.ATLAS_RESOURCE_URI);

            log.debug("{} set to {} creating new endpoint to handle exchange", AtlasConstants.ATLAS_RESOURCE_URI,
                    newResourceUri);
            AtlasEndpoint newEndpoint = findOrCreateEndpoint(getEndpointUri(), newResourceUri);
            newEndpoint.onExchange(exchange);
            return;
        }

        AtlasSession atlasSession = getOrCreateAtlasContext(incomingMessage).createSession();
        boolean convertToString = isStringConversionRequired(atlasSession.getMapping());
        Object body = incomingMessage.getBody();
        if (convertToString) {
            // read the whole stream into a String
            // the XML and JSON parsers expect that
            body = incomingMessage.getBody(String.class);
        }

        populateSourcePayload(atlasSession, body);
        getAtlasContext().process(atlasSession);

        List<Audit> errors = new ArrayList<>();
        for (Audit audit : atlasSession.getAudits().getAudit()) {
            switch (audit.getStatus()) {
            case ERROR:
                errors.add(audit);
                break;
            case WARN:
                LOG.warn("{}: docId='{}', path='{}'", audit.getMessage(), audit.getDocId(), audit.getPath());
                break;
            default:
                LOG.info("{}: docId='{}', path='{}'", audit.getMessage(), audit.getDocId(), audit.getPath());
            }
        }
        if (!errors.isEmpty()) {
            StringBuilder buf = new StringBuilder("Errors: ");
            errors.stream().forEach(a -> buf.append(
                    String.format("[%s: docId='%s', path='%s'], ", a.getMessage(), a.getDocId(), a.getPath())));
            throw new AtlasException(buf.toString());
        }

        // now lets output the results to the exchange
        Message out = exchange.getOut();
        out.setBody(getTargetPayload(atlasSession));
        out.setHeaders(incomingMessage.getHeaders());
        out.setAttachments(incomingMessage.getAttachments());
    }

    private AtlasContext getOrCreateAtlasContext(Message incomingMessage) throws Exception {
        String path = getResourceUri();
        ObjectHelper.notNull(path, "mappingUri");
        AtlasMappingFormat mappingFormat = path.toLowerCase().endsWith("json")
                ? AtlasMappingFormat.JSON : AtlasMappingFormat.XML;

        Reader reader;
        String content = incomingMessage.getHeader(AtlasConstants.ATLAS_MAPPING, String.class);
        if (content != null) {
            // use content from header
            reader = new StringReader(content);
            if (log.isDebugEnabled()) {
                log.debug("Atlas mapping content read from header {} for endpoint {}", AtlasConstants.ATLAS_MAPPING,
                        getEndpointUri());
            }
            // remove the header to avoid it being propagated in the routing
            incomingMessage.removeHeader(AtlasConstants.ATLAS_MAPPING);
            AtlasMapping mapping = ((DefaultAtlasContextFactory) getOrCreateAtlasContextFactory())
                                    .getMappingService()
                                    .loadMapping(reader, mappingFormat);
            return ((DefaultAtlasContextFactory) getOrCreateAtlasContextFactory()).createContext(mapping);
        } else if (getAtlasContext() != null) {
            // no mapping specified in header, and found an existing context
            return getAtlasContext();
        }

        // No mapping in header, and no existing context. Create new one from resourceUri
        if (log.isDebugEnabled()) {
            log.debug("Atlas mapping content read from resourceUri: {} for endpoint {}",
                    new Object[] { path, getEndpointUri() });
        }
        reader = getEncoding() != null ? new InputStreamReader(getResourceAsInputStream(), getEncoding())
                : new InputStreamReader(getResourceAsInputStream());
        AtlasMapping mapping = ((DefaultAtlasContextFactory) getOrCreateAtlasContextFactory())
                .getMappingService()
                .loadMapping(reader, mappingFormat);
        atlasContext = ((DefaultAtlasContextFactory) getOrCreateAtlasContextFactory()).createContext(mapping);
        return atlasContext;
    }

    private synchronized AtlasContextFactory getOrCreateAtlasContextFactory() throws Exception {
        if (atlasContextFactory == null) {

            Properties properties = new Properties();

            // load the properties from property file which may overrides the default ones
            if (ObjectHelper.isNotEmpty(getPropertiesFile())) {
                InputStream reader = ResourceHelper.resolveMandatoryResourceAsInputStream(getCamelContext(),
                        getPropertiesFile());
                try {
                    properties.load(reader);
                    log.info("Loaded the Atlas properties file " + getPropertiesFile());
                } finally {
                    IOHelper.close(reader, getPropertiesFile(), log);
                }
                log.debug("Initializing AtlasContextFactory with properties {}", properties);
                atlasContextFactory = new DefaultAtlasContextFactory(properties);
            } else {
                atlasContextFactory = DefaultAtlasContextFactory.getInstance();
            }
        }
        return atlasContextFactory;
    }

    private void populateSourcePayload(AtlasSession session, Object body) {
        // TODO handle headers docId - https://github.com/atlasmap/atlasmap/issues/67
        String docId = null;
        if (session.getMapping().getDataSource() != null) {
            Optional<DataSource> first = session.getMapping().getDataSource().stream()
                    .filter(ds -> ds.getDataSourceType() == DataSourceType.SOURCE)
                    .findFirst();
            docId = first.isPresent() ? first.get().getId() : null;
        }
        if (docId != null && !docId.isEmpty()) {
            session.setSourceDocument(docId, body);
        } else {
            session.setDefaultSourceDocument(body);
        }
    }

    private Object getTargetPayload(AtlasSession session) {
        // TODO handle headers docId - https://github.com/atlasmap/atlasmap/issues/67
        String docId = null;
        if (session.getMapping().getDataSource() != null) {
            Optional<DataSource> first = session.getMapping().getDataSource().stream()
                    .filter(ds -> ds.getDataSourceType() == DataSourceType.TARGET)
                    .findFirst();
            docId = first.isPresent() ? first.get().getId() : null;
        }
        if (docId != null && !docId.isEmpty()) {
            return session.getTargetDocument(docId);
        } else {
            return session.getDefaultTargetDocument();
        }
    }

    private boolean isStringConversionRequired(final AtlasMapping atlasMapping) {
        if (atlasMapping == null || atlasMapping.getDataSource() == null) {
            return false;
        }

        // TODO handle headers docId - https://github.com/atlasmap/atlasmap/issues/67
        Optional<DataSource> first = atlasMapping.getDataSource().stream()
                .filter(ds -> ds.getDataSourceType() == DataSourceType.SOURCE)
                .findFirst();
        return first.isPresent() && first.get().getUri() != null
                && !(first.get().getUri().startsWith("atlas:core")
                        || first.get().getUri().startsWith("atlas:java"));
    }

}