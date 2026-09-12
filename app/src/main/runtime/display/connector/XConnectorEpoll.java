package com.winlator.cmod.runtime.display.connector;

import android.util.SparseArray;
import androidx.annotation.Keep;

import com.winlator.cmod.runtime.system.LogManager;

import java.io.IOException;
import java.nio.ByteBuffer;

public class XConnectorEpoll implements Runnable {
  private final ConnectionHandler connectionHandler;
  private final RequestHandler requestHandler;
  private final int epollFd;
  private final int serverFd;
  private final int shutdownFd;
  private Thread epollThread;
  private boolean running = false;
  private boolean multithreadedClients = false;
  private boolean canReceiveAncillaryMessages = false;
  private int initialInputBufferCapacity = 4096;
  private int initialOutputBufferCapacity = 4096;
  private final SparseArray<Client> connectedClients = new SparseArray<>();

  private String TAG = "XConnectorEpoll";

  static {
    System.loadLibrary("winlator");
  }

  public XConnectorEpoll(
      UnixSocketConfig socketConfig,
      ConnectionHandler connectionHandler,
      RequestHandler requestHandler) {
    this.connectionHandler = connectionHandler;
    this.requestHandler = requestHandler;

    serverFd = createAFUnixSocket(socketConfig.path);
    if (serverFd < 0) {
      throw new RuntimeException("Failed to create an AF_UNIX socket.");
    }

    epollFd = createEpollFd();
    if (epollFd < 0) {
      closeFd(serverFd);
      throw new RuntimeException("Failed to create epoll fd.");
    }

    if (!addFdToEpoll(epollFd, serverFd)) {
      closeFd(serverFd);
      closeFd(epollFd);
      throw new RuntimeException("Failed to add server fd to epoll.");
    }

    shutdownFd = createEventFd();
    if (!addFdToEpoll(epollFd, shutdownFd)) {
      closeFd(serverFd);
      closeFd(shutdownFd);
      closeFd(epollFd);
      throw new RuntimeException("Failed to add shutdown fd to epoll.");
    }

    epollThread = new Thread(this);
  }

  public synchronized void start() {
    if (running || epollThread == null) return;
    running = true;
    epollThread.start();
  }

  public synchronized void stop() {
    if (!running || epollThread == null) return;
    running = false;
    requestShutdown();

    while (epollThread.isAlive()) {
      try {
        epollThread.join();
      } catch (InterruptedException e) {
      }
    }
    epollThread = null;
  }

  @Override
  public void run() {
    while (running && doEpollIndefinitely(epollFd, serverFd, !multithreadedClients))
      ;
    shutdown();
  }

  @Keep
  private void handleNewConnection(int fd) {
    final Client client = new Client(this, new ClientSocket(fd));
    client.connected = true;
    if (multithreadedClients) {
      client.shutdownFd = createEventFd();
      client.pollThread =
          new Thread(
              () -> {
                connectionHandler.handleNewConnection(client);
                while (client.connected
                    && waitForSocketRead(client.clientSocket.fd, client.shutdownFd)) {
                  handleExistingConnection(client.clientSocket.fd);
                }
              });
      client.pollThread.start();
    } else connectionHandler.handleNewConnection(client);
    synchronized (connectedClients) {
      connectedClients.put(fd, client);
    }
  }

  @Keep
  private void handleExistingConnection(int fd) {
    Client client;
    synchronized (connectedClients) {
      client = connectedClients.get(fd);
    }
    if (client == null) return;

    XInputStream inputStream = client.getInputStream();
    try {
      if (inputStream != null) {
        if (inputStream.readMoreData(canReceiveAncillaryMessages) > 0) {
          int activePosition = 0;
          while (running && requestHandler.handleRequest(client))
            activePosition = inputStream.getActivePosition();
          inputStream.setActivePosition(activePosition);
        } else killConnection(client, "EOF on read"); // handleExistingConnection's readMoreData()<=0 branch
      } else requestHandler.handleRequest(client);
    } catch (IOException e) {
      killConnection(client, "IOException: " + e);
    }
  }

  public Client getClient(int fd) {
    synchronized (connectedClients) {
      return connectedClients.get(fd);
    }
  }

  public void killConnection(Client client, String reason) {
    if (client == null) {
      LogManager.logW(TAG, "killConnection called with null client; reason=" + reason);
      return;
    }
    int fd = client.clientSocket != null ? client.clientSocket.fd : -1;
    // Log immediately on entry: fd, reason, whether multithreadedClients - Commented out to avoid noise
    // LogManager.logI(TAG, "killConnection entry: fd=" + fd + " reason=\"" + reason + "\" multithreadedClients=" + multithreadedClients);

    // Both the client's poll thread (on EOF) and the connector's thread (on shutdown)
    // can call this. Only the winner performs the actual resource cleanup.
    boolean wonRace = client.markKillingOnce();

    if (wonRace) {
      // Remove from map FIRST to avoid FD-reuse races where a new connection on
      // the same FD could be deleted by this cleanup while it's being accepted.
      synchronized (connectedClients) {
        connectedClients.remove(fd);
      }

      client.connected = false;
      connectionHandler.handleConnectionShutdown(client);

      if (multithreadedClients) {
        // Signal the poll thread to exit its native loop (if we are not that thread)
        if (client.pollThread != null && Thread.currentThread() != client.pollThread) {
          client.requestShutdown();
        }
      } else {
        removeFdFromEpoll(epollFd, fd);
      }
    }


    // BOTH winner and loser must join the thread to ensure it's dead before
    // shutdown() proceeds (unless the current thread IS the poll thread).
    if (multithreadedClients && client.pollThread != null && Thread.currentThread() != client.pollThread) {
      // FIX: Timed join to avoid teardown hang if the poll thread is blocked
      // behind a SIGSTOPped peer. The native socket now has SO_SNDTIMEO,
      // so it should exit eventually. 5s grace period avoids ANRs.
      boolean interrupted = Thread.interrupted();
      try {
        long timeout = 5000;
        long start = System.currentTimeMillis();
        while (client.pollThread.isAlive()) {
          long remaining = timeout - (System.currentTimeMillis() - start);
          if (remaining <= 0) {
            LogManager.logW(TAG, "killConnection: pollThread for fd=" + fd + " did not exit; proceeding to avoid ANR.");
            break;
          }
          try {
            client.pollThread.join();
          } catch (InterruptedException e) {
            interrupted = true;
          }
        }
      } finally {
        if (interrupted) {
          // Restore interrupt status and log interruption
          Thread.currentThread().interrupt();
          LogManager.logW(TAG,
                  "Interrupted while joining pollThread for fd=" + fd + " reason=\"" + reason + "\"");
        }
      }
      client.pollThread = null;
    } else removeFdFromEpoll(epollFd, fd);

    // Resource cleanup only happens once, and only after ensuring the thread is joined.
    if (wonRace) {
      // Free direct byte buffers and ancillary FDs to avoid resource leak warnings
      try {
        client.releaseIOStreams();
      } catch (Exception e) {
        LogManager.logW(TAG, "Exception releasing IO streams for fd=" + fd, e);
      }
      try {
        client.clientSocket.closeAncillaryFds();
      } catch (Exception e) {
        LogManager.logW(TAG, "Exception closing ancillary FDs for fd=" + fd, e);
      }
      if (multithreadedClients) closeFd(client.shutdownFd);
      closeFd(fd);
      LogManager.log(TAG, "killConnection completed: fd=" + fd + " reason=\"" + reason + "\"");
    }
  }

  private void shutdown() {
    while (true) {
      Client client;
      synchronized (connectedClients) {
        int size = connectedClients.size();
        if (size == 0) break;
        // Pop from the map here to prevent busy-spinning if killConnection returns early.
        client = connectedClients.valueAt(size - 1);
        connectedClients.removeAt(size - 1);
      }
      killConnection(client, "connector shutdown");
    }

    removeFdFromEpoll(epollFd, serverFd);
    removeFdFromEpoll(epollFd, shutdownFd);
    closeFd(serverFd);
    closeFd(shutdownFd);
    closeFd(epollFd);
  }

  public int getInitialInputBufferCapacity() {
    return initialInputBufferCapacity;
  }

  public void setInitialInputBufferCapacity(int initialInputBufferCapacity) {
    this.initialInputBufferCapacity = initialInputBufferCapacity;
  }

  public int getInitialOutputBufferCapacity() {
    return initialOutputBufferCapacity;
  }

  public void setInitialOutputBufferCapacity(int initialOutputBufferCapacity) {
    this.initialOutputBufferCapacity = initialOutputBufferCapacity;
  }

  public boolean isMultithreadedClients() {
    return multithreadedClients;
  }

  public void setMultithreadedClients(boolean multithreadedClients) {
    this.multithreadedClients = multithreadedClients;
  }

  public boolean isCanReceiveAncillaryMessages() {
    return canReceiveAncillaryMessages;
  }

  public void setCanReceiveAncillaryMessages(boolean canReceiveAncillaryMessages) {
    this.canReceiveAncillaryMessages = canReceiveAncillaryMessages;
  }

  private void requestShutdown() {
    try {
      ByteBuffer data = ByteBuffer.allocateDirect(8);
      data.asLongBuffer().put(1);
      (new ClientSocket(shutdownFd)).write(data);
      XInputStream.freeDirectBuffer(data);
    } catch (IOException e) {
    }
  }

  public static native void closeFd(int fd);

  private native int createEpollFd();

  private native int createEventFd();

  private native boolean doEpollIndefinitely(int epollFd, int serverFd, boolean addClientToEpoll);

  private native boolean addFdToEpoll(int epollFd, int fd);

  private native void removeFdFromEpoll(int epollFd, int fd);

  private native boolean waitForSocketRead(int clientFd, int shutdownFd);

  private native int createAFUnixSocket(String path);
}
