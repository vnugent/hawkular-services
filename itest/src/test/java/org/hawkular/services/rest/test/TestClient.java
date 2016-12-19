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
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.jboss.logging.Logger;
import org.testng.Assert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * A wrapper for {@link OkHttpClient} that allows for fluent sending of requests and a fluent execution of common
 * assertions against reponses.
 *
 * @author <a href="https://github.com/ppalaga">Peter Palaga</a>
 */
public class TestClient {

    public static class Retry {
        public static Retry none() {
            return new Retry(0, 500);
        }

        public static Retry times(int times) {
            return new Retry(times, 500);
        }
        private final int delayMs;
        private final int times;
        public Retry(int times, int delayMs) {
            super();
            this.times = times;
            this.delayMs = delayMs;
        }
        public Retry delay(int delayMs) {
            return new Retry(this.times, delayMs);
        }

        public <T> T retry(Supplier<T> supplier) {
            Throwable lastException = null;
            for (int i = 0; i < times; i++) {
                try {
                    Thread.sleep(delayMs);
                    log.tracef("Retry[%d]", i);
                    return supplier.get();
                } catch (Throwable e) {
                    lastException = e;
                }
            }
            String msg = String.format(
                    "No success getting [%s] after trying [%d] times with delay [%d] ms",
                    supplier, times, delayMs);
            if (lastException == null) {
                throw new AssertionError(msg);
            } else {
                throw new AssertionError(msg, lastException);
            }
        }
    }

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

        public TestResponse putJson(String json) throws IOException {
            Request request = requestBuilder.put(RequestBody.create(MEDIA_TYPE_JSON, json)).build();
            log.tracef("About to execute request [%s]", request);
            Response response = client.newCall(request).execute();
            log.tracef("Got response [%s]", response);
            return new TestResponse(request, response);
        }

        public TestResponse putObject(Object payload) throws IOException {
            String json = mapper.writeValueAsString(payload);
            return putJson(json);
        }

        public TestRequestBuilder url(HttpUrl url) {
            requestBuilder.url(url);
            return this;
        }

        public TestRequestBuilder url(String url) {
            requestBuilder.url(url);
            return this;
        }
    }

    public class TestResponse {
        private String bodyString;
        private final Request request;
        private final Response response;

        public TestResponse(Request request, Response response) {
            super();
            this.request = request;
            this.response = response;
        }

        public JsonNode asJson() {
            String json = asString();
            try {
                return mapper.readTree(json);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public Stream<JsonNode> asJsonStream() {
            JsonNode json = asJson();
            Assert.assertTrue(json.isArray() || json.isObject(),
                    String.format("Request [%s] should have returned a json array or object, while it returned [%s]",
                            request, json.getNodeType()));
            Spliterator<JsonNode> jsonSpliterator = Spliterators.spliteratorUnknownSize(json.elements(), 0);
            return StreamSupport.stream(jsonSpliterator, false);
        }

        public <T> T asObject(Class<T> cls) {
            String json = asString();
            try {
                return mapper.readValue(json, cls);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public TestResponse assertCode(int code) {
            Assert.assertEquals(response.code(), code, "Unexpected response code for request [" + request + "]");
            return this;
        }

        public TestResponse assertWithRetries(Consumer<TestResponse> assertion, Retry retry) {
            try {
                assertion.accept(this);
                return this;
            } catch (Throwable e) {
                log.tracef("Request [%s] failed, starting retries", this.request);
                return retry.retry(() -> {
                    log.tracef("About to execute request [%s]", this.request);
                    Response newResponse;
                    try {
                        newResponse = TestClient.this.client.newCall(this.request).execute();
                    } catch (Exception e1) {
                        throw new RuntimeException(e1);
                    }
                    log.tracef("Got response [%s]", response);
                    TestResponse testResponse = new TestResponse(request, newResponse);
                    assertion.accept(testResponse);
                    return testResponse;
                });
            }
        }

        public TestResponse assertJson(Consumer<JsonNode> assertion) {
            assertion.accept(asJson());
            return this;
        }

        public <T> TestResponse assertObject(Class<T> cls, Consumer<T> assertion) {
            assertion.accept(asObject(cls));
            return this;
        }

        public String asString() {
            if (bodyString == null) {
                try {
                    bodyString = response.body().string();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            return bodyString;
        }

        public Request getRequest() {
            return request;
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
