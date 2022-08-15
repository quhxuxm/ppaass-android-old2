package com.ppaass.agent.service.handler.tcp;

import com.ppaass.agent.protocol.message.Message;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

public class PpaassMessageDecoder extends ByteToMessageDecoder {
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        Message result = new Message();
        out.add(result);
    }
}
