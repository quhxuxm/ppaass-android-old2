package com.ppaass.agent.service.handler.tcp;

import com.ppaass.agent.protocol.message.Message;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

public class PpaassMessageEncoder extends MessageToByteEncoder<Message> {
    @Override
    protected void encode(ChannelHandlerContext ctx, Message msg, ByteBuf out) throws Exception {
        out.writeInt(msg.getId().length());
        out.writeBytes(msg.getId().getBytes());
        if (msg.getRefId() != null) {
            out.writeInt(msg.getRefId().length());
            out.writeBytes(msg.getRefId().getBytes());
        } else {
            out.writeInt(0);
        }
        if (msg.getConnectionId() != null) {
            out.writeInt(msg.getConnectionId().length());
            out.writeBytes(msg.getConnectionId().getBytes());
        } else {
            out.writeInt(0);
        }
        out.writeLong(msg.getUserToken().length());
        out.writeBytes(msg.getUserToken().getBytes());
        if (msg.getPayload() != null) {
            out.writeInt(msg.getPayload().length);
            out.writeBytes(msg.getPayload());
        } else {
            out.writeInt(0);
        }
    }
}
