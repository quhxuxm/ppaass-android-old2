package com.ppaass.agent.service.handler.tcp;

import com.ppaass.agent.protocol.message.Message;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4SafeDecompressor;

import java.util.List;

public class PpaassMessageDecoder extends ByteToMessageDecoder {
    private static final String FLAG = "__PPAASS__";
    private static final int HEADER_LENGTH = FLAG.length() + 1 + 8;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.readableBytes() < HEADER_LENGTH) {
            return;
        }
        ByteBuf headerBuf = Unpooled.buffer(HEADER_LENGTH);
        in.getBytes(0, headerBuf);
        byte[] flagBytes = new byte[FLAG.length()];
        headerBuf.readBytes(flagBytes);
        String flag = new String(flagBytes);
        if (!FLAG.equals(flag)) {
            throw new UnsupportedOperationException();
        }
        boolean compress = headerBuf.readBoolean();
        long messageLength = headerBuf.readLong();
        if (in.readableBytes() < HEADER_LENGTH + messageLength) {
            return;
        }
        in.readBytes(HEADER_LENGTH);
        ByteBuf bodyBuf = Unpooled.buffer((int) messageLength);
        in.readBytes(bodyBuf);
        if (compress) {
            int compressedBodyBytesLength = bodyBuf.readableBytes();
            LZ4SafeDecompressor lz4Decompressor = LZ4Factory.fastestInstance().safeDecompressor();
            byte[] compressedBodyBytes = new byte[compressedBodyBytesLength];
            bodyBuf.readBytes(compressedBodyBytes);
            byte[] decompressBodyBytes = lz4Decompressor.decompress(compressedBodyBytes, 0, compressedBodyBytes.length,
                    compressedBodyBytesLength);
            bodyBuf = Unpooled.wrappedBuffer(decompressBodyBytes);
        }
        Message result = PpaassMessageUtil.INSTANCE.parseMessageBytes(bodyBuf);
        out.add(result);
    }
}
