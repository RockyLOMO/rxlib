/*
 * Copyright (c) 2015, Blake
 * All Rights Reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * 3. The name of the author may not be used to endorse or promote
 * products derived from this software without specific prior
 * written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package org.rx.socks.shadowsocks.network.nio;

import org.rx.socks.shadowsocks.Constant;
import org.rx.socks.shadowsocks.misc.Config;
import org.rx.socks.shadowsocks.misc.Util;
import org.rx.socks.shadowsocks.network.proxy.IProxy;
import org.rx.socks.shadowsocks.network.proxy.ProxyFactory;
import org.rx.socks.shadowsocks.ss.CryptFactory;
import org.rx.socks.shadowsocks.ss.ICrypt;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Logger;


public class PipeWorker implements Runnable {
    private Logger logger = Logger.getLogger(PipeWorker.class.getName());
    private SocketChannel _localChannel;
    private SocketChannel _remoteChannel;
    private ISocketHandler _localSocketHandler;
    private ISocketHandler _remoteSocketHandler;
    private IProxy _proxy;
    private ICrypt _crypt;
    public String socketInfo;
    private ByteArrayOutputStream _outStream;
    private BlockingQueue _processQueue;
    private volatile boolean requestedClose;

    public PipeWorker(ISocketHandler localHandler, SocketChannel localChannel, ISocketHandler remoteHandler, SocketChannel remoteChannel, Config config) {
        _localChannel = localChannel;
        _remoteChannel = remoteChannel;
        _localSocketHandler = localHandler;
        _remoteSocketHandler = remoteHandler;
        _crypt = CryptFactory.get(config.getMethod(), config.getPassword());
        _proxy = ProxyFactory.get(config.getProxyType());
        _outStream = new ByteArrayOutputStream(Constant.BUFFER_SIZE);
        _processQueue = new LinkedBlockingQueue();
        requestedClose = false;
        socketInfo = String.format("Local: %s, Remote: %s", localChannel, remoteChannel);
    }

    public void close() {
        requestedClose = true;
        processData(null, 0, false);
    }

    public void forceClose() {
        logger.fine("PipeWorker::forceClose " + socketInfo);

        // close socket now!
        try {
            if (_localChannel.isOpen()) {
                _localChannel.close();
            }
            if (_remoteChannel.isOpen()) {
                _remoteChannel.close();
            }
        } catch (IOException e) {
            logger.fine("PipeWorker::forceClose> " + e.toString());
        }

        // follow the standard close steps
        close();
    }

    public void processData(byte[] data, int count, boolean isEncrypted) {
        if (data != null) {
            byte[] dataCopy = new byte[count];
            System.arraycopy(data, 0, dataCopy, 0, count);
            _processQueue.add(new PipeEvent(dataCopy, isEncrypted));
        }
        else {
            _processQueue.add(new PipeEvent());
        }
    }

    @Override
    public void run() {
        PipeEvent event;
        ISocketHandler socketHandler;
        SocketChannel channel;
        List<byte[]> sendData = null;

        while(true) {
            // make sure all the requests in the queue are processed
            if (_processQueue.isEmpty() && requestedClose) {
                logger.fine("PipeWorker closed ("+  _processQueue.size() + "): " + this.socketInfo);
                if (_localChannel.isOpen()) {
                    _localSocketHandler.send(new ChangeRequest(_localChannel, ChangeRequest.CLOSE_CHANNEL));
                }
                if (_remoteChannel.isOpen()) {
                    _remoteSocketHandler.send(new ChangeRequest(_remoteChannel, ChangeRequest.CLOSE_CHANNEL));
                }
                break;
            }

            try {
                event = (PipeEvent)_processQueue.take();

                // if event data is null, it means this is a wake-up call
                // to check if any other thread is requested to close sockets
                if (event.data == null) {
                    continue;
                }

                // process proxy packet if needed
                if (!_proxy.isReady()) {
                    // packet for local socket
                    byte[] temp = _proxy.getResponse(event.data);
                    if (temp != null) {
                        _localSocketHandler.send(new ChangeRequest(_localChannel, ChangeRequest.CHANGE_SOCKET_OP,
                                SelectionKey.OP_WRITE), temp);
                    }
                    // packet for remote socket (ss payload + request)
                    sendData = _proxy.getRemoteResponse(event.data);
                    if (sendData == null) {
                        continue;
                    }
                    // index 0 is always ss payload
                    logger.info("Connected to: " + Util.getRequestedHostInfo(sendData.get(0)));
                    //logger.info("Test: " + Util.bytesToString(temp, 0, temp.length));
                }
                else {
                    sendData.clear();
                    sendData.add(event.data);
                }

                for (byte[] bytes : sendData) {
                    // empty stream for new data
                    _outStream.reset();

                    if (event.isEncrypted) {
                        _crypt.encrypt(bytes, _outStream);
                        channel = _remoteChannel;
                        socketHandler = _remoteSocketHandler;
                    } else {
                        _crypt.decrypt(bytes, _outStream);
                        channel = _localChannel;
                        socketHandler = _localSocketHandler;
                    }

                    // data is ready to send to socket
                    ChangeRequest request = new ChangeRequest(channel, ChangeRequest.CHANGE_SOCKET_OP, SelectionKey.OP_WRITE);
                    socketHandler.send(request, _outStream.toByteArray());
                }
            } catch (InterruptedException e) {
                logger.fine(Util.getErrorMessage(e));
                break;
            }
        }
    }
}
