package jerbear.cummio;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.xml.bind.DatatypeConverter;

import org.java_websocket.WebSocket;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.DefaultSSLWebSocketServerFactory;
import org.java_websocket.server.WebSocketServer;

import static jerbear.cummio.GridChunk.size;

public class WSSServer extends WebSocketServer
{
	private HashMap<WebSocket, Client> clients;
	
	public WSSServer()
	{
		super(new InetSocketAddress(4433));
		setReuseAddr(true);
		
		try
		{
			SSLContext sslContext = SSLContext.getInstance("TLS");
			
			char[] keyPassword = "12345".toCharArray(); //very strong plain text password
			byte[] certBytes = parseDERFromPEM(Files.readAllBytes(new File("cert.pem").toPath()), "CERTIFICATE");
			byte[] keyBytes = parseDERFromPEM(Files.readAllBytes(new File("privkey.pem").toPath()), "PRIVATE KEY");
			
			X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(certBytes));
			RSAPrivateKey key = (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
			
			KeyStore ks = KeyStore.getInstance("JKS");
			ks.load(null);
			ks.setCertificateEntry("cert-alias", cert);
			ks.setKeyEntry("key-alias", key, keyPassword, new Certificate[] {cert});
			
			KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
			kmf.init(ks, keyPassword);
			
			KeyManager[] km = kmf.getKeyManagers();
			sslContext.init(km, null, null);
			
			setWebSocketFactory(new DefaultSSLWebSocketServerFactory(sslContext));
		}
		catch(IOException | CertificateException | NoSuchAlgorithmException | InvalidKeySpecException | KeyStoreException | UnrecoverableKeyException | KeyManagementException oops)
		{
			System.err.println("Unable to enable SSL. WS Server will be unsecured.");
			oops.printStackTrace();
		}
	}
	
	@Override
	public void onStart()
	{
		clients = new HashMap<WebSocket, Client>();
		System.out.println("Server started. Ctrl+C to close.");
	}
	
	@Override
	public void onOpen(WebSocket conn, ClientHandshake handshake)
	{
		Client client = new Client();
		client.ping = new Timer();
		client.ping.scheduleAtFixedRate(new TimerTask()
		{
			@Override
			public void run()
			{
				if(!conn.isClosed())
				{
					if(clients.get(conn).timeout + 10000 < System.currentTimeMillis())
						conn.close();
					else
						conn.send(".");
				}
			}
		}, 5000, 5000);
		
		clients.put(conn, client);
		
		for(Entry<WebSocket, Client> other : clients.entrySet())
		{
			WebSocket os = other.getKey();
			Client oc = other.getValue();
			
			if(conn == os)
			{
				os.send("j1" + client.id);
			}
			else
			{
				os.send("j0" + client.id);
				conn.send("j0" + oc.id);
				conn.send("m1" + oc.id + "x" + oc.x + "y" + oc.y);
			}
		}
	}
	
	@Override
	public void onMessage(WebSocket conn, String message)
	{
		Client client = clients.get(conn);
		client.timeout = System.currentTimeMillis();
		
		if(message.startsWith("x"))
		{
			int yidx = message.indexOf("y");
			int vidx = message.indexOf("v");
			int widx = message.indexOf("w");
			int lidx = message.indexOf("l");
			int hidx = message.indexOf("h");
			
			if(yidx == -1 || vidx == -1 || widx == -1 || lidx == -1 || hidx == -1)
				return;
			
			try
			{
				double newx = Double.parseDouble(message.substring(1, yidx));
				double newy = Double.parseDouble(message.substring(yidx + 1, vidx));
				double dx = newx - client.x;
				double dy = newy - client.y;
				
				if(Math.sqrt(dx * dx + dy * dy) >= 23) //client using haxx (or lagging)
				{
					conn.send("m1" + client.id + "x" + client.x + "y" + client.y);
					return;
				}
				else
				{
					client.x = newx;
					client.y = newy;
				}
				
				long viewx = Long.parseLong(message.substring(vidx + 1, widx));
				long viewy = Long.parseLong(message.substring(widx + 1, lidx));
				
				int max = size * 5;
				int width = Math.min(max, Integer.parseInt(message.substring(lidx + 1, hidx)));
				int height = Math.min(max, Integer.parseInt(message.substring(hidx + 1)));
				
				if(client.viewx != viewx || client.viewy != viewy || client.width != width || client.height != height)
				{
					client.viewx = viewx;
					client.viewy = viewy;
					client.width = width;
					client.height = height;
					
					long startx = ((long) Math.floor((double) viewx / size));
					long starty = ((long) Math.floor((double) viewy / size));
					long endx = ((long) Math.ceil((double) (viewx + width) / size));
					long endy = ((long) Math.ceil((double) (viewy + height) / size));
					
					for(long x = startx; x < endx; x++)
					{
						for(long y = starty; y < endy; y++)
						{
							if(!client.cacheContains(x, y))
							{
								GridChunk chunk = new GridChunk(x, y);
								client.gridCache.add(chunk);
								conn.send("gx" + x + "y" + y + "d" + chunk);
							}
						}
					}
					
					Iterator<GridChunk> iter = client.gridCache.iterator();
					while(iter.hasNext())
					{
						GridChunk chunk = iter.next();
						if(!client.canSee(chunk))
						{
							iter.remove();
							chunk.deleteEmpty();
						}
					}
				}
			}
			catch(NumberFormatException oops)
			{
				return;
			}
			
			for(WebSocket other : clients.keySet())
			{
				if(conn != other)
					other.send("m0" + client.id + "x" + client.x + "y" + client.y);
			}
		}
		else if(message.startsWith("c"))
		{
			int end = message.length() > 201 ? 201 : message.length();
			String chat = message.substring(1, end).trim();
			if(chat.isEmpty()) return;
			
			if(chat.startsWith("/claim "))
			{
				String[] split = chat.split(" ", 3);
				String password;
				GridChunk chunk;
				
				switch(split.length)
				{
					case 3:
						password = split[1].trim();
						chunk = coordToChunk(split[2]);
						
						if(chunk == null)
							return;
						
						if(!client.canSee(chunk))
							return;
						
						break;
					case 2:
						password = split[1].trim();
						chunk = client.getChunk();
						break;
					default:
						return;
				}
				
				if(chunk.hasPassword())
					return;
				
				if(!isValidPassword(password, conn))
					return;
				
				chunk.setPassword(password);
				sendChunkUpdate(chunk);
			}
			else if(chat.startsWith("/unclaim "))
			{
				String[] split = chat.split(" ", 3);
				String password;
				GridChunk chunk;
				
				switch(split.length)
				{
					case 3:
						password = split[1].trim();
						chunk = coordToChunk(split[2]);
						
						if(chunk == null)
							return;
						
						if(!client.canSee(chunk))
							return;
						break;
					case 2:
						password = split[1].trim();
						chunk = client.getChunk();
						break;
					default:
						return;
				}
				
				if(!isValidPassword(password, conn))
					return;
				
				if(!chunk.checkPassword(password))
					return;
				
				chunk.setPassword("");
				sendChunkUpdate(chunk);
			}
			if(chat.startsWith("/reclaim "))
			{
				String[] split = chat.split(" ", 4);
				String passwordOld, passwordNew;
				GridChunk chunk;
				
				switch(split.length)
				{
					case 4:
						passwordOld = split[1].trim();
						passwordNew = split[2].trim();
						chunk = coordToChunk(split[3]);
						
						if(chunk == null)
							return;
						
						if(!client.canSee(chunk))
							return;
						
						break;
					case 3:
						passwordOld = split[1].trim();
						passwordNew = split[2].trim();
						chunk = client.getChunk();
						break;
					default:
						return;
				}
				
				if(!isValidPassword(passwordOld, conn) || !isValidPassword(passwordNew, conn))
					return;
				
				if(!chunk.checkPassword(passwordOld))
					return;
				
				chunk.setPassword(passwordNew);
				sendChunkUpdate(chunk);
			}
			else if(!chat.startsWith("/"))
			{
				for(Entry<WebSocket, Client> other : clients.entrySet())
				{
					WebSocket os = other.getKey();
					Client oc = other.getValue();

					GridChunk visible = oc.getChunk();
					if(conn != os && client.canSee(visible))
						os.send("c" + client.id + "m" + chat);
				}
			}
		}
		else if(message.startsWith("p"))
		{
			int yidx = message.indexOf("y");
			int pidx = message.indexOf("p", 1);
			if(yidx == -1)
				return;
			
			try
			{
				byte r = (byte) Integer.parseInt(message.substring(1, 3), 16);
				byte g = (byte) Integer.parseInt(message.substring(3, 5), 16);
				byte b = (byte) Integer.parseInt(message.substring(5, 7), 16);
				
				long x = Long.parseLong(message.substring(8, yidx));
				long y;
				
				if(pidx == -1)
					y = Long.parseLong(message.substring(yidx + 1));
				else
					y = Long.parseLong(message.substring(yidx + 1, pidx));
				
				long gx = (long) Math.floor(x / 384d);
				long gy = (long) Math.floor(y / 384d);
				int sx = (int) (Math.floor(x / 24d) - gx * 16);
				int sy = (int) (Math.floor(y / 24d) - gy * 16);
				GridChunk chunk = new GridChunk(gx, gy);
				
				if(!client.canSee(chunk))
					return;
				
				if(chunk.hasPassword())
				{
					if(pidx == -1)
						return;
					else if(!chunk.checkPassword(message.substring(pidx + 1)))
						return;
				}
				
				chunk.fill(sx, sy, r, g, b);
				sendChunkUpdate(chunk);
			}
			catch(NumberFormatException oops)
			{
				return;
			}
		}
		else if(message.startsWith("e"))
		{
			int yidx = message.indexOf("y");
			if(yidx == -1)
				return;
			
			try
			{
				long x = Long.parseLong(message.substring(3, yidx));
				long y = Long.parseLong(message.substring(yidx + 1));
				
				long gx = (long) Math.floor(x / 384d);
				long gy = (long) Math.floor(y / 384d);
				int sx = (int) (Math.floor(x / 24d) - gx * 16);
				int sy = (int) (Math.floor(y / 24d) - gy * 16);
				GridChunk chunk = new GridChunk(gx, gy);
				
				if(!client.canSee(chunk))
					return;
				
				conn.send("e" + message.charAt(1) + chunk.pick(sx, sy));
			}
			catch(NumberFormatException oops)
			{
				return;
			}
		}
	}
	
	@Override
	public void onClose(WebSocket conn, int code, String reason, boolean remote)
	{
		Client bye = clients.get(conn);
		clients.remove(conn);
		bye.ping.cancel();
		
		for(GridChunk chunk : bye.gridCache)
		{
			chunk.deleteEmpty();
		}
		
		for(WebSocket other : clients.keySet())
			other.send("d" + bye.id);
	}
	
	@Override
	public void onError(WebSocket conn, Exception oops)
	{
		if(!(oops instanceof WebsocketNotConnectedException))
			oops.printStackTrace();
	}
	
	private void sendChunkUpdate(GridChunk chunk)
	{
		String b64 = chunk.toString();
		for(Entry<WebSocket, Client> other : clients.entrySet())
		{
			WebSocket os = other.getKey();
			Client oc = other.getValue();
			
			if(oc.canSee(chunk))
				os.send("gx" + chunk.x + "y" + chunk.y + "d" + b64);
		}
	}
	
	public static void main(String[] args) throws IOException
	{
		WSSServer ws = new WSSServer();
		
		Runtime.getRuntime().addShutdownHook(new Thread()
		{
			@Override
			public void run()
			{
				for(Client client : ws.clients.values())
				{
					for(GridChunk chunk : client.gridCache)
						chunk.deleteEmpty();
				}
			}
		});
		
		try
		{
			ws.run();
		}
		catch(Throwable oops)
		{
			oops.printStackTrace();
			System.exit(1);
		}
	}
	
	private static byte[] parseDERFromPEM(byte[] pem, String type)
	{
		String data = new String(pem);
		String[] tokens = data.split("-----BEGIN " + type + "-----");
		tokens = tokens[1].split("-----END " + type + "-----");
		return DatatypeConverter.parseBase64Binary(tokens[0]);
	}
	
	private boolean isValidPassword(String password, WebSocket conn)
	{
		if(password.isEmpty())
			return false;
		
		if(password.length() > 50)
		{
			conn.send("c" + clients.get(conn).id + "m[PRIVATE] Password can be up to 50 characters long");
			return false;
		}
		
		return !Pattern.compile("\\s").matcher(password).find();
	}
	
	private GridChunk coordToChunk(String coord)
	{
		Matcher matcher = Pattern.compile("(-?\\d+)_(-?\\d+)").matcher(coord);
		if(!matcher.find())
			return null;
		
		long x = Long.parseLong(matcher.group(1));
		long y = Long.parseLong(matcher.group(2));
		return new GridChunk(x, y);
	}
}