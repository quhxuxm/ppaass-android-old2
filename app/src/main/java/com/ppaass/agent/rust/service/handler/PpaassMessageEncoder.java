package com.ppaass.agent.rust.service.handler;

import android.util.Log;
import com.ppaass.agent.rust.protocol.message.PpaassMessage;
import com.ppaass.agent.rust.service.IVpnConst;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import java.io.ByteArrayOutputStream;
import java.util.zip.GZIPOutputStream;

public class PpaassMessageEncoder extends MessageToByteEncoder<PpaassMessage> {
    private final boolean compress;

    public PpaassMessageEncoder(boolean compress) {
        this.compress = compress;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, PpaassMessage msg, ByteBuf out) throws Exception {
        out.writeBytes(IVpnConst.PPAASS_PROTOCOL_FLAG.getBytes());
        out.writeBoolean(this.compress);
        //Message body
        var messageBytes = PpaassMessageUtil.INSTANCE.convertPpaassMessageToBytes(msg);
        if (compress) {
            var compressedBytesOutputStream = new ByteArrayOutputStream();
            var gzipOutputStream = new GZIPOutputStream(compressedBytesOutputStream);
            gzipOutputStream.write(messageBytes);
            gzipOutputStream.finish();
            var compressedBodyBytes = compressedBytesOutputStream.toByteArray();
            out.writeLong(compressedBodyBytes.length);
            out.writeBytes(compressedBodyBytes);
            Log.v(PpaassMessageEncoder.class.getName(),
                    "Write following data to remote(compressed):\n" + ByteBufUtil.prettyHexDump(out) + "\n");
            return;
        }
        out.writeLong(messageBytes.length);
        out.writeBytes(messageBytes);
        Log.v(PpaassMessageEncoder.class.getName(),
                "Write following data to remote(non-compress):\n" + ByteBufUtil.prettyHexDump(out) + "\n");
    }
}
