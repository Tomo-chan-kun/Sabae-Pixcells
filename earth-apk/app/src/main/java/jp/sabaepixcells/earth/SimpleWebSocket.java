package jp.sabaepixcells.earth;

import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

public final class SimpleWebSocket {
    public interface Listener {
        void onOpen();
        void onText(String text);
        void onClosed(String reason);
        void onError(Exception e);
    }

    private final Listener listener;
    private final SecureRandom random=new SecureRandom();
    private volatile boolean running=false;
    private Socket socket;
    private InputStream in;
    private OutputStream out;

    public SimpleWebSocket(Listener listener){this.listener=listener;}

    public void connect(String host,int port,String path){
        close();
        new Thread(()->run(host,port,path),"EarthWebSocket").start();
    }

    private void run(String host,int port,String path){
        try{
            socket=new Socket(); socket.connect(new InetSocketAddress(host,port),5000); socket.setTcpNoDelay(true);
            in=new BufferedInputStream(socket.getInputStream()); out=new BufferedOutputStream(socket.getOutputStream());
            byte[] keyBytes=new byte[16]; random.nextBytes(keyBytes);
            String key=Base64.encodeToString(keyBytes,Base64.NO_WRAP);
            String req="GET "+path+" HTTP/1.1\r\nHost: "+host+":"+port+"\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: "+key+"\r\nSec-WebSocket-Version: 13\r\n\r\n";
            out.write(req.getBytes(StandardCharsets.US_ASCII)); out.flush();
            String header=readHttpHeader();
            if(!header.startsWith("HTTP/1.1 101")&&!header.startsWith("HTTP/1.0 101"))throw new Exception("WebSocket handshake failed: "+header.split("\r?\n")[0]);
            String expected=Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)),Base64.NO_WRAP);
            if(!header.toLowerCase(Locale.ROOT).contains("sec-websocket-accept: "+expected.toLowerCase(Locale.ROOT)))throw new Exception("WebSocket accept mismatch");
            running=true; listener.onOpen();
            while(running){
                Frame f=readFrame();
                if(f.opcode==1) listener.onText(new String(f.payload,StandardCharsets.UTF_8));
                else if(f.opcode==8){ running=false; break; }
                else if(f.opcode==9) sendFrame(10,f.payload);
            }
            listener.onClosed("closed");
        }catch(Exception e){ if(running||socket!=null)listener.onError(e); }
        finally{ running=false; closeSocket(); }
    }

    private String readHttpHeader() throws Exception{
        ByteArrayOutputStream b=new ByteArrayOutputStream(); int state=0;
        while(b.size()<16384){ int x=in.read(); if(x<0)throw new EOFException(); b.write(x);
            if(state==0&&x=='\r')state=1; else if(state==1&&x=='\n')state=2; else if(state==2&&x=='\r')state=3; else if(state==3&&x=='\n')break; else state=0; }
        return b.toString(StandardCharsets.US_ASCII.name());
    }

    private static final class Frame{final int opcode;final byte[]payload;Frame(int o,byte[]p){opcode=o;payload=p;}}
    private Frame readFrame() throws Exception{
        int b0=in.read(),b1=in.read(); if(b0<0||b1<0)throw new EOFException();
        int opcode=b0&0x0f; boolean masked=(b1&0x80)!=0; long len=b1&0x7f;
        if(len==126){len=((long)read1()<<8)|read1();}
        else if(len==127){len=0;for(int i=0;i<8;i++)len=(len<<8)|read1();}
        if(len>1_000_000)throw new Exception("frame too large");
        byte[] mask=null; if(masked){mask=new byte[]{(byte)read1(),(byte)read1(),(byte)read1(),(byte)read1()};}
        byte[] p=new byte[(int)len]; int off=0; while(off<p.length){int n=in.read(p,off,p.length-off);if(n<0)throw new EOFException();off+=n;}
        if(mask!=null)for(int i=0;i<p.length;i++)p[i]^=mask[i%4];
        return new Frame(opcode,p);
    }
    private int read1()throws Exception{int x=in.read();if(x<0)throw new EOFException();return x&255;}

    public boolean isOpen(){return running;}
    public synchronized void sendText(String text)throws Exception{if(!running)throw new Exception("not open");sendFrame(1,text.getBytes(StandardCharsets.UTF_8));}
    private synchronized void sendFrame(int opcode,byte[]payload)throws Exception{
        if(out==null)throw new Exception("not connected");
        ByteArrayOutputStream h=new ByteArrayOutputStream(); h.write(0x80|opcode); int len=payload.length;
        if(len<126)h.write(0x80|len); else if(len<=65535){h.write(0x80|126);h.write((len>>>8)&255);h.write(len&255);} else {h.write(0x80|127);for(int i=7;i>=0;i--)h.write((len>>>(8*i))&255);}
        byte[]mask=new byte[4];random.nextBytes(mask);h.write(mask);
        byte[]p=payload.clone();for(int i=0;i<p.length;i++)p[i]^=mask[i%4];
        out.write(h.toByteArray());out.write(p);out.flush();
    }
    public void close(){running=false;closeSocket();}
    private void closeSocket(){try{if(socket!=null)socket.close();}catch(Exception ignored){}socket=null;in=null;out=null;}
}
