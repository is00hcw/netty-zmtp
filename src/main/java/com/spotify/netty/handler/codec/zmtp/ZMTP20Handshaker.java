package com.spotify.netty.handler.codec.zmtp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;

import static com.spotify.netty.handler.codec.zmtp.ZMTPUtils.checkNotNull;

/**
 * A ZMTP20Codec instance is a ChannelUpstreamHandler that, when placed in a ChannelPipeline, will
 * perform a ZMTP/2.0 handshake with the connected peer and replace itself with the proper pipeline
 * components to encode and decode ZMTP frames.
 */
public class ZMTP20Handshaker implements ZMTPHandshaker {

  private final ZMTPSocketType socketType;
  private final boolean interop;
  private final byte[] localIdentity;

  private boolean splitHandshake;

  /**
   * Construct a ZMTP20Codec with the specified session and optional interoperability behavior.
   *
   * @param socketType    The ZMTP/2.0 socket type.
   * @param interop       whether this socket should implement the ZMTP/1.0 interoperability
   *                      handshake
   * @param localIdentity Local identity. An identity will be generated if null.
   */
  public ZMTP20Handshaker(final ZMTPSocketType socketType, boolean interop,
                          final byte[] localIdentity) {
    this.socketType = checkNotNull(socketType, "ZMTP/2.0 requires a socket type");
    this.interop = interop;
    this.localIdentity = localIdentity;
  }

  public ZMTP20Handshaker(final ZMTPSocketType socketType, final boolean interop) {
    this(socketType, interop, null);
  }

  public ZMTP20Handshaker(final ZMTPSocketType socketType) {
    this(socketType, true);
  }

  @Override
  public ByteBuf onConnect() {
    if (interop) {
      return makeZMTP2CompatSignature();
    } else {
      return makeZMTP2Greeting(true);
    }
  }

  @Override
  public ZMTPHandshake inputOutput(final ByteBuf buffer, final Channel channel)
      throws ZMTPException {
    if (splitHandshake) {
      return new ZMTPHandshake(2, parseZMTP2Greeting(buffer, false));
    }

    if (interop) {
      buffer.markReaderIndex();
      int version = detectProtocolVersion(buffer);
      if (version == 1) {
        buffer.resetReaderIndex();
        // when a ZMTP/1.0 peer is detected, just send the identity bytes. Together
        // with the compatibility signature it makes for a valid ZMTP/1.0 greeting.
        channel.writeAndFlush(Unpooled.wrappedBuffer(localIdentity));
        return new ZMTPHandshake(version, ZMTPUtils.readZMTP1RemoteIdentity(buffer));
      } else {
        splitHandshake = true;
        channel.writeAndFlush(makeZMTP2Greeting(false));
        return null;
      }
    } else {
      return new ZMTPHandshake(2, parseZMTP2Greeting(buffer, true));
    }
  }

  /**
   * Read enough bytes from buffer to deduce the remote protocol version.
   *
   * @param buffer the buffer of data to determine version from
   * @return false if not enough data is available, else true
   * @throws IndexOutOfBoundsException if there is not enough data available in buffer
   */
  static int detectProtocolVersion(final ByteBuf buffer) {
    if (buffer.readByte() != (byte) 0xff) {
      return 1;
    }
    buffer.skipBytes(8);
    if ((buffer.readByte() & 0x01) == 0) {
      return 1;
    }
    return 2;
  }

  /**
   * Make a ByteBuf containing a ZMTP/2.0 greeting, possibly leaving out the 10 initial signature
   * octets if includeSignature is false.
   *
   * @param includeSignature true if a full greeting should be sent, false if the initial 10 octets
   *                         should be left out
   * @return a ByteBuf containing the greeting
   */
  private ByteBuf makeZMTP2Greeting(boolean includeSignature) {
    ByteBuf out = Unpooled.buffer();
    if (includeSignature) {
      ZMTPUtils.encodeLength(0, out, true);
      // last byte of signature
      out.writeByte(0x7f);
      // protocol revision
    }
    out.writeByte(0x01);
    // socket-type
    out.writeByte(socketType.ordinal());
    // identity
    // the final-short flag octet
    out.writeByte(0x00);
    out.writeByte(localIdentity.length);
    out.writeBytes(localIdentity);
    return out;
  }

  /**
   * Create and return a ByteBuf containing the ZMTP/2.0 compatibility detection signature message
   * as specified in the Backwards Compatibility section of http://rfc.zeromq.org/spec:15
   */
  private ByteBuf makeZMTP2CompatSignature() {
    ByteBuf out = Unpooled.buffer();
    ZMTPUtils.encodeLength(localIdentity.length + 1, out, true);
    out.writeByte(0x7f);
    return out;
  }

  static byte[] parseZMTP2Greeting(ByteBuf buffer, boolean expectSignature) throws ZMTPException {
    if (expectSignature) {
      if (buffer.readByte() != (byte) 0xff) {
        throw new ZMTPException("Illegal ZMTP/2.0 greeting, first octet not 0xff");
      }
      buffer.skipBytes(9);
    }
    // ignoring version number and socket type for now
    buffer.skipBytes(2);
    int val = buffer.readByte();
    if (val != 0x00) {
      String s = String.format("Malformed greeting. Byte 13 expected to be 0x00, was: 0x%02x", val);
      throw new ZMTPException(s);
    }
    int len = buffer.readByte();
    final byte[] identity = new byte[len];
    buffer.readBytes(identity);
    return identity;
  }

}