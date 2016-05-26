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

import java.io.IOException;
import java.util.Map;
import java.util.function.Consumer;

import org.jboss.logging.Logger;
import org.testng.Assert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;

/**
 * A wrapper for {@link OkHttpClient} that allows for fluent sending of requests and a fluent execution of common
 * assertions against reponses.
 *
 * @author <a href="https://github.com/ppalaga">Peter Palaga</a>
 */
public class TestClient {
    public class TestRequestBuilder {
        private final Request.Builder requestBuilder = new Request.Builder();

        public TestRequestBuilder(Map<String, String> defaultHeaders) {
            super();
            defaultHeaders.entrySet().forEach(e -> header(e.getKey(), e.getValue()));
        }

        public TestRequestBuilder addHeader(String name, String value) {
            requestBuilder.addHeader(name, value);
            return this;
        }

        public Request build() {
            return requestBuilder.build();
        }

        public TestResponse delete() throws IOException {
            Request request = requestBuilder.delete().build();
            log.tracef("About to execute request [%s]", request);
            Response response = client.newCall(request).execute();
            log.tracef("Got response [%s]", response);
            return new TestResponse(request, response);
        }

        public TestResponse get() throws Exception {
            Request request = requestBuilder.get().build();
            log.tracef("About to execute request [%s]", request);
            Response response = client.newCall(request).execute();
            log.tracef("Got response [%s]", response);
            return new TestResponse(request, response);
        }

        public TestResponse get(int expectedCode, int attemptCount, int retryAfterMs) throws Exception {
            Request request = requestBuilder.get().build();
            Exception lastException = null;
            for (int i = 0; i < attemptCount; i++) {
                lastException = null;
                try {
                    log.tracef("About to execute request [%s]", request);
                    Response response = client.newCall(request).execute();
                    log.tracef("Got response [%s]", response);
                    if (expectedCode == response.code()) {
                        return new TestResponse(request, response);
                    }
                } catch (Exception e) {
                    lastException = e;
                }
                Thread.sleep(retryAfterMs);
            }
            throw lastException;
        }

        public TestRequestBuilder header(String name, String value) {
            requestBuilder.header(name, value);
            return this;
        }

        public TestRequestBuilder path(String path) {
            requestBuilder.url(baseUri + path);
            return this;
        }

        public TestResponse postJson(String json) throws IOException {
            Request request = requestBuilder.post(RequestBody.create(MEDIA_TYPE_JSON, json)).build();
            log.tracef("About to execute request [%s]", request);
            Response response = client.newCall(request).execute();
            log.tracef("Got response [%s]", response);
            return new TestResponse(request, response);
        }

        public TestResponse postObject(Object payload) throws IOException {
            String json = mapper.writeValueAsString(payload);
            return postJson(json);
        }

        public TestRequestBuilder put(RequestBody body) {
            requestBuilder.put(body);
            return this;
        }

        public TestRequestBuilder url(String url) {
            requestBuilder.url(url);
            return this;
        }
    }

    public class TestResponse {
        private final Request request;

        private final Response response;

        public TestResponse(Request request, Response response) {
            super();
            this.request = request;
            this.response = response;
        }

        public JsonNode asJson() throws JsonProcessingException, IOException {
            String json = response.body().string();
            return mapper.readTree(json);
        }

        public <T> T asObject(Class<T> cls) throws IOException {
            String json = response.body().string();
            return mapper.readValue(json, cls);
        }

        public TestResponse assertCode(int code) {
            Assert.assertEquals(response.code(), code, "Unexpected response code for request [" + request + "]");
            return this;
        }

        public TestResponse assertJson(Consumer<JsonNode> assertion) throws JsonProcessingException, IOException {
            assertion.accept(asJson());
            return this;
        }

        public <T> TestResponse assertObject(Class<T> cls, Consumer<T> assertion)
                throws JsonProcessingException, IOException {
            assertion.accept(asObject(cls));
            return this;
        }

    }

    private static final Logger log = Logger.getLogger(TestClient.class);

    protected static final MediaType MEDIA_TYPE_JSON = MediaType.parse("application/json");

    private final String baseUri;
    private final OkHttpClient client;
    private final Map<String, String> defaultHeaders;
    private final ObjectMapper mapper;

    public TestClient(OkHttpClient client, ObjectMapper mapper, String baseUri,
            Map<String, String> defaultHeaders) {
        super();
        this.client = client;
        this.mapper = mapper;
        this.baseUri = baseUri;
        this.defaultHeaders = defaultHeaders;
    }

    public TestRequestBuilder newRequest() {
        return new TestRequestBuilder(defaultHeaders);
    }
}