package com.ppaass.agent;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;

public class Test {
    public static void main(String[] args) throws IOException, DecoderException {
        InetAddress destinationAddress = InetAddress.getAllByName("10.246.128.21")[0];
//        InetAddress destinationAddress = InetAddress.getAllByName("8.8.8.8")[0];
        int destinationPort = 53;
        InetSocketAddress deviceToRemoteDestinationAddress =
                new InetSocketAddress(destinationAddress, destinationPort);
        DatagramSocket deviceToRemoteUdpSocket = new DatagramSocket();
//        byte[] deviceToRemoteUdpPacketContent = new byte[]{
//                -107, -124, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 4, 116, 105, 109, 101, 7, 97, 110, 100, 114, 111, 105, 100, 3,
//                99, 111, 109, 0, 0, 1, 0, 1
//        };
        byte[] deviceToRemoteUdpPacketContent =
                Hex.decodeHex(
                        "c7000000010858765b28b43e8f4c000044d021f20252e591fd1cb84ecd8b8beeebe73fc10fc781cf6f9e2e4f87244dd07d2056ebf18e64b68e2017972bf285f68de2c9cf99d997be5900da227d3732124198075897976f008456542998d503da632a52cc2be47956a633e334182138bb72b529f8173b6f7b77c356d6fa18d4a5b78f59b81da1bfbd3ca2047c793aa4c1a18027025f1ab946b27464bad0e899068ea5eafd0f2b7af6cf964d5a6f64132d49c4a4565dd0234e2407a9d4c489a51df9b37e50b57c88b315784c04e94cd503792b9f3390f04d140daeb7e821de24e00a64ab3201126cf129d292757f19b1ec22ae24b0d1064392b4ef9afbbb0d61f833b6e7f9442fc45e06bd22d8396b650e9105c90700d354005b4c31323f9cf70fc680c83bd116fb843ec8f7a7abc4de8509e4804814577b397a8e070be909f1f749479740c0e7b2fe1132c517e59005df2cf9063df00c1a4a50b2981b1b2d3e90b0fd2307078a54879bf1a345b8a035e6c08bb03ab4e48cf80702c3c5e40e1f65fb9cc0b4a618f1c69d202ca87f8d38adba3e4deb651d72769bc81b85dfd3adc26a8e9d82f52101837b9cc336ca355480646c46fe12a3ebb7fd3d7556330b9244e142f6d9f48f9a95fad7f5a938224e0fe529c3169aa04b8ac530b45eea607b1aec5d0914bacc4f532bcaecdfed1e94fb8a00bf11a58e20db4472617acf79abe72477f3aca63a8a8374ab0c685a8f1488dc60026cbd337b29ffd0ff89057a9b3ba88a399ed465e7e921e3b447c74cf7320e09f27d98c0e2cd0c25356c1889d063225d18323948794d006419f3a2ae87532384c9562f2d9ee22facf180722dab2bee833d1361a1feea0e861fff429f7d7502fb216610a891d7a5197420b913841b538adc2d8f6a9a27b8aadb00a5828c7139ebb48c803d6452d70e44d17786e0f211761f8ab9d6f7ef351048de959cb19cf19e45aff770451a413a2aef1016e1afa33685d9ef85e44fcd1932f9af90e7d1a19c78a427d4ff1143610057b120bc6b26950439c281509c987856dbb46cac3bdd97f5237156df0e3475860de7559a1c226e3ddc98f18a54f7f77392b60230a6757975f5cfad7588a3147178ad6d174ec0fed4509472051bfe97a6567485af3426c66193ff56dd1655ed1bea7cc3c407640b883e40099d3982cf6a8cb2217e2b49cfdb646d41aab8aad2755bc91f2d4617638b1b8a3fd1e929cfd6c15ef7f333f54d1149a5acf2a35f49b2292129296714aaf6a0f6d37e1cd636e658115f8f220164534e134f4ac561fb823ce532d3522498b21cd4b0ea3f3e38b9da3cb6f092783d981e9f95b2bd6ec0c6fb0567d7ebc6c64a54646abd3cf7ec1a5040e5ae7c501d01b2d04bacab7b3b404dc1304c22b7810a9647cc3cfb98177d6ea999e081854739e57abf0c58d2aaea1d07c642368f38c2859ac155b20f58054a1df5495bba26cb45ebff0962750495d89338f79beb6c9961aa0ad4433ad386fdf67bcc09b234f6e623729e466c5aebbd5ee60e14a5c4d9141ed36f1755efbbb0d5bf3a14718978378820a8acc01e1fa00db8c87d4845cbe8e0a665941dad8efbeabd2236c18a51bddd91fc25e6402436d4af682e4fd346d865b62216c2368f8603785e444a1291132f1efe00bfdad14c1eef356ec31e8f87f483bbaa54cfe1b461383580177b4a70d4491c50c63aab250f3954c8fb83917bbcd49a63af41fa58186eedd3bca8bb4a17565cfbfaff796a5d87886b9ddb");
        DatagramPacket deviceToRemoteUdpPacket =
                new DatagramPacket(deviceToRemoteUdpPacketContent, deviceToRemoteUdpPacketContent.length);
        deviceToRemoteUdpSocket.connect(deviceToRemoteDestinationAddress);
        System.out.println("Begin to send udp:\n" + Hex.encodeHexString(deviceToRemoteUdpPacketContent));
        deviceToRemoteUdpSocket.send(deviceToRemoteUdpPacket);
        byte[] remoteToDeviceUdpPacketContent = new byte[65535];
        DatagramPacket remoteRelayToDeviceUdpPacket =
                new DatagramPacket(remoteToDeviceUdpPacketContent, remoteToDeviceUdpPacketContent.length);
        System.out.println("Begin to receive.");
        deviceToRemoteUdpSocket.receive(remoteRelayToDeviceUdpPacket);
        deviceToRemoteUdpSocket.disconnect();
        System.out.println("Receive:\n" + Hex.encodeHexString(remoteRelayToDeviceUdpPacket.getData()));
    }
}
