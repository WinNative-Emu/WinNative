package com.winlator.cmod.runtime.display.connector;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

public class Client {
  public final ClientSocket clientSocket;
  private final XConnectorEpoll connector;
  private XInputStream inputStream;
  private XOutputStream outputStream;
  private Object tag;
  protected Thread pollThread;
  protected int shutdownFd;
  protected volatile boolean connected; // was a plain boolean; read/written cross-thread

  // Guards killConnection(): both this client's own poll thread (on EOF/IOException)
  // and the connector's teardown thread (XConnectorEpoll.shutdown()) can call
  // killConnection() for the same client concurrently. Without a single-entry
  // gate, both run the full close sequence, double-closing shutdownFd/clientSocket.fd
  // after the OS has already reassigned the number elsewhere (fdsan abort).
  private final AtomicBoolean killing = new AtomicBoolean(false);

  public Client(XConnectorEpoll connector, ClientSocket clientSocket) {
    this.connector = connector;
    this.clientSocket = clientSocket;
  }

  /** True the first time this is called for this client; false on every call after. */
  protected boolean markKillingOnce() {
    return killing.compareAndSet(false, true);
  }

  public void createIOStreams() {
    if (inputStream != null || outputStream != null) return;
    inputStream = new XInputStream(clientSocket, connector.getInitialInputBufferCapacity());
    outputStream = new XOutputStream(clientSocket, connector.getInitialOutputBufferCapacity());
    inputStream.setByteOrder(ByteOrder.LITTLE_ENDIAN);
    outputStream.setByteOrder(ByteOrder.LITTLE_ENDIAN);
  }

  public XInputStream getInputStream() {
    return inputStream;
  }

  public XOutputStream getOutputStream() {
    return outputStream;
  }

  public void releaseIOStreams() {
    if (inputStream != null) {
      inputStream.release();
      inputStream = null;
    }
    if (outputStream != null) {
      outputStream.release();
      outputStream = null;
    }
  }

  public Object getTag() {
    return tag;
  }

  public void setTag(Object tag) {
    this.tag = tag;
  }

  protected void requestShutdown() {
    try {
      ByteBuffer data = ByteBuffer.allocateDirect(8);
      data.asLongBuffer().put(1);
      (new ClientSocket(shutdownFd)).write(data);
      XInputStream.freeDirectBuffer(data);
    } catch (IOException e) {
    }
  }
}
