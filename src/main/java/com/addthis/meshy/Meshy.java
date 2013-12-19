/*
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
package com.addthis.meshy;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.InputStream;

import java.net.InetSocketAddress;
import java.net.NetworkInterface;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import java.nio.channels.ClosedChannelException;
import java.text.DecimalFormat;

import com.addthis.basis.util.Bytes;
import com.addthis.basis.util.JitterClock;
import com.addthis.basis.util.Parameter;
import com.addthis.basis.util.Strings;

import com.addthis.meshy.service.file.FileSource;
import com.addthis.meshy.service.file.FileTarget;
import com.addthis.meshy.service.host.HostSource;
import com.addthis.meshy.service.host.HostTarget;
import com.addthis.meshy.service.message.MessageSource;
import com.addthis.meshy.service.message.MessageTarget;
import com.addthis.meshy.service.peer.PeerSource;
import com.addthis.meshy.service.peer.PeerTarget;
import com.addthis.meshy.service.stream.StreamSource;
import com.addthis.meshy.service.stream.StreamTarget;

import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Meter;
import com.yammer.metrics.core.VirtualMachineMetrics;

import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * full mesh nodes are both clients and servers. so the client logic is not exclusive to client-only nodes.
 * try not to let this confuse you when reading the code. this is a partial explanation as to why there are
 * client and server codes co-mingled.
 */
public abstract class Meshy implements ChannelMaster, Closeable {

    private static final Logger log = LoggerFactory.getLogger(Meshy.class);
    static final HashMap<Integer, Class<? extends SessionHandler>> idHandlerMap = new HashMap<>();
    static final HashMap<Class<? extends SessionHandler>, Integer> handlerIdMap = new HashMap<>();
    static final AtomicInteger nextHandlerID = new AtomicInteger(1);
    static final DecimalFormat numbers = new DecimalFormat("#,###");
    static final boolean throttleLog = Parameter.boolValue("meshy.throttleLog", true);
    static final int statsInterval = Parameter.intValue("meshy.stats.time", 1) * 1000;
    static final VirtualMachineMetrics vmMetrics = VirtualMachineMetrics.getInstance();
    static final AtomicInteger nextSession = new AtomicInteger(0);
    static final Enumeration<NetworkInterface> netIfEnum;
    private static final Meter bytesInMeter = Metrics.newMeter(Meshy.class, "bytesIn", "bytesIn", TimeUnit.SECONDS);
    private static final Meter bytesOutMeter = Metrics.newMeter(Meshy.class, "bytesOut", "bytesOut", TimeUnit.SECONDS);

    private static String hostname;

    static void registerHandlerClass(Class<? extends SessionHandler> clazz) {
        if (!handlerIdMap.containsKey(clazz)) {
            int id = nextHandlerID.getAndIncrement();
            idHandlerMap.put(id, clazz);
            handlerIdMap.put(clazz, id);
        }
        try {
            hostname = Strings.splitArray(Bytes.toString(Bytes.readFully(Runtime.getRuntime().exec("hostname").getInputStream())), ".")[0];
        } catch (Exception ex) {
            // ignore
        }
    }

    static {
        registerHandlerClass(HostSource.class);
        registerHandlerClass(HostTarget.class);
        registerHandlerClass(FileSource.class);
        registerHandlerClass(FileTarget.class);
        registerHandlerClass(PeerSource.class);
        registerHandlerClass(PeerTarget.class);
        registerHandlerClass(StreamSource.class);
        registerHandlerClass(StreamTarget.class);
        registerHandlerClass(MessageSource.class);
        registerHandlerClass(MessageTarget.class);
        /* create enum for "smart" auto-mesh */
        try {
            netIfEnum = NetworkInterface.getNetworkInterfaces();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * utility
     */
    public static byte[] getBytes(int length, ChannelBuffer buffer) {
        byte request[] = new byte[length];
        buffer.readBytes(request);
        return request;
    }

    public static InputStream getInput(int length, ChannelBuffer buffer) {
        return new ByteArrayInputStream(getBytes(length, buffer));
    }

    protected Meshy() {
        if (hostname != null) {
            uuid = hostname + "-" + Long.toHexString(System.currentTimeMillis() & 0xffffff);
        } else {
            uuid = Long.toHexString(UUID.randomUUID().getMostSignificantBits());
        }
        clientFactory = new NioClientSocketChannelFactory(Executors.newCachedThreadPool(), Executors.newCachedThreadPool());
        clientBootstrap = new ClientBootstrap(clientFactory);
        clientBootstrap.setPipelineFactory(new ChannelPipelineFactory() {
            public ChannelPipeline getPipeline() throws Exception {
                return Channels.pipeline(new MeshyChannelHandler());
            }
        });
        clientBootstrap.setOption("tcpNoDelay", true);
        clientBootstrap.setOption("keepAlive", true);
        clientBootstrap.setOption("connectTimeoutMillis", 30000);
        updateLastEventTime();
    }

    private final ChannelFactory clientFactory;
    private final ClientBootstrap clientBootstrap;

    private final String uuid;
    private final AtomicLong lastEvent = new AtomicLong(0);
    private final ChannelGroup allChannels = new DefaultChannelGroup();
    private final AtomicInteger bytesIn = new AtomicInteger(0);
    private final AtomicInteger bytesOut = new AtomicInteger(0);
    private final Collection<ChannelCloseListener> channelCloseListeners = new ArrayList<>();

    protected final HashSet<ChannelState> connectedChannels = new HashSet<>();
    /* nodes actively being peered */
    protected final HashSet<String> inPeering = new HashSet<>();
    protected final HashSet<InetSocketAddress> needsPeering = new HashSet<>();

    @Override
    public void close() {
        synchronized (connectedChannels) {
            for (ChannelState state : connectedChannels) {
                state.debugSessions();
            }
        }
        allChannels.close().awaitUninterruptibly();
        clientFactory.releaseExternalResources();
    }

    protected ChannelFuture connect(InetSocketAddress addr) {
        return clientBootstrap.connect(addr);
    }

    public int getChannelCount() {
        return allChannels.size();
    }

    public int getPeeredCount() {
        return connectedChannels.size();
    }

    @Override
    public void sentBytes(int size) {
        bytesOutMeter.mark(size);
        bytesOut.addAndGet(size);
    }

    @Override
    public void recvBytes(int size) {
        bytesInMeter.mark(size);
        bytesIn.addAndGet(size);
    }

    protected int getAndClearSent() {
        return bytesOut.getAndSet(0);
    }

    protected int getAndClearRecv() {
        return bytesIn.getAndSet(0);
    }

    @Override
    public String getUUID() {
        return uuid;
    }

    /**
     * @param nameFilter null = all channels, empty = named channels, non-empty = exact match
     * @return Iterator of ChannelState objects
     */
    @Override
    public Collection<ChannelState> getChannels(final String nameFilter) {
        HashSet<ChannelState> set = new HashSet<>();
        synchronized (connectedChannels) {
            for (ChannelState state : connectedChannels) {
                if ((nameFilter == MeshyConstants.LINK_ALL) || (state.getRemoteAddress() != null && (nameFilter == MeshyConstants.LINK_NAMED || nameFilter.equals(state.getName())))) {
                    set.add(state);
                }
            }
        }
        return set;
    }

    @Override
    public TargetHandler createHandler(int type) {
        try {
            return (TargetHandler) idHandlerMap.get(type).newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected void updateLastEventTime() {
        lastEvent.set(JitterClock.globalTime());
    }

    @Override
    public long lastEventTime() {
        return lastEvent.get();
    }

    protected void connectChannel(Channel channel, ChannelState channelState) {
        allChannels.add(channel);
        synchronized (connectedChannels) {
            connectedChannels.add(channelState);
        }
        log.debug(this + " connectChannel @ " + channel.getRemoteAddress());
    }

    private void closeChannel(Channel channel) {
        allChannels.remove(channel);
        if (channel.getId() != null) {
            synchronized (connectedChannels) {
                ChannelState match = null;
                for (ChannelState state : connectedChannels) {
                    if (state.getChannel() == channel) {
                        match = state;
                        break;
                    }
                }
                if (match != null) {
                    connectedChannels.remove(match);
                    inPeering.remove(match.getName());
                }
            }
            synchronized (channelCloseListeners) {
                for (ChannelCloseListener channelCloseListener : channelCloseListeners) {
                    channelCloseListener.channelClosed(channel.getId());
                }
            }
        }
        log.debug(this + " closeChannel @ " + channel.getRemoteAddress());
    }

    public boolean addChannelCloseListener(ChannelCloseListener channelCloseListener) {
        synchronized (this.channelCloseListeners) {
            return this.channelCloseListeners.add(channelCloseListener);
        }
    }

    public boolean removeChannelCloseListener(ChannelCloseListener channelCloseListener) {
        synchronized (this.channelCloseListeners) {
            return this.channelCloseListeners.remove(channelCloseListener);
        }
    }

    /*
     * gather connections related to the targetUuid requested
     */
    @Override
    public void createSession(SourceHandler sourceHandler, Class<? extends TargetHandler> targetHandler, String targetUuid) {
        ChannelGroup group = new DefaultChannelGroup();
        HashSet<String> uuids = new HashSet<>();
        boolean breakOnMatch = targetUuid != null && !targetUuid.isEmpty();
        int sessionID = nextSession.incrementAndGet();
        synchronized (connectedChannels) {
            for (ChannelState state : connectedChannels) {
                if (
                        (targetUuid == MeshyConstants.LINK_ALL) ||
                        (targetUuid == MeshyConstants.LINK_NAMED && state.getRemoteAddress() != null) ||
                        (state.getName() != null && targetUuid.equals(state.getName()))
                        ) {
                    /* prevent dups if >1 connection to the same host */
                    if (state.getName() != null && !uuids.add(state.getName())) {
                        continue;
                    }
                    group.add(state.getChannel());
                    /* add channel callback path to source */
                    state.addSourceHandler(sessionID, sourceHandler);
                    if (breakOnMatch) {
                        break;
                    }
                }
            }
        }
        sourceHandler.init(sessionID, handlerIdMap.get(targetHandler), group);
        log.debug(this + " createSession " + sourceHandler + " target=" + targetHandler + " uuid=" + (targetUuid != null ? "'" + targetUuid + "'" : null) + " group=" + group + " sessionID=" + sessionID);
    }

    /**
     * one channel handler is created per meshy instance (client or server)
     */
    class MeshyChannelHandler extends SimpleChannelHandler {

        private ChannelState getAttachState(ChannelHandlerContext ctx) {
            synchronized (ctx) {
                ChannelState state = (ChannelState) ctx.getAttachment();
                if (state == null) {
                    state = new ChannelState(Meshy.this, ctx.getChannel());
                    log.trace(state + " created for " + ctx.hashCode());
                    ctx.setAttachment(state);
                }
                return state;
            }
        }

        @Override
        public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) {
            updateLastEventTime();
            connectChannel(ctx.getChannel(), getAttachState(ctx));
            getAttachState(ctx).channelConnected(e);
        }

        @Override
        public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
            updateLastEventTime();
            getAttachState(ctx).channelClosed(e);
            closeChannel(ctx.getChannel());
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent ex) {
            updateLastEventTime();
            log.warn(ctx.getAttachment() + " exception = " + ex);
            if (!(ex.getCause() instanceof ClosedChannelException)) {
                log.warn("", ex.getCause());
                ctx.getChannel().close();
            }
            try {
                channelClosed(ctx, null);
            } catch (Exception ee) {
                log.error("Mystery exception we are swallowing", ee);
            }
        }

        @Override
        public void messageReceived(ChannelHandlerContext ctx, MessageEvent msg) {
            updateLastEventTime();
            getAttachState(ctx).messageReceived(msg);
        }
    }
}
