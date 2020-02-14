/**
 * Copyright (C) 2017 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atlasmap.itests.core.issue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.atlasmap.api.AtlasContext;
import io.atlasmap.api.AtlasSession;
import io.atlasmap.core.AtlasMappingService;
import io.atlasmap.core.DefaultAtlasContextFactory;
import io.atlasmap.itests.core.TestHelper;
import io.atlasmap.v2.AtlasMapping;
import org.junit.Before;
import org.junit.Test;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * https://github.com/atlasmap/atlasmap/issues/233
 */
public class Atlasmap1746Test {

    private AtlasMappingService mappingService;

    @Before
    public void before() {
        mappingService = DefaultAtlasContextFactory.getInstance().getMappingService();
    }

    @Test
    public void test() throws Exception {
        URL url = Thread.currentThread().getContextClassLoader().getResource("mappings/issue/atlasmap-1746-mapping.json");
        AtlasMapping mapping = mappingService.loadMapping(url);
        AtlasContext context = DefaultAtlasContextFactory.getInstance().createContext(mapping);
        AtlasSession session = context.createSession();
        url = Thread.currentThread().getContextClassLoader().getResource("mappings/issue/atlasmap-1746-source.xml");
        session.setSourceDocument("MsgFromATLASRs", new String(Files.readAllBytes(Paths.get(url.toURI()))));
        context.process(session);
        assertFalse(TestHelper.printAudit(session), session.hasErrors());
        Object outputJson = session.getTargetDocument("MsgFromATLASRs");
        assertNotNull("target json document was null", outputJson);
        ObjectMapper om = new ObjectMapper();
        JsonNode expected = om.readTree(Thread.currentThread().getContextClassLoader().getResource("mappings/issue/atlasmap-1746-target.json"));
        JsonNode actual = om.readTree((String)outputJson);
        assertEquals(expected.toPrettyString(), actual.toPrettyString());
    }

}
