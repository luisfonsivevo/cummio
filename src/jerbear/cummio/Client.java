package jerbear.cummio;

import static jerbear.cummio.GridChunk.size;

import java.util.ArrayList;
import java.util.Timer;

public class Client
{
	static int nextID = 0;
	
	int id = nextID++;
	Timer ping;
	long timeout = System.currentTimeMillis();
	double x, y;
	long viewx, viewy;
	int width, height;
	ArrayList<GridChunk> gridCache = new ArrayList<GridChunk>();
	
	public boolean cacheContains(long x, long y)
	{
		for(GridChunk chunk : gridCache)
		{
			if(chunk.equals(x, y))
				return true;
		}
		
		return false;
	}
	
	public boolean canSee(GridChunk chunk)
	{
		return viewx < size * (chunk.x + 1) &&
				viewx + width > chunk.x * size &&
				viewy < size * (chunk.y + 1) &&
				viewy + height > chunk.y * size;
	}
	
	public GridChunk getChunk()
	{
		long gx = ((long) Math.floor((double) x / size));
		long gy = ((long) Math.floor((double) y / size));
		
		for(GridChunk chunk : gridCache)
		{
			if(chunk.equals(gx, gy))
				return chunk;
		}
		
		return null; //uhhh
	}
}