package com.screenohrecord.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Properties;
import java.util.function.BooleanSupplier;
import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbFileOutputStream;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;

/** SMB2 and passive binary FTP. All connections are restricted to the current Wi-Fi network. */
final class TransferConnection implements Closeable {
    private final Context context;
    private final Network network;
    private final BooleanSupplier cancelled;
    private FTPClient ftp;
    private CIFSContext smbContext;
    private SmbFile directory;

    static Network wifi(Context context) {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        Network network = manager.getActiveNetwork();
        NetworkCapabilities caps = manager.getNetworkCapabilities(network);
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                && !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? network : null;
    }

    TransferConnection(Context context, TransferSettings.Config config, BooleanSupplier cancelled) throws IOException {
        this.context = context.getApplicationContext();
        this.cancelled = cancelled;
        network = wifi(context);
        if (network == null) throw new IOException("Connect to Wi-Fi to transfer recordings");
        try {
            check();
            InetAddress address = null;
            for (InetAddress candidate : network.getAllByName(config.host)) {
                if (candidate instanceof Inet4Address) { address = candidate; break; }
            }
            if (address == null) throw new IOException("Server needs an IPv4 address on this Wi-Fi network");
            if ("ftp".equals(config.protocol)) openFtp(config, address);
            else openSmb(config, address);
            check();
        } catch (IOException | RuntimeException error) {
            close();
            throw error;
        }
    }

    void check() throws IOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("Transfer cancelled");
        if (!network.equals(wifi(context))) throw new IOException("Wi-Fi changed. Local recordings retained");
    }

    private void openFtp(TransferSettings.Config config, InetAddress address) throws IOException {
        ftp = new FTPClient();
        ftp.setSocketFactory(network.getSocketFactory());
        ftp.setControlEncoding("UTF-8");
        ftp.setConnectTimeout(10000);
        ftp.setDefaultTimeout(10000);
        ftp.setDataTimeout(java.time.Duration.ofSeconds(10));
        ftp.connect(address, config.port);
        ftp.setSoTimeout(10000);
        if (!FTPReply.isPositiveCompletion(ftp.getReplyCode()) || !ftp.login(config.username, config.password))
            throw new IOException("FTP login failed. Check the server and credentials");
        ftp.enterLocalPassiveMode();
        // Commons Net uses the control peer for PASV, preventing a server from redirecting data sockets elsewhere.
        ftp.setIpAddressFromPasvResponse(false);
        if (!ftp.setFileType(FTP.BINARY_FILE_TYPE)) throw new IOException("FTP binary mode unavailable");
        if (!config.folder.isEmpty() && !ftp.changeWorkingDirectory(config.folder))
            throw new IOException("FTP destination folder is unavailable");
    }

    private void openSmb(TransferSettings.Config config, InetAddress address) throws IOException {
        LinkProperties links = context.getSystemService(ConnectivityManager.class).getLinkProperties(network);
        InetAddress local = null;
        if (links != null) {
            for (LinkAddress link : links.getLinkAddresses()) {
                if (link.getAddress() instanceof Inet4Address) { local = link.getAddress(); break; }
            }
        }
        if (local == null) throw new IOException("Wi-Fi needs an IPv4 address for SMB");
        Properties properties = new Properties();
        properties.setProperty("jcifs.smb.client.laddr", local.getHostAddress());
        properties.setProperty("jcifs.smb.client.minVersion", "SMB202");
        properties.setProperty("jcifs.smb.client.maxVersion", "SMB210");
        properties.setProperty("jcifs.smb.client.dfs.disabled", "true");
        properties.setProperty("jcifs.smb.client.port139.enabled", "false");
        properties.setProperty("jcifs.resolveOrder", "DNS");
        properties.setProperty("jcifs.smb.client.connTimeout", "10000");
        properties.setProperty("jcifs.smb.client.soTimeout", "10000");
        properties.setProperty("jcifs.smb.client.responseTimeout", "10000");
        properties.setProperty("jcifs.smb.client.attrExpirationPeriod", "0");
        BaseContext base = new BaseContext(new PropertyConfiguration(properties));
        smbContext = config.username.isEmpty() ? base.withAnonymousCredentials()
                : base.withCredentials(new NtlmPasswordAuthenticator(config.domain, config.username, config.password));
        String path = config.share + "/" + config.folder;
        StringBuilder pathBuilder = new StringBuilder();
        for (String part : path.split("/")) {
            if (!part.isEmpty()) pathBuilder.append(part).append('/');
        }
        directory = new SmbFile("smb://" + address.getHostAddress() + ":" + config.port + "/" + pathBuilder, smbContext);
        if (!directory.exists() || !directory.isDirectory()) throw new IOException("SMB share or folder is unavailable");
    }

    private SmbFile smb(String name) throws IOException {
        return new SmbFile(directory, name);
    }

    boolean exists(String name) throws IOException {
        check();
        if (ftp != null) return file(name) != null;
        try (SmbFile file = smb(name)) { return file.exists(); }
    }

    long size(String name) throws IOException {
        check();
        if (ftp != null) {
            FTPFile file = file(name);
            return file == null ? -1 : file.getSize();
        }
        try (SmbFile file = smb(name)) { return file.exists() ? file.length() : -1; }
    }

    private FTPFile file(String name) throws IOException {
        FTPFile file = ftp.mlistFile(name);
        if (file != null) return file;
        FTPFile[] files = ftp.listFiles();
        if (!FTPReply.isPositiveCompletion(ftp.getReplyCode())) throw new IOException("FTP file lookup failed");
        for (FTPFile candidate : files) if (name.equals(candidate.getName())) return candidate;
        return null;
    }

    OutputStream write(String name) throws IOException {
        check();
        if (ftp != null) {
            OutputStream result = ftp.storeFileStream(name);
            if (result == null) throw new IOException("Cannot create destination file");
            return result;
        }
        SmbFile file = smb(name);
        try {
            return new java.io.FilterOutputStream(new SmbFileOutputStream(file, false)) {
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    out.write(bytes, offset, length);
                }
                @Override public void close() throws IOException {
                    try { super.close(); } finally { file.close(); }
                }
            };
        } catch (IOException | RuntimeException error) { file.close(); throw error; }
    }

    InputStream read(String name) throws IOException {
        check();
        if (ftp != null) {
            InputStream result = ftp.retrieveFileStream(name);
            if (result == null) throw new IOException("Cannot read back destination file");
            return result;
        }
        SmbFile file = smb(name);
        try {
            return new java.io.FilterInputStream(file.getInputStream()) {
                @Override public void close() throws IOException {
                    try { super.close(); } finally { file.close(); }
                }
            };
        } catch (IOException | RuntimeException error) { file.close(); throw error; }
    }

    void finishStream() throws IOException {
        check();
        if (ftp != null && !ftp.completePendingCommand()) throw new IOException("FTP transfer did not complete");
    }

    void rename(String from, String to) throws IOException {
        check();
        if (exists(to)) throw new IOException("Destination filename already exists");
        if (ftp != null) {
            if (!ftp.rename(from, to)) throw new IOException("Cannot finalize FTP file");
        } else {
            try (SmbFile source = smb(from); SmbFile destination = smb(to)) { source.renameTo(destination, false); }
        }
    }

    void remove(String name) throws IOException {
        check();
        if (ftp != null) {
            if (!ftp.deleteFile(name)) throw new IOException("Cannot delete destination test file");
        } else {
            try (SmbFile file = smb(name)) { file.delete(); }
        }
    }

    @Override public void close() {
        if (ftp != null && ftp.isConnected()) {
            try { ftp.disconnect(); } catch (IOException ignored) { }
        }
        if (directory != null) directory.close();
        if (smbContext != null) {
            try { smbContext.close(); } catch (IOException ignored) { }
        }
    }
}
