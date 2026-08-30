package net.minecraft.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;
import java.util.List;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.PacketBuffer;

public class MessageDeserializer2 extends ByteToMessageDecoder
{
	/*
	 * ultramine: a frame length is a 21-bit varint, so anyone who can open a
	 * socket can announce a two-megabyte packet and then dribble it in a byte at
	 * a time. Netty holds every byte that has arrived until the frame is
	 * complete, so N connections cost 2N megabytes of heap before a single
	 * packet has been read - and before the client has identified itself at all.
	 *
	 * In the handshake and status phases the legitimate maximum is tiny: a
	 * handshake packet is a few hundred bytes (a couple of kilobytes behind a
	 * proxy that forwards player data), a status request is empty and a ping is
	 * eight bytes. Those get a cap. Login and play do not: FML's handshake syncs
	 * the whole block and item registry during login, which for a large pack is
	 * genuinely megabytes.
	 */
	private static final int MAX_PRE_LOGIN_FRAME = Integer.getInteger("ultramine.network.maxPreLoginFrameBytes", 16 * 1024);

	private static final String __OBFID = "CL_00001255";

	protected void decode(ChannelHandlerContext p_decode_1_, ByteBuf p_decode_2_, List p_decode_3_)
	{
		p_decode_2_.markReaderIndex();
		byte[] abyte = new byte[3];

		for (int i = 0; i < abyte.length; ++i)
		{
			if (!p_decode_2_.isReadable())
			{
				p_decode_2_.resetReaderIndex();
				return;
			}

			abyte[i] = p_decode_2_.readByte();

			if (abyte[i] >= 0)
			{
				PacketBuffer packetbuffer = new PacketBuffer(Unpooled.wrappedBuffer(abyte));

				try
				{
					int j = packetbuffer.readVarIntFromBuffer();

					if (j > MAX_PRE_LOGIN_FRAME && isBeforeLogin(p_decode_1_))
					{
						throw new CorruptedFrameException("packet of " + j + " bytes announced before login");
					}

					if (p_decode_2_.readableBytes() >= j)
					{
						p_decode_3_.add(p_decode_2_.readBytes(j));
						return;
					}

					p_decode_2_.resetReaderIndex();
				}
				finally
				{
					packetbuffer.release();
				}

				return;
			}
		}

		throw new CorruptedFrameException("length wider than 21-bit");
	}

	/**
	 * True while the connection has not reached the login phase - including
	 * before it has any state at all, which is the strictest reading and the
	 * right one for a socket that has sent nothing yet.
	 */
	private static boolean isBeforeLogin(ChannelHandlerContext ctx)
	{
		Object state = ctx.channel().attr(NetworkManager.attrKeyConnectionState).get();
		return state != EnumConnectionState.LOGIN && state != EnumConnectionState.PLAY;
	}
}