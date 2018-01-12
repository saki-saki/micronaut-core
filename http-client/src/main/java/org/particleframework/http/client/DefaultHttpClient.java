/*
 * Copyright 2018 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.particleframework.http.client;

import com.typesafe.netty.http.HttpStreamsClientHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.particleframework.context.annotation.Argument;
import org.particleframework.context.annotation.Prototype;
import org.particleframework.core.async.publisher.AsyncSingleResultPublisher;
import org.particleframework.core.async.publisher.Publishers;
import org.particleframework.core.async.subscriber.CompletionAwareSubscriber;
import org.particleframework.core.convert.ConversionService;
import org.particleframework.core.io.buffer.ByteBuffer;
import org.particleframework.core.io.buffer.ByteBufferFactory;
import org.particleframework.core.reflect.InstantiationUtils;
import org.particleframework.core.util.StringUtils;
import org.particleframework.http.HttpRequest;
import org.particleframework.http.HttpResponse;
import org.particleframework.http.MediaType;
import org.particleframework.http.MutableHttpRequest;
import org.particleframework.http.client.exceptions.HttpClientException;
import org.particleframework.http.codec.MediaTypeCodec;
import org.particleframework.http.codec.MediaTypeCodecRegistry;
import org.particleframework.http.netty.buffer.NettyByteBufferFactory;
import org.particleframework.http.sse.Event;
import org.particleframework.jackson.ObjectMapperFactory;
import org.particleframework.jackson.codec.JsonMediaTypeCodec;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import java.io.Closeable;
import java.net.Proxy.Type;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Default implementation of the {@link HttpClient} interface based on Netty
 *
 * @author Graeme Rocher
 * @since 1.0
 */
@Prototype
public class DefaultHttpClient implements HttpClient, Closeable, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultHttpClient.class);

    private final ServerSelector serverSelector;
    private final HttpClientConfiguration configuration;
    private final Charset charset;
    protected final Bootstrap bootstrap;
    protected final EventLoopGroup group;
    private MediaTypeCodecRegistry mediaTypeCodecRegistry;
    private ByteBufferFactory<ByteBufAllocator, ByteBuf> byteBufferFactory = new NettyByteBufferFactory();

    @Inject
    public DefaultHttpClient(@Argument URL url, HttpClientConfiguration configuration, MediaTypeCodecRegistry codecRegistry) {
        this(()-> url, configuration, codecRegistry);
    }

    /**
     * Construct a client for the given arguments
     *
     * @param serverSelector The {@link ServerSelector} to use for selecting servers
     * @param configuration The {@link HttpClientConfiguration} object
     * @param codecRegistry The {@link MediaTypeCodecRegistry} to use for encoding and decoding objects
     */
    public DefaultHttpClient(ServerSelector serverSelector, HttpClientConfiguration configuration, MediaTypeCodecRegistry codecRegistry) {
        this.serverSelector = serverSelector;
        this.bootstrap = new Bootstrap();
        this.configuration = configuration;
        OptionalInt numOfThreads = configuration.getNumOfThreads();
        Optional<Class<? extends ThreadFactory>> threadFactory = configuration.getThreadFactory();
        boolean hasThreads = numOfThreads.isPresent();
        boolean hasFactory = threadFactory.isPresent();
        if(hasThreads && hasFactory) {
            this.group = new NioEventLoopGroup(numOfThreads.getAsInt(), InstantiationUtils.instantiate(threadFactory.get()));
        }
        else if(hasThreads) {
            this.group = new NioEventLoopGroup(numOfThreads.getAsInt());
        }
        else {
            this.group = new NioEventLoopGroup();
        }
        this.bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true);

        for (Map.Entry<ChannelOption, Object> entry : configuration.getChannelOptions().entrySet()) {
            Object v = entry.getValue();
            if(v != null) {
                ChannelOption channelOption = entry.getKey();
                bootstrap.option(channelOption, v);
            }
        }
        this.charset = configuration.getEncoding();
        this.mediaTypeCodecRegistry = codecRegistry;
    }

    public DefaultHttpClient(ServerSelector serverSelector) {
        this(serverSelector, new HttpClientConfiguration(), MediaTypeCodecRegistry.of(
                new JsonMediaTypeCodec(new ObjectMapperFactory().objectMapper(Optional.empty(), Optional.empty()))
        ));
    }

    public DefaultHttpClient(@Argument URL url) {
        this(()-> url);
    }


    @Override
    public BlockingHttpClient toBlocking() {
        return new BlockingHttpClient() {
            @Override
            public <I, O> HttpResponse<O> exchange(HttpRequest<I> request, org.particleframework.core.type.Argument<O> bodyType) {
                Publisher<HttpResponse<O>> publisher = DefaultHttpClient.this.exchange(request, bodyType);
                CompletableFuture<HttpResponse<O>> future = new CompletableFuture<>();
                publisher.subscribe(new CompletionAwareSubscriber<HttpResponse<O>>() {
                    @Override
                    protected void doOnSubscribe(Subscription subscription) {
                        subscription.request(1);
                    }

                    @Override
                    protected void doOnNext(HttpResponse<O> message) {
                        future.complete(message);
                    }

                    @Override
                    protected void doOnError(Throwable t) {
                        future.completeExceptionally(t);
                    }

                    @Override
                    protected void doOnComplete() {
                    }
                });
                try {
                    return future.get();
                } catch (InterruptedException | ExecutionException e) {
                    throw new HttpClientException("Request execution exception: " + e.getMessage(), e);
                }
            }
        };
    }

    @Override
    public <I> Publisher<HttpResponse<Event<ByteBuffer<?>>>> eventStream(HttpRequest<I> request) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public <I, O> Publisher<HttpResponse<Event<O>>> eventStream(HttpRequest<I> request, org.particleframework.core.type.Argument<O> bodyType) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public <I> Publisher<HttpResponse<ByteBuffer<?>>> dataStream(HttpRequest<I> request) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public <I> Publisher<HttpResponse<Map<String, Object>>> jsonStream(HttpRequest<I> request) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public <I, O> Publisher<HttpResponse<O>> jsonStream(HttpRequest<I> request, org.particleframework.core.type.Argument<O> bodyType) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public <I, O> Publisher<HttpResponse<O>> exchange(HttpRequest<I> request) {
        return exchange(request, (org.particleframework.core.type.Argument<O>)null);
    }

    @Override
    public <I, O> Publisher<HttpResponse<O>> exchange(HttpRequest<I> request, org.particleframework.core.type.Argument<O> bodyType) {
        URL server = serverSelector.select();
        URI requestURI;
        try {
            requestURI = server.toURI().resolve(request.getUri());
        } catch (URISyntaxException e) {
            throw new HttpClientException("Invalid request URI for");
        }
        SslContext sslContext = buildSslContext(requestURI);

        return Publishers.fromCompletableFuture(() -> {
            CompletableFuture<HttpResponse<O>> completableFuture = new CompletableFuture<>();
            ChannelFuture connectionFuture = doConnect(requestURI, sslContext);
            connectionFuture.addListener(future -> {
                if(future.isSuccess()) {
                    try {
                        Channel channel = connectionFuture.channel();
                        NettyClientHttpRequest clientHttpRequest = (NettyClientHttpRequest) request;
                        Optional body = clientHttpRequest.getBody();
                        ByteBuf bodyContent = null;
                        if(body.isPresent() && mediaTypeCodecRegistry != null) {
                            Optional<MediaTypeCodec> registeredCodec = mediaTypeCodecRegistry.findCodec(request.getContentType().orElse(MediaType.APPLICATION_JSON_TYPE));
                            bodyContent = registeredCodec.map(codec -> (ByteBuf) codec.encode(body.get(), byteBufferFactory).asNativeBuffer())
                                                         .orElse(null);
                            if(bodyContent == null) {
                                bodyContent = ConversionService.SHARED.convert(body.get(), ByteBuf.class).orElse(null);
                            }
                        }

                        io.netty.handler.codec.http.HttpRequest nettyRequest = clientHttpRequest.getNettyRequest(bodyContent);

                        prepareHttpHeaders(requestURI, request, nettyRequest);

                        channel.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpResponse>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext channelHandlerContext, FullHttpResponse streamedResponse) {
                                FullNettyClientHttpResponse<O> response
                                        = new FullNettyClientHttpResponse<>(streamedResponse, mediaTypeCodecRegistry, byteBufferFactory, bodyType);
                                completableFuture.complete(response);
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                completableFuture.completeExceptionally(cause);
                            }
                        });

                        channel.writeAndFlush(nettyRequest).addListener(f -> {
                            ChannelFuture closeFuture = channel.closeFuture();
                            closeFuture.addListener(f2->{
                                if(!f2.isSuccess()) {
                                    if(LOG.isErrorEnabled()) {
                                        Throwable cause = f2.cause();
                                        LOG.error("Error closing request connection: " + cause.getMessage(), cause);
                                    }
                                }
                            });
                        });
                    } catch (Exception e) {
                        completableFuture.completeExceptionally(e);
                    }
                }
                else {
                    completableFuture.completeExceptionally(future.cause());
                }
            });
            return completableFuture;
        });
    }

    private <I> Publisher<HttpContent> buildSingleBodyPublisher(Channel channel, HttpRequest<I> request) {
        Optional<I> body = request.getBody();
        Publisher<HttpContent> bodyPublisher = null;
        if(body.isPresent() && mediaTypeCodecRegistry != null) {
            Optional<MediaTypeCodec> registeredCodec = mediaTypeCodecRegistry.findCodec(request.getContentType().orElse(MediaType.APPLICATION_HAL_JSON_TYPE));
            bodyPublisher = registeredCodec.map(codec -> new AsyncSingleResultPublisher<>(channel.eventLoop(), (Supplier<HttpContent>) () -> {
                ByteBuf byteBuf = (ByteBuf) codec.encode(body.get(), byteBufferFactory).asNativeBuffer();
                return new DefaultHttpContent(byteBuf);
            })).orElse(null);
        }
        return bodyPublisher;
    }

    /**
     * Creates an initial connection to the given remote host
     *
     * @param uri The URI to connect to
     * @param sslCtx The SslContext instance
     *
     * @return A ChannelFuture
     */
    protected ChannelFuture doConnect(URI uri, @Nullable SslContext sslCtx) {
        String host = uri.getHost();
        int port = uri.getPort() > -1 ? uri.getPort() : sslCtx != null ? 443 : 80;

        return doConnect(host, port, sslCtx);
    }

    /**
     * Creates an initial connection to the given remote host
     *
     * @param host The host
     * @param port The port
     * @param sslCtx The SslContext instance
     *
     * @return A ChannelFuture
     */
    protected ChannelFuture doConnect(String host, int port,@Nullable SslContext sslCtx) {
        Bootstrap localBootstrap = this.bootstrap.clone();
        localBootstrap.handler(new HttpClientInitializer(sslCtx, false));
        return doConnect(localBootstrap, host, port);
    }

    /**
     * Creates an initial connection with the given bootstrap and remote host
     *
     * @param bootstrap The bootstrap instance
     * @param host The host
     * @param port The port
     * @return The ChannelFuture
     */
    protected ChannelFuture doConnect(Bootstrap bootstrap, String host, int port) {
        return bootstrap.connect(host, port);
    }

    /**
     * Builds an {@link SslContext} for the given URI if necessary
     *
     * @param uriObject The URI
     * @return The {@link SslContext} instance
     */
    protected SslContext buildSslContext(URI uriObject) {
        final SslContext sslCtx;
        if (uriObject.getScheme().equals("https")) {
            sslCtx = buildSslContext(configuration);
        } else {
            sslCtx = null;
        }
        return sslCtx;
    }
    /**
     * Builds an {@link SslContext} from the {@link HttpClientConfiguration}
     *
     * @param configuration The configuration instance
     * @return The {@link SslContext} instance
     */
    protected SslContext buildSslContext(HttpClientConfiguration configuration) {
        SslContextBuilder sslContextBuilder = SslContextBuilder.forClient()
                .sslProvider(configuration.getSslProvider());
        configuration.getSslSessionCacheSize().ifPresent(sslContextBuilder::sessionCacheSize);
        configuration.getSslSessionTimeout().ifPresent(duration -> sslContextBuilder.sessionTimeout(duration.getSeconds()));
        configuration.getSslTrustManagerFactory().ifPresent(sslContextBuilder::trustManager);

        try {
            return sslContextBuilder.build();
        } catch (SSLException e) {
            throw new HttpClientException("Error constructing SSL context: " + e.getMessage(), e);
        }
    }

    /**
     * Configures the HTTP proxy for the pipeline
     * @param pipeline The pipeline
     * @param proxyType The proxy type
     * @param proxyAddress The proxy address
     */
    protected void configureProxy(ChannelPipeline pipeline, Type proxyType, SocketAddress proxyAddress) {
        String type = proxyType.name().toLowerCase();

        // TODO: move to configuration class
        String username = System.getProperty(type + ".proxyUser");
        String password = System.getProperty(type + ".proxyPassword");

        if(StringUtils.isNotEmpty(username) && StringUtils.isNotEmpty(password)) {
            switch(proxyType) {
                case HTTP:
                    pipeline.addLast(new HttpProxyHandler(proxyAddress, username, password));
                    break;
                case SOCKS:
                    pipeline.addLast(new Socks5ProxyHandler(proxyAddress, username, password));
                    break;
            }
        }
        else {
            switch(proxyType) {
                case HTTP:
                    pipeline.addLast(new HttpProxyHandler(proxyAddress));
                    break;
                case SOCKS:
                    pipeline.addLast(new Socks5ProxyHandler(proxyAddress));
                    break;
            }
        }
    }

    @Override
    public void close() {
        this.group.shutdownGracefully().addListener(f-> {
            if(!f.isSuccess() && LOG.isErrorEnabled()) {
                Throwable cause = f.cause();
                LOG.error("Error shutting down HTTP client: " + cause.getMessage(), cause);
            }
        });
    }


    private <I> void prepareHttpHeaders(URI requestURI, HttpRequest<I> request, io.netty.handler.codec.http.HttpRequest nettyRequest) {
        HttpHeaders headers = nettyRequest.headers();
        headers.set(HttpHeaderNames.HOST, requestURI.getHost());
        headers.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);

        Optional<I> body = request.getBody();
        if(body.isPresent()) {
            MediaType mediaType = request.getContentType().orElse(MediaType.APPLICATION_JSON_TYPE);
            headers.set(HttpHeaderNames.CONTENT_TYPE, mediaType);
            if(nettyRequest instanceof FullHttpRequest) {
                FullHttpRequest fullHttpRequest = (FullHttpRequest) nettyRequest;
                headers.set(HttpHeaderNames.CONTENT_LENGTH, fullHttpRequest.content().readableBytes());
            }
            else {
                headers.set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
            }
        }
    }

    /**
     * Initializes the HTTP client channel
     */
    protected class HttpClientInitializer extends ChannelInitializer<Channel> {

        SslContext sslContext;
        boolean stream;

        protected HttpClientInitializer(SslContext sslContext, boolean stream) {
            this.sslContext = sslContext;
            this.stream = stream;
        }

        protected void initChannel(Channel ch) {
            ChannelPipeline p = ch.pipeline();
            if(sslContext != null) {
                SSLEngine engine = sslContext.newEngine(ch.alloc());
                p.addFirst("ssl", new SslHandler(engine));
            }


            Optional<SocketAddress> proxy = configuration.getProxyAddress();
            if(proxy.isPresent()) {
                Type proxyType = configuration.getProxyType();
                SocketAddress proxyAddress = proxy.get();
                configureProxy(p, proxyType, proxyAddress);

            }
            Optional<Duration> readTimeout = configuration.getReadTimeout();
            readTimeout.ifPresent(duration -> p.addLast(new ReadTimeoutHandler(duration.toMillis(), TimeUnit.MILLISECONDS)));
            p.addLast("codec", new HttpClientCodec());
            p.addLast("aggregator", new HttpObjectAggregator(configuration.getMaxContentLength()));
            p.addLast("stream-handler", new HttpStreamsClientHandler());
        }
    }
}
