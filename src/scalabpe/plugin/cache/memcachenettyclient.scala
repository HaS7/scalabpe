package scalabpe.plugin.cache

import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

import scala.collection.mutable.ArrayBuffer

import org.jboss.netty.bootstrap.ClientBootstrap
import org.jboss.netty.buffer.ChannelBuffer
import org.jboss.netty.channel.Channel
import org.jboss.netty.channel.ChannelFuture
import org.jboss.netty.channel.ChannelFutureListener
import org.jboss.netty.channel.ChannelHandlerContext
import org.jboss.netty.channel.ChannelPipeline
import org.jboss.netty.channel.ChannelPipelineFactory
import org.jboss.netty.channel.ChannelStateEvent
import org.jboss.netty.channel.Channels
import org.jboss.netty.channel.ExceptionEvent
import org.jboss.netty.channel.MessageEvent
import org.jboss.netty.channel.group.DefaultChannelGroup
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory
import org.jboss.netty.handler.codec.frame.FrameDecoder
import org.jboss.netty.handler.timeout.IdleStateAwareChannelHandler
import org.jboss.netty.handler.timeout.IdleStateEvent
import org.jboss.netty.handler.timeout.IdleStateHandler
import org.jboss.netty.util.HashedWheelTimer
import org.jboss.netty.util.ThreadNameDeterminer
import org.jboss.netty.util.ThreadRenamingRunnable
import org.jboss.netty.util.Timeout
import org.jboss.netty.util.TimerTask

import scalabpe.core.ArrayBufferInt
import scalabpe.core.Dumpable
import scalabpe.core.Logging
import scalabpe.core.NamedThreadFactory
import scalabpe.core.QuickTimer
import scalabpe.core.QuickTimerEngine
import scalabpe.core.SelfCheckResult

object MemCacheNettyClient {
    val count = new AtomicInteger(1)
}

class MemCacheNettyClient(
        val soc: MemCacheSoc,
        val addrstr: String,
        val connectTimeout: Int = 15000,
        val pingInterval: Int = 60000,
        val connSizePerAddr: Int = 4,
        val timerInterval: Int = 100,
        val reconnectInterval: Int = 1,
        val reuseAddress: Boolean = false) extends Logging with Dumpable {

    var bossThreadFactory: NamedThreadFactory = _
    var workThreadFactory: NamedThreadFactory = _
    var timerThreadFactory: NamedThreadFactory = _
    var factory: NioClientSocketChannelFactory = _
    var bootstrap: ClientBootstrap = _
    var channelHandler: ChannelHandler = _
    var bossExecutor: ThreadPoolExecutor = _
    var workerExecutor: ThreadPoolExecutor = _
    var timer: HashedWheelTimer = _
    var qte: QuickTimerEngine = _

    val addrs = addrstr.split(",")
    var nextIdxs = new Array[Int](addrs.size)

    val channels = new Array[Channel](addrs.size * connSizePerAddr) // channel array
    val channelIds = new Array[String](addrs.size * connSizePerAddr) // connId array
    val dataMap = new ConcurrentHashMap[Int, TimeoutInfo]()

    val lock = new ReentrantLock(false)

    val connected = new AtomicBoolean()
    val shutdown = new AtomicBoolean()

    init

    def dump() {

        log.info("--- addrstr=" + addrstr)

        val buff = new StringBuilder

        buff.append("timer.threads=").append(1).append(",")
        buff.append("bossExecutor.getPoolSize=").append(bossExecutor.getPoolSize).append(",")
        buff.append("bossExecutor.getQueue.size=").append(bossExecutor.getQueue.size).append(",")
        buff.append("workerExecutor.getPoolSize=").append(workerExecutor.getPoolSize).append(",")
        buff.append("workerExecutor.getQueue.size=").append(workerExecutor.getQueue.size).append(",")
        buff.append("channels.size=").append(channels.size).append(",")

        val cnt = channels.filter(_ != null).size

        buff.append("connectedCount=").append(cnt).append(",")
        buff.append("dataMap.size=").append(dataMap.size).append(",")

        log.info(buff.toString)

        qte.dump()
    }

    def init(): Unit = {

        channelHandler = new ChannelHandler(this)

        // without this line, the thread name of netty will not be changed
        ThreadRenamingRunnable.setThreadNameDeterminer(ThreadNameDeterminer.CURRENT); // or PROPOSED

        bossThreadFactory = new NamedThreadFactory("memcacheboss" + MemCacheNettyClient.count.getAndIncrement())
        bossExecutor = Executors.newCachedThreadPool(bossThreadFactory).asInstanceOf[ThreadPoolExecutor]
        workThreadFactory = new NamedThreadFactory("memcachework" + MemCacheNettyClient.count.getAndIncrement())
        workerExecutor = Executors.newCachedThreadPool(workThreadFactory).asInstanceOf[ThreadPoolExecutor]
        timerThreadFactory = new NamedThreadFactory("memcachetimer" + MemCacheNettyClient.count.getAndIncrement())
        timer = new HashedWheelTimer(timerThreadFactory, 1, TimeUnit.SECONDS)
        qte = new QuickTimerEngine(onTimeout, timerInterval)

        factory = new NioClientSocketChannelFactory(bossExecutor, workerExecutor)
        bootstrap = new ClientBootstrap(factory);
        bootstrap.setPipelineFactory(new PipelineFactory());

        bootstrap.setOption("tcpNoDelay", true);
        bootstrap.setOption("keepAlive", true);
        bootstrap.setOption("connectTimeoutMillis", connectTimeout);

        if (reuseAddress)
            bootstrap.setOption("reuserAddress", true);
        else
            bootstrap.setOption("reuserAddress", false);

        for (hostidx <- 0 until addrs.size) {

            var ss = addrs(hostidx).split(":")
            var host = ss(0)
            var port = ss(1).toInt
            nextIdxs(hostidx) = hostidx

            for (connidx <- 0 until connSizePerAddr) {

                val idx = hostidx + connidx * addrs.size

                val future = bootstrap.connect(new InetSocketAddress(host, port))

                future.addListener(new ChannelFutureListener() {
                    def operationComplete(future: ChannelFuture) {
                        onConnectCompleted(future, hostidx, connidx)
                    }
                })

            }
        }

        val maxWait = connectTimeout.min(5000)
        val now = System.currentTimeMillis
        var t = 0L
        while (!connected.get() && (t - now) < maxWait) {
            Thread.sleep(50)
            t = System.currentTimeMillis
        }

        log.info("netty memcache client started, {}, connected={}", addrstr, connected.get())
    }

    def close(): Unit = {

        shutdown.set(true)

        if (factory != null) {

            log.info("stopping netty client {}", addrstr)

            timer.stop()
            timer = null

            val allChannels = new DefaultChannelGroup("netty-client-memcache-scala")
            for (ch <- channels if ch != null if ch.isOpen) {
                allChannels.add(ch)
            }
            val future = allChannels.close()
            future.awaitUninterruptibly()

            factory.releaseExternalResources()
            factory = null
        }

        qte.close()

        log.info("netty memcache client stopped {}", addrstr)
    }

    def selfcheck(): ArrayBuffer[SelfCheckResult] = {

        val buff = new ArrayBuffer[SelfCheckResult]()

        var errorId = 65301001

        var i = 0
        while (i < addrs.size) {
            if (channels(i) == null) {
                val msg = "sos [" + addrs(i) + "] has error"
                buff += new SelfCheckResult("SCALABPE.MEMCACHE", errorId, true, msg)
            }

            i += 1
        }

        if (buff.size == 0) {
            buff += new SelfCheckResult("SCALABPE.MEMCACHE", errorId)
        }

        buff
    }

    def reconnect(hostidx: Int, connidx: Int) {

        var ss = addrs(hostidx).split(":")
        var host = ss(0)
        var port = ss(1).toInt

        log.info("reconnect called, hostidx={},connidx={}", hostidx, connidx)

        val future = bootstrap.connect(new InetSocketAddress(host, port))

        future.addListener(new ChannelFutureListener() {
            def operationComplete(future: ChannelFuture) {
                onConnectCompleted(future, hostidx, connidx)
            }
        })

    }

    def onConnectCompleted(f: ChannelFuture, hostidx: Int, connidx: Int): Unit = {

        if (f.isCancelled()) {

            log.error("connect cancelled, hostidx=%d,connidx=%d".format(hostidx, connidx))

            if (timer != null) { // while shutdowning
                timer.newTimeout(new TimerTask() {

                    def run(timeout: Timeout) {
                        reconnect(hostidx, connidx)
                    }

                }, reconnectInterval, TimeUnit.SECONDS)
            }

        } else if (!f.isSuccess()) {

            log.error("connect failed, hostidx=%d,connidx=%d,e=%s".format(hostidx, connidx, f.getCause.getMessage))

            if (timer != null) { // while shutdowning
                timer.newTimeout(new TimerTask() {

                    def run(timeout: Timeout) {
                        reconnect(hostidx, connidx)
                    }

                }, reconnectInterval, TimeUnit.SECONDS)
            }
        } else {

            val ch = f.getChannel
            log.info("connect ok, hostidx=%d,connidx=%d,channelId=%s,channelAddr=%s,clientAddr=%s".format(hostidx, connidx, ch.getId, addrs(hostidx), ch.getLocalAddress.toString))
            val idx = hostidx + connidx * addrs.size

            lock.lock()

            try {
                if (channels(idx) == null) {
                    val theConnId = parseIpPort(ch.getRemoteAddress.toString) + ":" + ch.getId
                    channels(idx) = ch
                    channelIds(idx) = theConnId
                }
            } finally {
                lock.unlock()
            }

            if (connectedCount() == channels.size) {
                connected.set(true)
            }
        }

    }

    def connectedCount(): Int = {

        lock.lock()

        try {

            var i = 0
            var cnt = 0
            while (i < channels.size) {
                val ch = channels(i)
                if (ch != null) {
                    cnt += 1
                }
                i += 1
            }

            cnt

        } finally {
            lock.unlock()
        }

    }

    def selectChannelAndSend(sequence: Int, buff: ChannelBuffer, timeout: Int, addrIdx: Int, hasReply: Boolean): Tuple2[Channel, Int] = {

        lock.lock()

        try {

            var i = 0
            while (i < connSizePerAddr) {
                var nextIdx = nextIdxs(addrIdx)
                val ch = channels(nextIdx)
                val connId = channelIds(nextIdx)
                if (ch != null) { // && ch.isWritable

                    if (ch.isOpen) {

                        val t = qte.newTimer(timeout, sequence)
                        val ti = new TimeoutInfo(sequence, connId, t)
                        dataMap.put(sequence, ti)

                        ch.write(buff);

                        val d = (ch, nextIdx)
                        nextIdx += addrs.size
                        if (nextIdx >= channels.size) nextIdx = addrIdx
                        nextIdxs(addrIdx) = nextIdx
                        return d

                    } else {
                        log.error("channel not opened, idx={}, connId={}", i, connId)
                        removeChannel(connId)
                    }

                }
                i += 1
                nextIdx += addrs.size
                if (nextIdx >= channels.size) nextIdx = addrIdx
                nextIdxs(addrIdx) = nextIdx
            }

            return (null, 0)

        } finally {
            lock.unlock()
        }
    }

    def removeChannel(connId: String): Unit = {

        if (shutdown.get()) {
            return
        }

        lock.lock()

        var idx = -1
        try {
            var i = 0

            while (idx == -1 && i < channels.size) {
                val channel = channels(i)
                val theConnId = channelIds(i)
                if (channel != null && theConnId == connId) {
                    channels(i) = null
                    channelIds(i) = null
                    idx = i
                }

                i += 1
            }

        } finally {
            lock.unlock()
        }

        if (idx != -1) {

            val hostidx = idx % addrs.size
            val connidx = idx / addrs.size

            timer.newTimeout(new TimerTask() {

                def run(timeout: Timeout) {
                    reconnect(hostidx, connidx)
                }

            }, reconnectInterval, TimeUnit.SECONDS)
        }

    }

    def sendByAddr(sequence: Int, buff: ChannelBuffer, timeout: Int, addrIdx: Int, hasReply: Boolean = true): Boolean = {
        val (ch, idx) = selectChannelAndSend(sequence, buff, timeout, addrIdx, hasReply)
        return (ch != null)
    }

    def parseIpPort(s: String): String = {

        val p = s.indexOf("/")

        if (p >= 0)
            s.substring(p + 1)
        else
            s
    }

    def onTimeout(data: Any): Unit = {

        val sequence = data.asInstanceOf[Int]

        val ti = dataMap.remove(sequence)
        if (ti != null) {
            soc.timeoutError(sequence, ti.connId)
        } else {
            //log.error("timeout but sequence not found, seq={}",sequence)
        }

    }

    def onReceive(buff: ChannelBuffer, connId: String): Unit = {

        val sequence = parseSequence(buff)

        val ti = dataMap.remove(sequence)
        if (ti != null) {
            ti.timer.cancel()
        } else {
            //log.warn("receive but sequence not found, seq={}",sequence)
        }

        soc.receive(sequence, buff, connId)
    }

    def parseSequence(buff: ChannelBuffer): Int = {
        var s = buff.readerIndex
        val sequence = buff.getInt(s + 12)
        sequence
    }
    def onNetworkError(connId: String): Unit = {

        removeChannel(connId)

        val seqs = new ArrayBufferInt()
        val i = dataMap.values().iterator
        while (i.hasNext()) {
            val info = i.next()
            if (info.connId == connId) {
                seqs += info.sequence
            }
        }

        for (sequence <- seqs) {
            val ti = dataMap.remove(sequence)
            if (ti != null) {
                ti.timer.cancel()
                soc.networkError(sequence, connId)
            } else {
                //log.error("network error but sequence not found, seq={}",sequence)
            }
        }

    }

    def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent): Unit = {
        val ch = e.getChannel
        val connId = ctx.getAttachment().asInstanceOf[String]
        val buf = e.getMessage().asInstanceOf[ChannelBuffer]
        onReceive(buf, connId)
    }

    def channelConnected(ctx: ChannelHandlerContext, e: ChannelStateEvent): Unit = {
        val ch = e.getChannel
        val connId = parseIpPort(ch.getRemoteAddress.toString) + ":" + ch.getId
        ctx.setAttachment(connId);
    }

    def channelDisconnected(ctx: ChannelHandlerContext, e: ChannelStateEvent): Unit = {
        val ch = e.getChannel
        val connId = ctx.getAttachment().asInstanceOf[String]
        onNetworkError(connId)
        log.info("channelDisconnected id={}", connId)
    }

    def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent): Unit = {
        val ch = e.getChannel
        val connId = ctx.getAttachment().asInstanceOf[String];
        log.error("exceptionCaught connId={},e={}", connId, e)
        if (ch.isOpen)
            ch.close()
    }

    def channelIdle(ctx: ChannelHandlerContext, e: IdleStateEvent): Unit = {
        val ch = e.getChannel
        val connId = ctx.getAttachment().asInstanceOf[String];
        lock.lock()

        try {
            val (sequence, buff) = soc.generatePing()
            ch.write(buff);
        } finally {
            lock.unlock()
        }
    }

    class PipelineFactory extends Object with ChannelPipelineFactory {

        def getPipeline(): ChannelPipeline = {
            val pipeline = Channels.pipeline();
            pipeline.addLast("timeout", new IdleStateHandler(timer, 0, 0, pingInterval / 1000));
            pipeline.addLast("decoder", new MemCacheFrameDecoder());
            pipeline.addLast("handler", channelHandler);
            pipeline;
        }
    }

    class ChannelHandler(val client: MemCacheNettyClient) extends IdleStateAwareChannelHandler with Logging {

        override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent): Unit = {
            client.messageReceived(ctx, e)
        }

        override def channelIdle(ctx: ChannelHandlerContext, e: IdleStateEvent): Unit = {
            client.channelIdle(ctx, e)
        }

        override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent): Unit = {
            client.exceptionCaught(ctx, e)
        }

        override def channelConnected(ctx: ChannelHandlerContext, e: ChannelStateEvent): Unit = {
            client.channelConnected(ctx, e)
        }

        override def channelDisconnected(ctx: ChannelHandlerContext, e: ChannelStateEvent): Unit = {
            client.channelDisconnected(ctx, e)
        }

    }

    class TimeoutInfo(val sequence: Int, val connId: String, val timer: QuickTimer)

    class MemCacheFrameDecoder extends FrameDecoder {

        val magicReq = 0x80.toByte
        val magicRes = 0x81.toByte
        override def decode(ctx: ChannelHandlerContext, channel: Channel, buf: ChannelBuffer): Object = {

            val len = buf.readableBytes()
            if (len < 24) {
                return null;
            }

            var s = buf.readerIndex
            val magic = buf.getByte(s)
            if (magic != magicReq && magic != magicRes)
                throw new Exception("memcache frame is not valid")

            val length = 24 + buf.getInt(s + 8)
            if (len < length) return null

            val frame = buf.readBytes(length);
            return frame
        }
    }

}

