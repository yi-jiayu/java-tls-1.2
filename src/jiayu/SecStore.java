package jiayu;

import jiayu.tls.*;
import jiayu.tls.Certificate;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.xml.bind.DatatypeConverter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;

public class SecStore {
    // magic number for acknowledging successful upload
    private static final long UPLOAD_SUCCESS = 6584997751L;

    private final ServerSocket sc;
    private Path destDir;

    // should this be in memory?
    private PrivateKey serverkey;

    private byte[] serverCert;

    private CipherSuite preferredCipherSuite;

    public SecStore(int port) throws IOException {
        sc = new ServerSocket(port);
    }

    public SecStore(int port, Path destDir) throws IOException {
        this(port);
        setDestinationDirectory(destDir);
    }

    public void setDestinationDirectory(Path dest) throws NotDirectoryException {
        if (!Files.isDirectory(dest)) throw new NotDirectoryException(dest.toString());

        this.destDir = dest;
    }

    public void setPreferredCipherSuite(CipherSuite preferredCipherSuite) {
        this.preferredCipherSuite = preferredCipherSuite;
    }

    public void setServerKey(Path keyFile) throws IOException, InvalidKeySpecException, NoSuchAlgorithmException {
        if (!Files.exists(keyFile)) throw new FileNotFoundException();
        if (!Files.isRegularFile(keyFile)) throw new IllegalArgumentException();

        byte[] keyBytes = Files.readAllBytes(keyFile);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
        serverkey = KeyFactory.getInstance("RSA").generatePrivate(keySpec);
    }

    public void setServerCert(Path cert) throws IOException {
        if (!Files.exists(cert)) throw new FileNotFoundException();

        serverCert = Files.readAllBytes(cert);
    }

//    public void listen() throws IOException {
//        if (destDir == null) throw new IllegalStateException("No destination directory set");
//
//        while (true) {
//            receiveFile(ssc.accept());
//        }
//    }

    public void listen(Handler handler) throws IOException {
        while (true) handler.handle(sc.accept());

    }

//    private void receiveFile(SocketChannel sc) throws IOException {
//        // receive metadata
//        Metadata md = Metadata.readFrom(sc);
//        System.out.println("Received MD5 hash:   " + md.getMD5Hash().toHexString());
//
//        // create output file and channel
//        Path outputFile = destDir.resolve(md.getFileName());
//        FileChannel fc = FileChannel.open(outputFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
//
//        // create tcp buffer
//        ByteBuffer buffer = ByteBuffer.allocate(sc.socket().getReceiveBufferSize());
//
//        // receive content and write to output file
//        ChannelWriter.writeBytes(sc, fc, buffer, md.getSize());
//
//        // calculate md5 hash of output file
//        Checksum md5 = Checksum.getMD5Checksum(outputFile);
//        System.out.println("Calculated MD5 hash: " + md5.toHexString());
//
//        // compare checksums and send result
//        buffer.clear();
//        if (md5.compareTo(md.getMD5Hash())) {
//            System.out.println("File verified.");
//            buffer.putLong(UPLOAD_SUCCESS);
//            buffer.flip();
//        } else {
//            System.out.println("File verification failed.");
//            buffer.putLong(0);
//            buffer.flip();
//        }
//        sc.write(buffer);
//
//        // close socket
//        sc.close();
//    }

    public void receiveConnectionSecured(Socket socket) throws IOException {
        if (serverCert == null) throw new IllegalStateException();

        ConnectionState currentConnState = new ConnectionState(ConnectionEnd.SERVER);
        ConnectionState nextConnState = new ConnectionState(ConnectionEnd.SERVER);

        SecurityParameters sp = nextConnState.getSecurityParameters();

        RecordLayer recordLayer = RecordLayer.getInstance(socket, currentConnState);

        // receive client hello
        System.out.print("Waiting for ClientHello... ");
        System.out.flush();
        ClientHello clientHello;
        try {
            clientHello = (ClientHello) recordLayer.getNextIncomingMessage()
                    .asHandshakeMessage(HandshakeType.CLIENT_HELLO);
            System.out.println("Received.");
            System.out.println(clientHello);

            sp.setClientRandom(clientHello.getRandom().toBytes());

            // choose cipher suite
            System.out.print("Choosing cipher suite... ");
            System.out.flush();
            CipherSuite selectedCipherSuite = Arrays.asList(clientHello.getCipherSuites()).contains(preferredCipherSuite)
                    ? preferredCipherSuite
                    : clientHello.getCipherSuites()[0];
            System.out.println("Selected cipher suite: " + selectedCipherSuite.name());

            sp.setCipherSuite(selectedCipherSuite);

            // send server hello
            System.out.print("Sending ServerHello... ");
            System.out.flush();
            ServerHello serverHello = new ServerHello(selectedCipherSuite);
            recordLayer.putNextOutgoingMessage(serverHello);
            System.out.println("Done.");

            sp.setServerRandom(serverHello.getRandom().toBytes());

            // send server serverCert
            System.out.print("Sending Certificate... ");
            System.out.flush();
            Certificate certificate = new Certificate(new ASN1Cert(this.serverCert));
            recordLayer.putNextOutgoingMessage(certificate);
            System.out.println("Done.");

            // send server hello done
            System.out.print("Sending ServerHelloDone... ");
            System.out.flush();
            ServerHelloDone serverHelloDone = new ServerHelloDone();
            recordLayer.putNextOutgoingMessage(serverHelloDone);
            System.out.println("Done.");

            // receive ClientKeyExchange
            System.out.print("Receiving ClientKeyExchange... ");
            ClientKeyExchange clientKeyExchange = (ClientKeyExchange) recordLayer.getNextIncomingMessage().asHandshakeMessage(HandshakeType.CLIENT_KEY_EXCHANGE);
            System.out.println("Done.");

            // read premaster secret
            PremasterSecret premasterSecret = PremasterSecret.fromBytes(clientKeyExchange.getEncryptedPremasterSecret());
            try {
                premasterSecret.decrypt(serverkey, clientHello.getClientVersion());
            } catch (BadPaddingException | InvalidKeyException | IllegalBlockSizeException e) {
                e.printStackTrace();
                throw new FatalAlertException(AlertDescription.DECRYPT_ERROR);
            } catch (NoSuchPaddingException | NoSuchAlgorithmException e) {
                throw new FatalAlertException(AlertDescription.INTERNAL_ERROR);
            }

            System.out.println("Decrypted premaster secret: " + Arrays.toString(premasterSecret.getBytes()));

            // generate master secret
            MasterSecret masterSecret;
            //noinspection Duplicates
            try {
                masterSecret = MasterSecret.generateMasterSecret(premasterSecret, clientHello, serverHello);
                System.out.println("Master secret: " + Arrays.toString(masterSecret.getBytes()));
                System.out.println("Master secret length: " + masterSecret.getBytes().length);
            } catch (InvalidKeyException | NoSuchAlgorithmException e) {
                throw new FatalAlertException(AlertDescription.INTERNAL_ERROR);
            }

            sp.setMasterSecret(masterSecret.getBytes());

            // receive client ChangeCipherSpec
            recordLayer.getNextIncomingMessage().asChangeCipherSpecMessage();

            // initialise the next connection state
            try {
                nextConnState.init();
            } catch (NoSuchAlgorithmException | InvalidKeyException e) {
                e.printStackTrace();
                throw new FatalAlertException(AlertDescription.INTERNAL_ERROR);
            }

            // make pending connection state current
            currentConnState = nextConnState;

            // we update the record layer to use the new connection state after receiving a CCS
            recordLayer.updateConnectionState(currentConnState);

            // ideally, the record layer should have decrypted the message for us
            Finished clientFinished = (Finished) recordLayer.getNextIncomingMessage().asHandshakeMessage(HandshakeType.FINISHED);

//            // receive encrypted client Finished message
//            byte[] encryptedFinishedMessage = recordLayer.getNextIncomingMessage().getContent();
//
//            System.out.println(DatatypeConverter.printHexBinary(encryptedFinishedMessage));
//
//
//            byte[] decryptedFinishedMessage;
//            try {
//                decryptedFinishedMessage = GenericBlockCipher.decrypt(state, new GenericBlockCipher(iv, ciphertext));
//            } catch (NoSuchPaddingException | NoSuchAlgorithmException | InvalidKeyException | BadPaddingException | IllegalBlockSizeException | InvalidAlgorithmParameterException e) {
//                e.printStackTrace();
//                throw new FatalAlertException(AlertDescription.INTERNAL_ERROR);
//            }
//
//            DatatypeConverter.printHexBinary(decryptedFinishedMessage);

            // verify client Finished message
            Finished clientFinishedVerify = Finished.createClientFinishedMessage(masterSecret, clientHello, serverHello, certificate, serverHelloDone, clientKeyExchange);
            System.out.println("client finished generated by server: " + DatatypeConverter.printHexBinary(clientFinishedVerify.getContent()));


        } catch (FatalAlertException e) {
            e.printStackTrace();
        }
//
//
//        // receive client ChangeCipherSpecMessage
//        ChangeCipherSpecMessage.tryToReadFrom(sc);
//
//        // generate master secret
//        MasterSecret masterSecret;
//        try {
//            masterSecret = MasterSecret.generateMasterSecret(premasterSecret, clientHello, serverHello);
//            System.out.println("Master secret: " + Arrays.toString(masterSecret.getBytes()));
//        } catch (InvalidKeyException | NoSuchAlgorithmException e) {
//            e.printStackTrace();
//        }
//
//        // TODO: 11/04/2016 receive client Finished
//
//        // TODO: 11/04/2016 send server ChangeCipherSpecMessage
//
//        // TODO: 11/04/2016 send server Finished

    }

    @FunctionalInterface
    public interface Handler {
        void handle(Socket socket) throws IOException;
    }
}
