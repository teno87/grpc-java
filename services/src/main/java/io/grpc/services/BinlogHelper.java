/*
 * Copyright 2017 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.services;

import static io.grpc.BinaryLogProvider.BYTEARRAY_MARSHALLER;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.primitives.Bytes;
import com.google.protobuf.ByteString;
import io.grpc.Attributes;
import io.grpc.BinaryLogProvider.CallId;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import io.grpc.Grpc;
import io.grpc.InternalMetadata;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.Marshaller;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.binarylog.GrpcLogEntry;
import io.grpc.binarylog.GrpcLogEntry.Type;
import io.grpc.binarylog.Message;
import io.grpc.binarylog.Metadata.Builder;
import io.grpc.binarylog.MetadataEntry;
import io.grpc.binarylog.Peer;
import io.grpc.binarylog.Peer.PeerType;
import io.grpc.binarylog.Uint128;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * A binary log class that is configured for a specific {@link MethodDescriptor}.
 */
@ThreadSafe
final class BinlogHelper {
  private static final Logger logger = Logger.getLogger(BinlogHelper.class.getName());
  private static final int IP_PORT_BYTES = 2;
  private static final int IP_PORT_UPPER_MASK = 0xff00;
  private static final int IP_PORT_LOWER_MASK = 0xff;
  private static final boolean SERVER = true;
  private static final boolean CLIENT = false;

  @VisibleForTesting
  static final CallId emptyCallId = new CallId(0, 0);
  @VisibleForTesting
  static final SocketAddress DUMMY_SOCKET = new DummySocketAddress();
  @VisibleForTesting
  static final boolean DUMMY_IS_COMPRESSED = false;

  @VisibleForTesting
  final SinkWriter writer;

  @VisibleForTesting
  BinlogHelper(SinkWriter writer) {
    this.writer = writer;
  }

  // TODO(zpencer): move proto related static helpers into this class
  static final class SinkWriterImpl extends SinkWriter {
    private final BinaryLogSink sink;
    private final int maxHeaderBytes;
    private final int maxMessageBytes;

    SinkWriterImpl(BinaryLogSink sink, int maxHeaderBytes, int maxMessageBytes) {
      this.sink = sink;
      this.maxHeaderBytes = maxHeaderBytes;
      this.maxMessageBytes = maxMessageBytes;
    }

    @Override
    void logSendInitialMetadata(Metadata metadata, boolean isServer, CallId callId) {
      GrpcLogEntry entry = GrpcLogEntry
          .newBuilder()
          .setType(Type.SEND_INITIAL_METADATA)
          .setLogger(isServer ? GrpcLogEntry.Logger.SERVER : GrpcLogEntry.Logger.CLIENT)
          .setCallId(callIdToProto(callId))
          .setMetadata(metadataToProto(metadata, maxHeaderBytes))
          .build();
      sink.write(entry);
    }

    @Override
    void logRecvInitialMetadata(
        Metadata metadata, boolean isServer, CallId callId, SocketAddress peerSocket) {
      GrpcLogEntry entry = GrpcLogEntry
          .newBuilder()
          .setType(Type.RECV_INITIAL_METADATA)
          .setLogger(isServer ? GrpcLogEntry.Logger.SERVER : GrpcLogEntry.Logger.CLIENT)
          .setCallId(callIdToProto(callId))
          .setPeer(socketToProto(peerSocket))
          .setMetadata(metadataToProto(metadata, maxHeaderBytes))
          .build();
      sink.write(entry);
    }

    @Override
    void logTrailingMetadata(Metadata metadata, boolean isServer, CallId callId) {
      GrpcLogEntry entry = GrpcLogEntry
          .newBuilder()
          .setType(isServer ? Type.SEND_TRAILING_METADATA : Type.RECV_TRAILING_METADATA)
          .setLogger(isServer ? GrpcLogEntry.Logger.SERVER : GrpcLogEntry.Logger.CLIENT)
          .setCallId(callIdToProto(callId))
          .setMetadata(metadataToProto(metadata, maxHeaderBytes))
          .build();
      sink.write(entry);
    }

    @Override
    <T> void logOutboundMessage(
        Marshaller<T> marshaller,
        T message,
        boolean compressed,
        boolean isServer,
        CallId callId) {
      if (marshaller != BYTEARRAY_MARSHALLER) {
        throw new IllegalStateException("Expected the BinaryLog's ByteArrayMarshaller");
      }
      byte[] bytes = (byte[]) message;
      GrpcLogEntry entry = GrpcLogEntry
          .newBuilder()
          .setType(Type.SEND_MESSAGE)
          .setLogger(isServer ? GrpcLogEntry.Logger.SERVER : GrpcLogEntry.Logger.CLIENT)
          .setCallId(callIdToProto(callId))
          .setMessage(messageToProto(bytes, compressed, maxMessageBytes))
          .build();
      sink.write(entry);
    }

    @Override
    <T> void logInboundMessage(
        Marshaller<T> marshaller,
        T message,
        boolean compressed,
        boolean isServer,
        CallId callId) {
      if (marshaller != BYTEARRAY_MARSHALLER) {
        throw new IllegalStateException("Expected the BinaryLog's ByteArrayMarshaller");
      }
      byte[] bytes = (byte[]) message;
      GrpcLogEntry entry = GrpcLogEntry
          .newBuilder()
          .setType(Type.RECV_MESSAGE)
          .setLogger(isServer ? GrpcLogEntry.Logger.SERVER : GrpcLogEntry.Logger.CLIENT)
          .setCallId(callIdToProto(callId))
          .setMessage(messageToProto(bytes, compressed, maxMessageBytes))
          .build();
      sink.write(entry);
    }

    @Override
    int getMaxHeaderBytes() {
      return maxHeaderBytes;
    }

    @Override
    int getMaxMessageBytes() {
      return maxMessageBytes;
    }
  }

  abstract static class SinkWriter {
    /**
     * Logs the sending of initial metadata. This method logs the appropriate number of bytes
     * as determined by the binary logging configuration.
     */
    abstract void logSendInitialMetadata(Metadata metadata, boolean isServer, CallId callId);

    /**
     * Logs the receiving of initial metadata. This method logs the appropriate number of bytes
     * as determined by the binary logging configuration.
     */
    abstract void logRecvInitialMetadata(
        Metadata metadata, boolean isServer, CallId callId, SocketAddress peerSocket);

    /**
     * Logs the trailing metadata. This method logs the appropriate number of bytes
     * as determined by the binary logging configuration.
     */
    abstract void logTrailingMetadata(Metadata metadata, boolean isServer, CallId callId);

    /**
     * Logs the outbound message. This method logs the appropriate number of bytes from
     * {@code message}, and returns a duplicate of the message.
     * The number of bytes logged is determined by the binary logging configuration.
     * This method takes ownership of {@code message}.
     */
    abstract <T> void logOutboundMessage(
        Marshaller<T> marshaller, T message, boolean compressed, boolean isServer, CallId callId);

    /**
     * Logs the inbound message. This method logs the appropriate number of bytes from
     * {@code message}, and returns a duplicate of the message.
     * The number of bytes logged is determined by the binary logging configuration.
     * This method takes ownership of {@code message}.
     */
    abstract <T> void logInboundMessage(
        Marshaller<T> marshaller, T message, boolean compressed, boolean isServer, CallId callId);

    /**
     * Returns the number bytes of the header this writer will log, according to configuration.
     */
    abstract int getMaxHeaderBytes();

    /**
     * Returns the number bytes of the message this writer will log, according to configuration.
     */
    abstract int getMaxMessageBytes();
  }

  static SocketAddress getPeerSocket(Attributes streamAttributes) {
    SocketAddress peer = streamAttributes.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
    if (peer == null) {
      return DUMMY_SOCKET;
    }
    return peer;
  }

  public ClientInterceptor getClientInterceptor(final CallId callId) {
    return new ClientInterceptor() {
      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          final MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        return new SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
          @Override
          public void start(Listener<RespT> responseListener, Metadata headers) {
            writer.logSendInitialMetadata(headers, CLIENT, callId);
            ClientCall.Listener<RespT> wListener =
                new SimpleForwardingClientCallListener<RespT>(responseListener) {
                  @Override
                  public void onMessage(RespT message) {
                    writer.logInboundMessage(
                        method.getResponseMarshaller(),
                        message,
                        DUMMY_IS_COMPRESSED,
                        CLIENT,
                        callId);
                    super.onMessage(message);
                  }

                  @Override
                  public void onHeaders(Metadata headers) {
                    SocketAddress peer = getPeerSocket(getAttributes());
                    writer.logRecvInitialMetadata(headers, CLIENT, callId, peer);
                    super.onHeaders(headers);
                  }

                  @Override
                  public void onClose(Status status, Metadata trailers) {
                    writer.logTrailingMetadata(trailers, CLIENT, callId);
                    super.onClose(status, trailers);
                  }
                };
            super.start(wListener, headers);
          }

          @Override
          public void sendMessage(ReqT message) {
            writer.logOutboundMessage(
                method.getRequestMarshaller(),
                message,
                DUMMY_IS_COMPRESSED,
                CLIENT,
                callId);
            super.sendMessage(message);
          }
        };
      }
    };
  }

  public ServerInterceptor getServerInterceptor(final CallId callId) {
    return new ServerInterceptor() {
      @Override
      public <ReqT, RespT> Listener<ReqT> interceptCall(
          final ServerCall<ReqT, RespT> call,
          Metadata headers,
          ServerCallHandler<ReqT, RespT> next) {
        SocketAddress peer = getPeerSocket(call.getAttributes());
        writer.logRecvInitialMetadata(headers, SERVER, callId, peer);
        ServerCall<ReqT, RespT> wCall = new SimpleForwardingServerCall<ReqT, RespT>(call) {
          @Override
          public void sendMessage(RespT message) {
            writer.logOutboundMessage(
                call.getMethodDescriptor().getResponseMarshaller(),
                message,
                DUMMY_IS_COMPRESSED,
                SERVER,
                callId);
            super.sendMessage(message);
          }

          @Override
          public void sendHeaders(Metadata headers) {
            writer.logSendInitialMetadata(headers, SERVER, callId);
            super.sendHeaders(headers);
          }

          @Override
          public void close(Status status, Metadata trailers) {
            writer.logTrailingMetadata(trailers, SERVER, callId);
            super.close(status, trailers);
          }
        };

        return new SimpleForwardingServerCallListener<ReqT>(next.startCall(wCall, headers)) {
          @Override
          public void onMessage(ReqT message) {
            writer.logInboundMessage(
                call.getMethodDescriptor().getRequestMarshaller(),
                message,
                DUMMY_IS_COMPRESSED,
                SERVER,
                callId);
            super.onMessage(message);
          }
        };
      }
    };
  }

  interface Factory {
    @Nullable
    BinlogHelper getLog(String fullMethodName);
  }

  static final class FactoryImpl implements Factory {
    // '*' for global, 'service/*' for service glob, or 'service/method' for fully qualified.
    private static final Pattern logPatternRe = Pattern.compile("[^{]+");
    // A curly brace wrapped expression. Will be further matched with the more specified REs below.
    private static final Pattern logOptionsRe = Pattern.compile("\\{[^}]+}");
    private static final Pattern configRe = Pattern.compile(
        String.format("^(%s)(%s)?$", logPatternRe.pattern(), logOptionsRe.pattern()));
    // Regexes to extract per-binlog options
    // The form: {m:256}
    private static final Pattern msgRe = Pattern.compile("\\{m(?::(\\d+))?}");
    // The form: {h:256}
    private static final Pattern headerRe = Pattern.compile("\\{h(?::(\\d+))?}");
    // The form: {h:256,m:256}
    private static final Pattern bothRe = Pattern.compile("\\{h(?::(\\d+))?;m(?::(\\d+))?}");

    private final BinlogHelper globalLog;
    private final Map<String, BinlogHelper> perServiceLogs;
    private final Map<String, BinlogHelper> perMethodLogs;

    /**
     * Accepts a string in the format specified by the binary log spec.
     */
    @VisibleForTesting
    FactoryImpl(BinaryLogSink sink, String configurationString) {
      Preconditions.checkNotNull(sink);
      Preconditions.checkState(configurationString != null && configurationString.length() > 0);
      BinlogHelper globalLog = null;
      Map<String, BinlogHelper> perServiceLogs = new HashMap<String, BinlogHelper>();
      Map<String, BinlogHelper> perMethodLogs = new HashMap<String, BinlogHelper>();

      for (String configuration : Splitter.on(',').split(configurationString)) {
        Matcher configMatcher = configRe.matcher(configuration);
        if (!configMatcher.matches()) {
          throw new IllegalArgumentException("Bad input: " + configuration);
        }
        String methodOrSvc = configMatcher.group(1);
        String binlogOptionStr = configMatcher.group(2);
        BinlogHelper binLog = createBinaryLog(sink, binlogOptionStr);
        if (binLog == null) {
          continue;
        }
        if (methodOrSvc.equals("*")) {
          if (globalLog != null) {
            logger.log(Level.SEVERE, "Ignoring duplicate entry: " + configuration);
            continue;
          }
          globalLog = binLog;
          logger.info("Global binlog: " + globalLog);
        } else if (isServiceGlob(methodOrSvc)) {
          String service = MethodDescriptor.extractFullServiceName(methodOrSvc);
          if (perServiceLogs.containsKey(service)) {
            logger.log(Level.SEVERE, "Ignoring duplicate entry: " + configuration);
            continue;
          }
          perServiceLogs.put(service, binLog);
          logger.info(String.format("Service binlog: service=%s log=%s", service, binLog));
        } else {
          // assume fully qualified method name
          if (perMethodLogs.containsKey(methodOrSvc)) {
            logger.log(Level.SEVERE, "Ignoring duplicate entry: " + configuration);
            continue;
          }
          perMethodLogs.put(methodOrSvc, binLog);
          logger.info(String.format("Method binlog: method=%s log=%s", methodOrSvc, binLog));
        }
      }
      this.globalLog = globalLog;
      this.perServiceLogs = Collections.unmodifiableMap(perServiceLogs);
      this.perMethodLogs = Collections.unmodifiableMap(perMethodLogs);
    }

    /**
     * Accepts a full method name and returns the log that should be used.
     */
    @Override
    public BinlogHelper getLog(String fullMethodName) {
      BinlogHelper methodLog = perMethodLogs.get(fullMethodName);
      if (methodLog != null) {
        return methodLog;
      }
      BinlogHelper serviceLog = perServiceLogs.get(
          MethodDescriptor.extractFullServiceName(fullMethodName));
      if (serviceLog != null) {
        return serviceLog;
      }
      return globalLog;
    }

    /**
     * Returns a binlog with the correct header and message limits or {@code null} if the input
     * is malformed. The input should be a string that is in one of these forms:
     *
     * <p>{@code {h(:\d+)?}, {m(:\d+)?}, {h(:\d+)?,m(:\d+)?}}
     *
     * <p>If the {@code logConfig} is null, the returned binlog will have a limit of
     * Integer.MAX_VALUE.
     */
    @VisibleForTesting
    @Nullable
    static BinlogHelper createBinaryLog(BinaryLogSink sink, @Nullable String logConfig) {
      if (logConfig == null) {
        return new BinlogHelper(
            new SinkWriterImpl(sink, Integer.MAX_VALUE, Integer.MAX_VALUE));
      }
      try {
        Matcher headerMatcher;
        Matcher msgMatcher;
        Matcher bothMatcher;
        final int maxHeaderBytes;
        final int maxMsgBytes;
        if ((headerMatcher = headerRe.matcher(logConfig)).matches()) {
          String maxHeaderStr = headerMatcher.group(1);
          maxHeaderBytes =
              maxHeaderStr != null ? Integer.parseInt(maxHeaderStr) : Integer.MAX_VALUE;
          maxMsgBytes = 0;
        } else if ((msgMatcher = msgRe.matcher(logConfig)).matches()) {
          maxHeaderBytes = 0;
          String maxMsgStr = msgMatcher.group(1);
          maxMsgBytes = maxMsgStr != null ? Integer.parseInt(maxMsgStr) : Integer.MAX_VALUE;
        } else if ((bothMatcher = bothRe.matcher(logConfig)).matches()) {
          String maxHeaderStr = bothMatcher.group(1);
          String maxMsgStr = bothMatcher.group(2);
          maxHeaderBytes =
              maxHeaderStr != null ? Integer.parseInt(maxHeaderStr) : Integer.MAX_VALUE;
          maxMsgBytes = maxMsgStr != null ? Integer.parseInt(maxMsgStr) : Integer.MAX_VALUE;
        } else {
          logger.log(Level.SEVERE, "Illegal log config pattern: " + logConfig);
          return null;
        }
        return new BinlogHelper(new SinkWriterImpl(sink, maxHeaderBytes, maxMsgBytes));
      } catch (NumberFormatException e) {
        logger.log(Level.SEVERE, "Illegal log config pattern: " + logConfig);
        return null;
      }
    }

    /**
     * Returns true if the input string is a glob of the form: {@code <package-service>/*}.
     */
    static boolean isServiceGlob(String input) {
      return input.endsWith("/*");
    }
  }

  /**
   * Returns a {@link Uint128} from a CallId.
   */
  static Uint128 callIdToProto(CallId callId) {
    Preconditions.checkNotNull(callId);
    return Uint128
        .newBuilder()
        .setHigh(callId.hi)
        .setLow(callId.lo)
        .build();
  }

  @VisibleForTesting
  // TODO(zpencer): the binlog design does not specify how to actually express the peer bytes
  static Peer socketToProto(SocketAddress address) {
    Preconditions.checkNotNull(address);
    PeerType peerType = PeerType.UNKNOWN_PEERTYPE;
    byte[] peerAddress = null;

    if (address instanceof InetSocketAddress) {
      InetAddress inetAddress = ((InetSocketAddress) address).getAddress();
      if (inetAddress instanceof Inet4Address) {
        peerType = PeerType.PEER_IPV4;
      } else if (inetAddress instanceof Inet6Address) {
        peerType = PeerType.PEER_IPV6;
      } else {
        logger.log(Level.SEVERE, "unknown type of InetSocketAddress: {}", address);
      }
      int port = ((InetSocketAddress) address).getPort();
      byte[] portBytes = new byte[IP_PORT_BYTES];
      portBytes[0] = (byte) ((port & IP_PORT_UPPER_MASK) >> 8);
      portBytes[1] = (byte) (port & IP_PORT_LOWER_MASK);
      peerAddress = Bytes.concat(inetAddress.getAddress(), portBytes);
    } else if (address.getClass().getName().equals("io.netty.channel.unix.DomainSocketAddress")) {
      // To avoid a compile time dependency on grpc-netty, we check against the runtime class name.
      peerType = PeerType.PEER_UNIX;
    }
    if (peerAddress == null) {
      peerAddress = address.toString().getBytes(Charset.defaultCharset());
    }
    return Peer.newBuilder()
        .setPeerType(peerType)
        .setPeer(ByteString.copyFrom(peerAddress))
        .build();
  }

  @VisibleForTesting
  static io.grpc.binarylog.Metadata metadataToProto(Metadata metadata, int maxHeaderBytes) {
    Preconditions.checkNotNull(metadata);
    Preconditions.checkState(maxHeaderBytes >= 0);
    Builder builder = io.grpc.binarylog.Metadata.newBuilder();
    // This code is tightly coupled with Metadata's implementation
    byte[][] serialized;
    if (maxHeaderBytes > 0 && (serialized = InternalMetadata.serialize(metadata)) != null) {
      int written = 0;
      for (int i = 0; i < serialized.length && written < maxHeaderBytes; i += 2) {
        byte[] key = serialized[i];
        byte[] value = serialized[i + 1];
        if (written + key.length + value.length <= maxHeaderBytes) {
          builder.addEntry(
              MetadataEntry
                  .newBuilder()
                  .setKey(ByteString.copyFrom(key))
                  .setValue(ByteString.copyFrom(value))
                  .build());
          written += key.length;
          written += value.length;
        }
      }
    }
    return builder.build();
  }

  @VisibleForTesting
  static Message messageToProto(byte[] message, boolean compressed, int maxMessageBytes) {
    Preconditions.checkNotNull(message);
    Message.Builder builder = Message
        .newBuilder()
        .setFlags(flagsForMessage(compressed))
        .setLength(message.length);
    if (maxMessageBytes > 0) {
      int desiredBytes = Math.min(maxMessageBytes, message.length);
      builder.setData(ByteString.copyFrom(message, 0, desiredBytes));
    }
    return builder.build();
  }

  /**
   * Returns a flag based on the arguments.
   */
  @VisibleForTesting
  static int flagsForMessage(boolean compressed) {
    return compressed ? 1 : 0;
  }

  private static class DummySocketAddress extends SocketAddress {
    private static final long serialVersionUID = 0;
  }
}
