package jiayu;

import jiayu.tls.SecureSocket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HashSet;

public abstract class AbstractSecStoreClient implements SecStoreClient {

    final HashSet<X509Certificate> caCerts;

    boolean connected;

    SecureSocket socket;
    InputStream in;
    OutputStream out;

    AbstractSecStoreClient() {
        connected = false;
        caCerts = new HashSet<>();
    }

    @Override
    public void addCACert(Path caCert) throws CertificateException, IOException {
        caCerts.add((X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(Files.newInputStream(caCert)));
    }

    @Override
    public void connect(String host, int port) throws IOException {

        socket = new SecureSocket();
        caCerts.forEach(caCert -> socket.addCACertificate(caCert));
        System.out.println(String.format("Connecting to server at %s:%d", host, port));
        socket.connectSecured(host, port);
        in = socket.getInputStream();
        out = socket.getOutputStream();
        connected = true;
    }

    @Override
    public boolean uploadFile(String file) throws IOException {
        return uploadFile(Paths.get(file));
    }

    @Override
    public void disconnect() throws IOException {
        if (!connected) throw new IllegalStateException("not connected");

        in.close();
        out.close();

        socket.close();
    }

//    void log(long encTime, long rtt) throws IOException {
//        Path logfile = Paths.get("C:\\Users\\jiayu\\IdeaProjects\\tls-1.2-implementation-java\\misc\\logfile.txt");
//        Files.write(logfile, String.format(",%d,%d%n", encTime, rtt).getBytes(), StandardOpenOption.APPEND);
//    }
}
