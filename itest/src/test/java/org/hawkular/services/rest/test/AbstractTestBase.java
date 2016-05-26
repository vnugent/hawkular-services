/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hawkular.services.rest.test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.hawkular.inventory.json.InventoryJacksonConfig;
import org.jboss.arquillian.testng.Arquillian;
import org.testng.annotations.BeforeMethod;

import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.AnnotationIntrospectorPair;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;
import com.fasterxml.jackson.module.jaxb.JaxbAnnotationIntrospector;
import com.squareup.okhttp.Credentials;
import com.squareup.okhttp.OkHttpClient;

/**
 * A base for the integration tests.
 *
 * @author <a href="https://github.com/ppalaga">Peter Palaga</a>
 */
public class AbstractTestBase extends Arquillian {

    protected static final String authHeader;
    protected static final String baseUri;
    protected static final OkHttpClient client;
    protected static final String host;
    protected static final ObjectMapper mapper;
    protected static final String testPasword = System.getProperty("hawkular.itest.rest.password");
    protected static final String testUser = System.getProperty("hawkular.itest.rest.user");
    private static final Random random = new Random();

    static {
        authHeader = Credentials.basic(testUser, testPasword);

        String h = System.getProperty("hawkular.bind.address", "localhost");
        if ("0.0.0.0".equals(h)) {
            h = "localhost";
        }
        host = h;
        int portOffset = Integer.parseInt(System.getProperty("hawkular.port.offset", "0"));
        int httpPort = portOffset + 8080;

        baseUri = "http://" + host + ":" + httpPort;

        client = new OkHttpClient();
        client.setConnectTimeout(60, TimeUnit.SECONDS);
        client.setReadTimeout(60, TimeUnit.SECONDS);
        client.setWriteTimeout(60, TimeUnit.SECONDS);

        mapper = new ObjectMapper();
        AnnotationIntrospector jacksonIntrospector = new JacksonAnnotationIntrospector();
        AnnotationIntrospector jaxbIntrospector = new JaxbAnnotationIntrospector(mapper.getTypeFactory());
        AnnotationIntrospector introspectorPair = new AnnotationIntrospectorPair(jacksonIntrospector, jaxbIntrospector);
        mapper.setAnnotationIntrospector(introspectorPair);
        InventoryJacksonConfig.configure(mapper);

    }

    public static TestClient newClient(String tenantId) {
        final Map<String, String> defaultHeaders = Collections.unmodifiableMap(new HashMap<String, String>(){/**  */
            private static final long serialVersionUID = 1L;
        {
            put("Authorization", authHeader);
            put("Accept", "application/json");
            put("Hawkular-Tenant", tenantId);
        }});
        return new TestClient(client, mapper, baseUri, defaultHeaders);
    }


    protected TestClient testClient;

    @BeforeMethod
    public void beforeTest(Method method) {
        String tenantId = method.getDeclaringClass().getSimpleName() + "." + method.getName() + "." + random.nextInt();
        this.testClient = newClient(tenantId);
    }

}
