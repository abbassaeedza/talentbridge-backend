package com.talentbridge.security;

import com.google.api.client.http.*;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Google's supported transport SPI; signature verification and key caching stay in TokenVerifier. */
final class GoogleCertificateTransport extends HttpTransport {
    private final HttpClient client;
    private final Duration budget;
    GoogleCertificateTransport() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER).build(),Duration.ofSeconds(1));
    }
    GoogleCertificateTransport(HttpClient client,Duration budget) { this.client=client; this.budget=budget; }

    @Override protected LowLevelHttpRequest buildRequest(String method,String url) throws IOException {
        if (!"GET".equals(method) || !"https://www.googleapis.com/oauth2/v3/certs".equals(url)) throw unavailable();
        return new LowLevelHttpRequest() {
            @Override public void addHeader(String name,String value) { }
            @Override public LowLevelHttpResponse execute() throws IOException {
                var request=java.net.http.HttpRequest.newBuilder(URI.create(url)).timeout(budget).GET().build();
                var future=client.sendAsync(request,info -> new BoundedCertificates());
                try {
                    var response=future.get(budget.toMillis(),TimeUnit.MILLISECONDS);
                    // An IOException avoids the verifier loader's HTTP-status retries/backoff entirely.
                    if (response.statusCode()!=200) throw unavailable();
                    return new Certificates(response.body());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw unavailable();
                } catch (ExecutionException | TimeoutException failure) { throw unavailable(); }
                finally { if (!future.isDone()) future.cancel(true); }
            }
        };
    }
    private static IOException unavailable() { return new IOException("GOOGLE_CERTIFICATES_UNAVAILABLE"); }
    private static final class BoundedCertificates implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription; private long bytes;
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription value) { subscription=value; delegate.onSubscribe(value); }
        public void onNext(List<ByteBuffer> items) {
            for (ByteBuffer item : items) bytes+=item.remaining();
            if (bytes>262144) { subscription.cancel(); delegate.onError(unavailable()); }
            else delegate.onNext(items);
        }
        public void onError(Throwable error) { delegate.onError(error); }
        public void onComplete() { delegate.onComplete(); }
    }
    private static final class Certificates extends LowLevelHttpResponse {
        private final byte[] bytes;
        Certificates(byte[] bytes) { this.bytes=bytes; }
        public InputStream getContent() { return new ByteArrayInputStream(bytes); }
        public String getContentEncoding() { return null; }
        public long getContentLength() { return bytes.length; }
        public String getContentType() { return "application/json"; }
        public String getStatusLine() { return "HTTP/1.1 200 OK"; }
        public int getStatusCode() { return 200; }
        public String getReasonPhrase() { return "OK"; }
        public int getHeaderCount() { return 0; }
        public String getHeaderName(int index) { return null; }
        public String getHeaderValue(int index) { return null; }
    }
}
