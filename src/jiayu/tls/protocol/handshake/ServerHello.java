package jiayu.tls.protocol.handshake;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;

public class ServerHello extends Handshake {
    private static final short SERVER_VERSION = 0x0303;
    private static final byte COMPRESSION_METHOD = 0x00;

    private final int length;
    private final byte[] header;

    private final short serverVersion;
    private final Random random;
    private final UIntVector sessionId;
    private final CipherSuite cipherSuite;
    private final byte compressionMethod;

    public ServerHello(CipherSuite selectedCipherSuite) {
        this(
                new SecureRandom().nextInt(),
                selectedCipherSuite
        );
    }

    public ServerHello(int sessionId, CipherSuite selectedCipherSuite) {
        this(
                SERVER_VERSION,
                new Random(),
                new UIntVector(sessionId),
                selectedCipherSuite,
                COMPRESSION_METHOD
        );
    }

    private ServerHello(short serverVersion, Random random, UIntVector sessionId, CipherSuite cipherSuite, byte compressionMethod) {
        super(HandshakeType.SERVER_HELLO);

        this.serverVersion = serverVersion;
        this.random = random;
        this.sessionId = sessionId;
        this.cipherSuite = cipherSuite;
        this.compressionMethod = COMPRESSION_METHOD;

        length = 2                  // server version (2 bytes)
                + 32                // random (32 bytes)
                + 1                 // sessionid.length (1 byte)
                + sessionId.length  // sessionid (sessionid.length)
                + 2                 // selected cipher suite (2 bytes)
                + 1;                // selected compression method (1 byte)

        header = createHeader(length);
    }

    public short getServerVersion() {
        return serverVersion;
    }

    public Random getRandom() {
        return random;
    }

    public int getSessionId() {
        return sessionId.getValue();
    }

    public CipherSuite getCipherSuite() {
        return cipherSuite;
    }

    private byte[] toBytes() {
        return ByteBuffer.allocate(HEADER_LENGTH + length)
                .put(header)                  // header
                .putShort(serverVersion)      // server version
                .put(random.toBytes())        // random
                .put(sessionId.length)        // session id length
                .put(sessionId.bytes)         // session id
                .putShort(cipherSuite.value)  // cipher suite
                .put(compressionMethod)       // compression method
                .array();

    }

    @Override
    public byte[] getContent() {
        return toBytes();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("server_version: %s", Integer.toHexString(serverVersion)))
                .append(String.format("random: %s", Arrays.toString(random.toBytes())))
                .append(String.format("session_id: %d", sessionId.getValue()))
                .append(String.format("cipher_suite: %d", Integer.toHexString(cipherSuite.value)))
                .append(String.format("compression_method: %d", Integer.toHexString(compressionMethod)));

        return sb.toString();
    }
}
