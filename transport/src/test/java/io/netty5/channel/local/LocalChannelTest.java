/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty5.channel.local;

import io.netty5.bootstrap.Bootstrap;
import io.netty5.bootstrap.ServerBootstrap;
import io.netty5.buffer.api.Buffer;
import io.netty5.buffer.api.BufferAllocator;
import io.netty5.channel.AdaptiveReadHandleFactory;
import io.netty5.channel.Channel;
import io.netty5.channel.ChannelHandler;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.channel.ChannelInitializer;
import io.netty5.channel.ChannelOption;
import io.netty5.channel.MaxMessagesReadHandleFactory;
import io.netty5.channel.EventLoop;
import io.netty5.channel.EventLoopGroup;
import io.netty5.channel.IoHandler;
import io.netty5.channel.MultithreadEventLoopGroup;
import io.netty5.channel.ServerChannelReadHandleFactory;
import io.netty5.channel.SimpleChannelInboundHandler;
import io.netty5.channel.SingleThreadEventLoop;
import io.netty5.util.concurrent.Future;
import io.netty5.util.concurrent.FutureCompletionStage;
import io.netty5.util.concurrent.FutureListener;
import io.netty5.util.concurrent.Promise;
import io.netty5.util.concurrent.RejectedExecutionHandler;
import io.netty5.util.internal.SilentDispose;
import io.netty5.util.internal.logging.InternalLogger;
import io.netty5.util.internal.logging.InternalLoggerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.ConnectException;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class LocalChannelTest {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(LocalChannelTest.class);

    private static final LocalAddress TEST_ADDRESS = new LocalAddress(LocalChannelTest.class);

    private static EventLoopGroup group1;
    private static EventLoopGroup group2;
    private static EventLoopGroup sharedGroup;

    @BeforeAll
    public static void beforeClass() {
        group1 = new MultithreadEventLoopGroup(2, LocalHandler.newFactory());
        group2 = new MultithreadEventLoopGroup(2, LocalHandler.newFactory());
        sharedGroup = new MultithreadEventLoopGroup(1, LocalHandler.newFactory());
    }

    @AfterAll
    public static void afterClass() throws InterruptedException {
        Future<?> group1Future = group1.shutdownGracefully(0, 0, SECONDS);
        Future<?> group2Future = group2.shutdownGracefully(0, 0, SECONDS);
        Future<?> sharedGroupFuture = sharedGroup.shutdownGracefully(0, 0, SECONDS);
        group1Future.asStage().await();
        group2Future.asStage().await();
        sharedGroupFuture.asStage().await();
    }

    static Stream<IntFunction<Buffer>> allocators() {
        BufferAllocator onHeap = BufferAllocator.onHeapUnpooled();
        BufferAllocator offHeap = BufferAllocator.offHeapUnpooled();
        return Stream.of(
                new IntFunction<Buffer>() {
                    @Override
                    public Buffer apply(int size) {
                        return onHeap.allocate(size);
                    }

                    @Override
                    public String toString() {
                        return "on-heap";
                    }
                },
                new IntFunction<Buffer>() {
                    @Override
                    public Buffer apply(int size) {
                        return offHeap.allocate(size);
                    }

                    @Override
                    public String toString() {
                        return "off-heap";
                    }
                },
                new IntFunction<Buffer>() {
                    @Override
                    public Buffer apply(int size) {
                        return onHeap.compose().ensureWritable(size);
                    }

                    @Override
                    public String toString() {
                        return "composite";
                    }
                });
    }

    @Test
    public void testLocalAddressReuse() throws Exception {
        for (int i = 0; i < 2; i ++) {
            Bootstrap cb = new Bootstrap();
            ServerBootstrap sb = new ServerBootstrap();

            cb.group(group1)
              .channel(LocalChannel.class)
              .handler(new TestHandler());

            sb.group(group2)
              .channel(LocalServerChannel.class)
              .childHandler(new ChannelInitializer<LocalChannel>() {
                  @Override
                  public void initChannel(LocalChannel ch) throws Exception {
                      ch.pipeline().addLast(new TestHandler());
                  }
              });

            Channel sc = null;
            Channel cc = null;
            try {
                // Start server
                sc = sb.bind(TEST_ADDRESS).asStage().get();

                final CountDownLatch latch = new CountDownLatch(1);
                // Connect to the server
                cc = cb.connect(sc.localAddress()).asStage().get();
                final Channel ccCpy = cc;
                cc.executor().execute(() -> {
                    // Send a message event up the pipeline.
                    ccCpy.pipeline().fireChannelRead("Hello, World");
                    latch.countDown();
                });
                assertTrue(latch.await(5, SECONDS));

                // Close the channel
                closeChannel(cc);
                closeChannel(sc);
                sc.closeFuture().asStage().sync();

                assertNull(LocalChannelRegistry.get(TEST_ADDRESS), String.format(
                        "Expected null, got channel '%s' for local address '%s'",
                        LocalChannelRegistry.get(TEST_ADDRESS), TEST_ADDRESS));
            } finally {
                closeChannel(cc);
                closeChannel(sc);
            }
        }
    }

    @Test
    public void testWriteFailsFastOnClosedChannel() throws Exception {
        Bootstrap cb = new Bootstrap();
        ServerBootstrap sb = new ServerBootstrap();

        cb.group(group1)
                .channel(LocalChannel.class)
                .handler(new TestHandler());

        sb.group(group2)
                .channel(LocalServerChannel.class)
                .childHandler(new ChannelInitializer<LocalChannel>() {
                    @Override
                    public void initChannel(LocalChannel ch) throws Exception {
                        ch.pipeline().addLast(new TestHandler());
                    }
                });

        Channel sc = null;
        Channel cc = null;
        try {
            // Start server
            sc = sb.bind(TEST_ADDRESS).asStage().get();

            // Connect to the server
            cc = cb.connect(sc.localAddress()).asStage().get();

            // Close the channel and write something.
            cc.close().asStage().sync();
            try {
                cc.writeAndFlush(new Object()).asStage().sync();
                fail("must raise a ClosedChannelException");
            } catch (CompletionException cause) {
                Throwable e = cause.getCause();
                assertThat(e).isInstanceOf(ClosedChannelException.class);
            }
        } finally {
            closeChannel(cc);
            closeChannel(sc);
        }
    }

    @Test
    public void testServerCloseChannelSameEventLoop() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        ServerBootstrap sb = new ServerBootstrap()
                .group(group2)
                .channel(LocalServerChannel.class)
                .childHandler(new SimpleChannelInboundHandler<Object>() {
                    @Override
                    protected void messageReceived(ChannelHandlerContext ctx, Object msg) throws Exception {
                        ctx.close();
                        latch.countDown();
                    }
                });
        Channel sc = null;
        Channel cc = null;
        try {
            sc = sb.bind(TEST_ADDRESS).asStage().get();

            Bootstrap b = new Bootstrap()
                    .group(group2)
                    .channel(LocalChannel.class)
                    .handler(new SimpleChannelInboundHandler<Object>() {
                        @Override
                        protected void messageReceived(ChannelHandlerContext ctx, Object msg) throws Exception {
                            // discard
                        }
                    });
            cc = b.connect(sc.localAddress()).asStage().get();
            cc.writeAndFlush(new Object());
            assertTrue(latch.await(5, SECONDS));
        } finally {
            closeChannel(cc);
            closeChannel(sc);
        }
    }

    @Test
    public void localChannelRaceCondition() throws Exception {
        final CountDownLatch closeLatch = new CountDownLatch(1);
        final EventLoopGroup clientGroup = new MultithreadEventLoopGroup(1, LocalHandler.newFactory()) {

            @Override
            protected EventLoop newChild(
                    Executor executor, int maxPendingTasks, RejectedExecutionHandler rejectedExecutionHandler,
                    IoHandler ioHandler, int maxTasksPerRun, Object... args) {
                return new SingleThreadEventLoop(executor, ioHandler, maxPendingTasks, rejectedExecutionHandler) {

                    @Override
                    protected void run() {
                        do {
                            runIO();
                            Runnable task = pollTask();
                            if (task != null) {
                                /* Only slow down the anonymous class in LocalChannel#doRegister() */
                                if (task.getClass().getEnclosingClass() == LocalChannel.class) {
                                    try {
                                        closeLatch.await(1, SECONDS);
                                    } catch (InterruptedException e) {
                                        throw new Error(e);
                                    }
                                }
                                task.run();
                                updateLastExecutionTime();
                            }
                        } while (!confirmShutdown());
                    }
                };
            }
        };
        Channel sc = null;
        Channel cc = null;
        try {
            ServerBootstrap sb = new ServerBootstrap();
            sc = sb.group(group2).
                   channel(LocalServerChannel.class).
                   childHandler(new ChannelInitializer<Channel>() {
                       @Override
                       protected void initChannel(Channel ch) throws Exception {
                           ch.close();
                           closeLatch.countDown();
                       }
                   }).
                   bind(TEST_ADDRESS).asStage().get();
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(clientGroup).
                    channel(LocalChannel.class).
                    handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) throws Exception {
                            /* Do nothing */
                        }
                    });
            Future<Channel> future = bootstrap.connect(sc.localAddress());
            assertTrue(future.asStage().await(2000, TimeUnit.MILLISECONDS), "Connection should finish, not time out");
            cc = future.asStage().join((r, e) -> r); // Get channel if future is successful, or null if it failed.
        } finally {
            closeChannel(cc);
            closeChannel(sc);
            clientGroup.shutdownGracefully(0, 0, SECONDS).asStage().await();
        }
    }

    @Test
    public void testReRegister() throws Exception {
        Bootstrap cb = new Bootstrap();
        ServerBootstrap sb = new ServerBootstrap();

        cb.group(group1)
                .channel(LocalChannel.class)
                .handler(new TestHandler());

        sb.group(group2)
                .channel(LocalServerChannel.class)
                .childHandler(new ChannelInitializer<LocalChannel>() {
                    @Override
                    public void initChannel(LocalChannel ch) throws Exception {
                        ch.pipeline().addLast(new TestHandler());
                    }
                });

        Channel sc = null;
        Channel cc = null;
        try {
            // Start server
            sc = sb.bind(TEST_ADDRESS).asStage().get();

            // Connect to the server
            cc = cb.connect(sc.localAddress()).asStage().get();

            cc.deregister().asStage().sync();
        } finally {
            closeChannel(cc);
            closeChannel(sc);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void testCloseInWritePromiseCompletePreservesOrder(IntFunction<Buffer> allocator) throws Exception {
        Bootstrap cb = new Bootstrap();
        ServerBootstrap sb = new ServerBootstrap();
        final CountDownLatch messageLatch = new CountDownLatch(2);
        final Buffer data = allocator.apply(1024);

        cb.group(group1)
          .channel(LocalChannel.class)
          .handler(new TestHandler());

        sb.group(group2)
          .channel(LocalServerChannel.class)
          .childHandler(new ChannelHandler() {
              @Override
              public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                  if (msg instanceof Buffer && msg.equals(data)) {
                      ((Buffer) msg).close();
                      messageLatch.countDown();
                  } else {
                      ctx.fireChannelRead(msg);
                  }
              }
              @Override
              public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                  messageLatch.countDown();
                  ctx.fireChannelInactive();
              }
          });

        Channel sc = null;
        Channel cc = null;
        try {
            // Start server
            sc = sb.bind(TEST_ADDRESS).asStage().get();

            // Connect to the server
            cc = cb.connect(sc.localAddress()).asStage().get();

            final Channel ccCpy = cc;
            // Make sure a write operation is executed in the eventloop
            cc.pipeline().lastContext().executor().execute(() -> {
                ccCpy.writeAndFlush(data)
                     .addListener(future -> ccCpy.pipeline().lastContext().close());
            });

            assertTrue(messageLatch.await(5, SECONDS));
            assertFalse(cc.isOpen());
        } finally {
            closeChannel(cc);
            closeChannel(sc);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void testCloseAfterWriteInSameEventLoopPreservesOrder(IntFunction<Buffer> allocator) throws Exception {
        Bootstrap cb = new Bootstrap();
        ServerBootstrap sb = new ServerBootstrap();
        final CountDownLatch messageLatch = new CountDownLatch(3);
        final Buffer data = allocator.apply(1024);

        cb.group(sharedGroup)
          .channel(LocalChannel.class)
          .handler(new ChannelHandler() {
              @Override
              public void channelActive(ChannelHandlerContext ctx) throws Exception {
                  ctx.writeAndFlush(data);
              }

              @Override
              public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                  if (msg instanceof Buffer && data.equals(msg)) {
                      ((Buffer) msg).close();
                      messageLatch.countDown();
                  } else {
                      ctx.fireChannelRead(msg);
                  }
              }
          });

        sb.group(sharedGroup)
          .channel(LocalServerChannel.class)
          .childHandler(new ChannelHandler() {
              @Override
              public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                  if (data.equals(msg)) {
                      messageLatch.countDown();
                      ctx.writeAndFlush(data);
                      ctx.close();
                  } else {
                      ctx.fireChannelRead(msg);
                  }
              }

              @Override
              public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                  messageLatch.countDown();
                  ctx.fireChannelInactive();
              }
          });

        Channel sc = null;
        Channel cc = null;
        try {
            // Start server
            sc = sb.bind(TEST_ADDRESS).asStage().get();

            // Connect to the server
            cc = cb.connect(sc.localAddress()).asStage().get();
            assertTrue(messageLatch.await(5, SECONDS));
            assertFalse(cc.isOpen());
        } finally {
            closeChannel(cc);
            closeChannel(sc);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void testWriteInWritePromiseCompletePreservesOrder(IntFunction<Buffer> allocator) throws Exception {
        Bootstrap cb = new Bootstrap();
        ServerBootstrap sb = new ServerBootstrap();
        final CountDownLatch messageLatch = new CountDownLatch(2);
        final Buffer data = allocator.apply(1024);
        final Buffer data2 = allocator.apply(512);

        cb.group(group1)
          .channel(LocalChannel.class)
          .handler(new TestHandler());

        sb.group(group2)
          .channel(LocalServerChannel.class)
          .childHandler(new ChannelHandler() {
              @Override
              public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                  final long count = messageLatch.getCount();
                  if (msg instanceof Buffer && data.equals(msg) && count == 2 || data2.equals(msg) && count == 1) {
                      ((Buffer) msg).close();
                      messageLatch.countDown();
                  } else {
                      ctx.fireChannelRead(msg);
                  }
              }
          });

        Channel sc = null;
        Channel cc = null;
        try {
            // Start server
            sc = sb.bind(TEST_ADDRESS).asStage().get();

            // Connect to the server
            cc = cb.connect(sc.localAddress()).asStage().get();

            final Channel ccCpy = cc;
            // Make sure a write operation is executed in the eventloop
            cc.pipeline().lastContext().executor().execute(() -> {
                ccCpy.writeAndFlush(data).addListener(future ->
                                                              ccCpy.writeAndFlush(data2));
            });

            assertTrue(messageLatch.await(5, SECONDS));
        } finally {
            closeChannel(cc);
            closeChannel(sc);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void testPeerWriteInWritePromiseCompleteDifferentEventLoopPreservesOrder(IntFunction<Buffer> allocator)
            throws Exception {
        Bootstrap cb = new Bootstrap();
        ServerBootstrap sb = new ServerBootstrap();
        final CountDownLatch messageLatch = new CountDownLatch(2);
        final Buffer data = allocator.apply(1024);
        final Buffer data2 = allocator.apply(512);
        final CountDownLatch serverChannelLatch = new CountDownLatch(1);
        final AtomicReference<Channel> serverChannelRef = new AtomicReference<>();

        cb.group(group1)
                .channel(LocalChannel.class)
                .handler(new ChannelHandler() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                        if (msg instanceof Buffer && data2.equals(msg)) {
                            ((Buffer) msg).close();
                            messageLatch.countDown();
                        } else {
                            ctx.fireChannelRead(msg);
                        }
                    }
                });

        sb.group(group2)
                .channel(LocalServerChannel.class)
                .childHandler(new ChannelInitializer<LocalChannel>() {
                    @Override
                    public void initChannel(LocalChannel ch) throws Exception {
                        ch.pipeline().addLast(new ChannelHandler() {
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                if (msg instanceof Buffer && data.equals(msg)) {
                                    ((Buffer) msg).close();
                                    messageLatch.countDown();
                                } else {
                                    ctx.fireChannelRead(msg);
                                }
                            }
                        });
                        serverChannelRef.set(ch);
                        serverChannelLatch.countDown();
                    }
                });

        Channel sc = null;
        Channel cc = null;
        try {
            // Start server
            sc = sb.bind(TEST_ADDRESS).asStage().get();

            // Connect to the server
            cc = cb.connect(sc.localAddress()).asStage().get();
            assertTrue(serverChannelLatch.await(5, SECONDS));

            final Channel ccCpy = cc;
            // Make sure a write operation is executed in the eventloop
            cc.pipeline().lastContext().executor().execute(() -> {
                ccCpy.writeAndFlush(data).addListener(future -> {
                    Channel serverChannelCpy = serverChannelRef.get();
                    serverChannelCpy.writeAndFlush(data2);
                });
            });

            assertTrue(messageLatch.await(5, SECONDS));
        } finally {
            closeChannel(cc);
            closeChannel(sc);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void testPeerWriteInWritePromiseCompleteSameEventLoopPreservesOrder(IntFunction<Buffer> allocator)
            throws Exception {
        Bootstrap cb = new Bootstrap();
        ServerBootstrap sb = new ServerBootstrap();
        final CountDownLatch messageLatch = new CountDownLatch(2);
        final Buffer data = allocator.apply(1024);
        final Buffer data2 = allocator.apply(512);
        final CountDownLatch serverChannelLatch = new CountDownLatch(1);
        final AtomicReference<Channel> serverChannelRef = new AtomicReference<>();

        cb.group(sharedGroup)
          .channel(LocalChannel.class)
          .handler(new ChannelHandler() {
              @Override
              public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                  if (msg instanceof Buffer && data2.equals(msg) && messageLatch.getCount() == 1) {
                      ((Buffer) msg).close();
                      messageLatch.countDown();
                  } else {
                      ctx.fireChannelRead(msg);
                  }
              }
          });

        sb.group(sharedGroup)
          .channel(LocalServerChannel.class)
          .childHandler(new ChannelInitializer<LocalChannel>() {
              @Override
              public void initChannel(LocalChannel ch) throws Exception {
                  ch.pipeline().addLast(new ChannelHandler() {
                      @Override
                      public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                          if (msg instanceof Buffer && data.equals(msg) && messageLatch.getCount() == 2) {
                              ((Buffer) msg).close();
                              messageLatch.countDown();
                          } else {
                              ctx.fireChannelRead(msg);
                          }
                      }
                  });
                  serverChannelRef.set(ch);
                  serverChannelLatch.countDown();
              }
          });

        Channel sc = null;
        Channel cc = null;
        try {
            // Start server
            sc = sb.bind(TEST_ADDRESS).asStage().get();

            // Connect to the server
            cc = cb.connect(sc.localAddress()).asStage().get();
            assertTrue(serverChannelLatch.await(5, SECONDS));

            final Channel ccCpy = cc;
            // Make sure a write operation is executed in the eventloop
            cc.pipeline().lastContext().executor().execute(() -> {
                ccCpy.writeAndFlush(data).addListener(future -> {
                    Channel serverChannelCpy = serverChannelRef.get();
                    serverChannelCpy.writeAndFlush(data2);
                });
            });

            assertTrue(messageLatch.await(5, SECONDS));
        } finally {
            closeChannel(cc);
            closeChannel(sc);
        }
    }

    @ParameterizedTest
    @MethodSource("allocators")
    public void testWriteWhilePeerIsClosedReleaseObjectAndFailPromise(IntFunction<Buffer> allocator) throws Exception {
        Bootstrap cb = new Bootstrap();
        ServerBootstrap sb = new ServerBootstrap();
        final CountDownLatch serverMessageLatch = new CountDownLatch(1);
        final LatchChannelFutureListener serverChannelCloseLatch = new LatchChannelFutureListener(1);
        final LatchChannelFutureListener clientChannelCloseLatch = new LatchChannelFutureListener(1);
        final CountDownLatch writeFailLatch = new CountDownLatch(1);
        final Buffer data = allocator.apply(1024);
        final Buffer data2 = allocator.apply(512);
        final CountDownLatch serverChannelLatch = new CountDownLatch(1);
        final AtomicReference<Channel> serverChannelRef = new AtomicReference<>();

        cb.group(group1)
          .channel(LocalChannel.class)
          .handler(new TestHandler());

        sb.group(group2)
          .channel(LocalServerChannel.class)
          .childHandler(new ChannelInitializer<LocalChannel>() {
              @Override
              public void initChannel(LocalChannel ch) throws Exception {
                  ch.pipeline().addLast(new ChannelHandler() {
                      @Override
                      public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                          if (msg instanceof Buffer && data.equals(msg)) {
                              ((Buffer) msg).close();
                              serverMessageLatch.countDown();
                          } else {
                              ctx.fireChannelRead(msg);
                          }
                      }
                  });
                  serverChannelRef.set(ch);
                  serverChannelLatch.countDown();
              }
          });

        Channel sc = null;
        Channel cc = null;
        try {
            // Start server
            sc = sb.bind(TEST_ADDRESS).asStage().get();

            // Connect to the server
            cc = cb.connect(sc.localAddress()).asStage().get();
            assertTrue(serverChannelLatch.await(5, SECONDS));

            final Channel ccCpy = cc;
            final Channel serverChannelCpy = serverChannelRef.get();
            serverChannelCpy.closeFuture().addListener(serverChannelCloseLatch);
            ccCpy.closeFuture().addListener(clientChannelCloseLatch);

            // Make sure a write operation is executed in the eventloop
            cc.pipeline().lastContext().executor().execute(() -> ccCpy.writeAndFlush(data).addListener(
                    future -> {
                        serverChannelCpy.executor().execute(() -> {
                            // The point of this test is to write while the peer is closed, so we should
                            // ensure the peer is actually closed before we write.
                            int waitCount = 0;
                            while (ccCpy.isOpen()) {
                                try {
                                    Thread.sleep(50);
                                } catch (InterruptedException ignored) {
                                    // ignored
                                }
                                if (++waitCount > 5) {
                                    fail();
                                }
                            }
                            serverChannelCpy.writeAndFlush(data2).addListener(
                                    future1 -> {
                                        if (!future1.isSuccess() &&
                                            future1.cause() instanceof ClosedChannelException) {
                                            writeFailLatch.countDown();
                                        }
                                    });
                        });
                        ccCpy.close();
                    }));

            assertTrue(serverMessageLatch.await(5, SECONDS));
            assertTrue(writeFailLatch.await(5, SECONDS));
            assertTrue(serverChannelCloseLatch.await(5, SECONDS));
            assertTrue(clientChannelCloseLatch.await(5, SECONDS));
            assertFalse(ccCpy.isOpen());
            assertFalse(serverChannelCpy.isOpen());
        } finally {
            closeChannel(cc);
            closeChannel(sc);
        }
    }

    @Test
    @Timeout(value = 3000, unit = TimeUnit.MILLISECONDS)
    public void testConnectFutureBeforeChannelActive() throws Exception {
        Bootstrap cb = new Bootstrap();
        ServerBootstrap sb = new ServerBootstrap();

        cb.group(group1)
                .channel(LocalChannel.class)
                .handler(new ChannelHandler() { });

        sb.group(group2)
                .channel(LocalServerChannel.class)
                .childHandler(new ChannelInitializer<LocalChannel>() {
                    @Override
                    public void initChannel(LocalChannel ch) throws Exception {
                        ch.pipeline().addLast(new TestHandler());
                    }
                });

        Channel sc = null;
        Channel cc = null;
        try {
            // Start server
            sc = sb.bind(TEST_ADDRESS).asStage().get();

            cc = cb.register().asStage().get();

            final AtomicReference<Future<Void>> ref = new AtomicReference<>();
            final Promise<Void> assertPromise = cc.executor().newPromise();

            cc.pipeline().addLast(new TestHandler() {
                @Override
                public Future<Void> connect(ChannelHandlerContext ctx,
                                             SocketAddress remoteAddress, SocketAddress localAddress) {
                    Future<Void> future = super.connect(ctx, remoteAddress, localAddress);
                    ref.set(future);
                    return future;
                }

                @Override
                public void channelActive(ChannelHandlerContext ctx) throws Exception {
                    // Ensure the promise was done before the handler method is triggered.
                    if (ref.get().isDone()) {
                        assertPromise.setSuccess(null);
                    } else {
                        assertPromise.setFailure(new AssertionError("connect promise should be done"));
                    }
                }
            });
            // Connect to the server
            cc.connect(sc.localAddress()).asStage().sync();
            FutureCompletionStage<Void> f = ref.get().asStage().sync();

            assertPromise.asFuture().asStage().sync();
            assertTrue(f.isSuccess());
        } finally {
            closeChannel(cc);
            closeChannel(sc);
        }
    }

    @Test
    public void testConnectionRefused() throws Throwable {
        try {
            Bootstrap sb = new Bootstrap();
            var exception = assertThrows(CompletionException.class,
                                         () -> sb.group(group1)
                                                 .channel(LocalChannel.class)
                                                 .handler(new TestHandler())
                                                 .connect(LocalAddress.ANY)
                                                 .asStage().sync());
            assertThat(exception).hasCauseInstanceOf(ConnectException.class);
        } catch (CompletionException e) {
            throw e.getCause();
        }
    }

    private static final class LatchChannelFutureListener extends CountDownLatch implements FutureListener<Object> {
        private LatchChannelFutureListener(int count) {
            super(count);
        }

        @Override
        public void operationComplete(Future<?> future) throws Exception {
            countDown();
        }
    }

    private static void closeChannel(Channel cc) throws Exception {
        if (cc != null) {
            cc.close().asStage().sync();
        }
    }

    static class TestHandler implements ChannelHandler {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            logger.info(String.format("Received message: %s", msg));
            SilentDispose.dispose(msg, logger);
        }
    }

    @Test
    public void testNotLeakBuffersWhenCloseByRemotePeer() throws Exception {
        Bootstrap cb = new Bootstrap();
        ServerBootstrap sb = new ServerBootstrap();

        cb.group(sharedGroup)
                .channel(LocalChannel.class)
                .handler(new SimpleChannelInboundHandler<Buffer>() {
                    @Override
                    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
                        ctx.writeAndFlush(ctx.bufferAllocator().copyOf(new byte[100]));
                    }

                    @Override
                    public void messageReceived(ChannelHandlerContext ctx, Buffer buffer) throws Exception {
                        // Just drop the buffer
                    }
                });

        sb.group(sharedGroup)
                .channel(LocalServerChannel.class)
                .childHandler(new ChannelInitializer<LocalChannel>() {
                    @Override
                    public void initChannel(LocalChannel ch) throws Exception {
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<Buffer>() {

                            @Override
                            public void messageReceived(ChannelHandlerContext ctx, Buffer buffer) throws Exception {
                                while (buffer.readableBytes() > 0) {
                                    // Fill the ChannelOutboundBuffer with multiple buffers
                                    ctx.write(buffer.readSplit(1));
                                }
                                // Flush and so transfer the written buffers to the inboundBuffer of the remote peer.
                                // After this point the remote peer is responsible to release all the buffers.
                                ctx.flush();
                                // This close call will trigger the remote peer close as well.
                                ctx.close();
                            }
                        });
                    }
                });

        Channel sc = null;
        LocalChannel cc = null;
        try {
            // Start server
            sc = sb.bind(TEST_ADDRESS).asStage().get();

            // Connect to the server
            cc = (LocalChannel) cb.connect(sc.localAddress()).asStage().get();

            // Close the channel
            closeChannel(cc);
            assertTrue(cc.inboundBuffer.isEmpty());
            closeChannel(sc);
        } finally {
            closeChannel(cc);
            closeChannel(sc);
        }
    }

    private static void writeAndFlushReadOnSuccess(final ChannelHandlerContext ctx, Object msg) {
        ctx.writeAndFlush(msg).addListener(future -> {
            if (future.isSuccess()) {
                ctx.read();
            }
        });
    }

    @Test
    @Timeout(value = 5000, unit = TimeUnit.MILLISECONDS)
    public void testAutoReadDisabledSharedGroup() throws Exception {
        testAutoReadDisabled(sharedGroup, sharedGroup);
    }

    @Test
    @Timeout(value = 5000, unit = TimeUnit.MILLISECONDS)
    public void testAutoReadDisabledDifferentGroup() throws Exception {
        testAutoReadDisabled(group1, group2);
    }

    private static void testAutoReadDisabled(EventLoopGroup serverGroup, EventLoopGroup clientGroup) throws Exception {
        final CountDownLatch latch = new CountDownLatch(100);
        Bootstrap cb = new Bootstrap();
        ServerBootstrap sb = new ServerBootstrap();

        cb.group(serverGroup)
                .channel(LocalChannel.class)
                .option(ChannelOption.AUTO_READ, false)
                .handler(new ChannelHandler() {

                    @Override
                    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
                        writeAndFlushReadOnSuccess(ctx, "test");
                    }

                    @Override
                    public void channelRead(final ChannelHandlerContext ctx, Object msg) throws Exception {
                        writeAndFlushReadOnSuccess(ctx, msg);
                    }
                });

        sb.group(clientGroup)
                .channel(LocalServerChannel.class)
                .childOption(ChannelOption.AUTO_READ, false)
                .childHandler(new ChannelHandler() {
                    @Override
                    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
                        ctx.read();
                    }

                    @Override
                    public void channelRead(final ChannelHandlerContext ctx, Object msg) throws Exception {
                        latch.countDown();
                        if (latch.getCount() > 0) {
                            writeAndFlushReadOnSuccess(ctx, msg);
                        }
                    }
                });

        Channel sc = null;
        Channel cc = null;
        try {
            // Start server
            sc = sb.bind(TEST_ADDRESS).asStage().get();
            cc = cb.connect(TEST_ADDRESS).asStage().get();

            latch.await();
        } finally {
            closeChannel(cc);
            closeChannel(sc);
        }
    }

    @Test
    @Timeout(value = 5000, unit = TimeUnit.MILLISECONDS)
    public void testMaxMessagesPerReadRespectedWithAutoReadSharedGroup() throws Exception {
        testMaxMessagesPerReadRespected(sharedGroup, sharedGroup, true);
    }

    @Test
    @Timeout(value = 5000, unit = TimeUnit.MILLISECONDS)
    public void testMaxMessagesPerReadRespectedWithoutAutoReadSharedGroup() throws Exception {
        testMaxMessagesPerReadRespected(sharedGroup, sharedGroup, false);
    }

    @Test
    @Timeout(value = 5000, unit = TimeUnit.MILLISECONDS)
    public void testMaxMessagesPerReadRespectedWithAutoReadDifferentGroup() throws Exception {
        testMaxMessagesPerReadRespected(group1, group2, true);
    }

    @Test
    @Timeout(value = 5000, unit = TimeUnit.MILLISECONDS)
    public void testMaxMessagesPerReadRespectedWithoutAutoReadDifferentGroup() throws Exception {
        testMaxMessagesPerReadRespected(group1, group2, false);
    }

    private static void testMaxMessagesPerReadRespected(
            EventLoopGroup serverGroup, EventLoopGroup clientGroup, final boolean autoRead) throws Exception {
        final CountDownLatch countDownLatch = new CountDownLatch(5);
        Bootstrap cb = new Bootstrap();
        ServerBootstrap sb = new ServerBootstrap();

        cb.group(serverGroup)
                .channel(LocalChannel.class)
                .option(ChannelOption.AUTO_READ, autoRead)
                .option(ChannelOption.READ_HANDLE_FACTORY, new AdaptiveReadHandleFactory(1))
                .handler(new ChannelReadHandler(countDownLatch, autoRead));
        sb.group(clientGroup)
                .channel(LocalServerChannel.class)
                .childHandler(new ChannelHandler() {
                    @Override
                    public void channelActive(final ChannelHandlerContext ctx) {
                        for (int i = 0; i < 10; i++) {
                            ctx.write(i);
                        }
                        ctx.flush();
                    }
                });

        Channel sc = null;
        Channel cc = null;
        try {
            // Start server
            sc = sb.bind(TEST_ADDRESS).asStage().get();
            cc = cb.connect(TEST_ADDRESS).asStage().get();

            countDownLatch.await();
        } finally {
            closeChannel(cc);
            closeChannel(sc);
        }
    }

    @Test
    @Timeout(value = 5000, unit = TimeUnit.MILLISECONDS)
    public void testServerMaxMessagesPerReadRespectedWithAutoReadSharedGroup() throws Exception {
        testServerMaxMessagesPerReadRespected(sharedGroup, sharedGroup, true);
    }

    @Test
    @Timeout(value = 5000, unit = TimeUnit.MILLISECONDS)
    public void testServerMaxMessagesPerReadRespectedWithoutAutoReadSharedGroup() throws Exception {
        testServerMaxMessagesPerReadRespected(sharedGroup, sharedGroup, false);
    }

    @Test
    @Timeout(value = 5000, unit = TimeUnit.MILLISECONDS)
    public void testServerMaxMessagesPerReadRespectedWithAutoReadDifferentGroup() throws Exception {
        testServerMaxMessagesPerReadRespected(group1, group2, true);
    }

    @Test
    @Timeout(value = 5000, unit = TimeUnit.MILLISECONDS)
    public void testServerMaxMessagesPerReadRespectedWithoutAutoReadDifferentGroup() throws Exception {
        testServerMaxMessagesPerReadRespected(group1, group2, false);
    }

    private static void testServerMaxMessagesPerReadRespected(
            EventLoopGroup serverGroup, EventLoopGroup clientGroup, final boolean autoRead) throws Exception {
        final CountDownLatch countDownLatch = new CountDownLatch(5);
        Bootstrap cb = new Bootstrap();
        ServerBootstrap sb = new ServerBootstrap();

        cb.group(serverGroup)
                .channel(LocalChannel.class)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        // NOOP
                    }
                });

        sb.group(clientGroup)
                .channel(LocalServerChannel.class)
                .option(ChannelOption.AUTO_READ, autoRead)
                .option(ChannelOption.READ_HANDLE_FACTORY, new ServerChannelReadHandleFactory(1))
                .handler(new ChannelReadHandler(countDownLatch, autoRead))
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        // NOOP
                    }
                });

        Channel sc = null;
        Channel cc = null;
        try {
            // Start server
            sc = sb.bind(TEST_ADDRESS).asStage().get();
            for (int i = 0; i < 5; i++) {
                try {
                    cc = cb.connect(TEST_ADDRESS).asStage().get();
                } finally {
                    closeChannel(cc);
                }
            }

            countDownLatch.await();
        } finally {
            closeChannel(sc);
        }
    }

    private static final class ChannelReadHandler implements ChannelHandler {

        private final CountDownLatch latch;
        private final boolean autoRead;
        private int read;

        ChannelReadHandler(CountDownLatch latch, boolean autoRead) {
            this.latch = latch;
            this.autoRead = autoRead;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            if (!autoRead) {
                ctx.read();
            }
            ctx.fireChannelActive();
        }

        @Override
        public void channelRead(final ChannelHandlerContext ctx, Object msg) {
            assertEquals(0, read);
            read++;
            ctx.fireChannelRead(msg);
        }

        @Override
        public void channelReadComplete(final ChannelHandlerContext ctx) {
            assertEquals(1, read);
            latch.countDown();
            if (latch.getCount() > 0) {
                if (!autoRead) {
                    // The read will be scheduled 100ms in the future to ensure we not receive any
                    // channelRead calls in the meantime.
                    ctx.executor().schedule(() -> {
                        read = 0;
                        ctx.read();
                    }, 100, TimeUnit.MILLISECONDS);
                } else {
                    read = 0;
                }
            } else {
                read = 0;
            }
            ctx.fireChannelReadComplete();
        }

        @Override
        public void channelExceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.fireChannelExceptionCaught(cause);
            ctx.close();
        }
    }

    @Test
    public void testReadCompleteCalledOnHandle() throws Exception {
        Bootstrap cb = new Bootstrap();
        ServerBootstrap sb = new ServerBootstrap();

        cb.group(sharedGroup)
                .channel(LocalChannel.class)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        // NOOP
                    }
                });

        CountDownLatch serverLatch = new CountDownLatch(1);
        CountDownLatch childLatch = new CountDownLatch(1);

        sb.group(sharedGroup)
                .channel(LocalServerChannel.class)
                .option(ChannelOption.READ_HANDLE_FACTORY, new ReadCompleteReadAllocator(serverLatch))
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        // NOOP
                    }
                })
                .childOption(ChannelOption.READ_HANDLE_FACTORY, new ReadCompleteReadAllocator(childLatch));

        Channel sc = null;
        Channel cc = null;
        try {
            // Start server
            sc = sb.bind(TEST_ADDRESS).asStage().get();
            try {
                cc = cb.connect(TEST_ADDRESS).asStage().get();
                cc.writeAndFlush("msg").asStage().get();
            } finally {
                closeChannel(cc);
            }

            serverLatch.await();
            childLatch.await();
        } finally {
            closeChannel(sc);
        }
    }

    private static final class ReadCompleteReadAllocator extends MaxMessagesReadHandleFactory {
        private final CountDownLatch latch;
        ReadCompleteReadAllocator(CountDownLatch latch) {
            this.latch = latch;
        }

        @Override
        public MaxMessageReadHandle newMaxMessageHandle(int maxMessagesPerRead) {
            return new MaxMessageReadHandle(maxMessagesPerRead) {
                @Override
                public int estimatedBufferCapacity() {
                    return 128;
                }

                @Override
                public void readComplete() {
                    super.readComplete();
                    latch.countDown();
                }
            };
        }
    }
}
