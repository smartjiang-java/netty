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
package io.netty5.channel;

import io.netty5.buffer.api.Buffer;
import io.netty5.buffer.api.BufferAllocator;
import io.netty5.buffer.api.DefaultBufferAllocators;
import io.netty5.util.Resource;
import io.netty5.util.DefaultAttributeMap;
import io.netty5.util.concurrent.DefaultPromise;
import io.netty5.util.concurrent.EventExecutor;
import io.netty5.util.concurrent.Future;
import io.netty5.util.concurrent.Promise;
import io.netty5.util.internal.PlatformDependent;
import io.netty5.util.internal.StringUtil;
import io.netty5.util.internal.logging.InternalLogger;
import io.netty5.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.AlreadyConnectedException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ConnectionPendingException;
import java.nio.channels.NotYetConnectedException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.netty5.channel.ChannelOption.ALLOW_HALF_CLOSURE;
import static io.netty5.channel.ChannelOption.AUTO_CLOSE;
import static io.netty5.channel.ChannelOption.AUTO_READ;
import static io.netty5.channel.ChannelOption.BUFFER_ALLOCATOR;
import static io.netty5.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS;
import static io.netty5.channel.ChannelOption.MESSAGE_SIZE_ESTIMATOR;
import static io.netty5.channel.ChannelOption.READ_HANDLE_FACTORY;
import static io.netty5.channel.ChannelOption.TCP_FASTOPEN_CONNECT;
import static io.netty5.channel.ChannelOption.WRITE_BUFFER_WATER_MARK;
import static io.netty5.channel.ChannelOption.WRITE_HANDLE_FACTORY;
import static io.netty5.util.internal.ObjectUtil.checkInRange;
import static io.netty5.util.internal.ObjectUtil.checkPositiveOrZero;
import static java.util.Objects.requireNonNull;

/**
 * A skeletal {@link Channel} implementation.
 */
public abstract class AbstractChannel<P extends Channel, L extends SocketAddress, R extends SocketAddress>
        extends DefaultAttributeMap implements Channel {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(AbstractChannel.class);
    private static final MessageSizeEstimator DEFAULT_MSG_SIZE_ESTIMATOR = DefaultMessageSizeEstimator.DEFAULT;

    private static final int DEFAULT_CONNECT_TIMEOUT = 30000;

    private static final Set<ChannelOption<?>> SUPPORTED_CHANNEL_OPTIONS = supportedOptions();

    private final P parent;
    private final ChannelId id;
    private final ChannelPipeline pipeline;
    private final ClosePromise closePromise;
    private final Runnable fireChannelWritabilityChangedTask;
    private final EventLoop eventLoop;
    private final boolean supportingDisconnect;

    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<AbstractChannel> WRITABLE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(AbstractChannel.class, "writable");
    private volatile int writable = 1;
    private volatile ChannelOutboundBuffer outboundBuffer;
    private volatile L localAddress;
    private volatile R remoteAddress;
    private volatile boolean registered;

    private volatile ReadBufferAllocator currentBufferAllocator;

    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<AbstractChannel> AUTOREAD_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(AbstractChannel.class, "autoRead");

    private volatile BufferAllocator bufferAllocator = DefaultBufferAllocators.preferredAllocator();
    private volatile ReadHandleFactory readHandleFactory;

    private volatile WriteHandleFactory writeHandleFactory;

    private volatile MessageSizeEstimator msgSizeEstimator = DEFAULT_MSG_SIZE_ESTIMATOR;

    private volatile int connectTimeoutMillis = DEFAULT_CONNECT_TIMEOUT;

    @SuppressWarnings("FieldMayBeFinal")
    private volatile int autoRead = 1;
    private volatile boolean autoClose = true;
    private volatile WriteBufferWaterMark writeBufferWaterMark = WriteBufferWaterMark.DEFAULT;
    private volatile boolean allowHalfClosure;

    /** Cache for the string representation of this channel */
    private boolean strValActive;
    private String strVal;

    // All fields below are only called from within the EventLoop thread.
    private boolean closeInitiated;
    private Throwable initialCloseCause;
    private ReadBufferAllocator readBeforeActive;

    private ReadSink readSink;

    private WriteSink writeSink;

    private MessageSizeEstimator.Handle estimatorHandler;
    private boolean inWriteFlushed;
    /** true if the channel has never been registered, false otherwise */
    private boolean neverRegistered = true;
    private boolean neverActive = true;

    private boolean inputClosedSeenErrorOnRead;

    /**
     * The future of the current connection attempt.  If not null, subsequent
     * connection attempts will fail.
     */
    private Promise<Void> connectPromise;
    private Future<?> connectTimeoutFuture;
    private R requestedRemoteAddress;

    /**
     * Creates a new instance.
     *
     * @param parent                    the parent of this channel. {@code null} if there's no parent.
     * @param eventLoop                 the {@link EventLoop} which will be used.
     * @param supportingDisconnect      {@code true} if and only if the channel has the {@code disconnect()}
     *                                  operation that allows a user to disconnect and then call {
     *                                  @link Channel#connect(SocketAddress)} again, such as UDP/IP.
     */
    protected AbstractChannel(P parent, EventLoop eventLoop, boolean supportingDisconnect) {
        this(parent, eventLoop, supportingDisconnect, new AdaptiveReadHandleFactory(),
                new MaxMessagesWriteHandleFactory(Integer.MAX_VALUE));
    }

    /**
     * Creates a new instance.
     *
     * @param parent                        the parent of this channel. {@code null} if there's no parent.
     * @param eventLoop                     the {@link EventLoop} which will be used.
     * @param supportingDisconnect          {@code true} if and only if the channel has the {@code disconnect()}
     *                                      operation that allows a user to disconnect and then call {
     *                                      @link Channel#connect(SocketAddress)} again, such as UDP/IP.
     * @param defaultReadHandleFactory      the {@link ReadHandleFactory} that is used by default.
     * @param defaultWriteHandleFactory     the {@link WriteHandleFactory} that is used by default.
     */
    protected AbstractChannel(P parent, EventLoop eventLoop,
                              boolean supportingDisconnect, ReadHandleFactory defaultReadHandleFactory,
                              WriteHandleFactory defaultWriteHandleFactory) {
        this(parent, eventLoop, supportingDisconnect, defaultReadHandleFactory, defaultWriteHandleFactory,
                DefaultChannelId.newInstance());
    }

    /**
     * Creates a new instance.
     *
     * @param parent                        the parent of this channel. {@code null} if there's no parent.
     * @param eventLoop                     the {@link EventLoop} which will be used.
     * @param supportingDisconnect          {@code true} if and only if the channel has the {@code disconnect()}
     *                                      operation that allows a user to disconnect and then call {
     *                                      @link Channel#connect(SocketAddress)} again, such as UDP/IP.
     * @param defaultReadHandleFactory      the {@link ReadHandleFactory} that is used by default.
     * @param defaultWriteHandleFactory     the {@link WriteHandleFactory} that is used by default.
     * @param id                            the {@link ChannelId} which will be used.
     */
    protected AbstractChannel(P parent, EventLoop eventLoop, boolean supportingDisconnect,
                              ReadHandleFactory defaultReadHandleFactory, WriteHandleFactory defaultWriteHandleFactory,
                              ChannelId id) {
        this.parent = parent;
        this.eventLoop = validateEventLoopGroup(eventLoop, "eventLoop", getClass());
        this.id = requireNonNull(id, "id");
        this.supportingDisconnect = supportingDisconnect;
        readHandleFactory = requireNonNull(defaultReadHandleFactory, "defaultReadHandleFactory");
        writeHandleFactory = requireNonNull(defaultWriteHandleFactory, "defaultWriteHandleFactory");
        closePromise = new ClosePromise(eventLoop);
        outboundBuffer = new ChannelOutboundBuffer(eventLoop);
        pipeline = newChannelPipeline();
        fireChannelWritabilityChangedTask = () -> pipeline().fireChannelWritabilityChanged();
    }

    protected static <T extends EventLoopGroup> T validateEventLoopGroup(
            T group, String name, Class<? extends Channel> channelType) {
        requireNonNull(group, name);
        if (!group.isCompatible(channelType)) {
            throw new IllegalArgumentException(group + " does not support channel of type " +
                            StringUtil.simpleClassName(channelType));
        }
        return group;
    }

    @Override
    public final ChannelId id() {
        return id;
    }

    /**
     * Returns a new {@link ChannelPipeline} instance.
     */
    protected ChannelPipeline newChannelPipeline() {
        return new DefaultAbstractChannelPipeline(this);
    }

    @Override
    public final BufferAllocator bufferAllocator() {
        return bufferAllocator;
    }

    @Override
    public final P parent() {
        return parent;
    }

    @Override
    public final ChannelPipeline pipeline() {
        return pipeline;
    }

    @Override
    public final EventLoop executor() {
        return eventLoop;
    }

    @Override
    public final L localAddress() {
        L localAddress = this.localAddress;
        if (localAddress == null) {
            try {
                this.localAddress = localAddress = localAddress0();
            } catch (Error e) {
                throw e;
            } catch (Throwable t) {
                // Sometimes fails on a closed socket in Windows.
                return null;
            }
        }
        return localAddress;
    }

    @Override
    public final R remoteAddress() {
        R remoteAddress = this.remoteAddress;
        if (remoteAddress == null) {
            try {
                this.remoteAddress = remoteAddress = remoteAddress0();
            } catch (Error e) {
                throw e;
            } catch (Throwable t) {
                // Sometimes fails on a closed socket in Windows.
                return null;
            }
        }
        return remoteAddress;
    }

    protected final void cacheAddresses(L localAddress, R  remoteAddress) {
        this.localAddress = localAddress;
        this.remoteAddress = remoteAddress;
    }

    @Override
    public final boolean isRegistered() {
        return registered;
    }

    @Override
    public final Future<Void> closeFuture() {
        return closePromise;
    }

    private long totalPending() {
        ChannelOutboundBuffer buf = outboundBuffer();
        if (buf == null) {
            return -1;
        }
        return buf.totalPendingWriteBytes() + pipeline().pendingOutboundBytes();
    }

    @Override
    public final long writableBytes() {
        long totalPending = totalPending();
        if (totalPending == -1) {
            // Already closed.
            return 0;
        }

        long bytes = writeBufferWaterMark.high() -
                totalPending;
        // If bytes is negative we know we are not writable.
        if (bytes > 0) {
            return WRITABLE_UPDATER.get(this) == 0 ? 0: bytes;
        }
        return 0;
    }

    /**
     * Returns the ID of this channel.
     */
    @Override
    public final int hashCode() {
        return id.hashCode();
    }

    /**
     * Returns {@code true} if and only if the specified object is identical
     * with this channel (i.e: {@code this == o}).
     */
    @Override
    public final boolean equals(Object o) {
        return this == o;
    }

    @Override
    public final int compareTo(Channel o) {
        if (this == o) {
            return 0;
        }

        return id().compareTo(o.id());
    }

    /**
     * Returns the {@link String} representation of this channel.  The returned
     * string contains the {@linkplain #hashCode() ID}, {@linkplain #localAddress() local address},
     * and {@linkplain #remoteAddress() remote address} of this channel for
     * easier identification.
     */
    @Override
    public String toString() {
        boolean active = isActive();
        if (strValActive == active && strVal != null) {
            return strVal;
        }

        SocketAddress remoteAddr = remoteAddress();
        SocketAddress localAddr = localAddress();
        if (remoteAddr != null) {
            StringBuilder buf = new StringBuilder(96)
                .append("[id: 0x")
                .append(id.asShortText())
                .append(", L:")
                .append(localAddr)
                .append(active? " - " : " ! ")
                .append("R:")
                .append(remoteAddr)
                .append(']');
            strVal = buf.toString();
        } else if (localAddr != null) {
            StringBuilder buf = new StringBuilder(64)
                .append("[id: 0x")
                .append(id.asShortText())
                .append(", L:")
                .append(localAddr)
                .append(']');
            strVal = buf.toString();
        } else {
            StringBuilder buf = new StringBuilder(16)
                .append("[id: 0x")
                .append(id.asShortText())
                .append(']');
            strVal = buf.toString();
        }

        strValActive = active;
        return strVal;
    }

    protected final void readIfIsAutoRead() {
        assertEventLoop();

        if (readBeforeActive != null) {
            ReadBufferAllocator readBufferAllocator = readBeforeActive;
            readBeforeActive = null;
            readTransport(readBufferAllocator);
        } else if (isAutoRead()) {
            read();
        }
    }

    private void assertEventLoop() {
        assert eventLoop.inEventLoop();
    }

    protected final ReadHandleFactory.ReadHandle readHandle() {
        return readSink().readHandle;
    }

    protected final WriteHandleFactory.WriteHandle writeHandle() {
        return writeSink().writeHandle;
    }

    private ReadSink readSink() {
        assertEventLoop();

        if (readSink == null) {
            readSink = new ReadSink(getReadHandleFactory().newHandle(this));
        }
        return readSink;
    }

    private WriteSink writeSink() {
        assertEventLoop();

        if (writeSink == null) {
            writeSink = new WriteSink(getWriteHandleFactory().newHandle(this));
        }
        return writeSink;
    }

    private void registerTransport(final Promise<Void> promise) {
        assertEventLoop();

        if (isRegistered()) {
            promise.setFailure(new IllegalStateException("registered to an event loop already"));
            return;
        }

        try {
            // check if the channel is still open as it could be closed in the mean time when the register
            // call was outside of the eventLoop
            if (!promise.setUncancellable() || !ensureOpen(promise)) {
                return;
            }
            boolean firstRegistration = neverRegistered;
            executor().registerForIo(this).addListener(f -> {
                if (f.isSuccess()) {

                    neverRegistered = false;
                    registered = true;

                    safeSetSuccess(promise);
                    pipeline.fireChannelRegistered();
                    // Only fire a channelActive if the channel has never been registered. This prevents firing
                    // multiple channel actives if the channel is deregistered and re-registered.
                    if (isActive()) {
                        if (firstRegistration) {
                            fireChannelActiveIfNotActiveBefore();
                        }
                        readIfIsAutoRead();
                    }
                } else {
                    // Close the channel directly to avoid FD leak.
                    closeNowAndFail(promise, f.cause());
                }
            });

        } catch (Throwable t) {
            // Close the channel directly to avoid FD leak.
            closeNowAndFail(promise, t);
        }
    }

    /**
     * Calls {@link ChannelPipeline#fireChannelActive()} if it was not done yet.
     *
     * @return {@code true} if {@link ChannelPipeline#fireChannelActive()} was called, {@code false} otherwise.
     */
    private boolean fireChannelActiveIfNotActiveBefore() {
        if (neverActive) {
            neverActive = false;
            pipeline().fireChannelActive();
            return true;
        }
        return false;
    }

    private void closeNowAndFail(Promise<Void> promise, Throwable cause) {
        closeForciblyTransport();
        closePromise.setClosed();
        safeSetFailure(promise, cause);
    }

    private void bindTransport(final SocketAddress localAddress, final Promise<Void> promise) {
        assertEventLoop();

        if (!promise.setUncancellable() || !ensureOpen(promise)) {
            return;
        }

        // See: https://github.com/netty/netty/issues/576
        if (localAddress instanceof InetSocketAddress && isOptionSupported(ChannelOption.SO_BROADCAST) &&
                Boolean.TRUE.equals(getOption(ChannelOption.SO_BROADCAST)) &&
            !((InetSocketAddress) localAddress).getAddress().isAnyLocalAddress() &&
            !PlatformDependent.isWindows() && !PlatformDependent.maybeSuperUser()) {
            // Warn a user about the fact that a non-root user can't receive a
            // broadcast packet on *nix if the socket is bound on non-wildcard address.
            logger.warn(
                    "A non-root user can't receive a broadcast packet if the socket " +
                    "is not bound to a wildcard address; binding to a non-wildcard " +
                    "address (" + localAddress + ") anyway as requested.");
        }

        boolean wasActive = isActive();
        try {
            doBind(localAddress);
        } catch (Throwable t) {
            safeSetFailure(promise, t);
            closeIfClosed();
            return;
        }

        if (!wasActive && isActive()) {
            invokeLater(() -> {
                if (fireChannelActiveIfNotActiveBefore()) {
                    readIfIsAutoRead();
                }
            });
        }

        safeSetSuccess(promise);
    }

    private void disconnectTransport(final Promise<Void> promise) {
        assertEventLoop();

        if (!promise.setUncancellable()) {
            return;
        }

        boolean wasActive = isActive();
        try {
            doDisconnect();
            // Reset remoteAddress and localAddress
            remoteAddress = null;
            localAddress = null;
            neverActive = true;
        } catch (Throwable t) {
            safeSetFailure(promise, t);
            closeIfClosed();
            return;
        }

        if (wasActive && !isActive()) {
            invokeLater(pipeline::fireChannelInactive);
        }

        safeSetSuccess(promise);
        closeIfClosed(); // doDisconnect() might have closed the channel
    }

    protected void closeTransport(final Promise<Void> promise) {
        assertEventLoop();

        ClosedChannelException closedChannelException =
                StacklessClosedChannelException.newInstance(AbstractChannel.class, "close(Promise)");
        close(promise, closedChannelException, closedChannelException);
    }

    private void updateWritabilityIfNeeded(boolean notify, boolean notifyLater) {
        long totalPending = totalPending();

        if (totalPending > writeBufferWaterMark.high()) {
            if (WRITABLE_UPDATER.compareAndSet(this, 1, 0)) {
                fireChannelWritabilityChangedIfNeeded(notify, notifyLater);
            }
        } else if (totalPending < writeBufferWaterMark.low()) {
            if (WRITABLE_UPDATER.compareAndSet(this, 0, 1)) {
                fireChannelWritabilityChangedIfNeeded(notify, notifyLater);
            }
        }
    }

    private void fireChannelWritabilityChangedIfNeeded(boolean notify, boolean notifyLater) {
        if (!notify) {
            return;
        }
        if (notifyLater) {
            executor().execute(fireChannelWritabilityChangedTask);
        } else {
            pipeline().fireChannelWritabilityChanged();
        }
    }

    /**
     * Shutdown the output portion of the corresponding {@link Channel}.
     * For example this will clean up the {@link ChannelOutboundBuffer} and not allow any more writes.
     * @param cause The cause which may provide rational for the shutdown.
     */
    private boolean shutdownOutput(final Promise<Void> promise, Throwable cause) {
        final ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
        if (outboundBuffer == null) {
            promise.setFailure(new ClosedChannelException());
            return false;
        }
        this.outboundBuffer = null; // Disallow adding any messages and flushes to outboundBuffer.

        final Throwable shutdownCause = cause == null ?
                new ChannelOutputShutdownException("Channel output shutdown") :
                new ChannelOutputShutdownException("Channel output shutdown", cause);
        // When a side enables SO_LINGER and calls showdownOutput(...) to start TCP half-closure
        // we can not call doDeregister here because we should ensure this side in fin_wait2 state
        // can still receive and process the data which is send by another side in the close_wait state。
        // See https://github.com/netty/netty/issues/11981
        try {
            // The shutdown function does not block regardless of the SO_LINGER setting on the socket
            // so we don't need to use GlobalEventExecutor to execute the shutdown
            doShutdown(ChannelShutdownDirection.Outbound);
            promise.setSuccess(null);
        } catch (Throwable err) {
            promise.setFailure(err);
        } finally {
            outboundBuffer.failFlushedAndClose(shutdownCause, shutdownCause);
        }
        return true;
    }

    private void close(final Promise<Void> promise, final Throwable cause,
                       final ClosedChannelException closeCause) {
        if (!promise.setUncancellable()) {
            return;
        }

        if (closeInitiated) {
            if (closePromise.isDone()) {
                // Closed already.
                safeSetSuccess(promise);
            } else {
                // This means close() was called before, so we just register a listener and return
                closePromise.addListener(promise, (p, future) -> p.setSuccess(null));
            }
            return;
        }

        closeInitiated = true;

        final boolean wasActive = isActive();
        final ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
        this.outboundBuffer = null; // Disallow adding any messages and flushes to outboundBuffer.
        Future<Executor> closeExecutorFuture = prepareToClose();
        if (closeExecutorFuture != null) {
            closeExecutorFuture.addListener(f -> {
                if (f.isFailed()) {
                    logger.warn("We couldnt obtain the closeExecutor", f.cause());
                    closeNow(outboundBuffer, wasActive, promise, cause, closeCause);
                } else {
                    Executor closeExecutor = f.getNow();
                    closeExecutor.execute(() -> {
                        try {
                            // Execute the close.
                            doClose0(promise);
                        } finally {
                            // Call invokeLater so closeAndDeregister is executed in the EventLoop again!
                            invokeLater(() -> {
                                closeAndUpdateWritability(outboundBuffer, cause, closeCause);
                                fireChannelInactiveAndDeregister(wasActive);
                            });
                        }
                    });
                }
            });
        } else {
            closeNow(outboundBuffer, wasActive, promise, cause, closeCause);
        }
    }

    private void closeNow(ChannelOutboundBuffer outboundBuffer, boolean wasActive, Promise<Void> promise,
                          Throwable cause, ClosedChannelException closeCause) {
        try {
            // Close the channel and fail the queued messages in all cases.
            doClose0(promise);
        } finally {
            closeAndUpdateWritability(outboundBuffer, cause, closeCause);
        }
        if (inWriteFlushed) {
            invokeLater(() -> fireChannelInactiveAndDeregister(wasActive));
        } else {
            fireChannelInactiveAndDeregister(wasActive);
        }
    }

    private void closeAndUpdateWritability(
            ChannelOutboundBuffer outboundBuffer, Throwable cause, Throwable closeCause) {
        if (outboundBuffer != null) {
            // Fail all the queued messages
            outboundBuffer.failFlushedAndClose(cause, closeCause);
            updateWritabilityIfNeeded(false, false);
        }
    }

    private void doClose0(Promise<Void> promise) {
        try {
            cancelConnect();
            doClose();
            closePromise.setClosed();
            safeSetSuccess(promise);
        } catch (Throwable t) {
            closePromise.setClosed();
            safeSetFailure(promise, t);
        }
    }

    private void fireChannelInactiveAndDeregister(final boolean wasActive) {
        deregister(newPromise(), wasActive && !isActive());
    }

    protected final void closeForciblyTransport() {
        assertEventLoop();

        try {
            cancelConnect();
            doClose();
        } catch (Exception e) {
            logger.warn("Failed to close a channel.", e);
        }
    }

    private void cancelConnect() {
        Promise<Void> promise = connectPromise;
        if (promise != null) {
            // Use tryFailure() instead of setFailure() to avoid the race against cancel().
            promise.tryFailure(new ClosedChannelException());
            connectPromise = null;
        }

        Future<?> future = connectTimeoutFuture;
        if (future != null) {
            future.cancel();
            connectTimeoutFuture = null;
        }
    }

    protected final void shutdownTransport(ChannelShutdownDirection direction, Promise<Void> promise) {
        assertEventLoop();

        if (!promise.setUncancellable()) {
            return;
        }
        if (!isActive()) {
            if (isOpen()) {
                promise.setFailure(new NotYetConnectedException());
            } else {
                promise.setFailure(new ClosedChannelException());
            }
            return;
        }
        if (isShutdown(direction)) {
            // Already shutdown so let's just make this a noop.
            promise.setSuccess(null);
            return;
        }
        boolean fireEvent = false;
        switch (direction) {
            case Outbound:
                fireEvent = shutdownOutput(promise, null);
                break;
            case Inbound:
                try {
                    doShutdown(direction);
                    promise.setSuccess(null);
                    fireEvent = true;
                } catch (Throwable cause) {
                    promise.setFailure(cause);
                }
                break;
            default:
                // Should never happen
                promise.setFailure(new AssertionError());
                break;
        }
        if (fireEvent) {
            pipeline().fireChannelShutdown(direction);
        }
    }

    private void deregisterTransport(final Promise<Void> promise) {
        assertEventLoop();

        deregister(promise, false);
    }

    private void deregister(final Promise<Void> promise, final boolean fireChannelInactive) {
        if (!promise.setUncancellable()) {
            return;
        }

        if (!registered) {
            safeSetSuccess(promise);
            return;
        }

        // As a user may call deregister() from within any method while doing processing in the ChannelPipeline,
        // we need to ensure we do the actual deregister operation later. This is needed as for example,
        // we may be in the ByteToMessageDecoder.callDecode(...) method and so still try to do processing in
        // the old EventLoop while the user already registered the Channel to a new EventLoop. Without delay,
        // the deregister operation this could lead to have a handler invoked by different EventLoop and so
        // threads.
        //
        // See:
        // https://github.com/netty/netty/issues/4435
        invokeLater(() -> {
            try {
                eventLoop.deregisterForIo(this).addListener(f -> {
                    if (f.isFailed()) {
                        logger.warn("Unexpected exception occurred while deregistering a channel.", f.cause());
                    }
                    deregisterDone(fireChannelInactive, promise);
                });
            } catch (Throwable t) {
                logger.warn("Unexpected exception occurred while deregistering a channel.", t);
                deregisterDone(fireChannelInactive, promise);
            }
        });
    }

    private void deregisterDone(boolean fireChannelInactive, Promise<Void> promise) {
        if (fireChannelInactive) {
            pipeline.fireChannelInactive();
        }
        // Ensure we also clear all scheduled reads so its possible to schedule again if the Channel is re-registered.
        clearScheduledRead();

        // Some transports like local and AIO does not allow the deregistration of
        // an open channel. Their doDeregister() calls close(). Consequently,
        // close() calls deregister() again - no need to fire channelUnregistered, so check
        // if it was registered.
        if (registered) {
            registered = false;
            pipeline.fireChannelUnregistered();

            if (!isOpen()) {
                // Remove all handlers from the ChannelPipeline. This is needed to ensure
                // handlerRemoved(...) is called and so resources are released.
                while (!pipeline.isEmpty()) {
                    try {
                        pipeline.removeLast();
                    } catch (NoSuchElementException ignore) {
                        // try again as there may be a race when someone outside the EventLoop removes
                        // handlers concurrently as well.
                    }
                }
            }
        }
        safeSetSuccess(promise);
    }

    private void readTransport(ReadBufferAllocator readBufferAllocator) {
        assertEventLoop();

        if (!isActive()) {
            readBeforeActive = readBufferAllocator;
            return;
        }

        if (isShutdown(ChannelShutdownDirection.Inbound)) {
            // Input was shutdown so not try to read.
            return;
        }
        boolean wasReadPending = currentBufferAllocator != null;
        currentBufferAllocator = readBufferAllocator;
        try {
            doRead(wasReadPending);
        } catch (final Exception e) {
            invokeLater(() -> pipeline.fireChannelExceptionCaught(e));
            closeTransport(newPromise());
        }
    }

    /**
     * Try reading from the underlying transport now.
     */
    protected final void readNow() {
        assert executor().inEventLoop();

        if (isShutdown(ChannelShutdownDirection.Inbound) && (inputClosedSeenErrorOnRead || !isAllowHalfClosure())) {
            // There is nothing to read anymore.
            clearScheduledRead();
            return;
        }

        final boolean closed;
        try {
            ReadSink readSink = readSink();
            try {
                closed = doReadNow(readSink);
            } catch (Throwable cause) {
                if (readSink.completeFailure(cause)) {
                    shutdownReadSide();
                } else {
                    closeTransport(newPromise());
                }
                return;
            }
            readSink.complete();
        } finally {
            // Check if there is a readPending which was not processed yet.
            // This could be for two reasons:
            // * The user called Channel.read() or ChannelHandlerContext.read() in channelRead(...) method
            // * The user called Channel.read() or ChannelHandlerContext.read() in channelReadComplete(...) method
            //
            // See https://github.com/netty/netty/issues/2254
            if (!isReadPending() && !isAutoRead()) {
                clearScheduledRead();
            }
        }

        if (closed) {
            shutdownReadSide();
        } else {
            readIfIsAutoRead();
        }
    }

    protected final void shutdownReadSide() {
        if (!isShutdown(ChannelShutdownDirection.Inbound)) {
            if (isAllowHalfClosure()) {
                shutdownTransport(ChannelShutdownDirection.Inbound, newPromise());
            } else {
                closeTransport(newPromise());
            }
        } else {
            inputClosedSeenErrorOnRead = true;
        }
    }

    private void clearScheduledRead() {
        assertEventLoop();
        currentBufferAllocator = null;
        doClearScheduledRead();
    }

    /**
     * Clear any previous scheduled read. By default this method does nothing but implementations might override it to
     * add extra logic.
     */
    protected void doClearScheduledRead() {
        // Do nothing by default
    }

    /**
     * Read messages now from the transport and so propagate these through the {@link ChannelPipeline}.
     *
     * <strong>This method should never be called directly by sub-classes, use {@link #readNow()}.</strong>
     *
     * @param readSink      the {@link Consumer} that should be called with methods that are read from the transport
     *                      to propagate these.
     * @return {@code true} if the channel should be shutdown / closed.
     */
    protected abstract boolean doReadNow(ReadSink readSink) throws Exception;

    /**
     * Returns {@code true} if a read is currently scheduled and pending for later execution.
     *
     * @return if a read is pending.
     */
    protected final boolean isReadPending() {
        assertEventLoop();
        return currentBufferAllocator != null;
    }

    private void writeTransport(Object msg, Promise<Void> promise) {
        assertEventLoop();

        ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
        if (outboundBuffer == null) {
            try {
                // release message now to prevent resource-leak
                Resource.dispose(msg);
            } finally {
                // If the outboundBuffer is null we know the channel was closed or the outbound was shutdown, so
                // need to fail the future right away. If it is not null the handling of the rest
                // will be done in writeFlushed()
                // See https://github.com/netty/netty/issues/2362
                final Throwable cause;
                if (!isActive()) {
                    cause = newClosedChannelException(initialCloseCause, "write(Object, Promise)");
                } else {
                    cause = ChannelOutputShutdownException.newInstance(AbstractChannel.class,
                            "writeTransport(Object, Promise)");
                }
                safeSetFailure(promise, cause);
            }
            return;
        }

        int size;
        try {
            msg = filterOutboundMessage(msg);
            if (estimatorHandler == null) {
                estimatorHandler = getMessageSizeEstimator().newHandle();
            }
            size = estimatorHandler.size(msg);
            if (size < 0) {
                size = 0;
            }
        } catch (Throwable t) {
            try {
                Resource.dispose(msg);
            } catch (Throwable inner) {
                t.addSuppressed(inner);
            } finally {
                safeSetFailure(promise, t);
            }
            return;
        }

        outboundBuffer.addMessage(msg, size, promise);
        updateWritabilityIfNeeded(true, false);
    }

    private void flushTransport() {
        assertEventLoop();

        ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
        if (outboundBuffer == null) {
            return;
        }

        outboundBuffer.addFlush();
        writeFlushed();
    }

    /**
     * Returns {@code true} if flushed messages should not be tried to write when calling {@link #flush()}. Instead
     * these will be written once {@link #writeFlushedNow()} is called, which is typically done once the underlying
     * transport becomes writable again.
     *
     * @return {@code true} if write will be done later on by calling {@link #writeFlushedNow()},
     * {@code false} otherwise.
     */
    protected boolean isWriteFlushedScheduled() {
        return false;
    }

    /**
     * Writing previous flushed messages if {@link #isWriteFlushedScheduled()} returns {@code false}. If {
     */
    protected final void writeFlushed() {
        assertEventLoop();

        if (isWriteFlushedScheduled()) {
            return;
        }
        writeFlushedNow();
    }

    /**
     * Writing previous flushed messages now.
     */
    protected final void writeFlushedNow() {
        assertEventLoop();

        if (inWriteFlushed) {
            // Avoid re-entrance
            return;
        }

        final ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
        if (outboundBuffer == null || outboundBuffer.isEmpty()) {
            return;
        }

        inWriteFlushed = true;

        try {
            // Mark all pending write requests as failure if the channel is inactive.
            if (!isActive()) {
                // Check if we need to generate the exception at all.
                if (!outboundBuffer.isEmpty()) {
                    if (isOpen()) {
                        outboundBuffer.failFlushed(new NotYetConnectedException());
                        updateWritabilityIfNeeded(true, true);
                    } else {
                        // Do not trigger channelWritabilityChanged because the channel is closed already.
                        outboundBuffer.failFlushed(newClosedChannelException(initialCloseCause, "writeFlushed()"));
                    }
                }
                return;
            }

            WriteSink writeSink = writeSink();
            writeSink.processWriteLoop(outboundBuffer);
        } finally {
            inWriteFlushed = false;
        }
    }

    /**
     * Called once the write loop completed. Subclasses might override this methods for custom logic.
     *
     * @param allWritten    {@code true} if all messages were written during the write loop, {@code false} otherwise.
     */
    protected void writeLoopComplete(boolean allWritten) {
        if (!allWritten) {
            // Schedule a new write.
            executor().execute(this::writeFlushed);
        }
    }

    private void closeWithErrorFromWriteFlushed(Throwable t) {
        /*
         * Just call {@link #close(Promise, Throwable, boolean)} here which will take care of
         * failing all flushed messages and also ensure the actual close of the underlying transport
         * will happen before the promises are notified.
         *
         * This is needed as otherwise {@link #isActive()} , {@link #isOpen()} and {@link #writableBytes()}}
         * may still return {@code true} / {@code > 0} even if the channel should be closed as result of
         * the exception.
         */
        initialCloseCause = t;
        close(newPromise(), t, newClosedChannelException(t, "writeFlushed()"));
    }

    private void handleWriteError(Throwable t) {
        assertEventLoop();

        if (t instanceof IOException && isAutoClose()) {
            closeWithErrorFromWriteFlushed(t);
        } else {
            try {
                if (shutdownOutput(newPromise(), t)) {
                    pipeline().fireChannelShutdown(ChannelShutdownDirection.Outbound);
                }
            } catch (Throwable t2) {
                initialCloseCause = t;
                close(newPromise(), t2, newClosedChannelException(t, "writeFlushed()"));
            }
        }
    }

    private ClosedChannelException newClosedChannelException(Throwable cause, String method) {
        ClosedChannelException exception =
                StacklessClosedChannelException.newInstance(AbstractChannel.class, method);
        if (cause != null) {
            exception.initCause(cause);
        }
        return exception;
    }

    private void sendOutboundEventTransport(Object event, Promise<Void> promise) {
        Resource.dispose(event);
        promise.setSuccess(null);
    }

    private boolean ensureOpen(Promise<Void> promise) {
        if (isOpen()) {
            return true;
        }

        safeSetFailure(promise, newClosedChannelException(initialCloseCause, "ensureOpen(Promise)"));
        return false;
    }

    /**
     * Marks the specified {@code promise} as success.  If the {@code promise} is done already, log a message.
     */
    private void safeSetSuccess(Promise<Void> promise) {
        if (!promise.trySuccess(null)) {
            logger.warn("Failed to mark a promise as success because it is done already: {}", promise);
        }
    }

    /**
     * Marks the specified {@code promise} as failure.  If the {@code promise} is done already, log a message.
     */
    private void safeSetFailure(Promise<Void> promise, Throwable cause) {
        if (!promise.tryFailure(cause)) {
            logger.warn("Failed to mark a promise as failure because it's done already: {}", promise, cause);
        }
    }

    private void closeIfClosed() {
        assertEventLoop();

        if (isOpen()) {
            return;
        }
        closeTransport(newPromise());
    }

    private void invokeLater(Runnable task) {
        try {
            // This method is used by outbound operation implementations to trigger an inbound event later.
            // They do not trigger an inbound event immediately because an outbound operation might have been
            // triggered by another inbound event handler method.  If fired immediately, the call stack
            // will look like this for example:
            //
            //   handlerA.inboundBufferUpdated() - (1) an inbound handler method closes a connection.
            //   -> handlerA.ctx.close()
            //      -> channel.unsafe.close()
            //         -> handlerA.channelInactive() - (2) another inbound handler method called while in (1) yet
            //
            // which means the execution of two inbound handler methods of the same handler overlap undesirably.
            executor().execute(task);
        } catch (RejectedExecutionException e) {
            logger.warn("Can't invoke task later as EventLoop rejected it", e);
        }
    }

    /**
     * Appends the remote address to the message of the exceptions caused by connection attempt failure.
     */
    protected static Throwable annotateConnectException(Throwable cause, SocketAddress remoteAddress) {
        if (cause instanceof ConnectException) {
            return new AnnotatedConnectException((ConnectException) cause, remoteAddress);
        }
        if (cause instanceof NoRouteToHostException) {
            return new AnnotatedNoRouteToHostException((NoRouteToHostException) cause, remoteAddress);
        }
        if (cause instanceof SocketException) {
            return new AnnotatedSocketException((SocketException) cause, remoteAddress);
        }

        return cause;
    }

    /**
     * Prepares to close the {@link Channel}. If this method returns an {@link Executor}, the
     * caller must call the {@link Executor#execute(Runnable)} method with a task that calls
     * {@link #doClose()} on the returned {@link Executor}. If this method returns {@code null},
     * {@link #doClose()} must be called from the caller thread. (i.e. {@link EventLoop})
     */
    protected Future<Executor> prepareToClose() {
        return null;
    }

    /**
     * Returns the {@link ChannelOutboundBuffer} that is used by this {@link AbstractChannel}. This might be
     * {@code null} if no more writes are allowed.
     *
     * @return the outbound buffer.
     */
    final ChannelOutboundBuffer outboundBuffer() {
        return outboundBuffer;
    }

    /**
     * Returns the {@link SocketAddress} which is bound locally.
     */
    protected abstract L localAddress0();

    /**
     * Return the {@link SocketAddress} which the {@link Channel} is connected to.
     */
    protected abstract R remoteAddress0();

    /**
     * Bind the {@link Channel} to the {@link SocketAddress}
     */
    protected abstract void doBind(SocketAddress localAddress) throws Exception;

    /**
     * Disconnect this {@link Channel} from its remote peer
     */
    protected abstract void doDisconnect() throws Exception;

    /**
     * Close the {@link Channel}
     */
    protected abstract void doClose() throws Exception;

    /**
     * Shutdown one direction of the {@link Channel}.
     *
     * @param direction     the direction to shutdown.
     * @throws Exception    thrown on error.
     */
    protected abstract void doShutdown(ChannelShutdownDirection direction) throws Exception;

    /**
     * Schedule a read operation.
     *
     * @param wasReadPendingAlready {@code true} if a read was already pending when {@link #read()} was called.
     */
    protected abstract void doRead(boolean wasReadPendingAlready) throws Exception;

    /**
     * Called in a loop when writes should be performed until this methods returns {@code false} or there are
     * no more messages to write.
     *
     * @param writeSink                             the {@link WriteSink} that must be completed with the write
     *                                              progress.
     *                                              {@link WriteSink#complete(long, long, int, boolean)} or
     *                                              {@link WriteSink#complete(long, Throwable, boolean)} must be called
     *                                              exactly once before this method returns non-exceptional.
     * @throws Exception                            if and error happened during writing.
     */
    protected abstract void doWriteNow(WriteSink writeSink) throws Exception;

    /**
     * Connect to remote peer.
     *
     * @param remoteAddress     the address of the remote peer.
     * @param localAddress      the local address of this channel.
     * @param initialData       the initial data that is written during connect
     *                          (if {@link ChannelOption#TCP_FASTOPEN_CONNECT} is supported)
     * @return                  {@code true} if the connect was completed, {@code false} if {@link #finishConnect()}
     *                          will be called later again to try finishing the connect.
     * @throws Exception        thrown on error.
     */
    protected abstract boolean doConnect(
            SocketAddress remoteAddress, SocketAddress localAddress, Buffer initialData) throws Exception;

    /**
     * Finish a connect request.
     *
     * @param requestedRemoteAddress    the remote address of the peer.
     * @return                  {@code true} if the connect was completed, {@code false} if {@link #finishConnect()}
     *                          will be called later again to try finishing the connect.
     * @throws Exception        thrown on error.
     */
    protected abstract boolean doFinishConnect(R requestedRemoteAddress) throws Exception;

    /**
     * Returns if a connect request was issued before and we are waiting for {@link #finishConnect()} to be called.
     *
     * @return {@code true} if there is an outstanding connect request.
     */
    protected final boolean isConnectPending() {
        assertEventLoop();
        return connectPromise != null;
    }

    @SuppressWarnings("unchecked")
    private void connectTransport(
            SocketAddress remoteAddress, SocketAddress localAddress, Promise<Void> promise) {
        assertEventLoop();
        if (!promise.setUncancellable() || !ensureOpen(promise)) {
            return;
        }

        try {
            if (connectPromise != null) {
                throw new ConnectionPendingException();
            }

            if (remoteAddress() != null) {
                // Already connected to a remote host.
                throw new AlreadyConnectedException();
            }

            boolean wasActive = isActive();
            int readable = 0;
            Buffer message = null;
            if (isOptionSupported(ChannelOption.TCP_FASTOPEN_CONNECT) && getOption(TCP_FASTOPEN_CONNECT)) {
                outboundBuffer.addFlush();
                Object current = outboundBuffer.current();
                if (current instanceof Buffer) {
                    message = (Buffer) current;
                    readable = message.readableBytes();
                }
            }
            if (doConnect(remoteAddress, localAddress, message)) {
                fulfillConnectPromise(promise, wasActive);
                if (message != null) {
                    outboundBuffer.removeBytes(readable - message.readableBytes());
                }
            } else {
                connectPromise = promise;
                requestedRemoteAddress = (R) remoteAddress;

                // Schedule connect timeout.
                int connectTimeoutMillis = getConnectTimeoutMillis();
                if (connectTimeoutMillis > 0) {
                    connectTimeoutFuture = executor().schedule(() -> {
                        Promise<Void> connectPromise = this.connectPromise;
                        if (connectPromise != null && !connectPromise.isDone()
                                && connectPromise.tryFailure(new ConnectTimeoutException(
                                "connection timed out: " + remoteAddress))) {
                            closeTransport(newPromise());
                        }
                    }, connectTimeoutMillis, TimeUnit.MILLISECONDS);
                }

                promise.asFuture().addListener(future -> {
                    if (future.isCancelled()) {
                        if (connectTimeoutFuture != null) {
                            connectTimeoutFuture.cancel();
                        }
                        connectPromise = null;
                        closeTransport(newPromise());
                    }
                });
            }
        } catch (Throwable t) {
            closeIfClosed();
            promise.tryFailure(annotateConnectException(t, remoteAddress));
        }
    }

    private void fulfillConnectPromise(Promise<Void> promise, boolean wasActive) {
        if (promise == null) {
            // Closed via cancellation and the promise has been notified already.
            return;
        }

        // Get the state as trySuccess() may trigger an ChannelFutureListeners that will close the Channel.
        // We still need to ensure we call fireChannelActive() in this case.
        boolean active = isActive();

        // trySuccess() will return false if a user cancelled the connection attempt.
        boolean promiseSet = promise.trySuccess(null);

        // Regardless if the connection attempt was cancelled, channelActive() event should be triggered,
        // because what happened is what happened.
        if (!wasActive && active) {
            if (fireChannelActiveIfNotActiveBefore()) {
                readIfIsAutoRead();
            }
        }

        // If a user cancelled the connection attempt, close the channel, which is followed by channelInactive().
        if (!promiseSet) {
            closeTransport(newPromise());
        }
    }

    private void fulfillConnectPromise(Promise<Void> promise, Throwable cause) {
        if (promise == null) {
            // Closed via cancellation and the promise has been notified already.
            return;
        }

        // Use tryFailure() instead of setFailure() to avoid the race against cancel().
        promise.tryFailure(cause);
        closeIfClosed();
    }

    /**
     * Should be called once the connect request is ready to be completed.
     */
    protected final void finishConnect() {
        // Note this method is invoked by the event loop only if the connection attempt was
        // neither cancelled nor timed out.
        assertEventLoop();

        boolean connectStillInProgress = false;
        try {
            boolean wasActive = isActive();
            if (!doFinishConnect(requestedRemoteAddress)) {
                connectStillInProgress = true;
                return;
            }
            requestedRemoteAddress = null;
            fulfillConnectPromise(connectPromise, wasActive);
        } catch (Throwable t) {
            fulfillConnectPromise(connectPromise, annotateConnectException(t, requestedRemoteAddress));
        } finally {
            if (!connectStillInProgress) {
                // Check for null as the connectTimeoutFuture is only created if a connectTimeoutMillis > 0 is used
                // See https://github.com/netty/netty/issues/1770
                if (connectTimeoutFuture != null) {
                    connectTimeoutFuture.cancel();
                }
                connectPromise = null;
            }
        }
    }

    /**
     * Invoked when a new message is added to a {@link ChannelOutboundBuffer} of this {@link AbstractChannel}, so that
     * the {@link Channel} implementation converts the message to another. (e.g. heap buffer -> direct buffer)
     */
    protected Object filterOutboundMessage(Object msg) throws Exception {
        return msg;
    }

    protected static void validateFileRegion(DefaultFileRegion region, long position) throws IOException {
        DefaultFileRegion.validate(region, position);
    }

    protected final boolean isSupportingDisconnect() {
        return supportingDisconnect;
    }

    @Override
    @SuppressWarnings("unchecked")
    public final <T> T getOption(ChannelOption<T> option) {
        requireNonNull(option, "option");
        if (option == AUTO_READ) {
            return (T) Boolean.valueOf(isAutoRead());
        }
        if (option == WRITE_BUFFER_WATER_MARK) {
            return (T) getWriteBufferWaterMark();
        }
        if (option == CONNECT_TIMEOUT_MILLIS) {
            return (T) Integer.valueOf(getConnectTimeoutMillis());
        }
        if (option == BUFFER_ALLOCATOR) {
            return (T) getBufferAllocator();
        }
        if (option == READ_HANDLE_FACTORY) {
            return getReadHandleFactory();
        }
        if (option == WRITE_HANDLE_FACTORY) {
            return getWriteHandleFactory();
        }
        if (option == AUTO_CLOSE) {
            return (T) Boolean.valueOf(isAutoClose());
        }
        if (option == MESSAGE_SIZE_ESTIMATOR) {
            return (T) getMessageSizeEstimator();
        }
        if (option == ALLOW_HALF_CLOSURE) {
            return (T) Boolean.valueOf(isAllowHalfClosure());
        }

        return getExtendedOption(option);
    }

    /**
     * Override to add support for more {@link ChannelOption}s.
     * You need to also call {@link super} after handling the extra options.
     *
     * @param option    the {@link ChannelOption}.
     * @return          the value for the option
     * @param <T>       the value type.
     * @throws UnsupportedOperationException    if the {@link ChannelOption} is not supported.
     */
    protected  <T> T getExtendedOption(ChannelOption<T> option) {
        throw new UnsupportedOperationException("ChannelOption not supported: " + option);
    }

    @Override
    public final <T> Channel setOption(ChannelOption<T> option, T value) {
        validate(option, value);

        if (option == AUTO_READ) {
            setAutoRead((Boolean) value);
        } else if (option == WRITE_BUFFER_WATER_MARK) {
            setWriteBufferWaterMark((WriteBufferWaterMark) value);
        } else if (option == CONNECT_TIMEOUT_MILLIS) {
            setConnectTimeoutMillis((Integer) value);
        } else if (option == BUFFER_ALLOCATOR) {
            setBufferAllocator((BufferAllocator) value);
        } else if (option == READ_HANDLE_FACTORY) {
            setReadHandleFactory((ReadHandleFactory) value);
        } else if (option == WRITE_HANDLE_FACTORY) {
            setWriteHandleFactory((WriteHandleFactory) value);
        } else if (option == AUTO_CLOSE) {
            setAutoClose((Boolean) value);
        } else if (option == MESSAGE_SIZE_ESTIMATOR) {
            setMessageSizeEstimator((MessageSizeEstimator) value);
        } else if (option == ALLOW_HALF_CLOSURE) {
            setAllowHalfClosure((Boolean) value);
        } else {
            setExtendedOption(option, value);
        }

        return this;
    }

    /**
     * Override to add support for more {@link ChannelOption}s.
     * You need to also call {@link super} after handling the extra options.
     *
     * @param option    the {@link ChannelOption}.
     * @param <T>       the value type.
     * @throws UnsupportedOperationException    if the {@link ChannelOption} is not supported.
     */
    protected <T> void setExtendedOption(ChannelOption<T> option, T value) {
        throw new UnsupportedOperationException("ChannelOption not supported: " + option);
    }

    @Override
    public final boolean isOptionSupported(ChannelOption<?> option) {
        if (SUPPORTED_CHANNEL_OPTIONS.contains(option)) {
            return true;
        }
        return isExtendedOptionSupported(option);
    }

    /**
     * Override to add support for more {@link ChannelOption}s.
     * You need to also call {@link super} after handling the extra options.
     *
     * @param option    the {@link ChannelOption}.
     * @return          {@code true} if supported, {@code false} otherwise.
     */
    protected boolean isExtendedOptionSupported(ChannelOption<?> option) {
        return false;
    }

    private static Set<ChannelOption<?>> supportedOptions() {
        return newSupportedIdentityOptionsSet(
                AUTO_READ, WRITE_BUFFER_WATER_MARK, CONNECT_TIMEOUT_MILLIS,
                BUFFER_ALLOCATOR, READ_HANDLE_FACTORY, WRITE_HANDLE_FACTORY, AUTO_CLOSE, MESSAGE_SIZE_ESTIMATOR,
                ALLOW_HALF_CLOSURE);
    }

    protected static Set<ChannelOption<?>> newSupportedIdentityOptionsSet(ChannelOption<?>... options) {
        Set<ChannelOption<?>> supportedOptionsSet = Collections.newSetFromMap(new IdentityHashMap<>());
        Collections.addAll(supportedOptionsSet, options);
        return Collections.unmodifiableSet(supportedOptionsSet);
    }

    private <T> void validate(ChannelOption<T> option, T value) {
        requireNonNull(option, "option");
        option.validate(value);
    }

    private int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    private void setConnectTimeoutMillis(int connectTimeoutMillis) {
        checkPositiveOrZero(connectTimeoutMillis, "connectTimeoutMillis");
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    private BufferAllocator getBufferAllocator() {
        return bufferAllocator;
    }

    private void setBufferAllocator(BufferAllocator bufferAllocator) {
        requireNonNull(bufferAllocator, "bufferAllocator");
        this.bufferAllocator = bufferAllocator;
    }

    @SuppressWarnings("unchecked")
    private <T extends ReadHandleFactory> T getReadHandleFactory() {
        return (T) readHandleFactory;
    }

    private void setReadHandleFactory(ReadHandleFactory readHandleFactory) {
        this.readHandleFactory = requireNonNull(readHandleFactory, "readHandleFactory");
    }

    @SuppressWarnings("unchecked")
    private <T extends WriteHandleFactory> T getWriteHandleFactory() {
        return (T) writeHandleFactory;
    }

    private void setWriteHandleFactory(WriteHandleFactory writeHandleFactory) {
        this.writeHandleFactory = requireNonNull(writeHandleFactory, "writeHandleFactory");
    }

    protected final boolean isAutoRead() {
        return autoRead == 1;
    }

    private void setAutoRead(boolean autoRead) {
        boolean oldAutoRead = AUTOREAD_UPDATER.getAndSet(this, autoRead ? 1 : 0) == 1;
        if (autoRead && !oldAutoRead) {
            read();
        } else if (!autoRead && oldAutoRead) {
            currentBufferAllocator = null;
            if (executor().inEventLoop()) {
                clearScheduledRead();
            } else {
                executor().execute(() -> {
                    if (!isReadPending() && !isAutoRead()) {
                        // Still no read triggered so clear it now
                        clearScheduledRead();
                    }
                });
            }
        }
    }

    private boolean isAutoClose() {
        return autoClose;
    }

    private void setAutoClose(boolean autoClose) {
        this.autoClose = autoClose;
    }

    private void setWriteBufferWaterMark(WriteBufferWaterMark writeBufferWaterMark) {
        this.writeBufferWaterMark = requireNonNull(writeBufferWaterMark, "writeBufferWaterMark");
    }

    private WriteBufferWaterMark getWriteBufferWaterMark() {
        return writeBufferWaterMark;
    }

    private MessageSizeEstimator getMessageSizeEstimator() {
        return msgSizeEstimator;
    }

    private void setMessageSizeEstimator(MessageSizeEstimator estimator) {
        requireNonNull(estimator, "estimator");
        msgSizeEstimator = estimator;
    }

    protected final boolean isAllowHalfClosure() {
        return allowHalfClosure;
    }

    private void setAllowHalfClosure(boolean allowHalfClosure) {
        this.allowHalfClosure = allowHalfClosure;
    }

    private static final class ClosePromise extends DefaultPromise<Void> {

        ClosePromise(EventExecutor eventExecutor) {
            super(eventExecutor);
        }

        @Override
        public Promise<Void> setSuccess(Void result) {
            throw new IllegalStateException();
        }

        @Override
        public Promise<Void> setFailure(Throwable cause) {
            throw new IllegalStateException();
        }

        @Override
        public boolean trySuccess(Void result) {
            throw new IllegalStateException();
        }

        @Override
        public boolean tryFailure(Throwable cause) {
            throw new IllegalStateException();
        }

        @Override
        public boolean setUncancellable() {
            return false;
        }

        void setClosed() {
            super.trySuccess(null);
        }
    }

    private static final class AnnotatedConnectException extends ConnectException {

        private static final long serialVersionUID = 3901958112696433556L;

        AnnotatedConnectException(ConnectException exception, SocketAddress remoteAddress) {
            super(exception.getMessage() + ": " + remoteAddress);
            initCause(exception);
        }

        // Suppress a warning since this method doesn't need synchronization
        @Override
        public Throwable fillInStackTrace() {   // lgtm[java/non-sync-override]
            return this;
        }
    }

    private static final class AnnotatedNoRouteToHostException extends NoRouteToHostException {

        private static final long serialVersionUID = -6801433937592080623L;

        AnnotatedNoRouteToHostException(NoRouteToHostException exception, SocketAddress remoteAddress) {
            super(exception.getMessage() + ": " + remoteAddress);
            initCause(exception);
        }

        // Suppress a warning since this method doesn't need synchronization
        @Override
        public Throwable fillInStackTrace() {   // lgtm[java/non-sync-override]
            return this;
        }
    }

    private static final class AnnotatedSocketException extends SocketException {

        private static final long serialVersionUID = 3896743275010454039L;

        AnnotatedSocketException(SocketException exception, SocketAddress remoteAddress) {
            super(exception.getMessage() + ": " + remoteAddress);
            initCause(exception);
        }

        // Suppress a warning since this method doesn't need synchronization
        @Override
        public Throwable fillInStackTrace() {   // lgtm[java/non-sync-override]
            return this;
        }
    }

    protected void runAfterTransportAction() {
        // Noop
    }

    protected static class DefaultAbstractChannelPipeline extends DefaultChannelPipeline {
        protected DefaultAbstractChannelPipeline(AbstractChannel<?, ?, ?> channel) {
            super(channel);
        }

        protected final AbstractChannel<?, ?, ?> abstractChannel() {
            return (AbstractChannel<?, ?, ?>) channel();
        }

        @Override
        protected final EventExecutor transportExecutor() {
            return abstractChannel().executor();
        }

        @Override
        protected final void pendingOutboundBytesUpdated(long pendingOutboundBytes) {
            abstractChannel().updateWritabilityIfNeeded(true, false);
        }

        @Override
        protected final void registerTransport(Promise<Void> promise) {
            AbstractChannel<?, ?, ?> channel = abstractChannel();
            channel.registerTransport(promise);
            channel.runAfterTransportAction();
        }

        @Override
        protected final void bindTransport(SocketAddress localAddress, Promise<Void> promise) {
            AbstractChannel<?, ?, ?> channel = abstractChannel();
            channel.bindTransport(localAddress, promise);
            channel.runAfterTransportAction();
        }

        @Override
        protected final void connectTransport(
                SocketAddress remoteAddress, SocketAddress localAddress, Promise<Void> promise) {
            AbstractChannel<?, ?, ?> channel = abstractChannel();
            channel.connectTransport(remoteAddress, localAddress, promise);
            channel.runAfterTransportAction();
        }

        @Override
        protected final void disconnectTransport(Promise<Void> promise) {
            AbstractChannel<?, ?, ?> channel = abstractChannel();
            channel.disconnectTransport(promise);
            channel.runAfterTransportAction();
        }

        @Override
        protected final void closeTransport(Promise<Void> promise) {
            AbstractChannel<?, ?, ?> channel = abstractChannel();
            abstractChannel().closeTransport(promise);
            channel.runAfterTransportAction();
        }

        @Override
        protected final void shutdownTransport(ChannelShutdownDirection direction, Promise<Void> promise) {
            AbstractChannel<?, ?, ?> channel = abstractChannel();
            channel.shutdownTransport(direction, promise);
            channel.runAfterTransportAction();
        }

        @Override
        protected final void deregisterTransport(Promise<Void> promise) {
            AbstractChannel<?, ?, ?> channel = abstractChannel();
            channel.deregisterTransport(promise);
            channel.runAfterTransportAction();
        }

        @Override
        protected final void readTransport(ReadBufferAllocator readBufferAllocator) {
            AbstractChannel<?, ?, ?> channel = abstractChannel();
            channel.readTransport(readBufferAllocator);
            channel.runAfterTransportAction();
        }

        @Override
        protected final void writeTransport(Object msg, Promise<Void> promise) {
            AbstractChannel<?, ?, ?> channel = abstractChannel();
            channel.writeTransport(msg, promise);
            channel.runAfterTransportAction();
        }

        @Override
        protected final void flushTransport() {
            AbstractChannel<?, ?, ?> channel = abstractChannel();
            channel.flushTransport();
            channel.runAfterTransportAction();
        }

        @Override
        protected final void sendOutboundEventTransport(Object event, Promise<Void> promise) {
            AbstractChannel<?, ?, ?> channel = abstractChannel();
            channel.sendOutboundEventTransport(event, promise);
            channel.runAfterTransportAction();
        }

        @Override
        protected final boolean isTransportSupportingDisconnect() {
            return abstractChannel().isSupportingDisconnect();
        }
    }

    /**
     * Return the {@link BufferAllocator} that is used to allocate {@link Buffer} that are used for reading.
     * By default this will just return {@link #bufferAllocator()}. Sub-classes might override this if some special
     * allocator is needed.
     *
     * @return the {@link BufferAllocator} that is used to allocate {@link Buffer}s that are used for reading.
     */
    protected BufferAllocator readBufferAllocator() {
        return bufferAllocator();
    }

    /**
     * Sink that will be used by {@link #doReadNow(ReadSink)} implementations.
     */
    protected final class ReadSink {
        final ReadHandleFactory.ReadHandle readHandle;

        private boolean readSomething;

        ReadSink(ReadHandleFactory.ReadHandle readHandle) {
            this.readHandle = readHandle;
        }

        /**
         * Process the read message and fire it through the {@link ChannelPipeline}
         *
         * @param attemptedBytesRead    The number of  bytes the read operation did attempt to read.
         * @param actualBytesRead       The number of bytes the read operation actually read.
         * @param message               the read message or {@code null} if none was read.
         * @return                      {@code true} if the read loop should continue reading, {@code false} otherwise.
         */
        public boolean processRead(int attemptedBytesRead, int actualBytesRead, Object message) {
            if (message == null) {
                readHandle.lastRead(attemptedBytesRead, actualBytesRead, 0);
                return false;
            } else {
                readSomething = true;
                currentBufferAllocator = null;
                boolean continueReading = readHandle.lastRead(attemptedBytesRead, actualBytesRead, 1);
                pipeline().fireChannelRead(message);
                return continueReading;
            }
        }

        /**
         * Allocate a {@link Buffer} with a capacity that is probably large enough to read all inbound data and
         * small enough not to waste its space.
         *
         * @return the allocated {@link Buffer}.
         */
        public Buffer allocateBuffer() {
            ReadBufferAllocator readBufferAllocator = currentBufferAllocator;
            if (readBufferAllocator == null) {
                readBufferAllocator = DefaultChannelPipeline.DEFAULT_READ_BUFFER_ALLOCATOR;
            }
            return readBufferAllocator.allocate(readBufferAllocator(), readHandle.estimatedBufferCapacity());
        }

        void complete() {
            // Check if something was read as in this case we wall need to call the *ReadComplete methods.
            if (readSomething) {
                readSomething = false;
                readHandle.readComplete();
                pipeline().fireChannelReadComplete();
            }
        }

        boolean completeFailure(Throwable cause) {
            complete();
            pipeline().fireChannelExceptionCaught(cause);

            if (cause instanceof PortUnreachableException) {
                return false;
            }
            // If oom will close the read event, release connection.
            // See https://github.com/netty/netty/issues/10434
            if (cause instanceof IOException && !(AbstractChannel.this instanceof ServerChannel)) {
                return true;
            }
            return false;
        }
    }

    /**
     * Sink that will be used by {@link #doWriteNow(WriteSink)} implementations.
     */
    protected final class WriteSink {
        final WriteHandleFactory.WriteHandle writeHandle;
        private ChannelOutboundBuffer outboundBuffer;

        private long attemptedBytesWrite;
        private long actualBytesWrite;
        private int messagesWritten;
        private Throwable writeError;

        private Boolean continueWriting;

        WriteSink(WriteHandleFactory.WriteHandle writeHandle) {
            this.writeHandle = writeHandle;
        }

        void processWriteLoop(ChannelOutboundBuffer outboundBuffer) {
            this.outboundBuffer = outboundBuffer;
            try {
                try {
                    // We checked before is the outboundBuffer is empty so let's use a do-while loop.
                    do {
                        doWriteNow(writeSink);
                    } while (continueWriting() && !outboundBuffer.isEmpty());
                } catch (Throwable t) {
                    handleWriteError(t);
                } finally {
                    try {
                        writeLoopComplete(outboundBuffer.isEmpty());
                    } catch (Throwable cause) {
                        // Something is really seriously wrong!
                        closeWithErrorFromWriteFlushed(cause);
                    }
                }
            } finally {
                writeHandle.writeComplete();

                // It's important that we call this with notifyLater true so we not get into trouble when flush() is
                // called again in channelWritabilityChanged(...).
                updateWritabilityIfNeeded(true, true);
            }
        }

        /**
         * Return the estimated maximum number of bytes that can be written with one gathering write operation.
         *
         * @return number of bytes.
         */
        public long estimatedMaxBytesPerGatheringWrite() {
            return writeHandle.estimatedMaxBytesPerGatheringWrite();
        }

        /**
         * The number of flushed messages that are ready to be written.
         *
         * @return the number of messages.
         */
        public int size() {
            return outboundBuffer.size();
        }

        /**
         * Return the first message that should be written.
         *
         * @return                          the first message.
         * @throws IllegalStateException    if called after {@link #complete(long, long, int, boolean)}
         *                                  or {@link #complete(long, Throwable, boolean)} was called.
         */
        public Object first() {
            return outboundBuffer.current();
        }

        /**
         * Call {@link Function#apply(Object)} for each message that is flushed until
         * {@link Function#apply(Object)} returns {@code false} or there are no more flushed messages.
         *
         * @param processor                 the {@link Function} to use.
         * @throws IllegalStateException    if called after {@link #complete(long, long, int, boolean)}
         *                                  or {@link #complete(long, Throwable, boolean)} was called.
         */
        public void forEach(Function<Object, Boolean> processor) {
            outboundBuffer.forEachFlushedMessage(processor);
        }

        /**
         * Notify of the last write operation and its result.
         *
         * @param attemptedBytesWrite       The number of  bytes the write operation did attempt to write.
         * @param actualBytesWrite          The number of bytes from the previous write operation. This may be negative
         *                                  if a write error occurs.
         * @param messagesWritten           The number of written messages, or {@code -1} if the amount of written
         *                                  messages is unknown.
         * @param mightContinueWriting      {@code true} if the write loop might continue writing messages,
         *                                  {@code false} otherwise
         *
         * @throws IllegalStateException    if called after {@link #complete(long, long, int, boolean)}
         *                                  or {@link #complete(long, Throwable, boolean)} was called.
         */
        public void complete(long attemptedBytesWrite, long actualBytesWrite, int messagesWritten,
                             boolean mightContinueWriting) {
            checkCompleteAlready();
            this.attemptedBytesWrite = checkPositiveOrZero(attemptedBytesWrite, "attemptedBytesWrite");
            this.actualBytesWrite = actualBytesWrite;
            this.messagesWritten = checkInRange(messagesWritten, -1, Integer.MAX_VALUE, "messagesWritten");
            this.continueWriting = mightContinueWriting ? Boolean.TRUE : Boolean.FALSE;
            this.writeError = null;
        }

        /**
         * Notify of the last write operation and its result.
         *
         * @param attemptedBytesWrite       The number of  bytes the write operation did attempt to write.
         * @param cause                     The error that happened during the write and can be recovered.
         * @param mightContinueWriting      {@code true} if the write loop might continue writing messages,
         *                                  {@code false} otherwise.
         *
         * @throws IllegalStateException    if called after {@link #complete(long, long, int, boolean)}
         *                                  or {@link #complete(long, Throwable, boolean)} was called.
         */
        public void complete(long attemptedBytesWrite, Throwable cause, boolean mightContinueWriting) {
            checkCompleteAlready();
            this.attemptedBytesWrite = checkPositiveOrZero(attemptedBytesWrite, "attemptedBytesWrite");
            this.writeError = requireNonNull(cause, "cause");
            this.actualBytesWrite = 0;
            this.messagesWritten = 0;
            this.continueWriting = mightContinueWriting ? Boolean.TRUE : Boolean.FALSE;
        }

        private void checkCompleteAlready() {
            if (continueWriting != null) {
                throw new IllegalStateException(StringUtil.simpleClassName(WriteSink.class) +
                        ".complete(...) was already called");
            }
        }

        private boolean continueWriting() {
            if (continueWriting == null) {
                throw new IllegalStateException(StringUtil.simpleClassName(WriteSink.class) +
                        ".complete(...) was not called");
            }
            try {
                if (writeError != null) {
                    outboundBuffer.remove(writeError);
                } else {
                    if (messagesWritten > 0) {
                        do {
                            outboundBuffer.remove();
                            messagesWritten--;
                        } while (messagesWritten > 0);
                    } else if (messagesWritten == -1 && actualBytesWrite >= 0) {
                        messagesWritten = outboundBuffer.removeBytes(actualBytesWrite);
                    }
                }
                return writeHandle.lastWrite(attemptedBytesWrite, actualBytesWrite, messagesWritten) &&
                        continueWriting == Boolean.TRUE;
            } finally {
                writeError = null;
                messagesWritten = 0;
                attemptedBytesWrite = 0;
                actualBytesWrite = 0;
                continueWriting = null;
            }
        }
    }
}
