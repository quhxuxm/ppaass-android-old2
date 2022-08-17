package com.ppaass.agent.service.handler.tcp;

import com.ppaass.agent.protocol.message.Message;
import com.ppaass.agent.service.IVpnConst;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4SafeDecompressor;

import java.util.List;

public class PpaassMessageDecoder extends ByteToMessageDecoder {
    private static final int HEADER_LENGTH = IVpnConst.PPAASS_PROTOCOL_FLAG.length() + 1 + 8;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.readableBytes() < HEADER_LENGTH) {
            return;
        }
        ByteBuf headerBuf = Unpooled.buffer(HEADER_LENGTH);
        in.getBytes(0, headerBuf);
        byte[] flagBytes = new byte[IVpnConst.PPAASS_PROTOCOL_FLAG.length()];
        headerBuf.readBytes(flagBytes);
        String flag = new String(flagBytes);
        if (!IVpnConst.PPAASS_PROTOCOL_FLAG.equals(flag)) {
            throw new UnsupportedOperationException();
        }
        boolean compress = headerBuf.readBoolean();
        int messageLength = (int) headerBuf.readLong();
        if (in.readableBytes() < HEADER_LENGTH + messageLength) {
            return;
        }
        in.readBytes(HEADER_LENGTH);
        ByteBuf bodyBuf = Unpooled.buffer(messageLength);
        in.readBytes(bodyBuf);
        if (compress) {
            LZ4SafeDecompressor lz4Decompressor = LZ4Factory.fastestInstance().safeDecompressor();
            byte[] compressedBodyBytes = new byte[messageLength];
            bodyBuf.readBytes(compressedBodyBytes);
            byte[] decompressBodyBytes = lz4Decompressor.decompress(compressedBodyBytes, 0, messageLength,
                    messageLength);
            bodyBuf = Unpooled.wrappedBuffer(decompressBodyBytes);
        }
        Message result = PpaassMessageUtil.INSTANCE.parseMessageBytes(bodyBuf);
        out.add(result);
    }
}
