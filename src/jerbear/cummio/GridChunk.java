package jerbear.cummio;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Base64;

import javax.imageio.ImageIO;
import javax.xml.bind.DatatypeConverter;

import org.mindrot.jbcrypt.BCrypt;

public class GridChunk
{
	public static final int size = 384; //24 * 16
	private static final int chunkLen = 768;
	private static final int hashLen = 60;
	
	public final long x, y;
	private final File file;
	
	public GridChunk(long x, long y)
	{
		this.x = x;
		this.y = y;
		
		file = new File(x + "_" + y + ".cum");
	}
	
	public void fill(int x, int y, byte r, byte g, byte b)
	{
		byte[] chunk = load();
		int i = (y * 16 + x) * 3;
		
		chunk[i] = r;
		chunk[i + 1] = g;
		chunk[i + 2] = b;
		
		if(isEmpty(chunk))
			file.delete();
		else
			save(chunk);
	}
	
	public String pick(int x, int y)
	{
		byte[] chunk = load();
		int i = (y * 16 + x) * 3;
		byte[] col = new byte[3];
		
		col[0] = chunk[i];
		col[1] = chunk[i + 1];
		col[2] = chunk[i + 2];
		return DatatypeConverter.printHexBinary(col);
	}
	
	public void deleteEmpty()
	{
		if(file.exists())
		{
			if(isEmpty(load()))
				file.delete();
		}
	}
	
	@Override
	public boolean equals(Object obj)
	{
		if(obj == null) return false;
		if(!(obj instanceof GridChunk)) return false;
		
		GridChunk other = (GridChunk) obj;
		return other.x == x && other.y == y;
	}
	
	public boolean equals(long x, long y)
	{
		return this.x == x && this.y == y;
	}
	
	@Override
	public String toString()
	{
		if(!file.exists())
			return "";
		
		byte[] chunk = load();
		
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_3BYTE_BGR);
		Graphics gfx = img.getGraphics();
		
		//draw grid lines
		int s16 = size / 16;
		
		if(hasPassword())
			gfx.setColor(new Color(127, 127, 127));
		else
			gfx.setColor(new Color(191, 191, 191));
		
		for(int i = 0; i < size; i += s16)
		{
			gfx.drawLine(0, i, size - 1, i); //horizontal
			gfx.drawLine(i, 0, i, size - 1); //vertical
		}
		
		//color squares
		for(int y = 0; y < 16; y++)
		{
			for(int x = 0; x < 16; x++)
			{
				int x1 = x * s16 + 1;
				int y1 = y * s16 + 1;
				
				int ci = (y * 16 + x) * 3;
				byte r = chunk[ci];
				byte g = chunk[ci + 1];
				byte b = chunk[ci + 2];
				
				gfx.setColor(new Color(r & 0xFF, g & 0xFF, b & 0xFF));
				gfx.fillRect(x1, y1, s16 - 1, s16 - 1);
			}
		}
		
		ByteArrayOutputStream os = new ByteArrayOutputStream();
		
		try
		{
			ImageIO.write(img, "jpg", os);
		}
		catch(IOException oops) {} //should never happen...right?
		
		return "data:image/jpg;base64," + Base64.getEncoder().encodeToString(os.toByteArray());
	}
	
	public void setPassword(String password)
	{
		if(!file.exists())
			return;
		
		byte[] hash;
		if(password.isEmpty())
		{
			hash = new byte[] {};
		}
		else
		{
			String bcrypt = BCrypt.hashpw(password, BCrypt.gensalt());
			hash = bcrypt.getBytes();
		}
		
		byte[] chunk = load();
		if(chunk.length == chunkLen) //cat chunk + hash
		{
			byte[] pwdchunk = new byte[chunk.length + hash.length];
			System.arraycopy(chunk, 0, pwdchunk, 0, chunk.length);
			System.arraycopy(hash, 0, pwdchunk, chunk.length, hash.length);
			save(pwdchunk);
		}
		else //cat chunk color + hash + chunk data
		{
			int didx = chunkLen;
			if(chunk[didx] == '$') //36 ascii
				didx += hashLen;

			int didx2 = chunkLen + hash.length;
			int dlen = chunk.length - didx;
			
			byte[] pwdchunk = new byte[chunkLen + hash.length + dlen];
			System.arraycopy(chunk, 0, pwdchunk, 0, chunkLen);
			System.arraycopy(hash, 0, pwdchunk, chunkLen, hash.length);
			System.arraycopy(chunk, didx, pwdchunk, didx2, dlen);
			save(pwdchunk);
		}
	}
	
	public boolean checkPassword(String password)
	{
		if(!file.exists())
			return true;
		
		byte[] chunk = load();
		
		if(chunk.length < chunkLen + hashLen)
			return true;
		
		if(chunk[chunkLen] != '$') //36 ascii
			return true;
		
		byte[] hash = new byte[hashLen];
		System.arraycopy(chunk, chunkLen, hash, 0, hashLen);
		String bcrypt = new String(hash);
		return BCrypt.checkpw(password, bcrypt);
	}
	
	public boolean hasPassword()
	{
		if(!file.exists())
			return false;
		
		byte[] chunk = load();
		
		if(chunk.length < chunkLen + hashLen)
			return false;
		
		if(chunk[chunkLen] != '$') //36 ascii
			return false;
		else
			return true;
	}
	
	private byte[] load()
	{
		try
		{
			return Files.readAllBytes(file.toPath());
		}
		catch(IOException oops)
		{
			byte[] chunk = new byte[16 * 16 * 3];
			for(int i = 0; i < chunk.length; i++)
			{
				chunk[i] = (byte) 255;
			}
			
			save(chunk);
			return chunk;
		}
	}
	
	private void save(byte[] chunk)
	{
		try
		{
			Files.write(file.toPath(), chunk);
		}
		catch(IOException oops)
		{
			oops.printStackTrace();
		}
	}
	
	private boolean isEmpty(byte[] chunk)
	{
		for(int i = 0; i < chunkLen; i++)
		{
			if(chunk[i] != (byte) 255)
				return false;
		}
		
		return true;
	}
}